package dev.ps5nitro.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ScanTest {
    private fun addrs(e: ScanEngine): Set<Long> = e.page(0, 100_000).map { it.address }.toSet()

    private fun seed(w: World, vararg values: Pair<Long, Int>) {
        for ((off, v) in values) w.u32(w.heapBase + off, v)
    }

    // ---------------------------------------------------------------- iterative engine

    @Test
    fun exactFirstScanThenNarrowing() {
        World().use { w ->
            val a = w.heapBase + 0x100; val b = w.heapBase + 0x2000; val c = w.heapBase + 0x3FF00
            for (x in listOf(a, b, c)) w.u32(x, 1234)
            w.u32(w.heapBase + 0x5001, 1234) // unaligned copy must not match a 4-byte aligned scan
            val e = IterativeEngine(w.client)
            assertEquals(3L, e.first(w.pid, w.regions(), u32spec(Compare.EXACT, 1234)))
            assertEquals(setOf(a, b, c), addrs(e))

            w.u32(a, 1235); w.u32(c, 1233)
            assertEquals(1L, e.next(u32spec(Compare.UNCHANGED)))
            val hit = e.page(0, 10).single()
            assertEquals(b, hit.address)
            assertEquals(1234, hit.value.leI32(0))
            assertEquals(1234, hit.previous!!.leI32(0))
            assertEquals(1L, e.next(u32spec(Compare.EXACT, 1234)))
            e.close()
        }
    }

    /** Runs one first scan + modifications + next scan and returns the surviving addresses (as heap offsets). */
    private fun scenario(
        first: ScanSpec,
        initial: List<Pair<Long, Int>>,
        change: List<Pair<Long, Int>>,
        next: ScanSpec,
        turbo: Int = 0,
    ): Set<Long> {
        World(turbo = turbo).use { w ->
            seed(w, *initial.toTypedArray())
            val e = ScanEngines.auto(w.client)
            e.first(w.pid, w.regions(), first)
            seed(w, *change.toTypedArray())
            e.next(next)
            val r = addrs(e).map { it - w.heapBase }.toSet()
            e.close()
            return r
        }
    }

    private val base = listOf(0x100L to 10, 0x200L to 20, 0x300L to 30, 0x400L to 40, 0x500L to 50, 0x600L to 60)
    private val moved = listOf(0x200L to 25, 0x300L to 30, 0x400L to 35, 0x500L to 50)

    @Test
    fun betweenIsInclusive() {
        val r = scenario(u32spec(Compare.BETWEEN, 20, 50), base, emptyList(), u32spec(Compare.BETWEEN, 20, 50))
        assertEquals(setOf(0x200L, 0x300L, 0x400L, 0x500L), r)
        // bounds given in the opposite order work the same way
        val r2 = scenario(u32spec(Compare.BETWEEN, 50, 20), base, emptyList(), u32spec(Compare.BETWEEN, 50, 20))
        assertEquals(r, r2)
    }

    @Test
    fun biggerAndSmaller() {
        assertEquals(
            setOf(0x400L, 0x500L, 0x600L),
            scenario(u32spec(Compare.BIGGER, 35), base, emptyList(), u32spec(Compare.BIGGER, 35)),
        )
        // zero is smaller than 25 too, so every untouched slot qualifies: check membership only
        val small = scenario(u32spec(Compare.SMALLER, 25), base, emptyList(), u32spec(Compare.SMALLER, 25))
        assertTrue(small.containsAll(listOf(0x100L, 0x200L)))
        assertTrue(listOf(0x300L, 0x400L, 0x500L, 0x600L).none { it in small })
    }

    private fun narrowFromBetween(next: ScanSpec): Set<Long> =
        scenario(u32spec(Compare.BETWEEN, 15, 55), base, moved, next)

    @Test fun increased() = assertEquals(setOf(0x200L), narrowFromBetween(u32spec(Compare.INCREASED)))
    @Test fun decreased() = assertEquals(setOf(0x400L), narrowFromBetween(u32spec(Compare.DECREASED)))
    @Test fun changed() = assertEquals(setOf(0x200L, 0x400L), narrowFromBetween(u32spec(Compare.CHANGED)))
    @Test fun unchanged() = assertEquals(setOf(0x300L, 0x500L), narrowFromBetween(u32spec(Compare.UNCHANGED)))
    @Test fun increasedBy() = assertEquals(setOf(0x200L), narrowFromBetween(u32spec(Compare.INCREASED_BY, 5)))
    @Test fun decreasedBy() = assertEquals(setOf(0x400L), narrowFromBetween(u32spec(Compare.DECREASED_BY, 5)))
    @Test fun increasedByWrongAmount() = assertEquals(emptySet<Long>(), narrowFromBetween(u32spec(Compare.INCREASED_BY, 6)))

    @Test
    fun floatExactUsesTheServersRelativeTolerance() {
        World().use { w ->
            w.f32(w.heapBase + 0x10, 100.0f)
            w.f32(w.heapBase + 0x20, 100.00005f) // 2.5e-7 relative: inside 1e-6
            w.f32(w.heapBase + 0x30, 100.001f)   // 5e-6 relative: outside
            val e = IterativeEngine(w.client)
            e.first(w.pid, w.regions(), ScanSpec(ValueType.F32, Compare.EXACT, ValueCodec.parse(ValueType.F32, "100")))
            assertEquals(setOf(w.heapBase + 0x10, w.heapBase + 0x20), addrs(e))
        }
    }

    @Test
    fun floatFuzzyMeansWithinOne() {
        World().use { w ->
            w.f32(w.heapBase + 0x10, 99.5f)
            w.f32(w.heapBase + 0x20, 101.5f)
            val e = IterativeEngine(w.client)
            e.first(w.pid, w.regions(), ScanSpec(ValueType.F32, Compare.FUZZY, ValueCodec.parse(ValueType.F32, "100")))
            assertEquals(setOf(w.heapBase + 0x10), addrs(e))
        }
    }

    @Test
    fun byteScanWithWildcardsFindsUnalignedMatches() {
        World().use { w ->
            w.bytes(w.heapBase + 0x301, byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0x11, 0xEF.toByte()))
            w.bytes(w.heapBase + 0x800, byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0x22, 0xEE.toByte())) // last byte differs
            val (pat, mask) = ValueCodec.parseAob("DE AD ?? EF")
            val e = IterativeEngine(w.client)
            e.first(w.pid, w.regions(), ScanSpec(ValueType.BYTES, Compare.EXACT, pat, null, mask))
            assertEquals(setOf(w.heapBase + 0x301), addrs(e))
            // and a follow-up scan on the same pattern keeps it
            assertEquals(1L, e.next(ScanSpec(ValueType.BYTES, Compare.EXACT, pat, null, mask)))
        }
    }

    @Test
    fun smallTypesAndNegativeNumbers() {
        World().use { w ->
            w.bytes(w.heapBase + 0x10, byteArrayOf(-5, 7, -5, 0))
            w.bytes(w.heapBase + 0x20, ValueCodec.parse(ValueType.I16, "-300"))
            val e8 = IterativeEngine(w.client)
            assertEquals(2L, e8.first(w.pid, w.regions(), ScanSpec(ValueType.I8, Compare.EXACT, ValueCodec.parse(ValueType.I8, "-5"))))
            val e16 = IterativeEngine(w.client)
            assertEquals(1L, e16.first(w.pid, w.regions(), ScanSpec(ValueType.I16, Compare.EXACT, ValueCodec.parse(ValueType.I16, "-300"))))
            assertEquals(w.heapBase + 0x20, e16.page(0, 5).single().address)
        }
    }

    @Test
    fun unknownInitialValueNeedsTheTurboEngine() {
        World().use { w ->
            try {
                IterativeEngine(w.client).first(w.pid, w.regions(), ScanSpec(ValueType.U32, Compare.UNKNOWN))
                fail("must be rejected")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    // ---------------------------------------------------------------- turbo engine

    @Test
    fun autoChoosesTurboOnlyWithSegments() {
        World(turbo = 0).use { assertEquals("iterative", ScanEngines.auto(it.client).name) }
        World(turbo = 0x0D).use { assertEquals("iterative", ScanEngines.auto(it.client).name) } // no segments bit
        World(turbo = 0x1D).use { assertEquals("turbo", ScanEngines.auto(it.client).name) }
    }

    @Test
    fun turboResidentScanKeepsSurvivorsOnTheConsole() {
        World(turbo = 0x1D).use { w ->
            val a = w.heapBase + 0x100; val b = w.heapBase + 0x2000; val c = w.heapBase + 0x3FF00
            for (x in listOf(a, b, c)) w.u32(x, 1234)
            val e = ScanEngines.auto(w.client)
            assertEquals(3L, e.first(w.pid, w.regions(), u32spec(Compare.EXACT, 1234)))
            assertEquals(setOf(a, b, c), addrs(e))
            w.u32(b, 99)
            assertEquals(2L, e.next(u32spec(Compare.EXACT, 1234)))
            assertEquals(setOf(a, c), addrs(e))
            w.u32(a, 1300)
            assertEquals(1L, e.next(u32spec(Compare.UNCHANGED)))
            assertEquals(c, e.page(0, 10).single().address)
            // paging
            assertTrue(e.page(5, 10).isEmpty())
            e.close()
            assertEquals(1, w.server.opCount(Op.TURBO_END))
        }
    }

    @Test
    fun turboSnapshotScansWithoutKnowingTheValue() {
        World(turbo = 0x1D).use { w ->
            seed(w, 0x100L to 5, 0x200L to 6, 0x300L to 7, 0x400L to 8)
            val e = ScanEngines.auto(w.client)
            val steps = ArrayList<Long>()
            assertEquals(4L, e.first(w.pid, w.regions(), u32spec(Compare.UNKNOWN), ScanProgress { d, _, _ -> steps.add(d) }))
            assertTrue(steps.isNotEmpty())
            seed(w, 0x200L to 9, 0x400L to 1)
            assertEquals(2L, e.next(u32spec(Compare.CHANGED)))
            assertEquals(setOf(0x200L, 0x400L), addrs(e).map { it - w.heapBase }.toSet())
            w.u32(w.heapBase + 0x200, 12) // 9 -> 12 since the last scan
            assertEquals(1L, e.next(u32spec(Compare.INCREASED)))
            val hit = e.page(0, 5).single()
            assertEquals(w.heapBase + 0x200, hit.address)
            assertEquals(12, hit.value.leI32(0))
            // the console reports the value its last scan matched, not a prior-scan value: no "previous" column
            assertNull(hit.previous)
            e.close()
        }
    }

    @Test
    fun turboDeclinesWhenTooManyResults() {
        World(turbo = 0x1D, residentCap = 1).use { w ->
            seed(w, 0x100L to 7, 0x200L to 7, 0x300L to 7)
            val e = ScanEngines.auto(w.client)
            try {
                e.first(w.pid, w.regions(), u32spec(Compare.EXACT, 7))
                fail("expected TooManyResultsException")
            } catch (_: TooManyResultsException) {
            }
            // the client stays usable afterwards
            w.client.ping()
        }
    }

    @Test
    fun turboRegionsAreListed() {
        World(turbo = 0x1D).use { w ->
            val regions = w.client.turboRegions(w.pid)
            assertEquals(3, regions.size)
            assertTrue(ScanRegions.slowRegions(regions).isEmpty())
            val selected = ScanRegions.select(w.client.maps(w.pid), skip = regions.filter { it.start == 0x1000000L })
            assertEquals(1, selected.size)
            assertEquals(w.heapBase, selected[0].start)
        }
    }

    @Test
    fun regionSelectionDefaultsToWritableDataAndMergesNeighbours() {
        val maps = listOf(
            MapEntry("eboot.bin", 0x1000, 0x2000, 0, 5),
            MapEntry("", 0x10000, 0x11000, 0, 3),
            MapEntry("", 0x11000, 0x12000, 0, 3),
            MapEntry("", 0x20000, 0x21000, 0, 1),
        )
        val r = ScanRegions.select(maps)
        assertEquals(1, r.size)
        assertEquals(0x10000L, r[0].start)
        assertEquals(0x2000L, r[0].length)
        assertEquals(3, ScanRegions.select(maps, includeExecutable = true, includeReadOnly = true).size)
    }

    @Test
    fun valueCodecRoundTrips() {
        assertArrayEquals(le32(1234), ValueCodec.parse(ValueType.U32, "1234"))
        assertArrayEquals(le32(-1), ValueCodec.parse(ValueType.I32, "-1"))
        assertEquals("100.0", ValueCodec.format(ValueType.F32, ValueCodec.parse(ValueType.F32, "100")))
        assertEquals("255", ValueCodec.format(ValueType.U8, ValueCodec.parse(ValueType.U8, "0xFF")))
        try { ValueCodec.parse(ValueType.U8, "256"); fail() } catch (_: NumberFormatException) {}
        val (p, m) = ValueCodec.parseAob("48 8B ?? 05")
        assertArrayEquals(byteArrayOf(0x48, 0x8B.toByte(), 0, 5), p)
        assertArrayEquals(byteArrayOf(1, 1, 0, 1), m)
    }
}
