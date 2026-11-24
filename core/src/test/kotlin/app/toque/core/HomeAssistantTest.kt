package app.toque.core

import app.toque.core.ha.HaDevice
import app.toque.core.ha.HomeAssistantBridge
import app.toque.core.ha.Mqtt
import app.toque.core.ha.MqttConnector
import app.toque.core.ha.MqttSettings
import app.toque.core.json.Json
import app.toque.core.json.obj
import app.toque.core.json.str
import app.toque.core.machine.EventType
import app.toque.core.machine.JobState
import app.toque.core.machine.LinkState
import app.toque.core.machine.MachineEvent
import app.toque.core.machine.Snapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class HomeAssistantTest {
    private class Sent(val topic: String, val payload: String, val retain: Boolean)

    private val device = HaDevice("abc123", "Monsieur Cuisine", "Monsieur Cuisine Connect", "0.9.0")
    private var now = 1_000_000L
    private val sent = ArrayList<Sent>()
    private var stops = 0
    private val bridge = HomeAssistantBridge(device, send = { t, p, r -> sent += Sent(t, p, r) }, onStop = { stops++ },
        wallClock = { 1_767_225_600_000L }, clock = { now })

    private val idle = Snapshot(link = LinkState.READY, hasSample = true, actualTempC = 21, weightG = 0)
    private fun running(remainingMs: Long, temp: Int = 37) = idle.copy(jobActive = true, jobLabel = "Yaourt", jobKind = "MANUAL",
        jobState = JobState.RUNNING.name, remainingMs = remainingMs, totalMs = 8 * 3600_000L, jobTempC = 37, actualTempC = temp)

    // ------------------------------------------------------------------ MQTT

    @Test fun connectPacketCarriesCredentialsAndWill() {
        val p = Mqtt.connect("toque-abc", 30, "ha", "secret", Mqtt.Will("toque/abc/availability", "offline", true))
        assertEquals(0x10, p[0].toInt() and 0xFF)
        val packet = Mqtt.read(ByteArrayInputStream(p))
        assertEquals(Mqtt.CONNECT, packet.type)
        val b = packet.body
        assertEquals("MQTT", String(b, 2, 4))
        assertEquals(4, b[6].toInt())
        // utilisateur, mot de passe, will retain, will, session propre
        assertEquals(0x80 or 0x40 or 0x20 or 0x04 or 0x02, b[7].toInt() and 0xFF)
        assertEquals(30, ((b[8].toInt() and 0xFF) shl 8) or (b[9].toInt() and 0xFF))
        val text = String(b, Charsets.UTF_8)
        for (s in listOf("toque-abc", "toque/abc/availability", "offline", "ha", "secret")) assertTrue(s, text.contains(s))
        // Sans identifiants ni volonté : session propre seulement.
        assertEquals(0x02, Mqtt.read(ByteArrayInputStream(Mqtt.connect("x", 30))).body[7].toInt())
        // Un mot de passe sans utilisateur n'est pas permis par MQTT 3.1.1 : il n'est pas envoyé.
        assertEquals(0x02, Mqtt.read(ByteArrayInputStream(Mqtt.connect("x", 30, "", "secret"))).body[7].toInt())
    }

    @Test fun publishRoundTripsIncludingLongPayloads() {
        val long = "x".repeat(20_000) // longueur sur trois octets
        for (payload in listOf("STOP", "", long, "éàü °C")) {
            val p = Mqtt.read(ByteArrayInputStream(Mqtt.publish("toque/a/state", payload, retain = true)))
            assertEquals(Mqtt.PUBLISH, p.type)
            assertEquals(1, p.flags and 1)
            val m = Mqtt.parsePublish(p)
            assertEquals("toque/a/state", m.topic)
            assertEquals(payload, m.text)
            assertEquals(0, m.qos)
        }
        assertEquals(0, Mqtt.read(ByteArrayInputStream(Mqtt.publish("t", "p", retain = false))).flags)
    }

    @Test fun parsesQos1PublishAndAcknowledgesIt() {
        // PUBLISH QoS 1 du broker : sujet « t », identifiant 0x0102, contenu « go ».
        val raw = byteArrayOf(0x32, 7, 0, 1, 't'.code.toByte(), 1, 2, 'g'.code.toByte(), 'o'.code.toByte())
        val m = Mqtt.parsePublish(Mqtt.read(ByteArrayInputStream(raw)))
        assertEquals("t", m.topic)
        assertEquals(1, m.qos)
        assertEquals(0x0102, m.packetId)
        assertEquals("go", m.text)
        assertArrayEquals(byteArrayOf(0x40, 2, 1, 2), Mqtt.puback(0x0102))
    }

    @Test fun subscribeAndControlPackets() {
        val s = Mqtt.read(ByteArrayInputStream(Mqtt.subscribe(1, "toque/a/command")))
        assertEquals(Mqtt.SUBSCRIBE, s.type)
        assertEquals(2, s.flags)
        assertArrayEquals(byteArrayOf(0xC0.toByte(), 0), Mqtt.pingreq())
        assertArrayEquals(byteArrayOf(0xE0.toByte(), 0), Mqtt.disconnect())
        assertEquals(0, Mqtt.connackCode(Mqtt.Packet(Mqtt.CONNACK, 0, byteArrayOf(0, 0))))
        assertEquals(4, Mqtt.connackCode(Mqtt.Packet(Mqtt.CONNACK, 0, byteArrayOf(0, 4))))
        assertEquals(-1, Mqtt.connackCode(Mqtt.Packet(Mqtt.SUBACK, 0, byteArrayOf(0, 0))))
    }

    @Test fun refusesOversizedAndTruncatedPackets() {
        // Longueur annoncée de 128 Kio : refusée sans allouer.
        val big = byteArrayOf(0x30, 0x80.toByte(), 0x80.toByte(), 0x08)
        assertTrue(runCatching { Mqtt.read(ByteArrayInputStream(big)) }.isFailure)
        assertTrue(runCatching { Mqtt.read(ByteArrayInputStream(byteArrayOf(0x30, 5, 0))) }.isFailure)
        assertTrue(runCatching { Mqtt.parsePublish(Mqtt.Packet(Mqtt.PUBLISH, 0, byteArrayOf(0, 9, 1))) }.isFailure)
    }

    // ------------------------------------------------------------------ Home Assistant

    @Test fun discoveryDescribesEveryEntityOfOneDevice() {
        val d = bridge.discovery()
        val topics = d.map { it.first }
        assertTrue(topics.toString(), "homeassistant/sensor/toque_abc123/status/config" in topics)
        assertTrue("homeassistant/button/toque_abc123/stop/config" in topics)
        assertTrue("homeassistant/event/toque_abc123/end/config" in topics)
        assertEquals(topics.size, topics.toSet().size)
        val ids = HashSet<String>()
        for ((_, payload) in d) {
            val m = Json.parse(payload).obj()
            assertTrue(ids.add(m.str("unique_id")!!))
            assertEquals("toque/abc123/availability", m.str("availability_topic"))
            assertEquals(listOf("toque_abc123"), m["device"].obj()["identifiers"])
        }
        // Lecture seule : le seul sujet de commande est celui du bouton « Arrêter ».
        val commands = d.map { Json.parse(it.second).obj() }.mapNotNull { it.str("command_topic") }
        assertEquals(listOf("toque/abc123/command"), commands)
        val button = Json.parse(d.first { it.first.contains("/button/") }.second).obj()
        assertEquals("STOP", button.str("payload_press"))
    }

    @Test fun connectingPublishesDiscoveryAvailabilityAndLastState() {
        bridge.onSnapshot(idle)
        sent.clear()
        bridge.onConnected()
        assertTrue(sent.filter { it.topic.startsWith("homeassistant/") }.all { it.retain })
        assertEquals(bridge.discovery().size, sent.count { it.topic.startsWith("homeassistant/") })
        val online = sent.single { it.topic == "toque/abc123/availability" }
        assertEquals("online", online.payload)
        assertTrue(online.retain)
        assertEquals("idle", Json.parse(sent.last { it.topic == bridge.stateTopic }.payload).obj().str("status"))
        assertEquals(Mqtt.Will("toque/abc123/availability", "offline", true).topic, bridge.will().topic)
        assertEquals("offline", bridge.will().payload)
    }

    @Test fun stateIsReadableAndCarriesTheEndTime() {
        val m = Json.parse(bridge.statePayload(running(2 * 3600_000L + 500))).obj()
        assertEquals("running", m.str("status"))
        assertEquals("Yaourt", m.str("program"))
        assertEquals(true, m["running"])
        assertEquals(7201.0, (m["remaining_s"] as Number).toDouble(), 0.0)
        assertEquals("2026-01-01T02:00:00Z", m.str("ends_at"))
        assertEquals(37.0, (m["target_c"] as Number).toDouble(), 0.0)
        val i = Json.parse(bridge.statePayload(idle)).obj()
        assertEquals("idle", i.str("status"))
        assertNull(i["ends_at"])
        assertNull(i["target_c"])
        assertEquals(0.0, (i["remaining_s"] as Number).toDouble(), 0.0)
        // Pas encore d'échantillon de la base : température et poids inconnus, pas 0.
        val none = Json.parse(bridge.statePayload(Snapshot())).obj()
        assertEquals("offline", none.str("status"))
        assertNull(none["temperature_c"])
        assertNull(none["weight_g"])
        assertEquals("fault", bridge.status(idle.copy(faultCode = "COMM_LOST")))
        assertEquals("finished", bridge.status(idle.copy(jobState = JobState.FINISHED.name, jobLabel = "Yaourt")))
        assertEquals("paused", bridge.status(idle.copy(jobState = JobState.PAUSED.name)))
    }

    @Test fun changingStatesAreThrottledButImportantOnesGoAtOnce() {
        bridge.onSnapshot(running(3600_000L))
        assertEquals(1, sent.size)
        // Le temps qui passe, la température : au plus toutes les 10 s.
        for (i in 1..50) {
            now += 100
            bridge.onSnapshot(running(3600_000L - i * 100L, temp = 37 + i % 2))
        }
        assertEquals(1, sent.size)
        now += 5_000
        bridge.onSnapshot(running(3590_000L))
        assertEquals(2, sent.size)
        // Couvercle ouvert, fin de programme : aussitôt.
        now += 100
        bridge.onSnapshot(running(3589_900L).copy(lidOpen = true))
        assertEquals(3, sent.size)
        now += 100
        bridge.onSnapshot(idle.copy(jobState = JobState.FINISHED.name, jobLabel = "Yaourt"))
        assertEquals(4, sent.size)
        assertEquals("finished", Json.parse(sent.last().payload).obj().str("status"))
        assertTrue(sent.all { it.topic == bridge.stateTopic && it.retain })
        // Rien ne change : rien de plus pendant 5 min, puis un rappel.
        for (i in 1..20) { now += 10_000; bridge.onSnapshot(idle.copy(jobState = JobState.FINISHED.name, jobLabel = "Yaourt")) }
        assertEquals(4, sent.size)
        now += 5 * 60_000L
        bridge.onSnapshot(idle.copy(jobState = JobState.FINISHED.name, jobLabel = "Yaourt"))
        assertEquals(5, sent.size)
    }

    @Test fun endOfProgramBecomesAnEventForNotifications() {
        bridge.onSnapshot(running(1_000L))
        sent.clear()
        bridge.onEvent(MachineEvent(EventType.JOB_FINISHED, "Terminé"))
        bridge.onEvent(MachineEvent(EventType.PHASE_CHANGED, "phase 2"))
        bridge.onEvent(MachineEvent(EventType.LID_OPENED, ""))
        assertEquals(1, sent.size)
        val e = sent.single()
        assertEquals("toque/abc123/event", e.topic)
        assertFalse(e.retain)
        val m = Json.parse(e.payload).obj()
        assertEquals("finished", m.str("event_type"))
        assertEquals("Yaourt", m.str("program"))
        bridge.onEvent(MachineEvent(EventType.JOB_ABORTED, ""))
        bridge.onEvent(MachineEvent(EventType.FAULT, "Liaison perdue"))
        assertEquals(listOf("finished", "aborted", "fault"), sent.map { Json.parse(it.payload).obj().str("event_type") })
    }

    @Test fun onlyStopOnTheCommandTopicDoesAnything() {
        bridge.onMessage("toque/abc123/command", "STOP")
        bridge.onMessage("toque/abc123/command", " stop\n")
        bridge.onMessage("toque/abc123/command", "START")
        bridge.onMessage("toque/other/command", "STOP")
        bridge.onMessage("homeassistant/status", "online")
        assertEquals(2, stops)
    }

    @Test fun nodeIdsAreSafeInTopics() {
        assertEquals("mc_2020_2024", HomeAssistantBridge.cleanNodeId("MC-2020/2024"))
        assertEquals("robot", HomeAssistantBridge.cleanNodeId("#+/"))
    }

    // ------------------------------------------------------------------ connexion réelle à un faux broker

    @Test fun connectorTalksToABrokerAndReceivesStop() {
        val server = ServerSocket(0)
        val received = LinkedBlockingQueue<Mqtt.Packet>()
        val brokerDone = CountDownLatch(1)
        val broker = Thread {
            server.accept().use { s ->
                val input = s.getInputStream()
                val out = s.getOutputStream()
                val connect = Mqtt.read(input)
                received += connect
                out.write(byteArrayOf(0x20, 2, 0, 0)) // CONNACK accepté
                val sub = Mqtt.read(input)
                received += sub
                out.write(byteArrayOf(0x90.toByte(), 3, 0, 1, 0)) // SUBACK
                received += Mqtt.read(input) // la publication
                out.write(Mqtt.publish("toque/abc123/command", "STOP", retain = false))
                out.flush()
                try {
                    received += Mqtt.read(input) // DISCONNECT à l'arrêt
                } catch (_: Exception) {
                }
            }
            brokerDone.countDown()
        }.apply { isDaemon = true; start() }

        val connected = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val statuses = ArrayList<String>()
        lateinit var conn: MqttConnector
        conn = MqttConnector(
            MqttSettings("127.0.0.1", server.localPort, "ha", "pw", "toque-abc123", will = bridge.will()),
            listOf(bridge.commandTopic),
            object : MqttConnector.Listener {
                override fun onConnected() { connected.countDown(); conn.publish("toque/abc123/state", "{}", retain = true) }
                override fun onMessage(message: Mqtt.Message) {
                    bridge.onMessage(message.topic, message.text)
                    if (stops > 0) stopped.countDown()
                }
                override fun onStatus(connected: Boolean, text: String) { synchronized(statuses) { statuses += text } }
            },
        )
        conn.start()
        assertTrue(connected.await(5, TimeUnit.SECONDS))
        assertTrue(stopped.await(5, TimeUnit.SECONDS))
        assertEquals(1, stops)
        conn.stop()
        assertTrue(brokerDone.await(5, TimeUnit.SECONDS))
        broker.join(1_000)
        server.close()

        assertEquals(Mqtt.CONNECT, received.poll()!!.type)
        val sub = received.poll()!!
        assertEquals(Mqtt.SUBSCRIBE, sub.type)
        assertTrue(String(sub.body).contains("toque/abc123/command"))
        val pub = Mqtt.parsePublish(received.poll()!!)
        assertEquals("toque/abc123/state", pub.topic)
        assertEquals(Mqtt.DISCONNECT, received.poll()?.type)
        assertTrue(statuses.toString(), statuses.any { it.contains("127.0.0.1") })
    }

    @Test fun connectorReportsARefusedLogin() {
        val server = ServerSocket(0)
        Thread {
            server.accept().use { s ->
                Mqtt.read(s.getInputStream())
                s.getOutputStream().write(byteArrayOf(0x20, 2, 0, 4)) // identifiants refusés
                s.getOutputStream().flush()
                Thread.sleep(200)
            }
        }.apply { isDaemon = true; start() }
        val failed = CountDownLatch(1)
        val conn = MqttConnector(MqttSettings("127.0.0.1", server.localPort, clientId = "x"), emptyList(),
            object : MqttConnector.Listener {
                override fun onConnected() {}
                override fun onMessage(message: Mqtt.Message) {}
                override fun onStatus(connected: Boolean, text: String) { if (!connected) { lastStatus = text; failed.countDown() } }
            })
        conn.start()
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        conn.stop()
        server.close()
        assertTrue(lastStatus, lastStatus.contains(Mqtt.connackMessage(4)))
        assertFalse(conn.connected)
    }

    @Volatile private var lastStatus = ""
}
