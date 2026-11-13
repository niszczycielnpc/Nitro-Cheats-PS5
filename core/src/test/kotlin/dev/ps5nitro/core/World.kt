package dev.ps5nitro.core

import java.io.Closeable

/** A mock console with one running game plus a connected client. */
class World(
    turbo: Int = 0,
    capability: String = "1.1",
    residentCap: Int = Int.MAX_VALUE,
    connect: Boolean = true,
) : Closeable {
    val server = MockPs5Server(capability = capability, turboEngines = turbo, residentCap = residentCap)
    val pid = 77
    val proc = server.addProcess(MockPs5Server.Proc(pid, "eboot.bin"))
    val text = MockPs5Server.Region("eboot.bin", 0x400000L, ByteArray(0x2000), 5)
    val data = MockPs5Server.Region("eboot.bin", 0x1000000L, ByteArray(0x1000), 3)
    val heap = MockPs5Server.Region("", 0x4_0000_0000L, ByteArray(0x40000), 3)
    val client = Ps5Client("127.0.0.1", server.port)
    val heapBase: Long get() = heap.start

    init {
        proc.regions.addAll(listOf(text, data, heap))
        server.foregroundPid = pid
        if (connect) client.connect()
    }

    fun regions(): List<ScanRegion> = ScanRegions.select(client.maps(pid))

    fun u32(addr: Long, v: Int) { check(server.poke(pid, addr, le32(v))) }
    fun f32(addr: Long, v: Float) = u32(addr, java.lang.Float.floatToRawIntBits(v))
    fun bytes(addr: Long, b: ByteArray) { check(server.poke(pid, addr, b)) }
    fun readU32(addr: Long): Int = server.peek(pid, addr, 4).leI32(0)

    override fun close() {
        client.close()
        server.close()
    }
}

fun le32(v: Int): ByteArray = LeWriter(4).u32(v).toByteArray()
fun spec(type: ValueType, cmp: Compare, v: ByteArray? = null, v2: ByteArray? = null) = ScanSpec(type, cmp, v, v2)
fun u32spec(cmp: Compare, v: Int? = null, v2: Int? = null) =
    ScanSpec(ValueType.U32, cmp, v?.let { le32(it) }, v2?.let { le32(it) })

fun waitFor(timeoutMs: Long = 5000, cond: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (cond()) return true
        Thread.sleep(10)
    }
    return cond()
}
