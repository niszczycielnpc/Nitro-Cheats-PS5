package dev.ps5nitro.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ps5nitro.core.ValueType
import dev.ps5nitro.core.hexToBytes
import dev.ps5nitro.core.toHex
import org.json.JSONArray
import org.json.JSONObject

/** One row of the watch list ("address list" in Cheat Engine terms). */
class WatchItem(
    val id: Long,
    label: String,
    val address: Long,
    val type: ValueType,
    /** Bytes to read: the type size, or the pattern length for ValueType.BYTES. */
    val length: Int,
) {
    var label by mutableStateOf(label)

    /** Last value read from the console, formatted for display. */
    var live by mutableStateOf("…")
    var liveBytes: ByteArray? = null
    var readFailed by mutableStateOf(false)

    /** While true the freeze loop keeps writing [frozenBytes] to [address]. */
    var frozen by mutableStateOf(false)
    var frozenBytes: ByteArray? = null
}

/** A code/static-data patch relative to the start of a module, so it survives restarts of the game. */
class CheatPatch(val module: String, val offset: Long, val on: ByteArray, val off: ByteArray)

class SavedCheat(val id: Long, name: String, description: String, val patches: List<CheatPatch>) {
    var name by mutableStateOf(name)
    var description by mutableStateOf(description)

    /** Whether we believe the ON bytes are currently written into the running game. */
    var isOn by mutableStateOf(false)

    /** Ticked in the export list. */
    var selected by mutableStateOf(true)
}

/** Persistence of the cheat list (JSON in SharedPreferences). */
object CheatStore {
    fun toJson(list: List<SavedCheat>): String {
        val arr = JSONArray()
        for (c in list) {
            val patches = JSONArray()
            for (p in c.patches) {
                val o = JSONObject()
                o.put("module", p.module)
                o.put("offset", java.lang.Long.toHexString(p.offset))
                o.put("on", p.on.toHex())
                o.put("off", p.off.toHex())
                patches.put(o)
            }
            val co = JSONObject()
            co.put("id", c.id)
            co.put("name", c.name)
            co.put("description", c.description)
            co.put("patches", patches)
            arr.put(co)
        }
        return arr.toString()
    }

    fun fromJson(text: String): List<SavedCheat> {
        val out = ArrayList<SavedCheat>()
        try {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val co = arr.getJSONObject(i)
                val pa = co.getJSONArray("patches")
                val patches = ArrayList<CheatPatch>()
                for (j in 0 until pa.length()) {
                    val po = pa.getJSONObject(j)
                    patches.add(
                        CheatPatch(
                            po.getString("module"),
                            java.lang.Long.parseUnsignedLong(po.getString("offset"), 16),
                            po.getString("on").hexToBytes(),
                            po.getString("off").hexToBytes(),
                        ),
                    )
                }
                out.add(SavedCheat(co.optLong("id", (i + 1).toLong()), co.getString("name"), co.optString("description", ""), patches))
            }
        } catch (e: Exception) {
            // a damaged list is dropped rather than crashing the app on start
        }
        return out
    }
}

/** One line of the scan result list. */
class ScanRow(val address: Long, val type: ValueType, val value: String, val previous: String?)
