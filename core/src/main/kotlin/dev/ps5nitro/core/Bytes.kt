package dev.ps5nitro.core

/** Growing little-endian byte buffer used to build request packets. */
class LeWriter(capacity: Int = 64) {
    private var buf = ByteArray(maxOf(capacity, 16))
    var size = 0
        private set

    private fun ensure(extra: Int) {
        val need = size + extra
        if (need > buf.size) buf = buf.copyOf(maxOf(need, buf.size * 2))
    }

    fun u8(v: Int): LeWriter {
        ensure(1); buf[size++] = v.toByte(); return this
    }

    fun u16(v: Int): LeWriter {
        ensure(2)
        buf[size++] = v.toByte(); buf[size++] = (v ushr 8).toByte()
        return this
    }

    fun u32(v: Int): LeWriter {
        ensure(4)
        for (i in 0 until 4) buf[size++] = (v ushr (8 * i)).toByte()
        return this
    }

    fun u32(v: Long): LeWriter = u32(v.toInt())

    fun u64(v: Long): LeWriter {
        ensure(8)
        for (i in 0 until 8) buf[size++] = (v ushr (8 * i)).toByte()
        return this
    }

    fun bytes(b: ByteArray, off: Int = 0, len: Int = b.size - off): LeWriter {
        ensure(len); System.arraycopy(b, off, buf, size, len); size += len; return this
    }

    fun zeros(n: Int): LeWriter {
        ensure(n)
        for (i in 0 until n) buf[size + i] = 0
        size += n
        return this
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/** Little-endian cursor over a byte array. */
class LeReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {
    val remaining: Int get() = end - pos

    private fun need(n: Int) {
        if (n < 0 || pos + n > end) throw Ps5ProtocolException("short buffer: need $n bytes at $pos/$end")
    }

    fun u8(): Int { need(1); return buf[pos++].toInt() and 0xFF }
    fun u16(): Int { val a = u8(); val b = u8(); return a or (b shl 8) }
    fun i32(): Int { val a = u8(); val b = u8(); val c = u8(); val d = u8(); return a or (b shl 8) or (c shl 16) or (d shl 24) }
    fun u32(): Long = i32().toLong() and 0xFFFFFFFFL
    fun i64(): Long { val lo = u32(); val hi = u32(); return lo or (hi shl 32) }

    fun bytes(n: Int): ByteArray {
        need(n)
        val r = buf.copyOfRange(pos, pos + n); pos += n; return r
    }

    fun skip(n: Int) { need(n); pos += n }

    /** Fixed-size NUL-padded text field. */
    fun cstr(fieldLen: Int): String {
        need(fieldLen)
        var e = pos
        val lim = pos + fieldLen
        while (e < lim && buf[e].toInt() != 0) e++
        val s = String(buf, pos, e - pos, Charsets.UTF_8)
        pos = lim
        return s
    }
}

fun ByteArray.leU16(off: Int): Int = (this[off].toInt() and 0xFF) or ((this[off + 1].toInt() and 0xFF) shl 8)

fun ByteArray.leI32(off: Int): Int =
    (this[off].toInt() and 0xFF) or ((this[off + 1].toInt() and 0xFF) shl 8) or
        ((this[off + 2].toInt() and 0xFF) shl 16) or ((this[off + 3].toInt() and 0xFF) shl 24)

fun ByteArray.leI64(off: Int): Long =
    (leI32(off).toLong() and 0xFFFFFFFFL) or (leI32(off + 4).toLong() shl 32)

fun ByteArray.toHex(sep: String = ""): String {
    val sb = StringBuilder(size * (2 + sep.length))
    for (i in indices) {
        if (i > 0 && sep.isNotEmpty()) sb.append(sep)
        val v = this[i].toInt() and 0xFF
        sb.append("0123456789ABCDEF"[v ushr 4]).append("0123456789ABCDEF"[v and 15])
    }
    return sb.toString()
}

/** Parses "48 8B-05 0x12" style hex (separators ignored). */
fun String.hexToBytes(): ByteArray {
    val clean = this.replace("0x", "").replace("0X", "").filter { it.isLetterOrDigit() }
    require(clean.length % 2 == 0) { "odd number of hex digits" }
    require(clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "not hex" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun hex64(v: Long): String = "0x" + java.lang.Long.toHexString(v).uppercase()
