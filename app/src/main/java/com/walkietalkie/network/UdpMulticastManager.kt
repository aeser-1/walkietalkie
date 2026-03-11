package com.walkietalkie.network

import android.content.Context
import android.net.wifi.WifiManager
import java.net.*
import java.nio.ByteBuffer

/**
 * Manages UDP multicast communication over the local network.
 *
 * Packet layout (bytes):
 *   [0..3]   Magic "WTKT"
 *   [4]      Type: 0=AUDIO, 1=PING, 2=LEAVE
 *   [5..36]  Username  (32 bytes, null-padded UTF-8)
 *   [37..68] Group     (32 bytes, null-padded UTF-8)
 *   [69..70] Data length (unsigned short, big-endian)
 *   [71..]   Payload (audio PCM bytes; empty for PING/LEAVE)
 */
class UdpMulticastManager(private val context: Context) {

    companion object {
        private const val MULTICAST_ADDRESS = "239.255.42.99"
        private const val PORT = 9876
        private val MAGIC = byteArrayOf('W'.code.toByte(), 'T'.code.toByte(), 'K'.code.toByte(), 'T'.code.toByte())
        private const val USERNAME_SIZE = 32
        private const val GROUP_SIZE = 32
        // 4 magic + 1 type + 32 username + 32 group + 2 data-len = 71
        private const val HEADER_SIZE = 71

        const val TYPE_AUDIO: Byte = 0
        const val TYPE_PING: Byte = 1
        const val TYPE_LEAVE: Byte = 2

        // Security limits
        /** Maximum accepted audio payload per packet (~160 ms at 8 kHz / 16-bit mono). */
        private const val MAX_AUDIO_BYTES = 2560
        /** PING/LEAVE must carry zero payload. */
        private const val MAX_CONTROL_BYTES = 0
        /** Minimum ms between accepted packets from the same username (≈50 pkt/s max). */
        private const val RATE_LIMIT_MS = 20L
        /** Hard cap on number of tracked remote users to prevent memory exhaustion. */
        private const val MAX_TRACKED_USERS = 50
        /** Allowlist for usernames and group names received over the network. */
        private val SAFE_NAME_REGEX = Regex("^[A-Za-z0-9 _-]{1,20}$")
    }

    data class Packet(
        val type: Byte,
        val username: String,
        val group: String,
        val data: ByteArray
    )

    @Volatile var username: String = ""
    @Volatile var group: String = ""

    var onPacketReceived: ((Packet) -> Unit)? = null

    // username -> last accepted packet timestamp (ms) — rate limiting
    private val lastPacketTime = HashMap<String, Long>()

    private var socket: MulticastSocket? = null
    private var groupAddress: InetAddress? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var receiveThread: Thread? = null
    private var pingThread: Thread? = null

    @Volatile private var running = false

