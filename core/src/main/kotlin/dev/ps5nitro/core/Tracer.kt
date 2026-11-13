package dev.ps5nitro.core

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** One debugger stop reported on the interrupt channel (1184-byte packet, PROTOCOL.md 1.9). */
class InterruptEvent(
    val lwpid: Int,
    val status: Int,
    val threadName: String,
    val regs: ByteArray,
    val dbregs: ByteArray,
) {
    /** Signal that stopped the thread: 5 (SIGTRAP) for hardware watchpoints and breakpoints. */
    val signal: Int get() = (status shr 8) and 0xFF
    val rip: Long get() = regs.leI64(RegOffsets.RIP)
    val dr6: Long get() = dbregs.leI64(6 * 8)

    companion object {
        const val PACKET_SIZE = 1184
        const val SIGTRAP = 5

        fun parse(p: ByteArray): InterruptEvent {
            val r = LeReader(p)
            val lwp = r.i32()
            val st = r.i32()
            val name = r.cstr(40)
            val regs = p.copyOfRange(0x30, 0x30 + 176)
            val db = p.copyOfRange(0x420, 0x420 + 128)
            return InterruptEvent(lwp, st, name, regs, db)
        }
    }
}

/**
 * Server socket that the console connects back to after DEBUG_ATTACH. The console always dials TCP
 * 755 on the client's IP, which Android apps cannot bind (ports below 1024). Use a rooted phone, or
 * relay 755 to [port] on another host (see tools/relay.py).
 */
class InterruptListener(private val port: Int = Wire.INTERRUPT_PORT) : Closeable {
    val events = LinkedBlockingQueue<InterruptEvent>()

    @Volatile
    private var closed = false
    private var server: ServerSocket? = null

    @Volatile
    var connected = false
        private set

    val boundPort: Int get() = server?.localPort ?: -1

    fun start() {
        val s = ServerSocket()
        try {
            s.reuseAddress = true
            s.bind(InetSocketAddress(port))
        } catch (e: IOException) {
            try { s.close() } catch (_: IOException) {}
            val why = if (e is BindException && port < 1024) {
                "Port $port is privileged - Android apps cannot listen on it. " +
                    "Use a rooted phone or relay port 755 to another port (tools/relay.py)."
            } else {
                "Cannot listen on port $port: ${e.message}"
            }
            throw Ps5Exception(why, e)
        }
        server = s
        Thread({ acceptLoop(s) }, "ps5-irq-accept").apply { isDaemon = true; start() }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!closed) {
            val c = try { s.accept() } catch (_: IOException) { return }
            connected = true
            Thread({ readLoop(c) }, "ps5-irq-read").apply { isDaemon = true; start() }
        }
    }

    private fun readLoop(c: Socket) {
        try {
            c.tcpNoDelay = true
            val inp = DataInputStream(BufferedInputStream(c.getInputStream(), 1 shl 16))
            val buf = ByteArray(InterruptEvent.PACKET_SIZE)
            while (!closed) {
                inp.readFully(buf)
                events.put(InterruptEvent.parse(buf))
            }
        } catch (_: IOException) {
            // console closed the channel (detach) - nothing else to do
        } finally {
            try { c.close() } catch (_: IOException) {}
        }
    }

    override fun close() {
        closed = true
        try { server?.close() } catch (_: IOException) {}
    }
}

/** A decoded instruction that ends exactly where a watchpoint stop happened. */
class InstrCandidate(
    val entry: DisasmEntry,
    val bytes: ByteArray,
    val effectiveAddress: Long?,
    val matchesTarget: Boolean,
) {
    val address: Long get() = entry.address
    val length: Int get() = entry.length

    /** "[rbx+0x10]" style description of the memory operand. */
    fun operandText(): String {
        if (!entry.hasMemOperand) return "(no memory operand)"
        if (entry.isRipRelative) return "[rip -> ${hex64(entry.ripRelTarget)}]"
        val sb = StringBuilder("[")
        val base = RegOffsets.nameOfZydis(entry.memBaseReg)
        val idx = RegOffsets.nameOfZydis(entry.memIndexReg)
        sb.append(base)
        if (idx.isNotEmpty()) {
            if (base.isNotEmpty()) sb.append('+')
            sb.append(idx)
            if (entry.memScale > 1) sb.append('*').append(entry.memScale)
        }
        if (entry.memDisp != 0L || (base.isEmpty() && idx.isEmpty())) {
            val d = entry.memDisp
            if (d < 0) sb.append("-0x").append(java.lang.Long.toHexString(-d))
            else sb.append("+0x").append(java.lang.Long.toHexString(d))
        }
        return sb.append(']').toString()
    }
}

/** All stops that landed on one RIP, with the instruction guesses for it (best first). */
class AccessHit(val rip: Long, val count: Int, val candidates: List<InstrCandidate>, val regs: ByteArray) {
    val best: InstrCandidate? get() = candidates.firstOrNull()
}

class TraceResult(val hits: List<AccessHit>, val stops: Int, val note: String)

/**
 * "What writes to this address?": arms a hardware watchpoint, collects the stops and works out which
 * instruction caused each one. A data watchpoint traps AFTER the access, so the culprit is the
 * instruction that ends exactly at the stop's RIP.
 */
