package home.phonelock.parent.adb

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A finished shell command: what it printed, and its exit code. */
internal class ShellResult(val output: String, val errorOutput: String, val exitCode: Int) {
    val allOutput get() = output + errorOutput
}

/**
 * A Wireless debugging connection to a device that trusts our [AdbIdentity] (paired earlier), speaking the adb
 * protocol (AOSP packages/modules/adb/protocol.txt): 24-byte little-endian message headers, a CNXN
 * handshake that the device upgrades to TLS (STLS), then one stream per command.
 *
 * Commands run one at a time; this is for setup scripts, not a general client. Not thread-safe.
 */
internal class AdbConnection private constructor(private var socket: Socket) : Closeable {
    private var input = DataInputStream(socket.inputStream.buffered())
    private var output: OutputStream = socket.outputStream.buffered()
    /** Largest payload the device accepts in one message (from its CNXN). */
    private var maxPayload = MAX_PAYLOAD_V1
    private var features = emptySet<String>()
    private var nextLocalId = 1

    /** Runs [command] in the device's shell and waits for it to finish. */
    fun shell(command: String): ShellResult {
        if ("shell_v2" in features) {
            val raw = runStream("shell,v2,raw:$command", null)
            return parseShellV2(raw)
        }
        // Old devices: no separate exit code, so append one to the output.
        val raw = String(runStream("shell:$command; echo \"\\n__EXIT:\$?\"", null))
        val exit = Regex("__EXIT:(\\d+)\\s*$").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        return ShellResult(raw.substringBeforeLast("\n__EXIT:").trimEnd('\n') + "\n", "", exit)
    }

    /** Installs (or updates) [apk] with Android's package installer; throws if it doesn't report Success. */
    fun install(apk: File) {
        val reply = String(runStream("exec:cmd package install -r -S ${apk.length()}", apk))
        if (!reply.contains("Success")) throw IOException(reply.trim().ifEmpty { "Install failed" })
    }

    /**
     * Opens a stream to [service], optionally sends [upload] through it, and returns everything the device
     * sent back until it closed the stream.
     */
    private fun runStream(service: String, upload: File?): ByteArray {
        val local = nextLocalId++
        send(A_OPEN, local, 0, (service + "\u0000").toByteArray())
        var remote = 0
        val received = ByteArrayOutputStream()

        // Wait for the device to accept the stream.
        while (remote == 0) {
            val m = read()
            when (m.command) {
                A_OKAY -> if (m.arg1 == local) remote = m.arg0
                A_CLSE -> if (m.arg1 == local) throw IOException("The device refused \"${service.substringBefore(':')}\"")
            }
        }

        // Upload, one message at a time: each must be acknowledged (OKAY) before the next.
        upload?.inputStream()?.use { file ->
            val chunk = ByteArray(minOf(maxPayload, UPLOAD_CHUNK))
            while (true) {
                val n = file.readFully(chunk)
                if (n <= 0) break
                send(A_WRTE, local, remote, chunk.copyOf(n))
                while (true) {
                    val m = read()
                    if (m.command == A_OKAY && m.arg1 == local) break
                    if (m.command == A_WRTE && m.arg1 == local) {
                        received.write(m.data)
                        send(A_OKAY, local, remote)
                    }
                    if (m.command == A_CLSE && m.arg1 == local) return received.toByteArray()
                }
            }
        }

        // Read until the device closes the stream.
        while (true) {
            val m = read()
            if (m.arg1 != local) continue
            when (m.command) {
                A_WRTE -> {
                    received.write(m.data)
                    send(A_OKAY, local, remote)
                }
                A_CLSE -> {
                    send(A_CLSE, local, remote)
                    return received.toByteArray()
                }
            }
        }
    }

