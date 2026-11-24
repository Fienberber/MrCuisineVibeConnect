package app.toque.core.ha

import app.toque.core.i18n.tr
import app.toque.core.json.Json
import app.toque.core.machine.EventType
import app.toque.core.machine.JobState
import app.toque.core.machine.LinkState
import app.toque.core.machine.MachineEvent
import app.toque.core.machine.Snapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Ce que Home Assistant affiche de l'appareil. */
data class HaDevice(
    /** Identifiant stable de ce robot (lettres, chiffres, _), dans les sujets et les unique_id. */
    val nodeId: String,
    val name: String,
    val model: String,
    val version: String,
)

/**
 * Toque vu de Home Assistant, par la découverte MQTT : HA crée l'appareil et ses entités tout seul
 * (intégration MQTT, aucun code côté HA). Lecture seule, plus un bouton « Arrêter » : rien ne
 * démarre le moteur ni la chauffe depuis HA.
 *
 * Sujets : `toque/<id>/state` (JSON, gardé par le broker), `toque/<id>/availability`
 * (online/offline, offline par la dernière volonté du broker), `toque/<id>/event` (fin de
 * programme, arrêt, alerte) et `toque/<id>/command` (STOP).
 *
 * Pas de réseau ici : [send] envoie, ce qui permet d'essayer la classe sans broker.
 */
