package dev.ps5nitro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UtilTest {
    private val modules = ModuleMap(
        listOf(
            MapEntry("eboot.bin", 0x400000L, 0x402000L, 0, 5),
            MapEntry("eboot.bin", 0x1000000L, 0x1001000L, 0, 3),
        ),
    )

    @Test
    fun addressParsing() {
        assertEquals(0x1A2BL, AddressText.parse("0x1A2B"))
        assertEquals(0x1A2BL, AddressText.parse("  1a2b "))
        assertEquals(0x4_0000_1000L, AddressText.parse("0x4`0000`1000"))
        assertEquals(-1L, AddressText.parse("FFFFFFFFFFFFFFFF"))
        assertEquals(0x401234L, AddressText.parse("eboot.bin+0x1234", modules))
        assertEquals(0x401234L, AddressText.parse("eboot.bin+1234", modules))
        for (bad in listOf("", "xyz", "0x", "12345678901234567", "eboot.bin+zz")) {
            try { AddressText.parse(bad, modules); fail("accepted \"$bad\"") } catch (_: IllegalArgumentException) {}
        }
        try { AddressText.parse("libc.sprx+10", modules); fail() } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("libc.sprx"))
        }
        try { AddressText.parse("eboot.bin+10", null); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun addressDescription() {
        assertEquals("eboot.bin+0x1234", AddressText.describe(0x401234L, modules))
        assertEquals("eboot.bin+0xC00100", AddressText.describe(0x1000100L, modules))
        assertEquals("0x500000", AddressText.describe(0x500000L, modules))
        assertEquals("0x10", AddressText.describe(0x10L, null))
    }

    @Test
    fun hexDump() {
        val data = byteArrayOf(0x48, 0x89.toByte(), 0x43, 0x10, 0x41, 0x42)
        val s = HexDump.format(0x400100L, data)
        assertEquals("0000400100  48 89 43 10 41 42" + "   ".repeat(10) + "  |H.C.AB|\n", s)
        val two = HexDump.format(0x10L, ByteArray(20) { it.toByte() })
        assertEquals(2, two.lines().filter { it.isNotEmpty() }.size)
        assertTrue(two.startsWith("0000000010  00 01 02"))
        assertEquals(3, HexDump.nops(3).size)
        assertEquals(0x90, HexDump.nops(1)[0].toInt() and 0xFF)
    }

    @Test
    fun firmware() {
        assertEquals("9.60", firmwareText(960))
        assertEquals("10.01", firmwareText(1001))
        assertEquals("4.03", firmwareText(403))
        assertEquals("?", firmwareText(0))
    }
}
