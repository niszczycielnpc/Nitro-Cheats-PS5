package dev.ps5nitro.core

import java.io.Closeable

class ScanHit(val address: Long, val value: ByteArray, val previous: ByteArray?)

/** [done]/[total] are engine specific (regions, bytes or slots); [hits] is the running result count. */
fun interface ScanProgress {
    fun onProgress(done: Long, total: Long, hits: Long)
}

class ScanCancelledException : Ps5Exception("scan cancelled")

/**
 * A Cheat-Engine style scan session: [first] creates the candidate set, [next] narrows it,
 * [page] reads candidates for display.
 */
interface ScanEngine : Closeable {
    val name: String
    val count: Long
    val active: Boolean
    val valueType: ValueType?
    val elementSize: Int

    fun first(pid: Int, regions: List<ScanRegion>, spec: ScanSpec, progress: ScanProgress? = null): Long
    fun next(spec: ScanSpec, progress: ScanProgress? = null): Long
    fun page(start: Long, max: Int): List<ScanHit>

    /** Aborts a running scan from another thread; the scan call then throws [ScanCancelledException]. */
    fun cancel()

    /** Forgets all candidates. */
    fun reset()

    override fun close() = reset()
}

object ScanRegions {
    /**
     * Turns the VM map into scan ranges. By default only readable+writable, non-executable memory is
     * used (heap, .data, .bss, stacks) which is where game state lives.
     */
    fun select(
        maps: List<MapEntry>,
        includeExecutable: Boolean = false,
        includeReadOnly: Boolean = false,
        skip: List<TurboRegion> = emptyList(),
    ): List<ScanRegion> {
        val usable = maps.filter {
            it.readable && it.size > 0 &&
                (it.writable || includeReadOnly) &&
                (includeExecutable || !it.executable) &&
                skip.none { s -> it.start < s.end && s.start < it.end }
        }.sortedBy { it.start }
        val out = ArrayList<ScanRegion>()
        for (m in usable) {
            val last = out.lastOrNull()
            if (last != null && last.end == m.start) {
                out[out.size - 1] = ScanRegion(last.start, last.length + m.size, last.name)
            } else {
                out.add(ScanRegion(m.start, m.size, m.name))
            }
        }
        return out
    }

    /** Regions the turbo probe found to be slow, uncached memory (GPU/Garlic blobs). */
    fun slowRegions(probe: List<TurboRegion>, minMbps: Int = 120): List<TurboRegion> =
        probe.filter { it.uncached && it.mbps < minMbps }
}

private class HitBuffer(val vsize: Int, cap: Int = 1024) {
    var addrs = LongArray(cap)
    var vals = ByteArray(cap * vsize)
    var n = 0

    fun add(addr: Long, src: ByteArray, off: Int) {
        if (n == addrs.size) {
            val nc = addrs.size * 2
            addrs = addrs.copyOf(nc)
            vals = vals.copyOf(nc * vsize)
        }
        addrs[n] = addr
        System.arraycopy(src, off, vals, n * vsize, vsize)
        n++
    }
}

/**
 * Client-side candidate list on top of SCAN_START / SCAN_COUNT. Works on every payload that has the
 * staged scanner, needs no server memory, but moves the candidate list over the network on each pass.
 */