class HomeAssistantBridge(
    private val device: HaDevice,
    private val discoveryPrefix: String = "homeassistant",
    private val send: (topic: String, payload: String, retain: Boolean) -> Unit,
    /** Demande d'arrêt reçue de HA ; à exécuter par le contrôleur, avec ses règles. */
    private val onStop: () -> Unit,
    /** Horloge murale (ms), pour l'heure de fin prévue. */
    private val wallClock: () -> Long = System::currentTimeMillis,
    /** Horloge monotone (ms), pour espacer les états qui changent sans cesse. */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val base = "toque/${device.nodeId}"
    val stateTopic = "$base/state"
    val availabilityTopic = "$base/availability"
    val eventTopic = "$base/event"
    val commandTopic = "$base/command"

    private var last: Snapshot? = null
    private var lastKey: List<Any?>? = null
    private var lastPayload = ""
    private var lastSentAt = Long.MIN_VALUE / 2

    /** La dernière volonté à déposer au broker en se connectant : HA voit l'appareil indisponible. */
    fun will(): Mqtt.Will = Mqtt.Will(availabilityTopic, OFFLINE, retain = true)

    /** Connexion (re)faite : découverte, disponibilité et dernier état connu. */
    @Synchronized
    fun onConnected() {
        for ((topic, payload) in discovery()) send(topic, payload, true)
        send(availabilityTopic, ONLINE, true)
        last?.let { s ->
            lastPayload = statePayload(s)
            lastSentAt = clock()
            send(stateTopic, lastPayload, true)
        }
    }

    /**
     * Nouvel état de la machine (~10 par seconde). Envoyé aussitôt si quelque chose d'important
     * change (programme, état, couvercle, alerte, liaison), sinon au plus toutes les [PERIOD_MS].
     */
    @Synchronized
    fun onSnapshot(s: Snapshot) {
        last = s
        val key = importantKey(s)
        val now = clock()
        if (key == lastKey && now - lastSentAt < PERIOD_MS) return
        val payload = statePayload(s)
        lastKey = key
        if (payload == lastPayload && now - lastSentAt < KEEPALIVE_MS) return
        lastPayload = payload
        lastSentAt = now
        send(stateTopic, payload, true)
    }

    /** Évènement de la machine : fin, arrêt et alerte deviennent des évènements HA (pour les notifications). */
    @Synchronized
    fun onEvent(e: MachineEvent) {
        val type = when (e.type) {
            EventType.JOB_FINISHED -> "finished"
            EventType.JOB_ABORTED -> "aborted"
            EventType.FAULT -> "fault"
            else -> return
        }
        val label = last?.jobLabel ?: ""
        send(eventTopic, Json.write(linkedMapOf("event_type" to type, "program" to label, "message" to e.message)), false)
    }

    /** Message reçu : seul « STOP » sur le sujet de commande est compris. */
    fun onMessage(topic: String, payload: String) {
        if (topic == commandTopic && payload.trim().equals(STOP, ignoreCase = true)) onStop()
    }

    fun statePayload(s: Snapshot): String {
        val st = s.state
        val running = s.running || st == JobState.PAUSED
        val remainingS = if (running) (s.remainingMs + 999) / 1000 else 0L
        val endsAt = if (st == JobState.RUNNING || st == JobState.STARTING) isoUtc(wallClock() + s.remainingMs) else null
        return Json.write(linkedMapOf(
            "status" to status(s),
            "program" to if (s.jobActive || st != null) s.jobLabel else "",
            "running" to running,
            "remaining_s" to remainingS,
            "ends_at" to endsAt,
            "temperature_c" to if (s.hasSample) s.actualTempC else null,
            "target_c" to if (running && s.jobTempC > 0) s.jobTempC else null,
            "weight_g" to if (s.hasSample) s.weightG else null,
            "lid_open" to s.lidOpen,
            "fault" to s.hasFault,
            "fault_message" to s.faultMessage,
            "link" to s.link.name.lowercase(Locale.ROOT),
        ))
    }

    /** offline, idle, running, paused, finished, aborted, fault. */
    fun status(s: Snapshot): String = when {
        s.hasFault -> "fault"
        s.link != LinkState.READY && s.state == null -> "offline"
        else -> when (s.state) {
            JobState.STARTING, JobState.RUNNING, JobState.STOPPING -> "running"
            JobState.PAUSED -> "paused"
            JobState.FINISHED -> "finished"
            JobState.ABORTED -> "aborted"
            null -> "idle"
        }
    }

    /** Les messages de découverte : (sujet de configuration, contenu). */
    fun discovery(): List<Pair<String, String>> {
        val dev = linkedMapOf(
            "identifiers" to listOf("toque_${device.nodeId}"),
            "name" to device.name,
            "manufacturer" to "Lidl / SilverCrest",
            "model" to device.model,
            "sw_version" to "Toque ${device.version}",
        )
        val origin = linkedMapOf("name" to "Toque", "sw_version" to device.version, "support_url" to SUPPORT_URL)
        fun entity(component: String, key: String, name: String, extra: Map<String, Any?>): Pair<String, String> {
            val m = linkedMapOf<String, Any?>(
                "name" to name,
                "unique_id" to "toque_${device.nodeId}_$key",
                "availability_topic" to availabilityTopic,
                "device" to dev,
                "origin" to origin,
            )
            m.putAll(extra)
            return "$discoveryPrefix/$component/toque_${device.nodeId}/$key/config" to Json.write(m)
        }
        fun stateEntity(component: String, key: String, name: String, template: String, extra: Map<String, Any?> = emptyMap()) =
            entity(component, key, name, linkedMapOf<String, Any?>("state_topic" to stateTopic, "value_template" to template) + extra)
        val onOff = { field: String -> "{{ 'ON' if value_json.$field else 'OFF' }}" }
        return listOf(
            stateEntity("sensor", "status", tr("État"), "{{ value_json.status }}", linkedMapOf(
                "device_class" to "enum",
                "options" to listOf("offline", "idle", "running", "paused", "finished", "aborted", "fault"),
                "icon" to "mdi:chef-hat",
            )),
            stateEntity("sensor", "program", tr("Programme"), "{{ value_json.program }}", linkedMapOf("icon" to "mdi:pot-steam")),
            stateEntity("binary_sensor", "running", tr("En cours"), onOff("running"), linkedMapOf("device_class" to "running")),
            stateEntity("sensor", "remaining", tr("Temps restant"), "{{ value_json.remaining_s }}", linkedMapOf(
                "device_class" to "duration", "unit_of_measurement" to "s",
            )),
            stateEntity("sensor", "ends_at", tr("Fin prévue"), "{{ value_json.ends_at }}", linkedMapOf("device_class" to "timestamp")),
            stateEntity("sensor", "temperature", tr("Température"), "{{ value_json.temperature_c }}", linkedMapOf(
                "device_class" to "temperature", "unit_of_measurement" to "°C", "state_class" to "measurement",
            )),
            stateEntity("sensor", "target", tr("Température visée"), "{{ value_json.target_c }}", linkedMapOf(
                "device_class" to "temperature", "unit_of_measurement" to "°C",
            )),
            stateEntity("sensor", "weight", tr("Poids"), "{{ value_json.weight_g }}", linkedMapOf(
                "device_class" to "weight", "unit_of_measurement" to "g", "state_class" to "measurement",
            )),
            stateEntity("binary_sensor", "lid", tr("Couvercle"), onOff("lid_open"), linkedMapOf("device_class" to "opening")),
            stateEntity("binary_sensor", "fault", tr("Alerte"), onOff("fault"), linkedMapOf(
                "device_class" to "problem",
                "json_attributes_topic" to stateTopic,
                "json_attributes_template" to "{{ {'message': value_json.fault_message} | tojson }}",
            )),
            entity("event", "end", tr("Fin de programme"), linkedMapOf(
                "state_topic" to eventTopic,
                "event_types" to listOf("finished", "aborted", "fault"),
                "icon" to "mdi:bell-ring",
            )),
            entity("button", "stop", tr("Arrêter"), linkedMapOf(
                "command_topic" to commandTopic,
                "payload_press" to STOP,
                "icon" to "mdi:stop-circle",
            )),
        )
    }

    /** Ce qui, en changeant, part tout de suite (le reste attend [PERIOD_MS]). */
    private fun importantKey(s: Snapshot): List<Any?> =
        listOf(status(s), s.jobLabel, s.lidOpen, s.faultCode, s.link, s.jobTempC)

    private fun isoUtc(ms: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(ms))
    }

    companion object {
        const val ONLINE = "online"
        const val OFFLINE = "offline"
        const val STOP = "STOP"
        /** Écart minimal entre deux états qui ne diffèrent que par le temps, la température ou le poids. */
        const val PERIOD_MS = 10_000L
        /** Un état identique est tout de même renvoyé de temps en temps. */
        const val KEEPALIVE_MS = 5 * 60_000L
        const val SUPPORT_URL = "https://github.com/Wuki60/MrCuisineVibeConnect"

        /** Identifiant de nœud valable dans un sujet MQTT et un unique_id HA. */
        fun cleanNodeId(raw: String): String =
            raw.lowercase(Locale.ROOT).map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }.joinToString("").trim('_').ifEmpty { "robot" }
    }
}
