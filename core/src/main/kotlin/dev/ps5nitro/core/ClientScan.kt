package dev.ps5nitro.core

/** Receives raw result blocks: concatenated (u32 offset, value) entries. Return false to abort the scan. */
fun interface BlockSink {
    fun onBlock(block: ByteArray): Boolean
}

class ScanAbortedException : Ps5Exception("scan aborted")

/** Progress of a long-running server-side pass (bytes or slots done / total, total may be 0 if unknown). */
fun interface ProgressSink {
    fun onProgress(done: Long, total: Long)
}

class TurboRecord(val address: Long, val current: ByteArray, val previous: ByteArray, val first: ByteArray?)

internal fun ScanSpec.alignmentByte(): Int =
    if (alignment > 0) alignment else if (type == ValueType.BYTES) 1 else type.size

internal fun Ps5Connection.readBlocks(sink: BlockSink) {
    while (true) {
        val len = readI64()
        if (len == Wire.SENTINEL64) return
        if (len < 0 || len > 0x1000000) throw Ps5ProtocolException("bad result block length $len")
        val blk = readFully(len.toInt())
        if (!sink.onBlock(blk)) {
            abort()
            throw ScanAbortedException()
        }
    }
}

private inline fun <T> Ps5Client.withScanTimeout(crossinline block: Ps5Connection.() -> T): T =
    exec {
        setReadTimeout(Ps5Connection.SCAN_READ_TIMEOUT_MS)
        try {
            block()
        } finally {
            setReadTimeout(Ps5Connection.DEFAULT_READ_TIMEOUT_MS)
        }
    }

// ------------------------------------------------------------------ iterative scanner (CC01/CC02/CC03)

/**
 * First pass over [address, address+length) (length < 4 GiB). Result entries carry u32 offsets
 * relative to [address].
 */
fun Ps5Client.scanStart(pid: Int, address: Long, length: Long, spec: ScanSpec, sink: BlockSink) {
    require(length in 1..0xFFFFFFFFL) { "range length must fit in 32 bits" }
    require(spec.compare.canStartScan) { "${spec.compare.label} cannot start a scan" }
    require(spec.compare != Compare.UNKNOWN) { "unknown initial value needs the turbo snapshot engine" }
    ensureAuth()
    withScanTimeout {
        val body = LeWriter(23).u32(pid).u64(address).u32(length.toInt())
            .u8(spec.type.id).u8(spec.compare.id).u8(spec.alignmentByte()).u32(spec.lenData())
        send(Op.SCAN_START, body.toByteArray())
        expectSuccess("SCAN_START")
        val payload = spec.payload()
        if (payload.isNotEmpty()) sendRaw(payload)
        if (spec.type == ValueType.BYTES) sendRaw(spec.mask!!)
        expectSuccess("SCAN_START(begin)")
        readBlocks(sink)
        expectSuccess("SCAN_START(end)")
    }
}

/**
 * Filtering pass. [chunks] yields packed candidate entries (u32 offset relative to [base], followed by
 * the previous value when the compare mode needs it), each chunk at most 256 KiB.
 */
fun Ps5Client.scanCount(pid: Int, base: Long, spec: ScanSpec, chunks: Iterator<ByteArray>, sink: BlockSink) {
    ensureAuth()
    withScanTimeout {
        val body = LeWriter(18).u32(pid).u64(base).u8(spec.type.id).u8(spec.compare.id).u32(spec.lenData())
        send(Op.SCAN_COUNT, body.toByteArray())
        expectSuccess("SCAN_COUNT")
        val payload = spec.payload()
        if (payload.isNotEmpty()) sendRaw(payload)
        if (spec.type == ValueType.BYTES) sendRaw(spec.mask!!)
        while (chunks.hasNext()) {
            val chunk = chunks.next()
            if (chunk.isEmpty()) continue
            require(chunk.size <= Wire.MAX_CHUNK_BYTES) { "chunk too large" }
            sendRaw(LeWriter(chunk.size + 4).u32(chunk.size).bytes(chunk).toByteArray())
            readBlocks(sink)
        }
        sendRaw(LeWriter(4).u32(-1).toByteArray())
        expectSuccess("SCAN_COUNT(end)")
    }
}

/**
 * Reads many small values in one round trip (SCAN_GET). Needs the auth handshake; if the console
 * refuses it we fall back to one PROC_READ per value.
 */
fun Ps5Client.readMany(pid: Int, addrs: LongArray, sizes: IntArray): List<ByteArray> {
    val n = addrs.size
    require(sizes.size == n)
    if (n == 0) return emptyList()
    try {
        ensureAuth()
    } catch (e: Ps5StatusException) {
        return List(n) { read(pid, addrs[it], sizes[it]) }
    }
    val out = ArrayList<ByteArray>(n)
    var i = 0
    while (i < n) {
        val k = minOf(2048, n - i)
        var total = 0
        for (j in i until i + k) total += sizes[j]
        val buf = exec {
            send(Op.SCAN_GET, LeWriter(8).u32(pid).u32(k).toByteArray())
            expectSuccess("SCAN_GET")
            val w = LeWriter(12 * k)
            for (j in i until i + k) w.u64(addrs[j]).u32(sizes[j])
            sendRaw(w.toByteArray())
            val data = readFully(total)
            if (readI64() != Wire.SENTINEL64) throw Ps5ProtocolException("SCAN_GET: missing terminator")
            data
        }
        var o = 0
        for (j in i until i + k) {
            out.add(buf.copyOfRange(o, o + sizes[j])); o += sizes[j]
        }
        i += k
    }
    return out
}