class WriterTracer(private val client: Ps5Client, private val listener: InterruptListener) {
    @Volatile
    var cancelled = false

    fun run(
        pid: Int,
        address: Long,
        size: Int,
        kind: WatchKind = WatchKind.WRITE,
        maxMillis: Long = 10_000,
        maxStops: Int = 200,
        onStop: ((Int) -> Unit)? = null,
    ): TraceResult {
        val watchSize = if (size in intArrayOf(1, 2, 4, 8) && address % size == 0L) size else 1
        val firstRegs = LinkedHashMap<Long, ByteArray>()
        val counts = HashMap<Long, Int>()
        var stops = 0
        var note = "finished"
        cancelled = false
        listener.events.clear()
        client.debugAttach(pid)
        var armed = false
        try {
            client.setWatchpoint(0, true, watchSize, kind, address)
            armed = true
            val deadline = System.currentTimeMillis() + maxMillis
            while (true) {
                if (cancelled) { note = "stopped by user"; break }
                if (System.currentTimeMillis() >= deadline) { note = "time limit reached"; break }
                if (stops >= maxStops) { note = "stop limit reached (the address is accessed very often)"; break }
                val ev = listener.events.poll(100, TimeUnit.MILLISECONDS)
                if (ev == null) {
                    if (stops == 0 && !listener.connected && System.currentTimeMillis() - (deadline - maxMillis) > 4000) {
                        note = "the console never connected back to the debug port"
                        break
                    }
                    continue
                }
                stops++
                if (ev.signal == InterruptEvent.SIGTRAP) {
                    val rip = ev.rip
                    counts[rip] = (counts[rip] ?: 0) + 1
                    if (!firstRegs.containsKey(rip)) firstRegs[rip] = ev.regs
                }
                client.debugContinue()
                onStop?.invoke(stops)
            }
        } finally {
            if (armed) runCatching { client.setWatchpoint(0, false, watchSize, kind, address) }
            runCatching { client.debugContinue() }
            runCatching { client.debugDetach() }
        }
        val hits = firstRegs.entries
            .map { (rip, regs) -> AccessHit(rip, counts[rip] ?: 1, candidatesFor(pid, rip, regs, address, watchSize, kind), regs) }
            .sortedByDescending { it.count }
        return TraceResult(hits, stops, note)
    }

    /** Instructions that end exactly at [rip] and touch memory, ranked by how well they explain the access. */
    fun candidatesFor(pid: Int, rip: Long, regs: ByteArray, watched: Long, size: Int, kind: WatchKind): List<InstrCandidate> {
        val out = ArrayList<InstrCandidate>()
        val window = client.read(pid, rip - MAX_INSN, MAX_INSN)
        for (k in 1..MAX_INSN) {
            val entries = client.disasm(pid, rip - k, k, 4)
            val e = entries.singleOrNull() ?: continue
            if (e.address != rip - k || e.length != k) continue
            val relevant = if (kind == WatchKind.WRITE) e.writesMemory else (e.writesMemory || e.readsMemory)
            if (!relevant) continue
            val bytes = window.copyOfRange(MAX_INSN - k, MAX_INSN)
            val ea = effectiveAddress(e, regs)
            val match = ea != null && Math.abs(ea - watched) < 32
            out.add(InstrCandidate(e, bytes, ea, match))
        }
        return out.sortedWith(
            compareByDescending<InstrCandidate> { it.matchesTarget }
                .thenByDescending { it.entry.writesMemory }
                .thenByDescending { it.length },
        )
    }

    companion object {
        const val MAX_INSN = 15

        /** Effective address of the memory operand using the registers at the stop (null if unknown). */
        fun effectiveAddress(e: DisasmEntry, regs: ByteArray): Long? {
            if (!e.hasMemOperand) return null
            if (e.isRipRelative) return e.ripRelTarget
            var ea = e.memDisp
            if (e.memBaseReg != 0) {
                val off = RegOffsets.offsetOfZydis(e.memBaseReg)
                if (off < 0) return null
                ea += regs.leI64(off)
            }
            if (e.memIndexReg != 0) {
                val off = RegOffsets.offsetOfZydis(e.memIndexReg)
                if (off < 0) return null
                ea += regs.leI64(off) * maxOf(1, e.memScale)
            }
            return ea
        }
    }
}

/** An instruction that references a global variable through a RIP-relative operand. */
class XrefHit(val address: Long, val entry: DisasmEntry?, val bytes: ByteArray?)

/**
 * Static alternative to the debugger: finds code with a RIP-relative operand pointing at [target]
 * inside the given executable ranges. Only finds globals/statics (not heap objects), but needs no
 * debug session and no inbound connection.
 */
fun Ps5Client.staticAccessors(pid: Int, executable: List<MapEntry>, target: Long): List<XrefHit> {
    val hits = ArrayList<XrefHit>()
    for (r in executable) {
        var s = r.start
        var left = r.size
        while (left > 0) {
            val chunk = minOf(left, 0x40000000L)
            for (a in findXrefsTo(pid, s, chunk.toInt(), target)) {
                val e = disasm(pid, a, 16, 1).firstOrNull { it.address == a }
                val b = if (e != null) read(pid, a, e.length) else null
                hits.add(XrefHit(a, e, b))
            }
            s += chunk; left -= chunk
        }
    }
    return hits
}
