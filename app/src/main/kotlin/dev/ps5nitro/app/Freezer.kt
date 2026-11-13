package dev.ps5nitro.app

import dev.ps5nitro.core.Ps5Client
import dev.ps5nitro.core.WriteEntry

/**
 * "Freeze" = write the same bytes again and again. ps5debug has no such feature, so the phone does it:
 * one background thread with its own connection (so scans on the other connection never stall it)
 * writes every frozen value in a single bulk request every few tens of milliseconds.
 *
 * Writing a value in a loop is what Cheat Engine does as well; patching the instruction that changes
 * the value (see the "Trace" tab and the cheat export) is cleaner when it is possible.
 */
object Freezer {
    @Volatile
    private var entries: List<WriteEntry> = emptyList()

    /** Bumped on every stop so that an old worker thread notices it has been replaced. */
    @Volatile
    private var generation = 0
    private var alive = false

    @Synchronized
    fun update(list: List<WriteEntry>) {
        entries = list
        if (list.isEmpty()) {
            generation++
            alive = false
        } else if (!alive) {
            alive = true
            val g = ++generation
            val t = Thread({ loop(g) }, "ps5-freezer")
            t.isDaemon = true
            t.start()
        }
    }

    private fun sleep(ms: Long): Boolean =
        try {
            Thread.sleep(ms)
            true
        } catch (e: InterruptedException) {
            false
        }

    private fun loop(g: Int) {
        var client: Ps5Client? = null
        var failures = 0
        while (g == generation) {
            val list = entries
            if (list.isEmpty()) break
            try {
                var c = client
                if (c == null) {
                    c = Ps5Client(Session.host, Session.port)
                    c.connect()
                    client = c
                }
                val status = c.writeMulti(Session.pid, list)
                failures = 0
                var bad = 0
                for (b in status) if (b.toInt() != 0) bad++
                Session.freezeStatus = if (bad == 0) "writes OK" else "$bad of ${list.size} writes rejected by the console"
            } catch (e: Exception) {
                Session.freezeStatus = "error: ${e.message}"
                try { client?.close() } catch (_: Exception) {}
                client = null
                failures++
                if (!sleep(minOf(3000L, 300L * failures))) break
                continue
            }
            if (!sleep(Session.prefs.freezeMs.toLong())) break
        }
        try { client?.close() } catch (_: Exception) {}
    }
}
