package app.toque.core.ha

import app.toque.core.i18n.tr
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Où et comment se connecter au broker. */
data class MqttSettings(
    val host: String,
    val port: Int = 1883,
    val user: String = "",
    val password: String = "",
    val clientId: String,
    val keepAliveS: Int = 30,
    val will: Mqtt.Will? = null,
)

/**
 * Garde une connexion au broker : se connecte, se reconnecte (attente croissante jusqu'à 1 min),
 * envoie les messages mis en file par [publish], reçoit ceux des abonnements. Ses propres fils :
 * rien ne touche au réseau depuis l'appelant (le fil principal d'Android l'interdit).
 */
class MqttConnector(
    private val settings: MqttSettings,
    /** Sujets auxquels s'abonner à chaque connexion. */
    private val subscriptions: List<String>,
    private val listener: Listener,
    private val connectTimeoutMs: Int = 10_000,
) {
    interface Listener {
        /** Connecté et abonné : le moment de (re)publier la découverte et l'état. Sur le fil du connecteur. */
        fun onConnected()
        fun onMessage(message: Mqtt.Message)
        /** État lisible par une personne : « Connecté à … », « Erreur : … ». */
        fun onStatus(connected: Boolean, text: String)
    }

    private class Out(val topic: String, val payload: String, val retain: Boolean)

    private val queue = LinkedBlockingQueue<Out>(QUEUE_SIZE)
    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    private var thread: Thread? = null

    @Volatile var connected = false
        private set

    fun start() {
        if (running) return
        running = true
        thread = Thread({ runLoop() }, "toque-mqtt").apply { isDaemon = true; start() }
    }

    /** Arrête tout ; envoie DISCONNECT si possible (le broker ne publie pas alors la dernière volonté). */
    fun stop() {
        running = false
        val s = socket
        if (s != null) {
            try {
                synchronized(s) { s.getOutputStream().write(Mqtt.disconnect()); s.getOutputStream().flush() }
            } catch (_: Exception) {
            }
        }
        closeSocket()
        thread?.interrupt()
        thread = null
    }

    /** Met un message en file ; perdu si la file est pleine (broker injoignable depuis longtemps). */
    fun publish(topic: String, payload: String, retain: Boolean = false): Boolean = queue.offer(Out(topic, payload, retain))

    private fun runLoop() {
        var backoffMs = 1_000L
        while (running) {
            try {
                session()
                backoffMs = 1_000L
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (!running) break
                connected = false
                listener.onStatus(false, tr("Erreur : %s", e.message ?: e.javaClass.simpleName))
            } finally {
                connected = false
                closeSocket()
            }
            if (!running) break
            try {
                Thread.sleep(backoffMs)
            } catch (_: InterruptedException) {
                break
            }
            backoffMs = minOf(backoffMs * 2, 60_000L)
        }
    }

    private fun session() {
        val s = Socket()
        socket = s
        s.connect(InetSocketAddress(settings.host, settings.port), connectTimeoutMs)
        s.tcpNoDelay = true
        s.soTimeout = connectTimeoutMs
        val input = s.getInputStream().buffered()
        val out = s.getOutputStream()
        write(s, out, Mqtt.connect(settings.clientId, settings.keepAliveS, settings.user, settings.password, settings.will))
        val ack = Mqtt.read(input)
        val code = Mqtt.connackCode(ack)
        if (code != 0) throw IOException(Mqtt.connackMessage(code))
        var id = 1
        for (t in subscriptions) write(s, out, Mqtt.subscribe(id++, t))
        connected = true
        listener.onStatus(true, tr("Connecté à %s", "${settings.host}:${settings.port}"))
        listener.onConnected()

        // Lecture sur ce fil, écriture sur un autre : la file se vide aussitôt, même sans rien recevoir.
        val writer = Thread({ writeLoop(s, out) }, "toque-mqtt-out").apply { isDaemon = true; start() }
        s.soTimeout = 0
        try {
            while (running) {
                val p = Mqtt.read(input)
                if (p.type == Mqtt.PUBLISH) {
                    val m = Mqtt.parsePublish(p)
                    if (m.qos == 1) write(s, out, Mqtt.puback(m.packetId))
                    listener.onMessage(m)
                }
                // CONNACK, SUBACK, PINGRESP : rien à faire.
            }
        } finally {
            writer.interrupt()
        }
    }

    private fun writeLoop(s: Socket, out: OutputStream) {
        val pingEveryMs = settings.keepAliveS * 1000L / 2
        var lastSent = System.currentTimeMillis()
        try {
            while (running && !s.isClosed) {
                val m = queue.poll(500, TimeUnit.MILLISECONDS)
                val now = System.currentTimeMillis()
                if (m != null) {
                    write(s, out, Mqtt.publish(m.topic, m.payload, m.retain))
                    lastSent = now
                } else if (pingEveryMs > 0 && now - lastSent >= pingEveryMs) {
                    write(s, out, Mqtt.pingreq())
                    lastSent = now
                }
            }
        } catch (_: InterruptedException) {
        } catch (_: SocketTimeoutException) {
            closeSocket()
        } catch (_: IOException) {
            // Liaison perdue : la lecture échoue aussi et la boucle principale se reconnecte.
            closeSocket()
        }
    }

    private fun write(s: Socket, out: OutputStream, data: ByteArray) {
        synchronized(s) {
            out.write(data)
            out.flush()
        }
    }

    private fun closeSocket() {
        val s = socket ?: return
        socket = null
        try {
            s.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val QUEUE_SIZE = 200
    }
}
