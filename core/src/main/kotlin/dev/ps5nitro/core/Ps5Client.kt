package dev.ps5nitro.core

import java.io.Closeable

/**
 * Blocking client for the ps5debug-NG command port (TCP 744). Thread safe: every request/response
 * exchange runs under the connection lock, so UI, freeze loop and scanner can share one client.
 *
 * Call [connect] first. After an I/O error the connection is dropped; call [reconnect].
 */
class Ps5Client(val host: String, val port: Int = Wire.DEFAULT_PORT) : Closeable {
    @Volatile
    internal var conn: Ps5Connection = Ps5Connection(host, port)

    @Volatile
    internal var authed = false

    var branding: Branding? = null
        private set
    var firmware: Int = 0
        private set
    var protocolVersion: String = ""
        private set

    /** Null until [probeTurbo] ran; stays null on payloads without turbo scan. */
    var turbo: TurboCaps? = null
        internal set
    internal var turboProbed = false

    val isConnected: Boolean get() = conn.isOpen

    /** IP the console sees us at - the console connects back to it for debugger events. */
    val localAddress: String? get() = conn.localAddress

    /** Opens the connection and reads version, firmware and branding. */
    fun connect(): Branding {
        conn.open()
        authed = false
        turboProbed = false
        turbo = null
        protocolVersion = protocolVersion()
        firmware = fwVersion()
        val b = fetchBranding()
        branding = b
        return b
    }

    fun reconnect(): Branding {
        conn.close()
        conn = Ps5Connection(host, port)
        return connect()
    }

    /** Runs one exchange under the lock. Transport errors drop the connection (it is out of sync then). */
    internal fun <T> exec(markBrokenOnError: Boolean = true, block: Ps5Connection.() -> T): T {
        val c = conn
        c.lock.lock()
        try {
            return c.block()
        } catch (e: Ps5StatusException) {
            throw e
        } catch (e: Ps5Exception) {
            if (markBrokenOnError) c.abort()
            throw e
        } finally {
            c.lock.unlock()
        }
    }

    // ---------------------------------------------------------------- info

    fun ping() = exec {
        send(Op.NOP)
        expectSuccess("NOP")
    }

    fun protocolVersion(): String = exec {
        send(Op.VERSION)
        val n = readI32()
        if (n !in 0..256) throw Ps5ProtocolException("bad version length $n")
        String(readFully(n), Charsets.UTF_8)
    }

    /** Firmware as a decimal number, e.g. 960 for 9.60. */
    fun fwVersion(): Int = exec {
        send(Op.FW_VERSION)
        readU16()
    }

    private fun fetchBranding(): Branding = exec {
        send(Op.BRANDING)
        val n = readI32()
        if (n !in 0..1024) throw Ps5ProtocolException("bad branding length $n")
        val raw = readFully(n)
        val z = raw.indexOf(0)
        if (z < 0) Branding(String(raw, Charsets.UTF_8), "0.0")
        else Branding(String(raw, 0, z, Charsets.UTF_8), String(raw, z + 1, raw.size - z - 1, Charsets.UTF_8))
    }

    fun notify(text: String) = exec {
        val msg = (text + "\u0000").toByteArray(Charsets.UTF_8)
        // The header's datalen covers only the 8-byte struct, the text follows as trailing data.
        send(Op.CONSOLE_NOTIFY, LeWriter(8).u32(0).u32(msg.size).toByteArray())
        sendRaw(msg)
        expectSuccess("NOTIFY")
    }

    // ---------------------------------------------------------------- processes

    fun processList(): List<ProcessEntry> = exec {
        send(Op.PROC_LIST)
        expectSuccess("PROC_LIST")
        val n = readI32()
        if (n !in 0..65535) throw Ps5ProtocolException("bad process count $n")
        val r = LeReader(readFully(n * 36))
        List(n) { ProcessEntry(r.cstr(32), r.i32()) }
    }

    /** The game in the foreground, or null when the console shows the home screen. */
    fun foregroundApp(): ForegroundApp? = exec {
        send(Op.FOREGROUND_APP)
        expectSuccess("FOREGROUND_APP")
        val r = LeReader(readFully(140))
        val pid = r.i32()
        val titleId = r.cstr(16)
        val contentId = r.cstr(64)
        val name = r.cstr(40)
        val ver = r.cstr(16)
        if (pid == 0) null else ForegroundApp(pid, titleId, contentId, name, ver)
    }

    fun processInfo(pid: Int): ProcessInfo = exec {
        send(Op.PROC_INFO, LeWriter(4).u32(pid).toByteArray())
        expectSuccess("PROC_INFO")
        val r = LeReader(readFully(188))
        val p = r.i32()
        val name = r.cstr(40)
        val path = r.cstr(64)
        val tid = r.cstr(16)
        val cid = r.cstr(64)
        ProcessInfo(p, name, path, tid, cid)
    }

