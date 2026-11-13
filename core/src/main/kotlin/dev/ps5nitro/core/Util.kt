package dev.ps5nitro.core

/** Small helpers the UI needs and that are worth testing without a phone. */
object AddressText {
    /**
     * Accepts `0x1A2B`, `1A2B` (hex, like every memory tool) and `module+offset` (`eboot.bin+0x1234`).
     * @throws IllegalArgumentException with a message that can be shown to the user
     */
    fun parse(text: String, modules: ModuleMap? = null): Long {
        val clean = text.trim().replace("`", "").replace("'", "").replace(" ", "").replace("_", "")
        if (clean.isEmpty()) throw IllegalArgumentException("Enter an address.")
        val plus = clean.lastIndexOf('+')
        if (plus > 0) {
            val name = clean.substring(0, plus)
            val off = parseHex(clean.substring(plus + 1), text)
            val m = modules?.byName(name)
                ?: throw IllegalArgumentException("There is no module \"$name\" in the selected process.")
            return m.base + off
        }
        return parseHex(clean, text)
    }

    private fun parseHex(s: String, original: String): Long {
        val h = s.removePrefix("0x").removePrefix("0X")
        if (h.isEmpty() || h.length > 16 || !h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            throw IllegalArgumentException("Invalid address: \"${original.trim()}\" (hex, e.g. 0x1A2B3C or eboot.bin+1234).")
        }
        return java.lang.Long.parseUnsignedLong(h, 16)
    }

    /** `eboot.bin+0x1234` for module addresses, `0x…` otherwise. */
    fun describe(addr: Long, modules: ModuleMap?): String {
        val m = modules?.find(addr)
        return if (m != null) m.name + "+0x" + java.lang.Long.toHexString(m.offsetOf(addr)).uppercase() else hex64(addr)
    }
}

object HexDump {
    /** Classic 16-bytes-per-row dump: `0000000400100  48 89 43 10 ...  |H.C.|`. */
    fun format(base: Long, data: ByteArray, perRow: Int = 16): String {
        val sb = StringBuilder(data.size * 4 + 64)
        var off = 0
        while (off < data.size) {
            val n = minOf(perRow, data.size - off)
            sb.append(String.format("%010X ", base + off))
            for (i in 0 until perRow) {
                if (i < n) sb.append(String.format(" %02X", data[off + i].toInt() and 0xFF)) else sb.append("   ")
            }
            sb.append("  |")
            for (i in 0 until n) {
                val c = data[off + i].toInt() and 0xFF
                sb.append(if (c in 0x20..0x7E) c.toChar() else '.')
            }
            sb.append("|\n")
            off += n
        }
        return sb.toString()
    }

    /** x86 NOP run of [length] bytes (0x90 each - valid for any instruction length). */
    fun nops(length: Int): ByteArray = ByteArray(length) { 0x90.toByte() }
}

/** Version string of the console as the payload reports it: 960 -> "9.60". */
fun firmwareText(fw: Int): String = if (fw <= 0) "?" else "${fw / 100}.${(fw % 100).toString().padStart(2, '0')}"
