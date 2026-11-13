package dev.ps5nitro.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** A console that answered the ps5debug UDP discovery broadcast. */
class DiscoveredConsole(val host: String)

/**
 * Finds consoles running ps5debug on the local network: the payload echoes the 4-byte magic
 * 0xFFFFAAAA that is sent to UDP port 1010 back to the sender.
 */
object Discovery {
    private val MAGIC = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0xFF.toByte(), 0xFF.toByte())

    /** Limited broadcast plus the directed broadcast address of every IPv4 interface. */
    fun defaultTargets(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        try {
            out.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {
            // no resolver needed for a literal, nothing to do
        }
        try {
            val nics = NetworkInterface.getNetworkInterfaces()
            while (nics != null && nics.hasMoreElements()) {
                val nic = nics.nextElement()
                if (!nic.isUp || nic.isLoopback) continue
                for (ia in nic.interfaceAddresses) {
                    val b = ia.broadcast
                    if (b is Inet4Address) out.add(b)
                }
            }
        } catch (_: Exception) {
            // some Android versions restrict interface enumeration; the limited broadcast still works
        }
        return out.toList()
    }

    fun discover(
        timeoutMs: Int = 2000,
        port: Int = Wire.DISCOVERY_PORT,
        targets: List<InetAddress> = defaultTargets(),
    ): List<DiscoveredConsole> {
        val found = LinkedHashSet<String>()
        val socket = DatagramSocket()
        try {
            socket.broadcast = true
            socket.soTimeout = 200
            val deadline = System.currentTimeMillis() + timeoutMs
            var nextSend = 0L
            val buf = ByteArray(64)
            while (System.currentTimeMillis() < deadline) {
                val now = System.currentTimeMillis()
                if (now >= nextSend) {
                    // UDP can drop the probe, so repeat it a few times during the window
                    for (t in targets) {
                        try { socket.send(DatagramPacket(MAGIC, MAGIC.size, t, port)) } catch (_: Exception) {}
                    }
                    nextSend = now + 600
                }
                val p = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(p)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (p.length == 4 && p.data[0] == MAGIC[0] && p.data[1] == MAGIC[1] &&
                    p.data[2] == MAGIC[2] && p.data[3] == MAGIC[3]
                ) {
                    p.address?.hostAddress?.let { found.add(it) }
                }
            }
        } finally {
            socket.close()
        }
        return found.map { DiscoveredConsole(it) }
    }
}