class IterativeEngine(
    private val client: Ps5Client,
    private val maxHits: Int = 6_000_000,
) : ScanEngine {
    override val name = "iterative"
    private var pid = 0
    private var type: ValueType? = null
    private var vsize = 0
    private var addrs = LongArray(0)
    private var vals = ByteArray(0)
    private var prevVals: ByteArray? = null
    private var n = 0

    @Volatile
    private var cancelled = false

    override val count: Long get() = n.toLong()
    override val active: Boolean get() = type != null
    override val valueType: ValueType? get() = type
    override val elementSize: Int get() = vsize

    override fun first(pid: Int, regions: List<ScanRegion>, spec: ScanSpec, progress: ScanProgress?): Long {
        require(spec.compare.canStartScan && spec.compare != Compare.UNKNOWN) {
            "A first scan needs a value; unknown-value scans need the turbo engine"
        }
        reset()
        cancelled = false
        val vs = spec.elementSize
        val buf = HitBuffer(vs)
        var overflow = false
        var base = 0L
        val sink = BlockSink { blk ->
            val es = 4 + vs
            var o = 0
            while (o + es <= blk.size) {
                if (buf.n >= maxHits || buf.n.toLong() * vs > MAX_BYTES) {
                    overflow = true
                    return@BlockSink false
                }
                buf.add(base + (blk.leI32(o).toLong() and 0xFFFFFFFFL), blk, o + 4)
                o += es
            }
            !cancelled
        }
        val windows = ArrayList<ScanRegion>()
        for (r in regions) {
            var s = r.start
            var left = r.length
            while (left > 0) {
                val l = minOf(left, WINDOW)
                windows.add(ScanRegion(s, l, r.name))
                s += l; left -= l
            }
        }
        for ((i, w) in windows.withIndex()) {
            if (cancelled) throw ScanCancelledException()
            base = w.start
            try {
                client.scanStart(pid, w.start, w.length, spec, sink)
            } catch (e: Ps5StatusException) {
                throw e
            } catch (e: Ps5Exception) {
                runCatching { client.reconnect() }
                if (overflow) throw TooManyResultsException()
                if (cancelled || e is ScanAbortedException) throw ScanCancelledException()
                throw e
            }
            progress?.onProgress(i + 1L, windows.size.toLong(), buf.n.toLong())
        }
        this.pid = pid
        type = spec.type
        vsize = vs
        addrs = buf.addrs.copyOf(buf.n)
        vals = buf.vals.copyOf(buf.n * vs)
        n = buf.n
        prevVals = null
        return n.toLong()
    }

    override fun next(spec: ScanSpec, progress: ScanProgress?): Long {
        val t = type ?: throw IllegalStateException("Run a first scan before narrowing")
        require(spec.type == t && spec.elementSize == vsize) { "The value type changed since the first scan" }
        require(spec.compare != Compare.UNKNOWN) { "Pick a comparison for the next scan" }
        cancelled = false
        val usePrev = spec.compare.needsPrevious
        val entrySize = 4 + if (usePrev) vsize else 0
        val perChunk = maxOf(1, Wire.MAX_CHUNK_BYTES / entrySize)
        val out = HitBuffer(vsize)
        val oldAddrs = addrs
        val oldVals = vals
        val oldN = n
        var i = 0
        while (i < oldN) {
            if (cancelled) throw ScanCancelledException()
            val base = oldAddrs[i]
            var j = i
            while (j < oldN && oldAddrs[j] - base < GROUP_SPAN) j++
            val gi = i
            val gj = j
            val chunks = object : Iterator<ByteArray> {
                var p = gi
                override fun hasNext() = p < gj
                override fun next(): ByteArray {
                    val e = minOf(gj, p + perChunk)
                    val w = LeWriter((e - p) * entrySize)
                    for (k in p until e) {
                        w.u32((oldAddrs[k] - base).toInt())
                        if (usePrev) w.bytes(oldVals, k * vsize, vsize)
                    }
                    p = e
                    return w.toByteArray()
                }
            }
            val sink = BlockSink { blk ->
                val es = 4 + vsize
                var o = 0
                while (o + es <= blk.size) {
                    out.add(base + (blk.leI32(o).toLong() and 0xFFFFFFFFL), blk, o + 4)
                    o += es
                }
                !cancelled
            }
            try {
                client.scanCount(pid, base, spec, chunks, sink)
            } catch (e: Ps5StatusException) {
                throw e
            } catch (e: Ps5Exception) {
                runCatching { client.reconnect() }
                if (cancelled || e is ScanAbortedException) throw ScanCancelledException()
                throw e
            }
            progress?.onProgress(j.toLong(), oldN.toLong(), out.n.toLong())
            i = j
        }
        // survivors are a subsequence of the old candidates - recover each one's previous value
        val prev = ByteArray(out.n * vsize)
        var p = 0
        for (k in 0 until out.n) {
            while (p < oldN && oldAddrs[p] != out.addrs[k]) p++
            if (p >= oldN) break
            System.arraycopy(oldVals, p * vsize, prev, k * vsize, vsize)
        }
        addrs = out.addrs.copyOf(out.n)
        vals = out.vals.copyOf(out.n * vsize)
        prevVals = prev
        n = out.n
        return n.toLong()
    }

    override fun page(start: Long, max: Int): List<ScanHit> {
        val s = start.coerceIn(0, n.toLong()).toInt()
        val e = minOf(n.toLong(), s.toLong() + max).toInt()
        val pv = prevVals
        return (s until e).map {
            ScanHit(
                addrs[it],
                vals.copyOfRange(it * vsize, (it + 1) * vsize),
                pv?.copyOfRange(it * vsize, (it + 1) * vsize),
            )
        }
    }

    override fun cancel() {
        cancelled = true
        client.abortConnection()
    }

    override fun reset() {
        type = null; vsize = 0; addrs = LongArray(0); vals = ByteArray(0); prevVals = null; n = 0
    }

    private companion object {
        const val WINDOW = 0x40000000L
        const val GROUP_SPAN = 0xF0000000L
        const val MAX_BYTES = 256L * 1024 * 1024
    }
}

