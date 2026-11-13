package dev.ps5nitro.core

/** Scan value types as numbered on the wire (PROTOCOL.md 7.1). */
enum class ValueType(val id: Int, val size: Int, val label: String) {
    U8(0, 1, "uint8"),
    I8(1, 1, "int8"),
    U16(2, 2, "uint16"),
    I16(3, 2, "int16"),
    U32(4, 4, "uint32"),
    I32(5, 4, "int32"),
    U64(6, 8, "uint64"),
    I64(7, 8, "int64"),
    F32(8, 4, "float"),
    F64(9, 8, "double"),
    BYTES(10, 0, "bytes (AOB)");

    val isFloat: Boolean get() = this == F32 || this == F64
    val isFixed: Boolean get() = size > 0
}

/** Scan compare modes as numbered on the wire (PROTOCOL.md 7.2). */
enum class Compare(val id: Int, val label: String, val needsValue: Boolean, val needsPrevious: Boolean) {
    EXACT(0, "exact value", true, false),
    FUZZY(1, "fuzzy (about)", true, false),
    BIGGER(2, "bigger than", true, false),
    SMALLER(3, "smaller than", true, false),
    BETWEEN(4, "between", true, false),
    INCREASED(5, "increased", false, true),
    INCREASED_BY(6, "increased by", true, true),
    DECREASED(7, "decreased", false, true),
    DECREASED_BY(8, "decreased by", true, true),
    CHANGED(9, "changed", false, true),
    UNCHANGED(10, "unchanged", false, true),
    UNKNOWN(11, "unknown initial value", false, false);

    val isBetween: Boolean get() = this == BETWEEN

    /** Comparisons that can start a scan (they do not depend on a previous value). */
    val canStartScan: Boolean get() = !needsPrevious
}

/**
 * What to look for. For [ValueType.BYTES] the [value] is the pattern and [mask] marks the bytes
 * that must match (1) or are wildcards (0); both have the same length.
 */
class ScanSpec(
    val type: ValueType,
    val compare: Compare,
    val value: ByteArray? = null,
    val value2: ByteArray? = null,
    val mask: ByteArray? = null,
    val alignment: Int = 0,
) {
    init {
        if (compare.needsValue) require(value != null) { "${compare.label} needs a value" }
        if (compare.isBetween) require(value2 != null) { "between needs two values" }
        if (type == ValueType.BYTES) {
            require(value != null && value.isNotEmpty()) { "bytes scan needs a pattern" }
            require(mask != null && mask.size == value.size) { "bytes scan needs a mask of the same length" }
            require(compare == Compare.EXACT) { "bytes scans only support exact match" }
            require(value.size <= 0x1000) { "pattern too long" }
        }
        if (!type.isFixed) require(compare == Compare.EXACT) { "unsupported compare for bytes" }
        if (type.isFixed) {
            value?.let { require(it.size == type.size) { "value size mismatch" } }
            value2?.let { require(it.size == type.size) { "value size mismatch" } }
        }
    }

    /** Size of one stored element (pattern length for AOB scans). */
    val elementSize: Int get() = if (type.isFixed) type.size else value!!.size

    /** Value bytes sent to the server (low and high value for BETWEEN). Empty when none is needed. */
    fun payload(): ByteArray {
        if (!compare.needsValue) return NO_BYTES
        val w = LeWriter()
        w.bytes(value!!)
        if (compare.isBetween) w.bytes(value2!!)
        return w.toByteArray()
    }

    /** Server-side "lenData" field. It must be non-zero even if no value follows. */
    fun lenData(): Int = if (compare.needsValue) payload().size else elementSize

    fun withCompare(c: Compare, v: ByteArray? = value, v2: ByteArray? = value2): ScanSpec =
        ScanSpec(type, c, v, v2, mask, alignment)
}

private val NO_BYTES = ByteArray(0)

