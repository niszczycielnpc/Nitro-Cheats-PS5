package dev.ps5nitro.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProtocolTest {
    @Test
    fun keystreamMatchesTheCReference() {
        // keystream.hex is produced by compiling the original auth.c routine (tools/keystream_ref.c)
        val ref = javaClass.getResourceAsStream("/keystream.hex")!!.readBytes().toString(Charsets.US_ASCII).trim()
        assertEquals(512, ref.length)
        assertEquals(ref, Keystream.bytes().toHex())
    }

    @Test
    fun statusConstantsAreTheBitSwappedServerMacros() {
        fun swap(x: Int) = ((x ushr 1) and 0x55555555) or ((x shl 1) and 0xAAAAAAAA.toInt())
        assertEquals(swap(0x40000000), Wire.ST_SUCCESS)
        assertEquals(swap(0xF0000002.toInt()), Wire.ST_ERROR)
        assertEquals(swap(0xF0000003.toInt()), Wire.ST_DATA_NULL)
        assertEquals(swap(0xF0000008.toInt()), Wire.ST_ALREADY_DEBUG)
        assertEquals(swap(0xF000000A.toInt()), Wire.ST_INVALID_INDEX)
        assertEquals(0x7780D98E, swap(Wire.AUTH_MAGIC))
    }

    @Test
    fun opcodesMatchTheProtocolTable() {
        assertEquals(0xBD000001.toInt(), Op.VERSION)
        assertEquals(0xBD000500.toInt(), Op.FW_VERSION)
        assertEquals(0xBD000501.toInt(), Op.BRANDING)
        assertEquals(0xBDAACC06.toInt(), Op.NOP)
        assertEquals(0xBDAA0001.toInt(), Op.PROC_LIST)
        assertEquals(0xBDAA0002.toInt(), Op.PROC_READ)
        assertEquals(0xBDAA0003.toInt(), Op.PROC_WRITE)
        assertEquals(0xBDAA0004.toInt(), Op.PROC_MAPS)
        assertEquals(0xBDAA000A.toInt(), Op.PROC_INFO)
        assertEquals(0xBDAA0020.toInt(), Op.PROC_DISASM_REGION)
        assertEquals(0xBDAA0022.toInt(), Op.PROC_FIND_XREFS_TO)
        assertEquals(0xBDAACCFF.toInt(), Op.PROC_AUTH)
        assertEquals(0xBDAACC01.toInt(), Op.SCAN_START)
        assertEquals(0xBDAACC02.toInt(), Op.SCAN_COUNT)
        assertEquals(0xBDAACC03.toInt(), Op.SCAN_GET)
        assertEquals(0xBDAACC04.toInt(), Op.PROC_WRITE_MULTI)
        assertEquals(0xBDAACC10.toInt(), Op.TURBO_CAPS)
        assertEquals(0xBDAACC11.toInt(), Op.TURBO_START)
        assertEquals(0xBDAACC12.toInt(), Op.TURBO_COUNT)
        assertEquals(0xBDAACC13.toInt(), Op.TURBO_GET)
        assertEquals(0xBDAACC14.toInt(), Op.TURBO_END)
        assertEquals(0xBDAACC16.toInt(), Op.TURBO_REGIONS)
        assertEquals(0xBDBB0001.toInt(), Op.DEBUG_ATTACH)
        assertEquals(0xBDBB0002.toInt(), Op.DEBUG_DETACH)
        assertEquals(0xBDBB0004.toInt(), Op.DEBUG_SET_WATCHPOINT)
        assertEquals(0xBDBB0005.toInt(), Op.DEBUG_GET_THREAD_LIST)
        assertEquals(0xBDBB0008.toInt(), Op.DEBUG_GETREGS)
        assertEquals(0xBDBB0010.toInt(), Op.DEBUG_CONTINUE)
        assertEquals(0xBDDD0004.toInt(), Op.CONSOLE_NOTIFY)
        assertEquals(0xBDDD0006.toInt(), Op.FOREGROUND_APP)
    }

    @Test
    fun connectReadsBrandingAndVersions() {
        World().use { w ->
            val b = w.client.branding!!
            assertTrue(b.text.startsWith("ps5debug-NG by OSR"))
            assertEquals("1.1", b.capability)
            assertTrue(b.supportsWriteMulti)
            assertEquals(960, w.client.firmware)
            assertEquals("1.3", w.client.protocolVersion)
            w.client.ping()
        }
    }

    @Test
    fun processesForegroundAndMaps() {
        World().use { w ->
            val list = w.client.processList()
            assertEquals(1, list.size)
            assertEquals("eboot.bin", list[0].name)
            assertEquals(77, list[0].pid)

            val fg = w.client.foregroundApp()!!
            assertEquals(77, fg.pid)
            assertEquals("PPSA01342", fg.titleId)
            assertEquals("01.004.000", fg.appVersion)

            val info = w.client.processInfo(77)
            assertEquals("PPSA01342", info.titleId)

            val maps = w.client.maps(77)
            assertEquals(3, maps.size)
            assertEquals("eboot.bin", maps[0].name)
            assertEquals(0x400000L, maps[0].start)
            assertEquals("r-x", maps[0].protText())
            assertEquals("rw-", maps[2].protText())
            assertEquals(0x4_0000_0000L, maps[2].start)

            w.server.foregroundPid = 0
            assertNull(w.client.foregroundApp())
        }
    }

    @Test
    fun readAndWrite() {
        World().use { w ->
            val a = w.heapBase + 0x10
            w.client.write(w.pid, a, byteArrayOf(1, 2, 3, 4))
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), w.client.read(w.pid, a, 4))
            // beyond the mapped memory the console hands back zeros
            val edge = w.heapBase + 0x40000 - 2
            w.bytes(edge, byteArrayOf(9, 9))
            assertArrayEquals(byteArrayOf(9, 9, 0, 0), w.client.read(w.pid, edge, 4))
            try {
                w.client.write(w.pid, 0x123456789L, byteArrayOf(1))
                fail("unmapped write must fail")
            } catch (e: Ps5StatusException) {
                assertEquals(Wire.ST_ERROR, e.status)
            }
            // the connection is still usable after a status error
            w.client.ping()
        }
    }

    @Test
    fun largeReadsAreChunked() {
        World().use { w ->
            val buf = ByteArray(0x30000) { (it * 7).toByte() }
            w.bytes(w.heapBase, buf)
            assertArrayEquals(buf, w.client.read(w.pid, w.heapBase, buf.size))
        }
    }

    @Test
    fun writeMultiReportsPerEntryStatus() {
        World().use { w ->
            val st = w.client.writeMulti(
                w.pid,
                listOf(
                    WriteEntry(w.heapBase + 0x20, byteArrayOf(5, 6)),
                    WriteEntry(0x1L, byteArrayOf(1)),
                    WriteEntry(w.heapBase + 0x30, byteArrayOf(7)),
                ),
            )
            assertArrayEquals(byteArrayOf(0, 4, 0), st)
            assertArrayEquals(byteArrayOf(5, 6), w.client.read(w.pid, w.heapBase + 0x20, 2))
            assertArrayEquals(byteArrayOf(7), w.client.read(w.pid, w.heapBase + 0x30, 1))
            assertEquals(1, w.server.opCount(Op.PROC_WRITE_MULTI))
        }
    }

    @Test
    fun writeMultiFallsBackOnOldPayloads() {
        World(capability = "0.5").use { w ->
            val st = w.client.writeMulti(w.pid, listOf(WriteEntry(w.heapBase + 8, byteArrayOf(3)), WriteEntry(2L, byteArrayOf(1))))
            assertArrayEquals(byteArrayOf(0, 4), st)
            assertEquals(0, w.server.opCount(Op.PROC_WRITE_MULTI))
            assertEquals(2, w.server.opCount(Op.PROC_WRITE))
        }
    }

    @Test
    fun notifyDeliversText() {
        World().use { w ->
            w.client.notify("hello from the phone")
            assertEquals("hello from the phone", w.server.lastNotification)
        }
    }

    @Test
    fun authHandshakeEnablesScans() {
        World().use { w ->
            assertFalse(w.server.authorised)
            w.client.ensureAuth()
            assertTrue(w.server.authorised)
        }
    }

    @Test
    fun scanGetBatchesAndFallsBack() {
        World().use { w ->
            val addrs = LongArray(5000) { w.heapBase + it * 4L }
            for (i in 0 until 5000) w.u32(addrs[i], i + 1)
            val vals = w.client.readMany(w.pid, addrs, IntArray(5000) { 4 })
            assertEquals(5000, vals.size)
            assertEquals(1, vals[0].leI32(0))
            assertEquals(5000, vals[4999].leI32(0))
            assertEquals(3, w.server.opCount(Op.SCAN_GET)) // 2048 + 2048 + 904
        }
    }

    @Test
    fun ioErrorsDropTheConnectionAndReconnectWorks() {
        World().use { w ->
            w.client.abortConnection()
            try {
                w.client.ping()
                fail("expected failure on an aborted connection")
            } catch (e: java.io.IOException) {
                assertNotNull(e.message)
            }
            w.client.reconnect()
            w.client.ping()
        }
    }
}
