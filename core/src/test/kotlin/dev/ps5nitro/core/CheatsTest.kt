package dev.ps5nitro.core

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

class CheatsTest {
    private val maps = listOf(
        MapEntry("eboot.bin", 0x400000L, 0x402000L, 0, 5),
        MapEntry("eboot.bin", 0x1000000L, 0x1001000L, 0, 3),
        MapEntry("libc.sprx", 0x2000000L, 0x2002000L, 0, 5),
        MapEntry("", 0x4_0000_0000L, 0x4_0004_0000L, 0, 3),
    )
    private val modules = ModuleMap(maps)
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private val gameName = "Crash Bandicoot 4: It's About Time"

    private fun sampleSet(): CheatSet = CheatExporter.build(
        modules, gameName, "ps5nitro", "PPSA01342", "01.004.000",
        listOf(
            AbsCheat("God mode", "Health never drops", listOf(AbsPatch(0x401234L, b(0x90, 0x90, 0x90), b(0x89, 0x43, 0x10)))),
            AbsCheat(
                "Infinite <lives> & \"stuff\"", "two patches",
                listOf(AbsPatch(0x1000100L, b(0xC6, 0x05), b(0x89, 0x0D)), AbsPatch(0x1000200L, b(1), b(0))),
            ),
        ),
    )

    private fun samplesDir(): File = File(System.getProperty("java.io.tmpdir"), "ps5nitro-samples").apply { mkdirs() }

    // ------------------------------------------------------------------------------------ modules

    @Test
    fun moduleMapGroupsMappingsByName() {
        assertEquals(listOf("eboot.bin", "libc.sprx"), modules.modules.map { it.name })
        val eboot = modules.byName("eboot.bin")!!
        assertEquals(0x400000L, eboot.base)
        assertEquals(0x1001000L, eboot.end)
        assertEquals("eboot.bin", modules.find(0x1000800L)?.name)
        assertEquals("libc.sprx", modules.find(0x2000010L)?.name)
        assertNull(modules.find(0x4_0000_0010L))      // anonymous heap
        assertNull(modules.find(0x800000L))           // gap between the two eboot.bin mappings
        assertEquals("eboot.bin", modules.mainModule()?.name)
        assertEquals(0xC00100L, eboot.offsetOf(0x1000100L))
    }

    @Test
    fun mainModuleFallbacks() {
        val m = ModuleMap(listOf(MapEntry("game.elf", 0x1000L, 0x2000L, 0, 5), MapEntry("libx.sprx", 0x5000L, 0x6000L, 0, 5)))
        assertEquals("game.elf", m.mainModule()?.name)
        assertNull(ModuleMap(listOf(MapEntry("", 0x1000L, 0x2000L, 0, 3))).mainModule())
    }

    // ------------------------------------------------------------------------------------ exporter

    @Test
    fun exporterComputesModuleRelativeOffsets() {
        val set = sampleSet()
        assertEquals("eboot.bin", set.process)
        assertEquals("PPSA01342", set.titleId)
        assertEquals(2, set.cheats.size)
        assertEquals(0x1234L, set.cheats[0].lines.single().offset)
        assertEquals(listOf(0xC00100L, 0xC00200L), set.cheats[1].lines.map { it.offset })
        assertArrayEquals(b(0x90, 0x90, 0x90), set.cheats[0].lines[0].on)
        assertArrayEquals(b(0x89, 0x43, 0x10), set.cheats[0].lines[0].off)
    }

    private fun exportError(vararg cheats: AbsCheat, process: String? = null): String =
        try {
            CheatExporter.build(modules, "g", "a", "PPSA01342", "01.004.000", cheats.toList(), process)
            fail("export must be refused"); ""
        } catch (e: CheatExportException) {
            e.message!!
        }

    @Test
    fun exporterRefusesHeapAddressesMixedModulesAndEmptyInput() {
        val heap = AbsCheat("heap", "", listOf(AbsPatch(0x4_0000_0100L, b(1), b(0))))
        assertTrue(exportError(heap).contains("not inside a named module"))

        val mixed = AbsCheat("mixed", "", listOf(AbsPatch(0x401000L, b(1), b(0)), AbsPatch(0x2000100L, b(1), b(0))))
        assertTrue(exportError(mixed).contains("one module"))

        val libc = AbsCheat("libc", "", listOf(AbsPatch(0x2000100L, b(1), b(0))))
        assertTrue(exportError(libc, process = "eboot.bin").contains("libc.sprx"))

        assertTrue(exportError().contains("at least one"))
        assertTrue(exportError(AbsCheat("none", "", emptyList())).contains("no patches"))
    }

