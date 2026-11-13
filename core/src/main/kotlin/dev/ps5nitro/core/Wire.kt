package dev.ps5nitro.core

import java.io.IOException

/**
 * Wire-level constants of the ps5debug-NG protocol (PROTOCOL.md, ps5debug-NG v1.3.x).
 * All integers on the wire are little-endian.
 */
object Wire {
    const val PACKET_MAGIC: Int = 0xFFAABBCC.toInt()
    const val DEFAULT_PORT = 744
    const val INTERRUPT_PORT = 755
    const val KLOG_PORT = 3232
    const val DISCOVERY_PORT = 1010
    const val DISCOVERY_MAGIC: Int = 0xFFFFAAAA.toInt()

    /** The client sends this raw; the server compares bitswap32(x) with 0x7780D98E. */
    const val AUTH_MAGIC: Int = 0xBB40E64D.toInt()

    /**
     * Status words exactly as they are seen on the wire. The server bit-swaps every
     * status it sends (CMD_SUCCESS 0x40000000 becomes 0x80000000), clients compare raw.
     */
    const val ST_SUCCESS: Int = 0x80000000.toInt()
    const val ST_ERROR: Int = 0xF0000001.toInt()
    const val ST_DATA_NULL: Int = 0xF0000003.toInt()
    const val ST_ALREADY_DEBUG: Int = 0xF0000004.toInt()
    const val ST_INVALID_INDEX: Int = 0xF0000005.toInt()

    /** 0xFFFFFFFFFFFFFFFF as it is used as a stream terminator. */
    const val SENTINEL64: Long = -1L

    /** Largest candidate chunk the server accepts in SCAN_COUNT / TURBOSCAN_COUNT. */
    const val MAX_CHUNK_BYTES = 0x40000

    fun statusName(st: Int): String = when (st) {
        ST_SUCCESS -> "SUCCESS"
        ST_ERROR -> "ERROR"
        ST_DATA_NULL -> "DATA_NULL (bad request, or scans not authorised)"
        ST_ALREADY_DEBUG -> "ALREADY_DEBUG (another debugger session is active)"
        ST_INVALID_INDEX -> "INVALID_INDEX"
        else -> "0x" + Integer.toHexString(st).uppercase()
    }
}

/** Command opcodes (PROTOCOL.md section 4). */
object Op {
    const val VERSION = 0xBD000001.toInt()
    const val FW_VERSION = 0xBD000500.toInt()
    const val BRANDING = 0xBD000501.toInt()
    const val PLATFORM_ID = 0xBD000502.toInt()
    const val NOP = 0xBDAACC06.toInt()

    const val PROC_LIST = 0xBDAA0001.toInt()
    const val PROC_READ = 0xBDAA0002.toInt()
    const val PROC_WRITE = 0xBDAA0003.toInt()
    const val PROC_MAPS = 0xBDAA0004.toInt()
    const val PROC_INFO = 0xBDAA000A.toInt()
    const val PROC_DISASM_REGION = 0xBDAA0020.toInt()
    const val PROC_FIND_XREFS_TO = 0xBDAA0022.toInt()
    const val PROC_ASSEMBLE = 0xBDAA0024.toInt()

    const val PROC_AUTH = 0xBDAACCFF.toInt()
    const val SCAN_START = 0xBDAACC01.toInt()
    const val SCAN_COUNT = 0xBDAACC02.toInt()
    const val SCAN_GET = 0xBDAACC03.toInt()
    const val PROC_WRITE_MULTI = 0xBDAACC04.toInt()

    const val TURBO_CAPS = 0xBDAACC10.toInt()
    const val TURBO_START = 0xBDAACC11.toInt()
    const val TURBO_COUNT = 0xBDAACC12.toInt()
    const val TURBO_GET = 0xBDAACC13.toInt()
    const val TURBO_END = 0xBDAACC14.toInt()
    const val TURBO_REGIONS = 0xBDAACC16.toInt()
    const val TURBO_CANCEL = 0xBDAACC17.toInt()

    const val DEBUG_ATTACH = 0xBDBB0001.toInt()
    const val DEBUG_DETACH = 0xBDBB0002.toInt()
    const val DEBUG_SET_WATCHPOINT = 0xBDBB0004.toInt()
    const val DEBUG_GET_THREAD_LIST = 0xBDBB0005.toInt()
    const val DEBUG_GETREGS = 0xBDBB0008.toInt()
    const val DEBUG_CONTINUE = 0xBDBB0010.toInt()

    const val CONSOLE_PRINT = 0xBDDD0003.toInt()
    const val CONSOLE_NOTIFY = 0xBDDD0004.toInt()
    const val FOREGROUND_APP = 0xBDDD0006.toInt()
}

/** Turbo-scan flag bits (PROTOCOL.md 2.2, TURBOSCAN family). */
object TsFlag {
    const val USE_ALIASING = 0x01
    const val SERVER_RESIDENT = 0x02
    const val SNAPSHOT = 0x04
    const val SNAPSHOT_INCLUDE_ZEROS = 0x08
    const val SNAPSHOT_SEGMENTS = 0x10
    const val SNAPSHOT_KEEP_FIRST = 0x20
    const val SNAPSHOT_KEEP_PREVIOUS = 0x40
}

/** Turbo-scan engine bits advertised by TURBOSCAN_CAPS. */
object TsEngine {
    const val SIMD_COMPARE = 0x01
    const val ALIASING = 0x02
    const val SERVER_RESIDENT = 0x04
    const val SNAPSHOT = 0x08
    const val SNAPSHOT_SEGMENTS = 0x10
    const val SNAPSHOT_CONFIG = 0x20
}

/** Base class of every error raised by this library. */
open class Ps5Exception(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The console answered with a non-success status word. */
class Ps5StatusException(val op: String, val status: Int) :
    Ps5Exception("$op: ${Wire.statusName(status)}")

/** The byte stream does not look like the protocol we expect. */
class Ps5ProtocolException(message: String) : Ps5Exception(message)

/** The requested feature is not available (old payload, missing capability, ...). */
class Ps5UnsupportedException(message: String) : Ps5Exception(message)