/**
 * Keeps the candidates on the console (TURBOSCAN). Much faster, supports unknown-initial-value scans
 * and millions of hits, and only moves the page you look at over the network.
 */
class TurboEngine(private val client: Ps5Client) : ScanEngine {
    override val name = "turbo"
    private var type: ValueType? = null
    private var vsize = 0
    private var n = 0L
    private var sessionOpen = false

    @Volatile
    private var cancelled = false

    /** Unknown-value snapshots drop all-zero slots unless this is set. */
    var includeZeros = false

    override val count: Long get() = n
    override val active: Boolean get() = type != null
    override val valueType: ValueType? get() = type
    override val elementSize: Int get() = vsize

    private fun <T> guarded(block: () -> T): T {
        try {
            return block()
        } catch (e: Ps5StatusException) {
            throw e
        } catch (e: Ps5Exception) {
            if (cancelled) {
                runCatching { client.reconnect() }
                sessionOpen = false
                throw ScanCancelledException()
            }
            if (e is TooManyResultsException) {
                runCatching { client.reconnect() }
                sessionOpen = false
            }
            throw e
        }
    }

    override fun first(pid: Int, regions: List<ScanRegion>, spec: ScanSpec, progress: ScanProgress?): Long {
        client.probeTurbo() ?: throw Ps5UnsupportedException("This payload has no turbo scan")
        cancelled = false
        reset()
        val count = guarded {
            if (spec.compare == Compare.UNKNOWN) {
                client.turboSnapshotStart(pid, regions, spec.type, spec.alignment, includeZeros) { d, t ->
                    progress?.onProgress(d, t, 0)
                }
            } else {
                client.turboResidentStart(pid, regions, spec) ?: throw TooManyResultsException()
            }
        }
        sessionOpen = true
        type = spec.type
        vsize = spec.elementSize
        n = count
        return count
    }

    override fun next(spec: ScanSpec, progress: ScanProgress?): Long {
        val t = type ?: throw IllegalStateException("Run a first scan before narrowing")
        require(spec.type == t && spec.elementSize == vsize) { "The value type changed since the first scan" }
        require(spec.compare != Compare.UNKNOWN) { "Pick a comparison for the next scan" }
        cancelled = false
        n = guarded { client.turboResidentCount(spec) { d, tot -> progress?.onProgress(d, tot, n) } }
        return n
    }

    override fun page(start: Long, max: Int): List<ScanHit> {
        if (type == null || start >= n) return emptyList()
        val want = minOf(max.toLong(), n - start).toInt()
        return guarded { client.turboGet(start, want, vsize) }
            .map { ScanHit(it.address, it.current, null) }
    }

    override fun cancel() {
        cancelled = true
        client.abortConnection()
    }

    override fun reset() {
        if (sessionOpen) {
            runCatching { client.turboEnd() }
            sessionOpen = false
        }
        type = null; vsize = 0; n = 0
    }
}

object ScanEngines {
    /**
     * Turbo when the payload keeps results on the console and can cover several regions in one session
     * (a game's memory is never a single block), otherwise the iterative engine.
     */
    fun auto(client: Ps5Client): ScanEngine {
        val caps = client.probeTurbo()
        val turbo = caps != null && caps.has(TsEngine.SERVER_RESIDENT) && caps.has(TsEngine.SNAPSHOT_SEGMENTS)
        return if (turbo) TurboEngine(client) else IterativeEngine(client)
    }
}