// ------------------------------------------------------------------ turbo scan (CC10..CC17)

/** Asks the payload what the turbo engine can do. Null on payloads that do not have it. */
fun Ps5Client.probeTurbo(): TurboCaps? {
    if (turboProbed) return turbo
    val caps = try {
        exec(markBrokenOnError = false) {
            setReadTimeout(4000)
            try {
                send(Op.TURBO_CAPS)
                if (readStatus() != Wire.ST_SUCCESS) {
                    null
                } else {
                    val r = LeReader(readFully(16))
                    val v = r.i32()
                    val e = r.i32()
                    val t = r.i32()
                    TurboCaps(v, e, t)
                }
            } finally {
                setReadTimeout(Ps5Connection.DEFAULT_READ_TIMEOUT_MS)
            }
        }
    } catch (e: Ps5Exception) {
        null
    }
    turbo = caps
    turboProbed = true
    return caps
}

private fun segmentList(regions: List<ScanRegion>): ByteArray {
    val w = LeWriter(4 + regions.size * 12).u32(regions.size)
    for (r in regions) w.u64(r.start).u32(r.length.toInt())
    return w.toByteArray()
}

private fun turboStartBody(pid: Int, address: Long, length: Long, spec: ScanSpec, lenData: Int, flags: Int, compareId: Int): ByteArray =
    LeWriter(27).u32(pid).u64(address).u32(length.toInt())
        .u8(spec.type.id).u8(compareId).u8(spec.alignmentByte()).u32(lenData).u32(flags).toByteArray()

/**
 * Known-value first scan that keeps the survivors on the console. Returns the survivor count, or null
 * if the console declined to keep them (too many). Every region must be shorter than 4 GiB.
 */
fun Ps5Client.turboResidentStart(pid: Int, regions: List<ScanRegion>, spec: ScanSpec): Long? {
    val caps = probeTurbo() ?: throw Ps5UnsupportedException("turbo scan not available")
    if (!caps.has(TsEngine.SERVER_RESIDENT)) throw Ps5UnsupportedException("server-resident results not available")
    require(regions.isNotEmpty()) { "no regions" }
    val segments = caps.has(TsEngine.SNAPSHOT_SEGMENTS)
    if (!segments && regions.size != 1) throw Ps5UnsupportedException("console cannot scan several regions in one session")
    ensureAuth()
    return withScanTimeout {
        var flags = TsFlag.SERVER_RESIDENT
        if (segments) flags = flags or TsFlag.SNAPSHOT_SEGMENTS
        val first = regions[0]
        val body = turboStartBody(pid, if (segments) 0 else first.start, if (segments) 0 else first.length,
            spec, spec.lenData(), flags, spec.compare.id)
        send(Op.TURBO_START, body)
        expectSuccess("TURBO_START")
        val payload = spec.payload()
        if (payload.isNotEmpty()) sendRaw(payload)
        if (spec.type == ValueType.BYTES) sendRaw(spec.mask!!)
        expectSuccess("TURBO_START(begin)")
        if (segments) sendRaw(segmentList(regions))
        val r = LeReader(readFully(12))
        val stored = r.i32()
        val count = r.i64()
        if (stored == 1) {
            expectSuccess("TURBO_START(end)")
            count
        } else {
            if (segments) {
                // declined: an immediate terminator and the final status follow
                if (readI64() != Wire.SENTINEL64) throw Ps5ProtocolException("TURBO_START: bad decline framing")
                expectSuccess("TURBO_START(end)")
                null
            } else {
                // The console streams every hit instead; we do not want them - drop the link.
                abort()
                throw TooManyResultsException()
            }
        }
    }
}

/**
 * Unknown-initial-value first scan: the console snapshots all [regions] and keeps them server side.
 * Returns the number of surviving slots. By default all-zero slots are dropped.
 */
