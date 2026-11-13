package dev.ps5nitro.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * In-process imitation of ps5debug-NG v1.3.x for tests. It is written from PROTOCOL.md and the server
 * sources (scan.c, scan_turbo.c, scan_compare.c, auth.c, debug.c). Every opcode, size and status
 * value is spelled out again here on purpose, so a typo in the client's constants shows up as a failure.
 */
class MockPs5Server(
    private val brand: String = "ps5debug-NG by OSR v1.3.2 [0123456789abcdef]",
    private val capability: String = "1.1",
    private val turboEngines: Int = 0,
    private val fw: Int = 960,
    /** Survivor limit of the server-resident store; above it the console declines (like TS_RESIDENT_CAP). */
    private val residentCap: Int = Int.MAX_VALUE,
) : Closeable {

    // ---- status words as the server defines them (macro values), bit-swapped when sent -----------------
    private object S {
        const val SUCCESS = 0x40000000
        const val ERROR = 0xF0000002.toInt()
        const val DATA_NULL = 0xF0000003.toInt()
        const val ALREADY_DEBUG = 0xF0000008.toInt()
        const val INVALID_INDEX = 0xF000000A.toInt()
    }

    private fun bitswap32(x: Int): Int = ((x ushr 1) and 0x55555555) or ((x shl 1) and 0xAAAAAAAA.toInt())

    // ---- the memory of the pretend game -------------------------------------------------------------------
    class Region(val name: String, val start: Long, val data: ByteArray, val prot: Int) {
        val end: Long get() = start + data.size
    }

    class Proc(
        val pid: Int,
        val name: String,
        val titleId: String = "PPSA01342",
        val contentId: String = "EP0000-PPSA01342_00-TESTTESTTESTTEST",
        val appVer: String = "01.004.000",
    ) {
        val regions = ArrayList<Region>()
    }

    private val lock = Any()
    private val procs = LinkedHashMap<Int, Proc>()

    @Volatile var foregroundPid = 0
    @Volatile var lastNotification: String? = null

    fun addProcess(p: Proc): Proc {
        synchronized(lock) { procs[p.pid] = p }
        return p
    }

    fun peek(pid: Int, addr: Long, len: Int): ByteArray = readMem(pid, addr, len) ?: error("no such pid $pid")
    fun poke(pid: Int, addr: Long, data: ByteArray): Boolean = writeMem(pid, addr, data)

    private fun readMem(pid: Int, addr: Long, len: Int): ByteArray? = synchronized(lock) {
        val p = procs[pid] ?: return null
        val out = ByteArray(len)
        for (r in p.regions) {
            val s = maxOf(addr, r.start)
            val e = minOf(addr + len, r.end)
            if (s < e) System.arraycopy(r.data, (s - r.start).toInt(), out, (s - addr).toInt(), (e - s).toInt())
        }
        out
    }

    private fun writeMem(pid: Int, addr: Long, data: ByteArray): Boolean = synchronized(lock) {
        val p = procs[pid] ?: return false
        var covered = 0L
        for (r in p.regions) {
            val s = maxOf(addr, r.start)
            val e = minOf(addr + data.size, r.end)
            if (s < e) covered += e - s
        }
        if (covered != data.size.toLong()) return false
        for (r in p.regions) {
            val s = maxOf(addr, r.start)
            val e = minOf(addr + data.size, r.end)
            if (s < e) System.arraycopy(data, (s - addr).toInt(), r.data, (s - r.start).toInt(), (e - s).toInt())
        }
        true
    }

    // ---- bookkeeping tests can look at ------------------------------------------------------------------
    private val opCounts = ConcurrentHashMap<Int, AtomicInteger>()
    fun opCount(op: Int): Int = opCounts[op]?.get() ?: 0

    @Volatile private var authState = 0x14000
    val authorised: Boolean get() = authState and 2 != 0

    // ---- debugger session (one at a time, like the real server) -----------------------------------------
    class Watch(val enabled: Boolean, val length: Int, val breakType: Int, val address: Long)

    /** Port the console dials for interrupts (the real one is always 755; tests listen elsewhere). */
    @Volatile var interruptPort = 755
    @Volatile var attachedPid = 0
        private set
    private val watchpoints = arrayOfNulls<Watch>(4)
    fun watchpoint(i: Int): Watch? = synchronized(lock) { watchpoints[i] }
    private val continues = AtomicInteger()
    val continueCount: Int get() = continues.get()
    private var irq: Socket? = null
    private val lastRegs = ConcurrentHashMap<Int, ByteArray>()
    val threads = listOf(1001, 1002)

    /** Sends a debugger stop (1184-byte packet) the way dispatch_debug_events does. */
    fun interrupt(lwpid: Int, rip: Long, regs: Map<Int, Long> = emptyMap(), signal: Int = 5) {
        val bb = ByteBuffer.allocate(1184).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0, lwpid)
        bb.putInt(4, (signal shl 8) or 0x7F)
        val name = "GameThread".toByteArray()
        for (i in name.indices) bb.put(8 + i, name[i])
        for ((off, v) in regs) bb.putLong(0x30 + off, v)
        bb.putLong(0x30 + 0x88, rip)
        bb.putLong(0x420 + 6 * 8, 1)
        lastRegs[lwpid] = bb.array().copyOfRange(0x30, 0x30 + 176)
        val s = irq ?: error("not attached")
        synchronized(s) {
            // one stream object: the tracer may detach (closing the socket) as soon as it has read this packet
            val o = s.getOutputStream()
            o.write(bb.array())
            o.flush()
        }
    }

    // ---- sockets --------------------------------------------------------------------------------------------
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    @Volatile private var closed = false
    private val sockets = Collections.synchronizedList(ArrayList<Socket>())

    init {
        thread(isDaemon = true, name = "mock-ps5-accept") {
            while (!closed) {
                val s = try { server.accept() } catch (_: IOException) { break }
                s.tcpNoDelay = true
                sockets.add(s)
                thread(isDaemon = true, name = "mock-ps5-conn") { serve(s) }
            }
        }
    }

    override fun close() {
        closed = true
        try { server.close() } catch (_: IOException) {}
        synchronized(sockets) { for (s in sockets) try { s.close() } catch (_: IOException) {} }
        try { irq?.close() } catch (_: IOException) {}
    }

    private class Rd(val b: ByteArray) {
        var p = 0
        fun u8(): Int = b[p++].toInt() and 0xFF
        fun u16(): Int = u8() or (u8() shl 8)
        fun u32(): Int = u8() or (u8() shl 8) or (u8() shl 16) or (u8() shl 24)
        fun u64(): Long = (u32().toLong() and 0xFFFFFFFFL) or (u32().toLong() shl 32)
    }

    private inner class Conn(val sock: Socket) {
        private val inp = DataInputStream(BufferedInputStream(sock.getInputStream(), 1 shl 16))
        private val out = BufferedOutputStream(sock.getOutputStream(), 1 shl 16)
        var turbo: Turbo? = null

        fun i32(): Int { out.flush(); return Integer.reverseBytes(inp.readInt()) }
        fun bytes(n: Int): ByteArray { out.flush(); val b = ByteArray(n); inp.readFully(b); return b }

        fun w8(v: Int) = out.write(v)
        fun w16(v: Int) { out.write(v); out.write(v ushr 8) }
        fun w32(v: Int) { for (i in 0 until 4) out.write(v ushr (8 * i)) }
        fun w64(v: Long) { for (i in 0 until 8) out.write((v ushr (8 * i)).toInt()) }
        fun wb(b: ByteArray) = out.write(b)
        fun wb(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        fun status(macro: Int) = w32(bitswap32(macro))
        fun flush() = out.flush()
        fun cstr(s: String, n: Int) { val b = ByteArray(n); val sb = s.toByteArray(); System.arraycopy(sb, 0, b, 0, minOf(sb.size, n - 1)); wb(b) }
    }

    private fun serve(sock: Socket) {
        val c = Conn(sock)
        try {
            while (true) {
                val magic = c.i32()
                val cmd = c.i32()
                val datalen = c.i32()
                if (magic != 0xFFAABBCC.toInt()) continue
                if (datalen < 0 || datalen > 0x100000) { c.status(S.ERROR); c.flush(); continue }
                val body = if (datalen > 0) c.bytes(datalen) else ByteArray(0)
                opCounts.getOrPut(cmd) { AtomicInteger() }.incrementAndGet()
                dispatch(c, cmd, body)
                c.flush()
            }
        } catch (_: EOFException) {
        } catch (_: IOException) {
        } finally {
            try { sock.close() } catch (_: IOException) {}
        }
    }

    // ---- command dispatch (opcodes are copied from section 4 of PROTOCOL.md) ---------------------------------
    private fun dispatch(c: Conn, cmd: Int, body: ByteArray) {
        val r = Rd(body)
        when (cmd) {
            0xBD000001.toInt() -> { val v = "1.3".toByteArray(); c.w32(v.size); c.wb(v) }
            0xBD000500.toInt() -> c.w16(fw)
            0xBD000501.toInt() -> {
                val b = brand.toByteArray() + byteArrayOf(0) + capability.toByteArray()
                c.w32(b.size); c.wb(b)
            }
            0xBD000502.toInt() -> c.w16(5)
            0xBDAACC06.toInt() -> c.status(S.SUCCESS)

            0xBDAA0001.toInt() -> procList(c)
            0xBDAA0002.toInt() -> procRead(c, r)
            0xBDAA0003.toInt() -> procWrite(c, r)
            0xBDAACC04.toInt() -> procWriteMulti(c, r)
            0xBDAA0004.toInt() -> procMaps(c, r)
            0xBDAA000A.toInt() -> procInfo(c, r)
            0xBDAA0020.toInt() -> procDisasm(c, r)
            0xBDAA0022.toInt() -> procXrefsTo(c, r)
            0xBDAACCFF.toInt() -> procAuth(c, r)
            0xBDAACC01.toInt() -> scanStart(c, r)
            0xBDAACC02.toInt() -> scanCount(c, r)
            0xBDAACC03.toInt() -> scanGet(c, r)
            0xBDAACC10.toInt() -> turboCaps(c)
            0xBDAACC11.toInt() -> turboStart(c, r)
            0xBDAACC12.toInt() -> turboCount(c, r)
            0xBDAACC13.toInt() -> turboGet(c, r)
            0xBDAACC14.toInt() -> if (!authorised) c.status(S.DATA_NULL) else { c.turbo = null; c.status(S.SUCCESS) }
            0xBDAACC16.toInt() -> turboRegions(c, r)
            0xBDAACC17.toInt() -> if (!authorised || body.size < 4 || r.u32() == 0) c.status(S.DATA_NULL) else c.status(S.SUCCESS)

            0xBDBB0001.toInt() -> debugAttach(c, r, body)
            0xBDBB0002.toInt() -> debugDetach(c)
            0xBDBB0004.toInt() -> debugWatchpoint(c, r)
            0xBDBB0005.toInt() -> if (attachedPid == 0) c.status(S.ERROR) else {
                c.status(S.SUCCESS); c.w32(threads.size); for (t in threads) c.w32(t)
            }
            0xBDBB0008.toInt() -> if (attachedPid == 0) c.status(S.ERROR) else {
                c.status(S.SUCCESS); c.wb(lastRegs[r.u32()] ?: ByteArray(176))
            }
            0xBDBB0010.toInt() -> if (attachedPid == 0) c.status(S.ERROR) else { continues.incrementAndGet(); c.status(S.SUCCESS) }

            0xBDDD0004.toInt() -> {
                r.u32()
                val n = r.u32()
                val text = c.bytes(n)
                lastNotification = String(text, 0, if (n > 0 && text[n - 1].toInt() == 0) n - 1 else n)
                c.status(S.SUCCESS)
            }
            0xBDDD0006.toInt() -> foreground(c)
            else -> c.status(S.ERROR)
        }
    }

    private fun procList(c: Conn) {
        val list = synchronized(lock) { procs.values.toList() }
        c.status(S.SUCCESS); c.w32(list.size)
        for (p in list) { c.cstr(p.name, 32); c.w32(p.pid) }
    }

    private fun procRead(c: Conn, r: Rd) {
        val pid = r.u32(); val addr = r.u64(); val len = r.u32()
        val data = readMem(pid, addr, len)
        if (data == null) { c.status(S.ERROR); return }
        c.status(S.SUCCESS); c.wb(data)
    }

    private fun procWrite(c: Conn, r: Rd) {
        val pid = r.u32(); val addr = r.u64(); val len = r.u32()
        c.status(S.SUCCESS)
        val data = c.bytes(len)
        c.status(if (writeMem(pid, addr, data)) S.SUCCESS else S.ERROR)
    }

    private fun procWriteMulti(c: Conn, r: Rd) {
        val pid = r.u32(); val count = r.u32(); val flags = r.u32()
        c.status(S.SUCCESS)
        val st = ByteArray(count)
        var anyFail = false
        for (i in 0 until count) {
            val addr = java.lang.Long.reverseBytes(java.nio.ByteBuffer.wrap(c.bytes(8)).long)
            val len = Integer.reverseBytes(java.nio.ByteBuffer.wrap(c.bytes(4)).int)
            val data = c.bytes(len)
            if (!writeMem(pid, addr, data)) { st[i] = 4; anyFail = true }
        }
        if (flags and 1 != 0) c.wb(st)
        c.status(if (anyFail && flags and 1 == 0) S.ERROR else S.SUCCESS)
    }

    private fun procMaps(c: Conn, r: Rd) {
        val pid = r.u32()
        val p = synchronized(lock) { procs[pid] }
        if (p == null) { c.status(S.ERROR); return }
        c.status(S.SUCCESS); c.w32(p.regions.size)
        for (g in p.regions) { c.cstr(g.name, 32); c.w64(g.start); c.w64(g.end); c.w64(0); c.w16(g.prot) }
    }

    private fun procInfo(c: Conn, r: Rd) {
        val pid = r.u32()
        val p = synchronized(lock) { procs[pid] }
        if (p == null) { c.status(S.ERROR); return }
        c.status(S.SUCCESS); c.w32(p.pid); c.cstr(p.name, 40); c.cstr("/app0/" + p.name, 64)
        c.cstr(p.titleId, 16); c.cstr(p.contentId, 64)
    }

    private fun foreground(c: Conn) {
        val p = synchronized(lock) { procs[foregroundPid] }
        c.status(S.SUCCESS)
        if (p == null) { c.wb(ByteArray(140)); return }
        c.w32(p.pid); c.cstr(p.titleId, 16); c.cstr(p.contentId, 64); c.cstr(p.name, 40); c.cstr(p.appVer, 16)
    }

    // ---- auth (auth.c) ------------------------------------------------------------------------------------------
    private fun procAuth(c: Conn, r: Rd) {
        val magic = r.u32(); val flags = r.u32()
        if (bitswap32(magic) != 0x7780D98E) { c.status(S.DATA_NULL); return }
        c.status(S.SUCCESS)
        val challenge = ByteArray(64) { (it * 37 + 11).toByte() }
        c.w16(64); c.wb(challenge)
        val resp = c.bytes(64)
        val key = keystream()
        var ok = true
        for (i in 0 until 64) if (resp[i] != (challenge[i].toInt() xor key[i and 0xFF].toInt()).toByte()) ok = false
        if (!ok) { c.status(S.DATA_NULL); return }
        authState = if (flags and 2 != 0) 0x14002 else if (flags and 1 != 0) 0x14001 else authState
        c.status(S.SUCCESS)
    }

    /** auth.c keystream: four shift registers seeded 200, 300, 400, 500; 256 bytes. */
    private fun keystream(): ByteArray {
        var s1 = 200; var s2 = 300; var s3 = 400; var s4 = 500
        val k = ByteArray(256)
        for (i in 0 until 256) {
            s1 = ((s1 shl 18) and 0xFFF80000.toInt()) xor (((s1 xor (s1 shl 6))) ushr 13)
            s2 = ((s2 shl 2) and 0xFFFFFFE0.toInt()) xor (((s2 xor (s2 shl 2))) ushr 27)
            s3 = ((s3 shl 7) and 0xFFFFF800.toInt()) xor (((s3 xor (s3 shl 13))) ushr 21)
            s4 = ((s4 shl 13) and 0xFFF00000.toInt()) xor (((s4 xor (s4 shl 3))) ushr 12)
            k[i] = ((s1 xor s2 xor s3 xor s4) and 0xFF).toByte()
        }
        return k
    }

    // ---- value comparison (scan_compare.c proc_scan_compareValues) ---------------------------------------------------
    private fun size(t: Int) = when (t) { 0, 1 -> 1; 2, 3 -> 2; 4, 5, 8 -> 4; 6, 7, 9 -> 8; else -> 0 }
    private fun uval(b: ByteArray, o: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i))
        return v
    }
    private fun sval(b: ByteArray, o: Int, n: Int): Long { val sh = 64 - 8 * n; return (uval(b, o, n) shl sh) shr sh }
    private fun fval(t: Int, b: ByteArray, o: Int): Double =
        if (t == 8) java.lang.Float.intBitsToFloat(uval(b, o, 4).toInt()).toDouble()
        else java.lang.Double.longBitsToDouble(uval(b, o, 8))

    /** Ordering of two values of type [t]; null when unordered (NaN). */
    private fun ord(t: Int, a: ByteArray, ao: Int, b: ByteArray, bo: Int): Int? {
        val n = size(t)
        return when (t) {
            0, 2, 4 -> uval(a, ao, n).compareTo(uval(b, bo, n))
            6 -> java.lang.Long.compareUnsigned(uval(a, ao, 8), uval(b, bo, 8))
            1, 3, 5, 7 -> sval(a, ao, n).compareTo(sval(b, bo, n))
            8, 9 -> {
                val x = fval(t, a, ao); val y = fval(t, b, bo)
                if (x.isNaN() || y.isNaN()) null else if (x < y) -1 else if (x > y) 1 else 0
            }
            else -> null
        }
    }

    private fun fuzzyF(a: Float, b: Float): Boolean {
        if (a == b) return true
        val diff = Math.abs(a - b)
        val tol = 1e-6f
        if (a == 0f || b == 0f) return java.lang.Float.MIN_NORMAL * tol > diff
        val sum = Math.abs(a) + Math.abs(b)
        if (java.lang.Float.MIN_NORMAL > sum) return java.lang.Float.MIN_NORMAL * tol > diff
        return tol > diff / sum
    }

    private fun fuzzyD(a: Double, b: Double): Boolean {
        if (a == b) return true
        val diff = Math.abs(a - b)
        val tol = 1e-6
        if (a == 0.0 || b == 0.0) return java.lang.Double.MIN_NORMAL * tol > diff
        val sum = Math.abs(a) + Math.abs(b)
        if (java.lang.Double.MIN_NORMAL > sum) return java.lang.Double.MIN_NORMAL * tol > diff
        return tol > diff / sum
    }

    private fun by(t: Int, mem: ByteArray, mo: Int, base: ByteArray, bo: Int, delta: ByteArray, sign: Int): Boolean {
        val n = size(t)
        return when (t) {
            8 -> {
                val p = java.lang.Float.intBitsToFloat(uval(base, bo, 4).toInt())
                val d = java.lang.Float.intBitsToFloat(uval(delta, 0, 4).toInt())
                java.lang.Float.intBitsToFloat(uval(mem, mo, 4).toInt()) == (if (sign > 0) p + d else p - d)
            }
            9 -> {
                val p = java.lang.Double.longBitsToDouble(uval(base, bo, 8))
                val d = java.lang.Double.longBitsToDouble(uval(delta, 0, 8))
                java.lang.Double.longBitsToDouble(uval(mem, mo, 8)) == (if (sign > 0) p + d else p - d)
            }
            else -> {
                val bits = 8 * n
                val maskAll = if (bits == 64) -1L else (1L shl bits) - 1
                val expect = if (sign > 0) uval(base, bo, n) + uval(delta, 0, n) else uval(base, bo, n) - uval(delta, 0, n)
                (expect and maskAll) == uval(mem, mo, n)
            }
        }
    }

    private fun compare(
        cmp: Int, t: Int, vlen: Int, scan: ByteArray?, mem: ByteArray, mo: Int,
        prev: ByteArray?, po: Int, mask: ByteArray?,
    ): Boolean {
        if (cmp in 4..10 && prev == null) return false
        when (cmp) {
            0 -> {
                if (t == 10) {
                    for (j in 0 until vlen) if (scan!![j] != mem[mo + j] && mask != null && mask[j].toInt() == 1) return false
                    return true
                }
                if (t == 8) {
                    if (uval(scan!!, 0, 4) == 0L) return uval(mem, mo, 4) == 0L
                    return fuzzyF(fval(8, scan, 0).toFloat(), fval(8, mem, mo).toFloat())
                }
                if (t == 9) {
                    if (uval(scan!!, 0, 8) == 0L) return uval(mem, mo, 8) == 0L
                    return fuzzyD(fval(9, scan, 0), fval(9, mem, mo))
                }
                for (j in 0 until vlen) if (scan!![j] != mem[mo + j]) return false
                return true
            }
            1 -> {
                if (t == 8 || t == 9) {
                    val d = fval(t, scan!!, 0) - fval(t, mem, mo)
                    return d < 1.0 && d > -1.0
                }
                return false
            }
            2 -> return (ord(t, mem, mo, scan!!, 0) ?: return false) > 0
            3 -> return (ord(t, mem, mo, scan!!, 0) ?: return false) < 0
            4 -> {
                val hi = prev!!
                val toHi = ord(t, hi, po, scan!!, 0) ?: return false
                val a = ord(t, mem, mo, scan, 0) ?: return false
                val b = ord(t, mem, mo, hi, po) ?: return false
                return if (toHi > 0) a >= 0 && b <= 0 else a <= 0 && b >= 0
            }
            5 -> return (ord(t, mem, mo, prev!!, po) ?: return false) > 0
            6 -> return by(t, mem, mo, prev!!, po, scan!!, +1)
            7 -> return (ord(t, mem, mo, prev!!, po) ?: return false) < 0
            8 -> return by(t, mem, mo, prev!!, po, scan!!, -1)
            9 -> return (ord(t, mem, mo, prev!!, po) ?: return true) != 0
            10 -> return (ord(t, mem, mo, prev!!, po) ?: return false) == 0
            11 -> {
                val n = size(t)
                if (n == 0) return false
                return if (t == 8 || t == 9) fval(t, mem, mo) != 0.0 else uval(mem, mo, n) != 0L
            }
            12 -> {
                val n = size(t)
                if (n == 0) return false
                if (t == 8 || t == 9) {
                    val v = fval(t, mem, mo)
                    return v != 0.0 && Math.abs(v) <= fval(t, scan!!, 0)
                }
                if (uval(mem, mo, n) == 0L) return false
                val a = if (t == 6 || t == 0 || t == 2 || t == 4) uval(mem, mo, n) else sval(mem, mo, n)
                val b = if (t == 6 || t == 0 || t == 2 || t == 4) uval(scan!!, 0, n) else sval(scan!!, 0, n)
                return java.lang.Long.compareUnsigned(a, b) <= 0
            }
        }
        return false
    }

    // ---- iterative scan (scan.c) -------------------------------------------------------------------------------------
    private val needsValueTable = intArrayOf(1, 1, 1, 1, 1, 0, 1, 0, 1, 0, 0, 0, 1, 0, 0, 0)

    /** Collects (u32 offset, value) entries and ships them as u64-length-prefixed blocks. */
    private inner class BlockOut(val c: Conn, val vlen: Int) {
        private val buf = ByteArrayOutputStream()
        private val limit = 0x3FFE8 - vlen
        fun add(offset: Int, mem: ByteArray, mo: Int) {
            if (buf.size() > limit) flush()
            for (i in 0 until 4) buf.write(offset ushr (8 * i))
            buf.write(mem, mo, vlen)
        }
        fun flush() {
            if (buf.size() == 0) return
            c.w64(buf.size().toLong()); c.wb(buf.toByteArray()); buf.reset()
        }
    }

    private fun scanStart(c: Conn, r: Rd) {
        if (!authorised) { c.status(S.DATA_NULL); return }
        val pid = r.u32(); val address = r.u64(); val length = r.u32()
        val type = r.u8(); val cmp = r.u8(); val alignment = r.u8(); val lenData = r.u32()
        if (cmp > 12 || lenData == 0) { c.status(S.DATA_NULL); return }
        val needsValue = needsValueTable[cmp] != 0 || cmp == 4
        var vlen = size(type); if (vlen == 0) vlen = lenData
        c.status(S.SUCCESS)
        val pattern = if (needsValue) c.bytes(lenData) else null
        val mask = if (type == 10) c.bytes(vlen) else null
        c.status(S.SUCCESS)
        val step = if (alignment != 0) alignment else vlen
        val mem = readMem(pid, address, length) ?: ByteArray(length)
        val hi = if (cmp == 4 && pattern != null && pattern.size >= 2 * vlen) pattern.copyOfRange(vlen, 2 * vlen) else null
        val out = BlockOut(c, vlen)
        var j = 0
        while (j + vlen <= length) {
            if (compare(cmp, type, vlen, pattern, mem, j, hi, 0, mask)) out.add(j, mem, j)
            j += step
        }
        out.flush()
        c.w64(-1L)
        c.status(S.SUCCESS)
    }

    private fun scanCount(c: Conn, r: Rd) {
        if (!authorised) { c.status(S.DATA_NULL); return }
        val pid = r.u32(); val base = r.u64(); val type = r.u8(); val cmp = r.u8(); val lenData = r.u32()
        if (cmp > 12) { c.status(S.DATA_NULL); return }
        var vlen = size(type); if (vlen == 0) vlen = lenData
        if (vlen == 0 || vlen > 0x1000) { c.status(S.DATA_NULL); return }
        val needsValue = needsValueTable[cmp] != 0 || cmp == 4
        val needsPrev = cmp in 5..10
        c.status(S.SUCCESS)
        val pattern = if (needsValue && lenData > 0) c.bytes(lenData) else null
        val mask = if (type == 10) c.bytes(vlen) else null
        val hi = if (cmp == 4 && pattern != null && lenData >= 2 * vlen) pattern.copyOfRange(vlen, 2 * vlen) else null
        val entry = if (needsPrev) 4 + vlen else 4
        while (true) {
            val chunkLen = c.i32()
            if (chunkLen == -1) break
            if (chunkLen == 0) { c.w64(-1L); continue }
            if (chunkLen < 0 || chunkLen > 0x40000) break
            val chunk = c.bytes(chunkLen)
            val out = BlockOut(c, vlen)
            var off = 0
            while (off + entry <= chunkLen) {
                val eo = uval(chunk, off, 4)
                val mem = readMem(pid, base + eo, vlen) ?: ByteArray(vlen)
                val prev = if (needsPrev) chunk.copyOfRange(off + 4, off + 4 + vlen) else hi
                if (compare(cmp, type, vlen, pattern, mem, 0, prev, 0, mask)) out.add(eo.toInt(), mem, 0)
                off += entry
            }
            out.flush()
            c.w64(-1L)
        }
        c.status(S.SUCCESS)
    }

    private fun scanGet(c: Conn, r: Rd) {
        if (!authorised) { c.status(S.DATA_NULL); return }
        val pid = r.u32(); val count = r.u32()
        c.status(S.SUCCESS)
        val e = Rd(c.bytes(count * 12))
        for (i in 0 until count) {
            val a = e.u64(); val l = e.u32()
            c.wb(readMem(pid, a, l) ?: ByteArray(l))
        }
        c.w64(-1L)
    }

    // ---- turbo scan (scan_turbo.c) --------------------------------------------------------------------------------------
    private class Turbo(val pid: Int, val type: Int, val vlen: Int) {
        class Rec(val addr: Long, var cur: ByteArray)
        var recs = ArrayList<Rec>()
    }

    private fun turboCaps(c: Conn) {
        if (turboEngines == 0) { c.status(S.ERROR); return }
        c.status(S.SUCCESS); c.w32(1); c.w32(turboEngines); c.w32(4); c.w32(0)
    }

    private fun turboRegions(c: Conn, r: Rd) {
        if (!authorised) { c.status(S.DATA_NULL); return }
        val pid = r.u32()
        val p = synchronized(lock) { procs[pid] }
        c.status(S.SUCCESS)
        val list = p?.regions?.filter { it.prot and 1 != 0 } ?: emptyList()
        c.w32(list.size)
        for (g in list) { c.w64(g.start); c.w64(g.end); c.w32(g.prot); c.w32(0); c.w32(500); c.w32(0) }
        c.status(S.SUCCESS)
    }

    private fun turboStart(c: Conn, r: Rd) {
        if (!authorised) { c.status(S.DATA_NULL); return }
        val pid = r.u32(); val address = r.u64(); val length = r.u32()
        val type = r.u8(); val cmp = r.u8(); val alignment = r.u8(); val lenData = r.u32(); val flags = r.u32()
        val snapshot = flags and 0x04 != 0
        val resident = flags and 0x02 != 0
        if (cmp > 12 || (lenData == 0 && !snapshot)) { c.status(S.DATA_NULL); return }
        val needsValue = needsValueTable[cmp] != 0 || cmp == 4
        var vlen = size(type); if (vlen == 0) vlen = lenData
        if (vlen == 0) { c.status(S.DATA_NULL); return }
        c.status(S.SUCCESS)
        val pattern = if (needsValue && lenData > 0) c.bytes(lenData) else null
        val mask = if (type == 10) c.bytes(vlen) else null
        c.status(S.SUCCESS)
        val step = if (alignment != 0) alignment else vlen
        val hi = if (cmp == 4 && pattern != null && lenData >= 2 * vlen) pattern.copyOfRange(vlen, 2 * vlen) else null

        // the ranges to cover
        val ranges = ArrayList<Pair<Long, Int>>()
        if (flags and 0x10 != 0) {
            val n = Integer.reverseBytes(java.nio.ByteBuffer.wrap(c.bytes(4)).int)
            val seg = Rd(c.bytes(n * 12))
            for (i in 0 until n) { val a = seg.u64(); val l = seg.u32(); ranges.add(a to l) }
        } else {
            ranges.add(address to length)
        }

        if (snapshot) {
            val includeZeros = flags and 0x08 != 0
            val sess = Turbo(pid, type, vlen)
            var slots = 0L; var total = 0L
            for ((a, l) in ranges) {
                total += l
                val mem = readMem(pid, a, l) ?: ByteArray(l)
                var j = 0
                while (j + vlen <= l) {
                    slots++
                    val keep = includeZeros || compare(11, type, vlen, null, mem, j, null, 0, null)
                    if (keep) sess.recs.add(Turbo.Rec(a + j, mem.copyOfRange(j, j + vlen)))
                    j += step
                }
            }
            c.w64(slots); c.w64(total)
            c.w64(total / 2); c.w64(total)
            c.w64(-1L)
            c.w32(1); c.w64(sess.recs.size.toLong())
            c.status(S.SUCCESS)
            c.turbo = sess
            return
        }

        // known-value scan over all ranges
        val hits = ArrayList<Turbo.Rec>()
        val streams = ArrayList<Pair<Int, ByteArray>>() // only for the non-resident single range
        for ((a, l) in ranges) {
            val mem = readMem(pid, a, l) ?: ByteArray(l)
            var j = 0
            while (j + vlen <= l) {
                if (compare(cmp, type, vlen, pattern, mem, j, hi, 0, mask)) hits.add(Turbo.Rec(a + j, mem.copyOfRange(j, j + vlen)))
                j += step
            }
        }
        if (!resident) {
            val out = BlockOut(c, vlen)
            for (h in hits) out.add((h.addr - address).toInt(), h.cur, 0)
            out.flush(); c.w64(-1L); c.status(S.SUCCESS)
            return
        }
        if (hits.size > residentCap) {
            c.w32(0); c.w64(0)
            if (flags and 0x10 != 0) {
                c.w64(-1L)                       // multi-segment decline: empty stream
            } else {
                val out = BlockOut(c, vlen)      // single range: transparent fallback streams everything
                for (h in hits) out.add((h.addr - address).toInt(), h.cur, 0)
                out.flush(); c.w64(-1L)
            }
            c.status(S.SUCCESS)
            return
        }
        val sess = Turbo(pid, type, vlen)
        sess.recs = hits
        c.turbo = sess
        c.w32(1); c.w64(hits.size.toLong())
        c.status(S.SUCCESS)
    }

    private fun turboCount(c: Conn, r: Rd) {
        if (!authorised) { c.status(S.DATA_NULL); return }
        val pid = r.u32(); val base = r.u64(); val type = r.u8(); val cmp = r.u8(); val lenData = r.u32(); val flags = r.u32()
        if (cmp > 12) { c.status(S.DATA_NULL); return }
        var vlen = size(type); if (vlen == 0) vlen = lenData
        if (vlen == 0 || vlen > 0x1000) { c.status(S.DATA_NULL); return }
        val needsValue = needsValueTable[cmp] != 0 || cmp == 4
        val needsPrev = cmp in 5..10
        c.status(S.SUCCESS)
        val pattern = if (needsValue && lenData > 0) c.bytes(lenData) else null
        val mask = if (type == 10) c.bytes(vlen) else null
        val hi = if (cmp == 4 && pattern != null && lenData >= 2 * vlen) pattern.copyOfRange(vlen, 2 * vlen) else null
        if (flags and 0x02 != 0) {
            val s = c.turbo
            var kept = 0L
            if (s != null && s.vlen == vlen) {
                val next = ArrayList<Turbo.Rec>()
                for (rec in s.recs) {
                    val mem = readMem(s.pid, rec.addr, vlen) ?: ByteArray(vlen)
                    val prev = if (needsPrev) rec.cur else hi
                    if (compare(cmp, type, vlen, pattern, mem, 0, prev, 0, mask)) next.add(Turbo.Rec(rec.addr, mem))
                }
                s.recs = next
                kept = next.size.toLong()
            } else if (s != null) {
                kept = s.recs.size.toLong()
            }
            c.w64(kept / 2); c.w64(-1L)          // one progress record, then the terminator
            c.w64(kept)
            c.status(S.SUCCESS)
            return
        }
        // client-driven chunks, like SCAN_COUNT
        val entry = if (needsPrev) 4 + vlen else 4
        while (true) {
            val chunkLen = c.i32()
            if (chunkLen == -1) break
            if (chunkLen == 0) { c.w64(-1L); continue }
            if (chunkLen < 0 || chunkLen > 0x40000) break
            val chunk = c.bytes(chunkLen)
            val out = BlockOut(c, vlen)
            var off = 0
            while (off + entry <= chunkLen) {
                val eo = uval(chunk, off, 4)
                val mem = readMem(pid, base + eo, vlen) ?: ByteArray(vlen)
                val prev = if (needsPrev) chunk.copyOfRange(off + 4, off + 4 + vlen) else hi
                if (compare(cmp, type, vlen, pattern, mem, 0, prev, 0, mask)) out.add(eo.toInt(), mem, 0)
                off += entry
            }
            out.flush(); c.w64(-1L)
        }
        c.status(S.SUCCESS)
    }

    private fun turboGet(c: Conn, r: Rd) {
        if (!authorised) { c.status(S.DATA_NULL); return }
        val start = r.u32().toLong() and 0xFFFFFFFFL; val count = r.u32().toLong() and 0xFFFFFFFFL
        val s = c.turbo
        c.status(S.SUCCESS)
        if (s == null) { c.w32(0); c.status(S.SUCCESS); return }
        val from = minOf(start, s.recs.size.toLong()).toInt()
        val to = minOf(from + count, s.recs.size.toLong()).toInt()
        c.w32(to - from)
        for (i in from until to) {
            val rec = s.recs[i]
            c.w64(rec.addr); c.wb(rec.cur); c.wb(rec.cur)      // previous == current without KEEP_PREVIOUS
        }
        c.status(S.SUCCESS)
    }

    // ---- disassembly: a tiny x86-64 decoder that knows only the instructions the tests use ------------------------------
    private class Insn(
        val addr: Long, val len: Int, val kind: Int, val base: Int, val index: Int, val scale: Int,
        val disp: Long, val ripTarget: Long, val mn: Int,
    )

    private fun decode(b: ByteArray, off: Int, avail: Int, addr: Long): Insn? {
        var p = off
        val end = off + avail
        fun peek(): Int = if (p < end) b[p].toInt() and 0xFF else -1
        var rexW = false; var rexX = 0; var rexB = 0
        val first = peek()
        if (first in 0x40..0x4F) { rexW = first and 8 != 0; rexX = (first shr 1) and 1; rexB = first and 1; p++ }
        val op = peek()
        if (op < 0) return null
        p++
        fun simple(kind: Int, mn: Int) = Insn(addr, p - off, kind, 0, 0, 0, 0, 0, mn)
        when (op) {
            0x90 -> return simple(0, 1)
            0xC3 -> return simple(0x02, 2)
            0xE8, 0xE9 -> {
                if (p + 4 > end) return null
                p += 4
                return simple(if (op == 0xE8) 0x01 else 0x04, 3)
            }
            0xEB -> { if (p + 1 > end) return null; p += 1; return simple(0x04, 4) }
            0x89, 0x8B, 0x01, 0xC7 -> {
                val modrm = peek(); if (modrm < 0) return null
                p++
                val mod = modrm shr 6; val reg = (modrm shr 3) and 7; val rm = modrm and 7
                if (op == 0xC7 && reg != 0) return null
                var baseReg = -1; var idx = -1; var scale = 0; var disp = 0L; var rip = false; var hasMem = mod != 3
                if (hasMem) {
                    var dispSize = 0
                    if (rm == 4) {
                        val sib = peek(); if (sib < 0) return null
                        p++
                        val ss = sib shr 6; val ii = ((sib shr 3) and 7) or (rexX shl 3); val bb = sib and 7
                        if (ii != 4) { idx = ii; scale = 1 shl ss }
                        if (bb == 5 && mod == 0) { dispSize = 4 } else baseReg = bb or (rexB shl 3)
                        if (mod == 1) dispSize = 1 else if (mod == 2) dispSize = 4
                    } else if (rm == 5 && mod == 0) {
                        rip = true; dispSize = 4
                    } else {
                        baseReg = rm or (rexB shl 3)
                        if (mod == 1) dispSize = 1 else if (mod == 2) dispSize = 4
                    }
                    if (p + dispSize > end) return null
                    if (dispSize == 1) disp = b[p].toLong()
                    else if (dispSize == 4) disp = (uval(b, p, 4) shl 32) shr 32
                    p += dispSize
                }
                if (op == 0xC7) { if (p + 4 > end) return null; p += 4 }
                val len = p - off
                var kind = 0
                if (hasMem) {
                    kind = kind or 0x10
                    if (rip) kind = kind or 0x20
                    kind = kind or when (op) {
                        0x89, 0xC7 -> 0x80
                        0x8B -> 0x40
                        else -> 0xC0
                    }
                }
                val target = if (rip) addr + len + disp else 0L
                return Insn(addr, len, kind, if (baseReg >= 0) 53 + baseReg else 0, if (idx >= 0) 53 + idx else 0, scale, disp, target, 10 + op)
            }
        }
        return null
    }

    private fun linearDecode(mem: ByteArray, address: Long, maxEntries: Int): List<Insn> {
        val out = ArrayList<Insn>()
        var off = 0
        while (off < mem.size && out.size < maxEntries) {
            val ins = decode(mem, off, mem.size - off, address + off)
            if (ins == null) { off += 1; continue }
            out.add(ins); off += ins.len
        }
        return out
    }

    private fun procDisasm(c: Conn, r: Rd) {
        val pid = r.u32(); val address = r.u64(); val length = r.u32(); val maxEntries = r.u32()
        if (maxEntries !in 1..1000000) { c.status(S.ERROR); return }
        val mem = readMem(pid, address, length)
        if (mem == null) { c.status(S.ERROR); return }
        c.status(S.SUCCESS)
        for (i in linearDecode(mem, address, maxEntries)) {
            c.w64(i.addr); c.w64(i.ripTarget); c.w64(i.disp)
            c.w8(i.len); c.w8(i.kind); c.w8(i.base); c.w8(i.index); c.w8(i.scale); c.w8(i.mn); c.w16(0)
        }
        c.wb(ByteArray(32) { 0xFF.toByte() })
    }

    private fun procXrefsTo(c: Conn, r: Rd) {
        val pid = r.u32(); val scanAddr = r.u64(); val scanLen = r.u32(); val target = r.u64()
        val mem = readMem(pid, scanAddr, scanLen)
        if (mem == null) { c.status(S.ERROR); return }
        c.status(S.SUCCESS)
        for (i in linearDecode(mem, scanAddr, Int.MAX_VALUE)) {
            if (i.kind and 0x20 != 0 && i.ripTarget == target) c.w64(i.addr)
        }
        c.w64(-1L)
    }

    // ---- debugger (debug.c) ---------------------------------------------------------------------------------------------
    private fun debugAttach(c: Conn, r: Rd, body: ByteArray) {
        if (body.size < 4) { c.status(S.DATA_NULL); return }
        val pid = r.u32()
        if (attachedPid != 0) { c.status(S.ALREADY_DEBUG); return }
        if (synchronized(lock) { procs[pid] } == null) { c.status(S.ERROR); return }
        val s = Socket()
        try {
            s.connect(InetSocketAddress(c.sock.inetAddress, interruptPort), 2000)
        } catch (_: IOException) {
            try { s.close() } catch (_: IOException) {}
            c.status(S.ERROR)
            return
        }
        s.tcpNoDelay = true
        irq = s
        attachedPid = pid
        c.status(S.SUCCESS)
    }

    private fun debugDetach(c: Conn) {
        synchronized(lock) { for (i in watchpoints.indices) watchpoints[i] = null }
        try { irq?.close() } catch (_: IOException) {}
        irq = null
        attachedPid = 0
        c.status(S.SUCCESS)
    }

    private fun debugWatchpoint(c: Conn, r: Rd) {
        if (attachedPid == 0) { c.status(S.ERROR); return }
        val index = r.u32(); val enabled = r.u32(); val length = r.u32(); val breakType = r.u32(); val addr = r.u64()
        if (index !in 0..3) { c.status(S.INVALID_INDEX); return }
        synchronized(lock) { watchpoints[index] = Watch(enabled != 0, length, breakType, addr) }
        c.status(S.SUCCESS)
    }
}
