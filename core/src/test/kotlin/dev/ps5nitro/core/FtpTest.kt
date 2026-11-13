package dev.ps5nitro.core

import java.net.ServerSocket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FtpTest {
    private fun set(name: String = "Crash Bandicoot 4: It's About Time", patchOn: Int = 0x90) = CheatSet(
        name, "me", "PPSA01342", "01.004.000", "eboot.bin",
        listOf(Cheat("God mode", "", listOf(PatchLine(0x1234, byteArrayOf(patchOn.toByte()), byteArrayOf(0x89.toByte()))))),
    )

    private val shnPath = "/data/etaHEN/cheats/shn/PPSA01342_01.004.000_nitro.shn"

    @Test
    fun pushPlacesTheFileAndRegistersItInTheCache() {
        MockFtpServer().use { ftp ->
            val r = CheatPush.push("127.0.0.1", ftp.port, set(), CheatFormat.SHN)
            assertEquals(shnPath, r.remotePath)
            assertFalse(r.replaced)
            assertTrue(r.registered)
            assertArrayEquals(ShnWriter.write(set()), ftp.files[shnPath])
            assertEquals(
                "\nPPSA01342_01.004.000_nitro.shn=Crash Bandicoot 4: It's About Time\n",
                ftp.text("/data/etaHEN/cheats/shn.txt"),
            )
            assertTrue(listOf("/data/etaHEN", "/data/etaHEN/cheats", "/data/etaHEN/cheats/shn").all { it in ftp.dirs })
        }
    }

    @Test
    fun secondPushReplacesTheFileWithoutDuplicatingTheCacheLine() {
        MockFtpServer().use { ftp ->
            CheatPush.push("127.0.0.1", ftp.port, set(), CheatFormat.SHN)
            val r = CheatPush.push("127.0.0.1", ftp.port, set(patchOn = 0xCC), CheatFormat.SHN)
            assertTrue(r.replaced)
            assertFalse(r.registered)
            assertArrayEquals(ShnWriter.write(set(patchOn = 0xCC)), ftp.files[shnPath])
            val cache = ftp.text("/data/etaHEN/cheats/shn.txt")!!
            assertEquals(1, Regex("PPSA01342_01.004.000_nitro.shn=").findAll(cache).count())
        }
    }

    @Test
    fun jsonGoesToItsOwnFolderAndCache() {
        MockFtpServer().use { ftp ->
            val r = CheatPush.push("127.0.0.1", ftp.port, set(), CheatFormat.JSON)
            assertEquals("/data/etaHEN/cheats/json/PPSA01342_01.004.000_nitro.json", r.remotePath)
            assertArrayEquals(JsonCheatWriter.write(set()), ftp.files[r.remotePath])
            assertTrue(ftp.text("/data/etaHEN/cheats/json.txt")!!.contains("PPSA01342_01.004.000_nitro.json="))
            assertNull(ftp.files["/data/etaHEN/cheats/shn.txt"])
        }
    }

    @Test
    fun existingCommunityFileIsNeverTouched() {
        MockFtpServer().use { ftp ->
            ftp.dirs.addAll(listOf("/data/etaHEN", "/data/etaHEN/cheats", "/data/etaHEN/cheats/shn"))
            val community = "<Trainer>community</Trainer>".toByteArray()
            ftp.files["/data/etaHEN/cheats/shn/PPSA01342_01.004.000.shn"] = community
            CheatPush.push("127.0.0.1", ftp.port, set(), CheatFormat.SHN)
            assertArrayEquals(community, ftp.files["/data/etaHEN/cheats/shn/PPSA01342_01.004.000.shn"])
            assertEquals(2, ftp.files.keys.count { it.startsWith("/data/etaHEN/cheats/shn/PPSA01342") })
        }
    }

    @Test
    fun cacheRegistrationFailureIsNotFatal() {
        MockFtpServer().use { ftp ->
            ftp.refuseAppend = true
            val r = CheatPush.push("127.0.0.1", ftp.port, set(), CheatFormat.SHN)
            assertFalse(r.registered)
            assertTrue(ftp.files.containsKey(shnPath))
            // the connection state is still sane: a plain push without registration works as well
            CheatPush.push("127.0.0.1", ftp.port, set(), CheatFormat.SHN, registerInCache = false)
        }
    }

    @Test
    fun unreachableServerExplainsWhatToDo() {
        val closedPort = ServerSocket(0).use { it.localPort }
        try {
            CheatPush.push("127.0.0.1", closedPort, set(), CheatFormat.SHN)
            fail()
        } catch (e: FtpException) {
            assertTrue(e.message!!.contains("etaHEN"))
        }
    }

    @Test
    fun badTitleIdIsRejectedBeforeAnythingIsSent() {
        MockFtpServer().use { ftp ->
            val bad = CheatSet("g", "a", "bogus", "01.000.000", "eboot.bin", set().cheats)
            try { CheatPush.push("127.0.0.1", ftp.port, bad, CheatFormat.SHN); fail() } catch (_: IllegalArgumentException) {}
            assertTrue(ftp.commands.isEmpty())
        }
    }

    @Test
    fun downloadListingAndLargeTransfers() {
        MockFtpServer().use { ftp ->
            val big = ByteArray(1_500_000) { (it * 31).toByte() }
            FtpClient("127.0.0.1", ftp.port).use { c ->
                c.connect()
                c.mkdirs("/data/x/y")
                c.upload("/data/x/y/big.bin", big)
                c.upload("/data/x/y/small.txt", "hi".toByteArray())
                assertEquals(big.size.toLong(), c.size("/data/x/y/big.bin"))
                assertNull(c.size("/data/x/y/none"))
                assertArrayEquals(big, c.download("/data/x/y/big.bin"))
                assertEquals(listOf("big.bin", "small.txt"), c.nlst("/data/x/y"))
                assertTrue(c.nlst("/data/missing").isEmpty())
                try { c.download("/data/x/y/none"); fail() } catch (e: FtpException) { assertEquals(550, e.code) }
                try { c.download("/data/x/y/big.bin", maxBytes = 1000); fail() } catch (_: FtpException) {}
            }
        }
    }

    @Test
    fun commandInjectionIsRefused() {
        MockFtpServer().use { ftp ->
            FtpClient("127.0.0.1", ftp.port).use { c ->
                c.connect()
                try { c.size("/a\r\nDELE /b"); fail() } catch (_: IllegalArgumentException) {}
            }
            assertTrue(ftp.commands.none { it.startsWith("DELE") })
        }
    }
}
