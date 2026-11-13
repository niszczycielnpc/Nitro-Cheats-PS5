package dev.ps5nitro.core

data class ProcessEntry(val name: String, val pid: Int)

/** Foreground title as reported by 0xBDDD0006 (pid == 0 means "no game running" and yields null). */
data class ForegroundApp(
    val pid: Int,
    val titleId: String,
    val contentId: String,
    val name: String,
    val appVersion: String,
)

data class ProcessInfo(val pid: Int, val name: String, val path: String, val titleId: String, val contentId: String)

/** One VM-map entry (58 bytes on the wire). */
data class MapEntry(val name: String, val start: Long, val end: Long, val fileOffset: Long, val prot: Int) {
    val size: Long get() = end - start
    val readable: Boolean get() = prot and 1 != 0
    val writable: Boolean get() = prot and 2 != 0
    val executable: Boolean get() = prot and 4 != 0
    fun contains(addr: Long): Boolean = addr >= start && addr < end

    fun protText(): String =
        (if (readable) "r" else "-") + (if (writable) "w" else "-") + (if (executable) "x" else "-")
}

/**
 * CMD_BRANDING answer: "<human text>\0<capability level>". The capability level is bumped when the
 * payload gains negotiable features ("1.0": bulk write + turbo scan, "1.1": verified writes).
 */
data class Branding(val text: String, val capability: String) {
    private fun parts(): Pair<Int, Int> {
        val p = capability.split('.')
        return (p.getOrNull(0)?.toIntOrNull() ?: 0) to (p.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    fun capabilityAtLeast(major: Int, minor: Int): Boolean {
        val (a, b) = parts()
        return a > major || (a == major && b >= minor)
    }

    val supportsWriteMulti: Boolean get() = capabilityAtLeast(1, 0)
}

data class TurboCaps(val version: Int, val engines: Int, val maxThreads: Int) {
    fun has(bit: Int): Boolean = engines and bit != 0
}

data class WriteEntry(val address: Long, val data: ByteArray)

/** One record returned by TURBOSCAN_REGIONS. */
data class TurboRegion(val start: Long, val end: Long, val prot: Int, val uncached: Boolean, val mbps: Int)

/** One instruction as classified by the server-side Zydis disassembler (32 bytes on the wire). */
data class DisasmEntry(
    val address: Long,
    val ripRelTarget: Long,
    val memDisp: Long,
    val length: Int,
    val kind: Int,
    val memBaseReg: Int,
    val memIndexReg: Int,
    val memScale: Int,
    val mnemonicLo: Int,
) {
    val isCall: Boolean get() = kind and 0x01 != 0
    val isRet: Boolean get() = kind and 0x02 != 0
    val isJump: Boolean get() = kind and 0x04 != 0
    val isCondBranch: Boolean get() = kind and 0x08 != 0
    val hasMemOperand: Boolean get() = kind and 0x10 != 0
    val isRipRelative: Boolean get() = kind and 0x20 != 0
    val readsMemory: Boolean get() = kind and 0x40 != 0
    val writesMemory: Boolean get() = kind and 0x80 != 0
    val end: Long get() = address + length
}

/** A readable address range worth scanning. */
data class ScanRegion(val start: Long, val length: Long, val name: String = "") {
    val end: Long get() = start + length
}