    fun start() {
        if (running) return
        acquireMulticastLock()
        try {
            groupAddress = InetAddress.getByName(MULTICAST_ADDRESS)
            socket = MulticastSocket(PORT).apply {
                soTimeout = 1000
                joinGroup(groupAddress)
            }
            running = true
            startReceiveThread()
            startPingThread()
        } catch (e: Exception) {
            e.printStackTrace()
            releaseMulticastLock()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        // Send leave packet synchronously before closing
        try { sendDirect(TYPE_LEAVE, ByteArray(0)) } catch (_: Exception) {}
        receiveThread?.interrupt()
        pingThread?.interrupt()
        try {
            socket?.leaveGroup(groupAddress)
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        releaseMulticastLock()
    }

    /** Called from the audio record thread — sends directly (no extra thread). */
    fun sendAudio(audioData: ByteArray) {
        if (!running) return
        try {
            sendDirect(TYPE_AUDIO, audioData)
        } catch (e: Exception) {
            if (running) e.printStackTrace()
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun startReceiveThread() {
        receiveThread = Thread {
            val buffer = ByteArray(65536)
            while (running) {
                try {
                    val datagram = DatagramPacket(buffer, buffer.size)
                    socket?.receive(datagram)
                    parsePacket(buffer, datagram.length)?.let { pkt ->
                        if (pkt.group == group && !isRateLimited(pkt.username)) {
                            onPacketReceived?.invoke(pkt)
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    // expected — loop again
                } catch (e: Exception) {
                    if (running) e.printStackTrace()
                }
            }
        }.also { it.isDaemon = true; it.name = "wt-receive"; it.start() }
    }

    private fun startPingThread() {
        pingThread = Thread {
            while (running) {
                try {
                    sendDirect(TYPE_PING, ByteArray(0))
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (running) e.printStackTrace()
                }
            }
        }.also { it.isDaemon = true; it.name = "wt-ping"; it.start() }
    }

    /**
     * Returns true (and drops the packet) when a username sends packets faster than
     * RATE_LIMIT_MS. Also evicts the oldest entry when the table exceeds MAX_TRACKED_USERS
     * to prevent memory exhaustion from fake usernames.
     */
    private fun isRateLimited(senderUsername: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastPacketTime[senderUsername]
        if (last != null && now - last < RATE_LIMIT_MS) return true

        // Prevent unbounded map growth
        if (lastPacketTime.size >= MAX_TRACKED_USERS && !lastPacketTime.containsKey(senderUsername)) {
            // Evict the stalest entry
            val oldest = lastPacketTime.minByOrNull { it.value }?.key
            if (oldest != null) lastPacketTime.remove(oldest)
        }
        lastPacketTime[senderUsername] = now
        return false
    }

    private fun sendDirect(type: Byte, data: ByteArray) {
        val packetBytes = buildPacket(type, data)
        val dp = DatagramPacket(packetBytes, packetBytes.size, groupAddress, PORT)
        socket?.send(dp)
    }

    private fun buildPacket(type: Byte, data: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE + data.size)
        buf.put(MAGIC)
        buf.put(type)
        buf.put(padString(username, USERNAME_SIZE))
        buf.put(padString(group, GROUP_SIZE))
        buf.putShort(data.size.toShort())
        if (data.isNotEmpty()) buf.put(data)
        return buf.array()
    }

    private fun parsePacket(raw: ByteArray, length: Int): Packet? {
        if (length < HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(raw, 0, length)

        val magic = ByteArray(4).also { buf.get(it) }
        if (!magic.contentEquals(MAGIC)) return null

        val type = buf.get()
        val usernameBytes = ByteArray(USERNAME_SIZE).also { buf.get(it) }
        val groupBytes = ByteArray(GROUP_SIZE).also { buf.get(it) }
        val dataLen = buf.short.toInt() and 0xFFFF

        // Validate packet type — reject unknown types entirely
        if (type != TYPE_AUDIO && type != TYPE_PING && type != TYPE_LEAVE) return null

        // Enforce payload size limits per type
        val maxPayload = if (type == TYPE_AUDIO) MAX_AUDIO_BYTES else MAX_CONTROL_BYTES
        if (dataLen > maxPayload) return null

        val parsedUsername = trimNulls(usernameBytes)
        val parsedGroup    = trimNulls(groupBytes)

        // Validate username and group against the same allowlist enforced in Settings
        if (!SAFE_NAME_REGEX.matches(parsedUsername)) return null
        if (!SAFE_NAME_REGEX.matches(parsedGroup))    return null

        val payload = if (dataLen > 0 && buf.remaining() >= dataLen) {
            ByteArray(dataLen).also { buf.get(it) }
        } else ByteArray(0)

        return Packet(
            type = type,
            username = parsedUsername,
            group = parsedGroup,
            data = payload
        )
    }

    private fun padString(s: String, size: Int): ByteArray {
        val src = s.toByteArray(Charsets.UTF_8)
        return ByteArray(size).also { src.copyInto(it, 0, 0, minOf(src.size, size)) }
    }

    private fun trimNulls(bytes: ByteArray): String {
        val end = bytes.indexOfFirst { it == 0.toByte() }.let { if (it < 0) bytes.size else it }
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    private fun acquireMulticastLock() {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wm.createMulticastLock("WalkieTalkieLock").apply {
            setReferenceCounted(true)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
    }
}