    @Test
    fun patchValidation() {
        try { AbsPatch(0x401000L, b(1, 2), b(1)); fail() } catch (_: IllegalArgumentException) {}
        try { PatchLine(-1, b(1), b(0)); fail() } catch (_: IllegalArgumentException) {}
        try { PatchLine(1, ByteArray(0), ByteArray(0)); fail() } catch (_: IllegalArgumentException) {}
        try { Cheat(" ", "", emptyList()); fail() } catch (_: IllegalArgumentException) {}
    }

    // ------------------------------------------------------------------------------------ SHN

    @Test
    fun shnFileHasTheExpectedShape() {
        val text = String(ShnWriter.write(sampleSet()), Charsets.UTF_8)
        assertTrue(text.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>"))
        assertTrue(text.contains("Game=\"Crash Bandicoot 4: It&apos;s About Time\""))
        assertTrue(text.contains("Cusa=\"PPSA01342\""))
        assertTrue(text.contains("Version=\"01.004.000\""))
        assertTrue(text.contains("Process=\"eboot.bin\""))
        assertTrue(text.contains("<Cheat Text=\"God mode\" Description=\"Health never drops\">"))
        assertTrue(text.contains("<Offset>1234</Offset>"))
        assertTrue(text.contains("<ValueOn>90-90-90</ValueOn>"))
        assertTrue(text.contains("<ValueOff>89-43-10</ValueOff>"))
        assertTrue(text.contains("<Offset>C00100</Offset>"))
        assertTrue(text.contains("Infinite &lt;lives&gt; &amp; &quot;stuff&quot;"))
        assertFalse(text.contains("<lives>"))
        File(samplesDir(), "PPSA01342_01.004.000_nitro.shn").writeBytes(ShnWriter.write(sampleSet()))
    }

    @Test
    fun shnRoundTrip() {
        val original = sampleSet()
        val back = ShnReader.read(ShnWriter.write(original))
        assertEquals(gameName, back.gameName)
        assertEquals("ps5nitro", back.author)
        assertEquals("PPSA01342", back.titleId)
        assertEquals("01.004.000", back.appVersion)
        assertEquals("eboot.bin", back.process)
        assertEquals(original.cheats.map { it.name }, back.cheats.map { it.name })
        for ((a, c) in original.cheats.zip(back.cheats)) {
            assertEquals(a.description, c.description)
            assertEquals(a.lines.size, c.lines.size)
            for ((x, y) in a.lines.zip(c.lines)) {
                assertEquals(x.offset, y.offset)
                assertArrayEquals(x.on, y.on)
                assertArrayEquals(x.off, y.off)
            }
        }
    }

    @Test
    fun shnReaderAcceptsTheEncodingQuirks() {
        val utf8 = String(ShnWriter.write(sampleSet()), Charsets.UTF_8)
        // community files say utf-16 but are stored as UTF-8
        val lying = utf8.replace("encoding=\"utf-8\"", "encoding=\"utf-16\"").toByteArray(Charsets.UTF_8)
        assertEquals(2, ShnReader.read(lying).cheats.size)
        // a real UTF-16LE file with BOM
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + utf8.replace("utf-8", "utf-16").toByteArray(Charsets.UTF_16LE)
        assertEquals(2, ShnReader.read(utf16).cheats.size)
        // UTF-8 with BOM
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8.toByteArray(Charsets.UTF_8)
        assertEquals(2, ShnReader.read(bom).cheats.size)
    }

    @Test
    fun shnReaderSkipsIncompleteLinesLikeEtaHen() {
        val xml = """
            <Trainer Game="g" Moder="m" Cusa="PPSA01342" Version="1.0" Process="eboot.bin">
              <Cheat Text="partial" Description="">
                <Cheatline><Offset>10</Offset><Section>0</Section><ValueOn>90</ValueOn><ValueOff></ValueOff></Cheatline>
                <Cheatline><Offset>20</Offset><Section>1</Section><ValueOn>90-90</ValueOn><ValueOff>0f-1f</ValueOff></Cheatline>
              </Cheat>
              <Cheat Description="no name"><Cheatline><Offset>30</Offset><ValueOn>01</ValueOn><ValueOff>00</ValueOff></Cheatline></Cheat>
            </Trainer>
        """.trimIndent()
        val set = ShnReader.read(xml.toByteArray())
        assertEquals(2, set.cheats.size)
        val line = set.cheats[0].lines.single()
        assertEquals(0x20L, line.offset)
        assertEquals(1, line.section)
        assertArrayEquals(b(0x0F, 0x1F), line.off)
        assertEquals("(unnamed)", set.cheats[1].name)
        assertEquals("Action", set.genre)
    }

    @Test
    fun shnReaderRejectsGarbageAndEntities() {
        for (bad in listOf("hello", "<Other/>", "<Trainer><Cheat Text=\"x\"><Cheatline><Offset>zz</Offset><ValueOn>90</ValueOn><ValueOff>90</ValueOff></Cheatline></Cheat></Trainer>")) {
            try { ShnReader.read(bad.toByteArray()); fail("accepted: $bad") } catch (_: Ps5ProtocolException) {}
        }
        val xxe = "<?xml version=\"1.0\"?><!DOCTYPE t [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><Trainer Game=\"&x;\"/>"
        try { ShnReader.read(xxe.toByteArray()); fail("DOCTYPE must be refused") } catch (_: Ps5ProtocolException) {}
    }

    @Test
    fun communityFilesParse() {
        val dir = File("/home/claude/src/ps5-cheats/shn")
        assumeTrue(dir.isDirectory)
        val files = dir.listFiles { f -> f.name.endsWith(".shn") }!!.sortedBy { it.name }
        assumeTrue(files.isNotEmpty())
        var ok = 0
        var lines = 0
        val failures = ArrayList<String>()
        for (f in files) {
            try {
                val s = ShnReader.read(f.readBytes())
                ok++
                lines += s.cheats.sumOf { it.lines.size }
            } catch (e: Ps5ProtocolException) {
                failures.add(f.name + ": " + e.message!!.take(120))
            }
        }
        println("community SHN files: ${files.size}, parsed $ok, patch lines $lines, failures ${failures.size}")
        failures.take(8).forEach { println("  FAIL $it") }
        assertTrue("most real files must parse (parsed $ok of ${files.size})", ok >= files.size * 0.9)
        assertTrue(lines > 0)
    }

    // ------------------------------------------------------------------------------------ JSON

    @Test
    fun jsonFileHasTheExpectedShape() {
        val set = CheatSet(
            gameName, "me", "PPSA01342", "01.004.000", "eboot.bin",
            listOf(
                Cheat("Two \"quotes\" \\ back\nline\ttab ąę€", "d", listOf(PatchLine(0x1234, b(0x90), b(0x89)))),
                Cheat("Other section", "", listOf(PatchLine(0xABCDEF, b(1, 2), b(3, 4), section = 2))),
            ),
        )
        val text = String(JsonCheatWriter.write(set), Charsets.UTF_8)
        assertTrue(text.contains("\"id\": \"PPSA01342\""))
        assertTrue(text.contains("\"version\": \"01.004.000\""))
        assertTrue(text.contains("\"process\": \"eboot.bin\""))
        assertTrue(text.contains("\"offset\": \"1234\""))
        assertTrue(text.contains("\"on\": \"90\""))
        assertTrue(text.contains("\"off\": \"89\""))
        assertTrue(text.contains("\"offset\": \"ABCDEF\""))
        assertTrue(text.contains("\"section\": \"2\""))                 // string, as etaHEN expects
        assertEquals(1, Regex("\"section\"").findAll(text).count())      // omitted for section 0
        assertTrue(text.contains("\\\"quotes\\\""))
        assertTrue(text.contains("\\n"))
        File(samplesDir(), "PPSA01342_01.004.000_nitro.json").writeBytes(JsonCheatWriter.write(set))
        File(samplesDir(), "second.json").writeBytes(JsonCheatWriter.write(sampleSet()))
    }

    // ------------------------------------------------------------------------------------ file naming

    @Test
    fun cheatFileNames() {
        assertEquals("PPSA01342_01.004.000_nitro.shn", CheatFiles.fileName("PPSA01342", "01.004.000", "shn"))
        assertEquals("CUSA05786_01.00.json", CheatFiles.fileName("CUSA05786", "01.00", "json", tag = null))
        assertEquals("PPSA01342_01.004.000_mytag.shn", CheatFiles.fileName("PPSA01342", "01.004.000", "shn", "my tag!"))
        assertEquals(
            "/data/etaHEN/cheats/json/PPSA01342_01.004.000_nitro.json",
            CheatFiles.remotePath("PPSA01342", "01.004.000", "json"),
        )
        assertEquals("/data/etaHEN/cheats/shn", CheatFiles.dir("shn"))
        for (badId in listOf("", "ppsa01342", "PPSA1342", "PPSA013422", "../../etc")) {
            try { CheatFiles.fileName(badId, "01.000.000", "shn"); fail("accepted $badId") } catch (_: IllegalArgumentException) {}
        }
        for (badVersion in listOf("", "1/2", "..", "a b", "01.004.000.000.000")) {
            try { CheatFiles.fileName("PPSA01342", badVersion, "shn"); fail("accepted $badVersion") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test
    fun escapingHelpers() {
        assertEquals("a&amp;b&lt;c&gt;&quot;d&quot;&apos;e&apos; f", xmlEscape("a&b<c>\"d\"'e'\u0001f"))
        assertEquals("\"a\\\"b\\\\c\\u0001\"", jsonString("a\"b\\c\u0001"))
        assertNotNull(CheatFormat.valueOf("SHN"))
    }
}