object ValueCodec {
    fun parse(type: ValueType, text: String): ByteArray {
        val t = text.trim()
        if (t.isEmpty()) throw NumberFormatException("empty value")
        return when (type) {
            ValueType.F32 -> {
                val bits = java.lang.Float.floatToRawIntBits(t.replace(',', '.').toFloat())
                LeWriter(4).u32(bits).toByteArray()
            }
            ValueType.F64 -> {
                val bits = java.lang.Double.doubleToRawLongBits(t.replace(',', '.').toDouble())
                LeWriter(8).u64(bits).toByteArray()
            }
            ValueType.BYTES -> throw NumberFormatException("use parseAob for byte patterns")
            else -> {
                val v = parseInteger(t)
                val (lo, hi) = range(type)
                if (type == ValueType.U64) {
                    // parseInteger returns the two's complement bit pattern for U64
                } else if (v < lo || v > hi) {
                    throw NumberFormatException("$t is out of range for ${type.label}")
                }
                val w = LeWriter(8)
                when (type.size) {
                    1 -> w.u8(v.toInt())
                    2 -> w.u16(v.toInt())
                    4 -> w.u32(v.toInt())
                    else -> w.u64(v)
                }
                w.toByteArray()
            }
        }
    }

    private fun parseInteger(t: String): Long {
        var s = t
        var neg = false
        if (s.startsWith("-")) { neg = true; s = s.substring(1) }
        val v = if (s.startsWith("0x") || s.startsWith("0X")) {
            java.lang.Long.parseUnsignedLong(s.substring(2), 16)
        } else {
            java.lang.Long.parseUnsignedLong(s, 10)
        }
        return if (neg) -v else v
    }

    private fun range(type: ValueType): Pair<Long, Long> = when (type) {
        ValueType.U8 -> 0L to 255L
        ValueType.I8 -> -128L to 127L
        ValueType.U16 -> 0L to 65535L
        ValueType.I16 -> -32768L to 32767L
        ValueType.U32 -> 0L to 0xFFFFFFFFL
        ValueType.I32 -> Int.MIN_VALUE.toLong() to Int.MAX_VALUE.toLong()
        ValueType.I64 -> Long.MIN_VALUE to Long.MAX_VALUE
        else -> Long.MIN_VALUE to Long.MAX_VALUE
    }

    fun format(type: ValueType, b: ByteArray, off: Int = 0): String = when (type) {
        ValueType.U8 -> (b[off].toInt() and 0xFF).toString()
        ValueType.I8 -> b[off].toInt().toString()
        ValueType.U16 -> b.leU16(off).toString()
        ValueType.I16 -> b.leU16(off).toShort().toInt().toString()
        ValueType.U32 -> (b.leI32(off).toLong() and 0xFFFFFFFFL).toString()
        ValueType.I32 -> b.leI32(off).toString()
        ValueType.U64 -> java.lang.Long.toUnsignedString(b.leI64(off))
        ValueType.I64 -> b.leI64(off).toString()
        ValueType.F32 -> formatFloat(java.lang.Float.intBitsToFloat(b.leI32(off)).toDouble(), true)
        ValueType.F64 -> formatFloat(java.lang.Double.longBitsToDouble(b.leI64(off)), false)
        ValueType.BYTES -> b.copyOfRange(off, b.size).toHex(" ")
    }

    private fun formatFloat(d: Double, single: Boolean): String {
        if (d.isNaN() || d.isInfinite()) return d.toString()
        if (d == Math.rint(d) && Math.abs(d) < 1e9) return d.toLong().toString() + ".0"
        return if (single) d.toFloat().toString() else d.toString()
    }

    /** "48 8B ?? 05" to (pattern, mask) - mask byte 1 means "must match", 0 is a wildcard. */
    fun parseAob(text: String): Pair<ByteArray, ByteArray> {
        val tokens = text.trim().split(Regex("[\\s,-]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) throw NumberFormatException("empty pattern")
        val pat = ByteArray(tokens.size)
        val mask = ByteArray(tokens.size)
        for ((i, tk) in tokens.withIndex()) {
            if (tk == "?" || tk == "??" || tk == "*" || tk == "**") continue
            val t = tk.removePrefix("0x").removePrefix("0X")
            if (t.length !in 1..2 || !t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                throw NumberFormatException("bad byte '$tk'")
            }
            pat[i] = t.toInt(16).toByte()
            mask[i] = 1
        }
        if (mask.all { it.toInt() == 0 }) throw NumberFormatException("pattern is all wildcards")
        return pat to mask
    }
}