fun Ps5Client.turboSnapshotStart(
    pid: Int,
    regions: List<ScanRegion>,
    type: ValueType,
    alignment: Int = 0,
    includeZeros: Boolean = false,
    progress: ProgressSink? = null,
): Long {
    val caps = probeTurbo() ?: throw Ps5UnsupportedException("turbo scan not available")
    if (!caps.has(TsEngine.SNAPSHOT)) throw Ps5UnsupportedException("snapshot scans not available")
    require(type.isFixed) { "snapshots need a fixed-size value type" }
    require(regions.isNotEmpty()) { "no regions" }
    val segments = caps.has(TsEngine.SNAPSHOT_SEGMENTS)
    if (!segments && regions.size != 1) throw Ps5UnsupportedException("console cannot snapshot several regions in one session")
    ensureAuth()
    val spec = ScanSpec(type, Compare.UNKNOWN, alignment = alignment)
    return withScanTimeout {
        var flags = TsFlag.SNAPSHOT
        if (segments) flags = flags or TsFlag.SNAPSHOT_SEGMENTS
        if (includeZeros) flags = flags or TsFlag.SNAPSHOT_INCLUDE_ZEROS
        val first = regions[0]
        val body = turboStartBody(pid, if (segments) 0 else first.start, if (segments) 0 else first.length,
            spec, 0, flags, Compare.UNKNOWN.id)
        send(Op.TURBO_START, body)
        expectSuccess("TURBO_START(snapshot)")
        expectSuccess("TURBO_START(snapshot begin)")
        if (segments) sendRaw(segmentList(regions))
        val plan = LeReader(readFully(16))
        plan.i64() // slot_count
        val totalBytes = plan.i64()
        while (true) {
            val done = readI64()
            if (done == Wire.SENTINEL64) break
            progress?.onProgress(done, totalBytes)
        }
        val sum = LeReader(readFully(12))
        val ok = sum.i32()
        val survivors = sum.i64()
        expectSuccess("TURBO_START(snapshot end)")
        if (ok == 0) throw Ps5Exception("The console could not create the snapshot (out of space or too large)")
        survivors
    }
}

/** Rescan of the server-resident set. Returns the new survivor count. */
fun Ps5Client.turboResidentCount(spec: ScanSpec, progress: ProgressSink? = null): Long {
    probeTurbo() ?: throw Ps5UnsupportedException("turbo scan not available")
    ensureAuth()
    return withScanTimeout {
        val body = LeWriter(22).u32(0).u64(0).u8(spec.type.id).u8(spec.compare.id)
            .u32(spec.lenData()).u32(TsFlag.SERVER_RESIDENT)
        send(Op.TURBO_COUNT, body.toByteArray())
        expectSuccess("TURBO_COUNT")
        val payload = spec.payload()
        if (payload.isNotEmpty()) sendRaw(payload)
        if (spec.type == ValueType.BYTES) sendRaw(spec.mask!!)
        while (true) {
            val done = readI64()
            if (done == Wire.SENTINEL64) break
            progress?.onProgress(done, 0)
        }
        val count = readI64()
        expectSuccess("TURBO_COUNT(end)")
        count
    }
}

/** Fetches [count] survivors starting at [start] (current value, previous value and optionally the first one). */
fun Ps5Client.turboGet(start: Long, count: Int, valueSize: Int): List<TurboRecord> {
    ensureAuth()
    return exec {
        send(Op.TURBO_GET, LeWriter(12).u32(start.toInt()).u32(count).u32(0).toByteArray())
        expectSuccess("TURBO_GET")
        val hdr = readI32()
        val n = hdr and 0x7FFFFFFF
        val hasFirst = hdr < 0
        if (n > count) throw Ps5ProtocolException("TURBO_GET returned $n > $count records")
        val ent = 8 + valueSize * (if (hasFirst) 3 else 2)
        val r = LeReader(readFully(n * ent))
        val list = ArrayList<TurboRecord>(n)
        repeat(n) {
            val a = r.i64()
            val cur = r.bytes(valueSize)
            val prev = r.bytes(valueSize)
            val first = if (hasFirst) r.bytes(valueSize) else null
            list.add(TurboRecord(a, cur, prev, first))
        }
        expectSuccess("TURBO_GET(end)")
        list
    }
}

/** Frees the server-resident session (also freed when the connection closes). */
fun Ps5Client.turboEnd() {
    if (turbo == null) return
    ensureAuth()
    exec {
        send(Op.TURBO_END)
        expectSuccess("TURBO_END")
    }
}

/** Lists readable regions with a measured read speed, so slow uncached GPU memory can be skipped. */
fun Ps5Client.turboRegions(pid: Int, max: Int = 4096, probeBytes: Int = 0x10000): List<TurboRegion> {
    probeTurbo() ?: throw Ps5UnsupportedException("turbo scan not available")
    ensureAuth()
    return withScanTimeout {
        send(Op.TURBO_REGIONS, LeWriter(16).u32(pid).u32(max).u32(probeBytes).u32(0).toByteArray())
        expectSuccess("TURBO_REGIONS")
        val n = readI32()
        if (n !in 0..16384) throw Ps5ProtocolException("bad region count $n")
        val r = LeReader(readFully(n * 32))
        val list = ArrayList<TurboRegion>(n)
        repeat(n) {
            val s = r.i64()
            val e = r.i64()
            val prot = r.i32()
            val flags = r.i32()
            val mbps = r.i32()
            r.i32()
            list.add(TurboRegion(s, e, prot, flags and 1 != 0, mbps))
        }
        expectSuccess("TURBO_REGIONS(end)")
        list
    }
}

class TooManyResultsException : Ps5Exception("Too many results - narrow the scan (different value/type, or use the unknown-value engine)")
