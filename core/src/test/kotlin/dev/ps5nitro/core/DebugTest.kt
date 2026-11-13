package dev.ps5nitro.core

import java.net.ServerSocket
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DebugTest {
    private val text = 0x400000L

    @Test
    fun writerTracerFindsTheInstructionThatWrites() {
        World().use { w ->
            // 0x400100: nop | 0x400101: mov [rbx+0x10], rax | 0x400105: nop   (the trap reports RIP = 0x400105)
            w.bytes(text + 0x100, byteArrayOf(0x90.toByte(), 0x48, 0x89.toByte(), 0x43, 0x10, 0x90.toByte()))
            val rbx = w.heapBase + 0x1000
            val target = rbx + 0x10
            InterruptListener(0).use { l ->
                l.start()
                w.server.interruptPort = l.boundPort
                val tracer = WriterTracer(w.client, l)
                var result: TraceResult? = null
                var failure: Throwable? = null
                val progress = ArrayList<Int>()
                val t = thread(name = "tracer") {
                    try {
                        result = tracer.run(w.pid, target, 4, WatchKind.WRITE, maxMillis = 8000, maxStops = 2) { progress.add(it) }
                    } catch (e: Throwable) {
                        failure = e
                    }
                }
                assertTrue("watchpoint armed", waitFor { w.server.watchpoint(0)?.enabled == true })
                val wp = w.server.watchpoint(0)!!
                assertEquals(target, wp.address)
                assertEquals(3, wp.length)     // DR7 length code for 4 bytes
                assertEquals(1, wp.breakType)  // write
                assertEquals(w.pid, w.server.attachedPid)

                val regs = mapOf(RegOffsets.RBX to rbx)
                w.server.interrupt(1001, text + 0x105, regs)
                assertTrue("first continue", waitFor { w.server.continueCount >= 1 })
                try {
                    w.server.interrupt(1001, text + 0x105, regs)
                } catch (e: Exception) {
                    // the tracer ended early: report why instead of a bare "socket closed"
                    t.join(2000)
                    throw AssertionError(
                        "second interrupt could not be sent (${e.message}); tracer finished=${!t.isAlive} " +
                            "stops=${result?.stops} note=${result?.note} failure=$failure progress=$progress",
                        e,
                    )
                }
                t.join(8000)
                assertFalse(t.isAlive)
                failure?.let { throw it }
                val r = result!!

                assertEquals(2, r.stops)
                assertEquals(listOf(1, 2), progress)
                val hit = r.hits.single()
                assertEquals(text + 0x105, hit.rip)
                assertEquals(2, hit.count)
                val best = hit.best!!
                assertArrayEquals(byteArrayOf(0x48, 0x89.toByte(), 0x43, 0x10), best.bytes)
                assertEquals(text + 0x101, best.address)
                assertEquals(4, best.length)
                assertTrue(best.entry.writesMemory)
                assertTrue(best.matchesTarget)
                assertEquals(target, best.effectiveAddress)
                assertEquals(0x10L, best.entry.memDisp)
                assertEquals("[rbx+0x10]", best.operandText())
                // the shorter decoding (without REX.W) is offered as an alternative
                assertTrue(hit.candidates.any { it.length == 3 })

                // cleanup: watchpoint disarmed, thread resumed, debugger detached, connection still fine
                val after = w.server.watchpoint(0)
                assertTrue(after == null || !after.enabled)
                assertTrue(w.server.continueCount >= 3)
                assertEquals(0, w.server.attachedPid)
                w.client.ping()
            }
        }
    }

    @Test
    fun tracerGivesUpWhenNothingHappens() {
        World().use { w ->
            InterruptListener(0).use { l ->
                l.start()
                w.server.interruptPort = l.boundPort
                val r = WriterTracer(w.client, l).run(w.pid, w.heapBase + 0x40, 8, WatchKind.READ_WRITE, maxMillis = 400)
                assertTrue(r.hits.isEmpty())
                assertEquals(0, r.stops)
                assertEquals("time limit reached", r.note)
                assertEquals(0, w.server.attachedPid)
            }
        }
    }

    @Test
    fun unalignedOrOddSizedTargetsFallBackToAByteWatch() {
        World().use { w ->
            InterruptListener(0).use { l ->
                l.start()
                w.server.interruptPort = l.boundPort
                val tracer = WriterTracer(w.client, l)
                val t = thread { tracer.run(w.pid, w.heapBase + 0x41, 4, WatchKind.WRITE, maxMillis = 3000) }
                assertTrue(waitFor { w.server.watchpoint(0)?.enabled == true })
                assertEquals(0, w.server.watchpoint(0)!!.length) // 1 byte
                tracer.cancelled = true
                t.join(5000)
                assertFalse(t.isAlive)
            }
        }
    }

    @Test
    fun attachFailsCleanlyWhenTheConsoleCannotCallBack() {
        World().use { w ->
            val closedPort = ServerSocket(0).use { it.localPort }
            w.server.interruptPort = closedPort
            InterruptListener(0).use { l ->
                l.start()
                try {
                    WriterTracer(w.client, l).run(w.pid, w.heapBase + 0x40, 4, WatchKind.WRITE, maxMillis = 500)
                    fail("attach must fail")
                } catch (e: Ps5Exception) {
                    // expected
                }
                assertEquals(0, w.server.attachedPid)
                w.client.ping()
            }
        }
    }

    @Test
    fun staticAccessorsFindRipRelativeReferences() {
        World().use { w ->
            val target = 0x1000100L
            fun ripInsn(at: Long, op: Int, modrm: Int, tgt: Long): ByteArray =
                byteArrayOf(op.toByte(), modrm.toByte()) + le32((tgt - (at + 6)).toInt())
            w.bytes(text + 0x200, ripInsn(text + 0x200, 0x89, 0x0D, target))      // mov [rip+x], ecx  (write)
            w.bytes(text + 0x300, ripInsn(text + 0x300, 0x8B, 0x05, target))      // mov eax, [rip+x]  (read)
            w.bytes(text + 0x400, ripInsn(text + 0x400, 0x89, 0x0D, 0x1000200L))  // a different variable
            val exec = w.client.maps(w.pid).filter { it.executable }
            assertEquals(1, exec.size)

            val hits = w.client.staticAccessors(w.pid, exec, target)
            assertEquals(listOf(text + 0x200, text + 0x300), hits.map { it.address })
            val store = hits[0].entry!!
            val load = hits[1].entry!!
            assertTrue(store.writesMemory && store.isRipRelative)
            assertTrue(load.readsMemory && !load.writesMemory)
            assertEquals(target, store.ripRelTarget)
            assertEquals(6, hits[0].bytes!!.size)
            assertEquals(0x89, hits[0].bytes!![0].toInt() and 0xFF)

            assertTrue(w.client.staticAccessors(w.pid, exec, 0x1000F00L).isEmpty())
        }
    }

    @Test
    fun disassemblyEntriesAreDecoded() {
        World().use { w ->
            w.bytes(text + 0x10, byteArrayOf(0x90.toByte(), 0xE8.toByte(), 0, 0, 0, 0, 0xC3.toByte()))
            val e = w.client.disasm(w.pid, text + 0x10, 7, 10)
            assertEquals(3, e.size)
            assertEquals(text + 0x10, e[0].address)
            assertTrue(e[1].isCall)
            assertEquals(5, e[1].length)
            assertTrue(e[2].isRet)
            assertNotNull(e.lastOrNull())
        }
    }

    @Test
    fun listenerReportsAnOccupiedPort() {
        ServerSocket(0).use { busy ->
            val l = InterruptListener(busy.localPort)
            try {
                l.start()
                fail("bind must fail")
            } catch (e: Ps5Exception) {
                assertTrue(e.message!!.contains(busy.localPort.toString()))
            } finally {
                l.close()
            }
        }
    }

    @Test
    fun interruptPacketIsParsed() {
        val p = ByteArray(InterruptEvent.PACKET_SIZE)
        fun put32(off: Int, v: Int) { for (i in 0..3) p[off + i] = (v shr (8 * i)).toByte() }
        fun put64(off: Int, v: Long) { for (i in 0..7) p[off + i] = (v shr (8 * i)).toByte() }
        put32(0, 4242); put32(4, (5 shl 8) or 0x7F)
        "Worker".toByteArray().copyInto(p, 8)
        put64(0x30 + RegOffsets.RIP, 0x123456789L)
        put64(0x420 + 6 * 8, 0x1)
        val ev = InterruptEvent.parse(p)
        assertEquals(4242, ev.lwpid)
        assertEquals(InterruptEvent.SIGTRAP, ev.signal)
        assertEquals("Worker", ev.threadName)
        assertEquals(0x123456789L, ev.rip)
        assertEquals(1L, ev.dr6)
    }
}
