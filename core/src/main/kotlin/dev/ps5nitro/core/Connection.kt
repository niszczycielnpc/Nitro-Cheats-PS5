package dev.ps5nitro.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.locks.ReentrantLock

/**
 * A single command connection (TCP :744). The server handles one command at a time per connection,
 * so callers must hold [lock] for the whole request/response exchange (Ps5Client does that).
 */
class Ps5Connection(
    val host: String,
    val port: Int = Wire.DEFAULT_PORT,
    private val connectTimeoutMs: Int = 5000,
) : Closeable {
    val lock = ReentrantLock()

    @Volatile
    private var socket: Socket? = null
    private var inp: InputStream? = null
    private var out: OutputStream? = null

    val isOpen: Boolean get() = socket?.let { !it.isClosed && it.isConnected } ?: false

    /** Local address of the connection - the console connects back to this IP for debug events. */
    val localAddress: String? get() = socket?.localAddress?.hostAddress

    fun open(readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS) {
        close()
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (e: IOException) {
            try { s.close() } catch (_: IOException) {}
            throw Ps5Exception("Cannot connect to $host:$port (${e.message})", e)
        }
        s.tcpNoDelay = true
        s.keepAlive = true
        s.soTimeout = readTimeoutMs
        socket = s
        inp = BufferedInputStream(s.getInputStream(), 1 shl 16)
        out = BufferedOutputStream(s.getOutputStream(), 1 shl 16)
    }

    fun setReadTimeout(ms: Int) {
        try {
            socket?.soTimeout = ms
        } catch (_: IOException) {
            // socket already closed (aborted scan); nothing to configure
        }
    }

    private fun o(): OutputStream = out ?: throw Ps5Exception("Not connected")
    private fun i(): InputStream = inp ?: throw Ps5Exception("Not connected")

    /** Sends a command header plus [body] and flushes. */
    fun send(cmd: Int, body: ByteArray = EMPTY) {
        val h = LeWriter(12 + body.size)
        h.u32(Wire.PACKET_MAGIC).u32(cmd).u32(body.size).bytes(body)
        try {
            o().write(h.toByteArray())
            o().flush()
        } catch (e: IOException) {
            throw Ps5Exception("Send failed: ${e.message}", e)
        }
    }

    /** Sends bytes without a command header (trailing data of a multi-phase command). */
    fun sendRaw(data: ByteArray, off: Int = 0, len: Int = data.size - off) {
        try {
            o().write(data, off, len)
            o().flush()
        } catch (e: IOException) {
            throw Ps5Exception("Send failed: ${e.message}", e)
        }
    }

    fun readInto(dst: ByteArray, off: Int, len: Int) {
        var done = 0
        val s = i()
        try {
            while (done < len) {
                val n = s.read(dst, off + done, len - done)
                if (n < 0) throw Ps5ProtocolException("Connection closed by the console")
                done += n
            }
        } catch (e: SocketTimeoutException) {
            throw Ps5Exception("Timed out waiting for the console", e)
        } catch (e: Ps5Exception) {
            throw e
        } catch (e: IOException) {
            throw Ps5Exception("Receive failed: ${e.message}", e)
        }
    }

    fun readFully(n: Int): ByteArray {
        if (n < 0) throw Ps5ProtocolException("negative length $n")
        val b = ByteArray(n)
        readInto(b, 0, n)
        return b
    }

    fun readI32(): Int = readFully(4).leI32(0)
    fun readU32(): Long = readI32().toLong() and 0xFFFFFFFFL
    fun readU16(): Int = readFully(2).leU16(0)
    fun readI64(): Long = readFully(8).leI64(0)

    fun readStatus(): Int = readI32()

    /** Reads a status word and throws unless it is SUCCESS. */
    fun expectSuccess(op: String) {
        val st = readStatus()
        if (st != Wire.ST_SUCCESS) throw Ps5StatusException(op, st)
    }

    /** Drops the connection immediately; any thread blocked in a read fails. */
    fun abort() {
        try { socket?.close() } catch (_: IOException) {}
    }

    override fun close() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null; inp = null; out = null
    }

    companion object {
        const val DEFAULT_READ_TIMEOUT_MS = 20_000
        const val SCAN_READ_TIMEOUT_MS = 10 * 60_000
        val EMPTY = ByteArray(0)
    }
}
