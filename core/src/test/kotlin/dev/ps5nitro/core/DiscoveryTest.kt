package dev.ps5nitro.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTest {
    private val magic = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0xFF.toByte(), 0xFF.toByte())

    private fun echoServer(reply: ByteArray?): Pair<DatagramSocket, Thread> {
        val s = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val t = thread(isDaemon = true) {
            val buf = ByteArray(64)
            while (!s.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (_: Exception) { return@thread }
                if (p.length == 4 && p.data.copyOf(4).contentEquals(magic)) {
                    val r = reply ?: continue
                    s.send(DatagramPacket(r, r.size, p.address, p.port))
                }
            }
        }
        return s to t
    }

    @Test
    fun findsAConsoleThatEchoesTheMagic() {
        val (s, _) = echoServer(magic)
        try {
            val found = Discovery.discover(1200, s.localPort, listOf(InetAddress.getByName("127.0.0.1")))
            assertEquals(listOf("127.0.0.1"), found.map { it.host })
        } finally { s.close() }
    }

    @Test
    fun ignoresOtherReplies() {
        val (s, _) = echoServer(byteArrayOf(1, 2, 3, 4))
        try {
            assertTrue(Discovery.discover(800, s.localPort, listOf(InetAddress.getByName("127.0.0.1"))).isEmpty())
        } finally { s.close() }
    }

    @Test
    fun defaultTargetsAlwaysIncludeTheLimitedBroadcast() {
        assertTrue(Discovery.defaultTargets().any { it.hostAddress == "255.255.255.255" })
    }
}