    fun maps(pid: Int): List<MapEntry> = exec {
        send(Op.PROC_MAPS, LeWriter(4).u32(pid).toByteArray())
        expectSuccess("PROC_MAPS")
        val n = readI32()
        if (n !in 0..500_000) throw Ps5ProtocolException("bad map count $n")
        val r = LeReader(readFully(n * 58))
        List(n) { MapEntry(r.cstr(32), r.i64(), r.i64(), r.i64(), r.u16()) }
    }

    // ---------------------------------------------------------------- memory

    /** Reads [len] bytes. Unreadable pages come back as zeros (that is what the server does). */
    fun read(pid: Int, addr: Long, len: Int): ByteArray = exec {
        val out = ByteArray(len)
        var done = 0
        while (done < len) {
            val n = minOf(len - done, 4 shl 20)
            send(Op.PROC_READ, LeWriter(16).u32(pid).u64(addr + done).u32(n).toByteArray())
            expectSuccess("PROC_READ")
            readInto(out, done, n)
            done += n
        }
        out
    }

    fun write(pid: Int, addr: Long, data: ByteArray) = exec {
        if (data.isEmpty()) return@exec
        send(Op.PROC_WRITE, LeWriter(16).u32(pid).u64(addr).u32(data.size).toByteArray())
        expectSuccess("PROC_WRITE(begin)")
        sendRaw(data)
        expectSuccess("PROC_WRITE")
    }

    /**
     * Many writes in one exchange (payload capability 1.0+). Returns one status byte per entry
     * (0 = ok). Falls back to single writes on older payloads.
     */
    fun writeMulti(pid: Int, entries: List<WriteEntry>): ByteArray {
        if (entries.isEmpty()) return ByteArray(0)
        if (branding?.supportsWriteMulti != true) {
            val st = ByteArray(entries.size)
            for ((i, e) in entries.withIndex()) {
                try { write(pid, e.address, e.data) } catch (_: Ps5StatusException) { st[i] = 4 }
            }
            return st
        }
        return exec {
            send(Op.PROC_WRITE_MULTI, LeWriter(12).u32(pid).u32(entries.size).u32(1).toByteArray())
            expectSuccess("WRITE_MULTI(begin)")
            val w = LeWriter(entries.sumOf { it.data.size + 12 })
            for (e in entries) w.u64(e.address).u32(e.data.size).bytes(e.data)
            sendRaw(w.toByteArray())
            val st = readFully(entries.size)
            expectSuccess("WRITE_MULTI")
            st
        }
    }

    // ---------------------------------------------------------------- auth

    /**
     * The staged scanners (SCAN_START/COUNT/GET and TURBOSCAN) only work after this handshake: the
     * console sends a 64 byte challenge, we XOR it with a fixed keystream and send it back.
     */
    fun ensureAuth() = exec {
        if (authed) return@exec
        send(Op.PROC_AUTH, LeWriter(8).u32(Wire.AUTH_MAGIC).u32(2).toByteArray())
        expectSuccess("AUTH")
        val chLen = readU16()
        if (chLen !in 1..256) throw Ps5ProtocolException("bad challenge length $chLen")
        val challenge = readFully(chLen)
        sendRaw(Keystream.respond(challenge))
        expectSuccess("AUTH(verify)")
        authed = true
    }

    /** Drops the TCP connection from any thread (unblocks a running scan); call [reconnect] afterwards. */
    fun abortConnection() {
        conn.abort()
    }

    override fun close() {
        conn.close()
    }
}

/** Challenge/response keystream of CMD_PROC_AUTH (auth.c: xorshift-like LFSR seeded 200,300,400,500). */
object Keystream {
    fun bytes(): ByteArray {
        var s1 = 200
        var s2 = 300
        var s3 = 400
        var s4 = 500
        val out = ByteArray(256)
        for (i in 0 until 256) {
            s1 = ((s1 shl 18) and 0xFFF80000.toInt()) xor ((s1 xor (s1 shl 6)) ushr 13)
            s2 = ((s2 shl 2) and 0xFFFFFFE0.toInt()) xor ((s2 xor (s2 shl 2)) ushr 27)
            s3 = ((s3 shl 7) and 0xFFFFF800.toInt()) xor ((s3 xor (s3 shl 13)) ushr 21)
            s4 = ((s4 shl 13) and 0xFFF00000.toInt()) xor ((s4 xor (s4 shl 3)) ushr 12)
            out[i] = (s1 xor s2 xor s3 xor s4).toByte()
        }
        return out
    }

    fun respond(challenge: ByteArray): ByteArray {
        val ks = bytes()
        return ByteArray(challenge.size) { (challenge[it].toInt() xor ks[it and 0xFF].toInt()).toByte() }
    }
}
