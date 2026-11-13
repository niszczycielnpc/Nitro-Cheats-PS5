package dev.ps5nitro.core

/** Hardware watchpoint kinds (x86 DR7 RW encodings, PROTOCOL.md 7.3). */
enum class WatchKind(val breakType: Int, val label: String) {
    WRITE(1, "writes"),
    READ_WRITE(3, "reads or writes"),
}

/** DR7 length encoding for a watched size (1, 2, 4 or 8 bytes). */
fun watchLengthCode(size: Int): Int = when (size) {
    1 -> 0
    2 -> 1
    4 -> 3
    8 -> 2
    else -> throw IllegalArgumentException("hardware watchpoints cover 1, 2, 4 or 8 bytes")
}

/**
 * Attaches the debugger to [pid]. The console then connects BACK to this machine on TCP 755, so an
 * [InterruptListener] must already be listening (see Tracer).
 */
fun Ps5Client.debugAttach(pid: Int) = exec {
    setReadTimeout(45_000)
    try {
        send(Op.DEBUG_ATTACH, LeWriter(4).u32(pid).toByteArray())
        expectSuccess("DEBUG_ATTACH")
    } finally {
        setReadTimeout(Ps5Connection.DEFAULT_READ_TIMEOUT_MS)
    }
}

/** Clears debug registers, resumes the game and detaches. */
fun Ps5Client.debugDetach() = exec {
    setReadTimeout(30_000)
    try {
        send(Op.DEBUG_DETACH)
        expectSuccess("DEBUG_DETACH")
    } finally {
        setReadTimeout(Ps5Connection.DEFAULT_READ_TIMEOUT_MS)
    }
}

/** Arms or disarms one of the four hardware watchpoints (index 0..3). */
fun Ps5Client.setWatchpoint(index: Int, enabled: Boolean, size: Int, kind: WatchKind, address: Long) = exec {
    send(
        Op.DEBUG_SET_WATCHPOINT,
        LeWriter(24).u32(index).u32(if (enabled) 1 else 0).u32(watchLengthCode(size))
            .u32(kind.breakType).u64(address).toByteArray(),
    )
    expectSuccess("SET_WATCHPOINT")
}

/** Resumes the game after a debugger stop (a no-op if it is running). */
fun Ps5Client.debugContinue() = exec {
    send(Op.DEBUG_CONTINUE, LeWriter(4).u32(0).toByteArray())
    expectSuccess("DEBUG_CONTINUE")
}

fun Ps5Client.threadList(): List<Int> = exec {
    send(Op.DEBUG_GET_THREAD_LIST)
    expectSuccess("GET_THREAD_LIST")
    val n = readI32()
    if (n !in 0..4096) throw Ps5ProtocolException("bad thread count $n")
    val r = LeReader(readFully(n * 4))
    List(n) { r.i32() }
}

/** The 176-byte FreeBSD amd64 `struct reg` of a stopped thread. */
fun Ps5Client.getRegs(lwpid: Int): ByteArray = exec {
    send(Op.DEBUG_GETREGS, LeWriter(4).u32(lwpid).toByteArray())
    expectSuccess("GETREGS")
    readFully(176)
}

/**
 * Server-side disassembly classification of [length] bytes at [address]. Instructions are decoded
 * linearly from [address]; at most [maxEntries] are returned.
 */
fun Ps5Client.disasm(pid: Int, address: Long, length: Int, maxEntries: Int = 4096): List<DisasmEntry> = exec {
    send(Op.PROC_DISASM_REGION, LeWriter(20).u32(pid).u64(address).u32(length).u32(maxEntries).toByteArray())
    expectSuccess("DISASM_REGION")
    val list = ArrayList<DisasmEntry>()
    while (true) {
        val raw = readFully(32)
        var allFF = true
        for (b in raw) if (b.toInt() != -1) { allFF = false; break }
        if (allFF) break
        val r = LeReader(raw)
        val addr = r.i64()
        val rel = r.i64()
        val disp = r.i64()
        val len = r.u8()
        val kind = r.u8()
        val base = r.u8()
        val idx = r.u8()
        val scale = r.u8()
        val mn = r.u8()
        list.add(DisasmEntry(addr, rel, disp, len, kind, base, idx, scale, mn))
        if (list.size > maxEntries + 1) throw Ps5ProtocolException("disassembly did not terminate")
    }
    list
}

/**
 * Addresses of instructions inside [scanAddress, +scanLength) whose RIP-relative operand points at
 * [target] (a global/static variable). Works without a debugger session.
 */
fun Ps5Client.findXrefsTo(pid: Int, scanAddress: Long, scanLength: Int, target: Long): List<Long> = exec {
    setReadTimeout(180_000)
    try {
        send(Op.PROC_FIND_XREFS_TO, LeWriter(24).u32(pid).u64(scanAddress).u32(scanLength).u64(target).toByteArray())
        expectSuccess("FIND_XREFS_TO")
        val out = ArrayList<Long>()
        while (true) {
            val a = readI64()
            if (a == Wire.SENTINEL64) break
            out.add(a)
            if (out.size > 500_000) throw Ps5ProtocolException("too many cross references")
        }
        out
    } finally {
        setReadTimeout(Ps5Connection.DEFAULT_READ_TIMEOUT_MS)
    }
}

/** Register offsets inside FreeBSD amd64 `struct reg`. */
object RegOffsets {
    const val R15 = 0x00; const val R14 = 0x08; const val R13 = 0x10; const val R12 = 0x18
    const val R11 = 0x20; const val R10 = 0x28; const val R9 = 0x30; const val R8 = 0x38
    const val RDI = 0x40; const val RSI = 0x48; const val RBP = 0x50; const val RBX = 0x58
    const val RDX = 0x60; const val RCX = 0x68; const val RAX = 0x70
    const val RIP = 0x88; const val RSP = 0xA0

    /** Zydis register ids 53..68 are RAX,RCX,RDX,RBX,RSP,RBP,RSI,RDI,R8..R15. */
    private val zydisGpr64Order = intArrayOf(RAX, RCX, RDX, RBX, RSP, RBP, RSI, RDI, R8, R9, R10, R11, R12, R13, R14, R15)
    private val names = arrayOf("rax", "rcx", "rdx", "rbx", "rsp", "rbp", "rsi", "rdi",
        "r8", "r9", "r10", "r11", "r12", "r13", "r14", "r15")

    /** Offset in `struct reg` for a Zydis register id, or -1 if unknown (only 64-bit GPRs are mapped). */
    fun offsetOfZydis(id: Int): Int = if (id in 53..68) zydisGpr64Order[id - 53] else -1

    fun nameOfZydis(id: Int): String = when (id) {
        0 -> ""
        in 53..68 -> names[id - 53]
        in 37..52 -> "e" + names[id - 37].removePrefix("r") // best effort for 32-bit address-size forms
        else -> "reg$id"
    }
}
