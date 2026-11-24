package app.toque.core.ha

import app.toque.core.i18n.tr
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Le strict nécessaire de MQTT 3.1.1 pour parler à un broker (Mosquitto de Home Assistant…) :
 * connexion avec identifiants et dernière volonté, publication QoS 0, abonnement QoS 0, ping.
 * Écrit ici plutôt que pris dans une bibliothèque : Toque n'a aucune dépendance.
 */
object Mqtt {
    const val CONNECT = 1
    const val CONNACK = 2
    const val PUBLISH = 3
    const val PUBACK = 4
    const val SUBSCRIBE = 8
    const val SUBACK = 9
    const val PINGREQ = 12
    const val PINGRESP = 13
    const val DISCONNECT = 14

    /** Plus grand paquet accepté du broker : Toque ne reçoit que de courtes commandes. */
    const val MAX_INCOMING = 64 * 1024

    class Packet(val type: Int, val flags: Int, val body: ByteArray)

    /** Message reçu sur un sujet auquel on est abonné. */
    class Message(val topic: String, val payload: ByteArray, val qos: Int, val packetId: Int) {
        val text: String get() = String(payload, Charsets.UTF_8)
    }

    class Will(val topic: String, val payload: String, val retain: Boolean)

    fun connect(clientId: String, keepAliveS: Int, user: String? = null, password: String? = null, will: Will? = null): ByteArray {
        require(keepAliveS in 0..65535) { "keepalive hors plage" }
        val b = ByteArrayOutputStream()
        string(b, "MQTT")
        b.write(4) // niveau de protocole : 3.1.1
        var flags = 0x02 // session propre
        if (will != null) flags = flags or 0x04 or (if (will.retain) 0x20 else 0)
        if (!password.isNullOrEmpty() && !user.isNullOrEmpty()) flags = flags or 0x40
        if (!user.isNullOrEmpty()) flags = flags or 0x80
        b.write(flags)
        b.write(keepAliveS shr 8)
        b.write(keepAliveS and 0xFF)
        string(b, clientId)
        if (will != null) {
            string(b, will.topic)
            bytes(b, will.payload.toByteArray(Charsets.UTF_8))
        }
        if (!user.isNullOrEmpty()) {
            string(b, user)
            if (!password.isNullOrEmpty()) bytes(b, password.toByteArray(Charsets.UTF_8))
        }
        return packet(CONNECT, 0, b.toByteArray())
    }

    fun publish(topic: String, payload: String, retain: Boolean): ByteArray {
        val b = ByteArrayOutputStream()
        string(b, topic)
        b.write(payload.toByteArray(Charsets.UTF_8))
        return packet(PUBLISH, if (retain) 1 else 0, b.toByteArray())
    }

    fun subscribe(packetId: Int, topic: String): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(packetId shr 8)
        b.write(packetId and 0xFF)
        string(b, topic)
        b.write(0) // QoS 0
        return packet(SUBSCRIBE, 2, b.toByteArray())
    }

    fun puback(packetId: Int): ByteArray = packet(PUBACK, 0, byteArrayOf((packetId shr 8).toByte(), (packetId and 0xFF).toByte()))
    fun pingreq(): ByteArray = packet(PINGREQ, 0, ByteArray(0))
    fun disconnect(): ByteArray = packet(DISCONNECT, 0, ByteArray(0))

    /** Code de retour d'un CONNACK (0 = accepté), ou -1 si le paquet n'en est pas un. */
    fun connackCode(p: Packet): Int = if (p.type == CONNACK && p.body.size >= 2) p.body[1].toInt() and 0xFF else -1

    fun connackMessage(code: Int): String = when (code) {
        0 -> tr("Connexion acceptée")
        1 -> tr("Version de MQTT refusée par le broker")
        2 -> tr("Identifiant de client refusé par le broker")
        3 -> tr("Broker indisponible")
        4 -> tr("Identifiant ou mot de passe incorrect")
        5 -> tr("Connexion non autorisée par le broker")
        else -> tr("Réponse inattendue du broker (%d)", code)
    }

    fun parsePublish(p: Packet): Message {
        require(p.type == PUBLISH) { "pas un PUBLISH" }
        val body = p.body
        if (body.size < 2) throw IOException("PUBLISH tronqué")
        val len = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        if (body.size < 2 + len) throw IOException("PUBLISH tronqué")
        val topic = String(body, 2, len, Charsets.UTF_8)
        val qos = (p.flags shr 1) and 3
        var at = 2 + len
        var id = 0
        if (qos > 0) {
            if (body.size < at + 2) throw IOException("PUBLISH tronqué")
            id = ((body[at].toInt() and 0xFF) shl 8) or (body[at + 1].toInt() and 0xFF)
            at += 2
        }
        return Message(topic, body.copyOfRange(at, body.size), qos, id)
    }

    /** Lit un paquet entier ; bloque jusqu'à son arrivée. */
    fun read(input: InputStream): Packet {
        val first = input.read()
        if (first < 0) throw EOFException("liaison fermée par le broker")
        var length = 0
        var shift = 0
        for (i in 0 until 4) {
            val d = input.read()
            if (d < 0) throw EOFException("liaison fermée par le broker")
            length = length or ((d and 0x7F) shl shift)
            if (d and 0x80 == 0) break
            shift += 7
            if (i == 3) throw IOException("longueur de paquet invalide")
        }
        if (length > MAX_INCOMING) throw IOException("paquet trop long ($length octets)")
        val body = ByteArray(length)
        var got = 0
        while (got < length) {
            val n = input.read(body, got, length - got)
            if (n < 0) throw EOFException("liaison fermée par le broker")
            got += n
        }
        return Packet(first shr 4, first and 0x0F, body)
    }

    private fun packet(type: Int, flags: Int, body: ByteArray): ByteArray {
        val b = ByteArrayOutputStream()
        b.write((type shl 4) or flags)
        var len = body.size
        do {
            var d = len and 0x7F
            len = len shr 7
            if (len > 0) d = d or 0x80
            b.write(d)
        } while (len > 0)
        b.write(body)
        return b.toByteArray()
    }

    private fun string(b: ByteArrayOutputStream, s: String) = bytes(b, s.toByteArray(Charsets.UTF_8))

    private fun bytes(b: ByteArrayOutputStream, data: ByteArray) {
        require(data.size <= 0xFFFF) { "chaîne trop longue pour MQTT" }
        b.write(data.size shr 8)
        b.write(data.size and 0xFF)
        b.write(data)
    }
}