    /** Splits shell v2 output into stdout, stderr and the exit code (id, 4-byte length, data per packet). */
    private fun parseShellV2(raw: ByteArray): ShellResult {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        var exit = -1
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        while (b.remaining() >= 5) {
            val id = b.get().toInt()
            val len = b.int
            if (len < 0 || len > b.remaining()) break
            val data = ByteArray(len).also { b.get(it) }
            when (id) {
                ID_STDOUT -> out.write(data)
                ID_STDERR -> err.write(data)
                ID_EXIT -> if (data.isNotEmpty()) exit = data[0].toInt() and 0xff
            }
        }
        return ShellResult(out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8), exit)
    }

    // ---------- messages ----------

    private class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    /** Header: command, arg0, arg1, data length, data checksum (unused since version 0x01000001), magic. */
    private fun send(command: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) {
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(data.size).putInt(0).putInt(command.inv())
        output.write(h.array())
        output.write(data)
        output.flush()
    }

    private fun read(): Message {
        val h = ByteArray(24).also(input::readFully)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val command = b.int
        val arg0 = b.int
        val arg1 = b.int
        val length = b.int
        b.int // checksum
        if (b.int != command.inv()) throw IOException("Corrupt adb message")
        if (length < 0 || length > MAX_PAYLOAD) throw IOException("adb message too large")
        return Message(command, arg0, arg1, ByteArray(length).also(input::readFully))
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        private const val A_CNXN = 0x4e584e43
        private const val A_OPEN = 0x4e45504f
        private const val A_OKAY = 0x59414b4f
        private const val A_CLSE = 0x45534c43
        private const val A_WRTE = 0x45545257
        private const val A_AUTH = 0x48545541
        private const val A_STLS = 0x534C5453
        private const val A_VERSION = 0x01000001
        private const val A_STLS_VERSION = 0x01000000
        private const val MAX_PAYLOAD = 1024 * 1024
        private const val MAX_PAYLOAD_V1 = 4096
        private const val UPLOAD_CHUNK = 64 * 1024
        private const val ID_STDOUT = 1
        private const val ID_STDERR = 2
        private const val ID_EXIT = 3
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val BANNER = "host::features=shell_v2,cmd"

        /**
         * Connects to the device's Wireless debugging port. Throws if it doesn't trust [identity] (not paired,
         * or pairing was removed on the device).
         */
        fun connect(identity: AdbIdentity, host: String, port: Int): AdbConnection {
            val plain = Socket()
            plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            plain.soTimeout = READ_TIMEOUT_MS
            val c = AdbConnection(plain)
            try {
                c.handshake(identity, host, port)
            } catch (e: Exception) {
                c.close()
                throw e
            }
            return c
        }

        /** Reads up to buf.size bytes, looping over short reads; returns the count (0 at end of file). */
        private fun InputStream.readFully(buf: ByteArray): Int {
            var n = 0
            while (n < buf.size) {
                val r = read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            return n
        }
    }

    /** CNXN, then the device asks for TLS (STLS); we agree, upgrade, and wait for its CNXN over TLS. */
    private fun handshake(identity: AdbIdentity, host: String, port: Int) {
        send(A_CNXN, A_VERSION, MAX_PAYLOAD, BANNER.toByteArray())
        var upgraded = false
        while (true) {
            // In TLS 1.3 our side of the handshake finishes before the device checks our certificate, so a
            // device that doesn't trust this phone shows up as the connection closing on the next read.
            val m = try {
                read()
            } catch (e: java.net.SocketTimeoutException) {
                throw IOException("The device stopped answering", e)
            } catch (e: IOException) {
                if (upgraded) throw IOException("The device doesn't trust this phone (pair it again)", e)
                throw e
            }
            when (m.command) {
                A_STLS -> {
                    send(A_STLS, A_STLS_VERSION, 0)
                    val tls = try {
                        identity.startTls(socket, host, port)
                    } catch (e: Exception) {
                        throw IOException("The device doesn't trust this phone yet (pair it first)", e)
                    }
                    socket = tls
                    input = DataInputStream(tls.inputStream.buffered())
                    output = tls.outputStream.buffered()
                    upgraded = true
                }
                A_CNXN -> {
                    maxPayload = minOf(MAX_PAYLOAD, m.arg1)
                    features = String(m.data).substringAfter("features=", "").substringBefore(';').split(',').toSet()
                    return
                }
                A_AUTH -> throw IOException("The device asked for USB-style authorization; turn on Wireless debugging and pair first")
            }
        }
    }
}
