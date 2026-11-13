package dev.ps5nitro.app

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import dev.ps5nitro.core.AddressText
import dev.ps5nitro.core.Cheat
import dev.ps5nitro.core.CheatFormat
import dev.ps5nitro.core.CheatPush
import dev.ps5nitro.core.CheatSet
import dev.ps5nitro.core.Compare
import dev.ps5nitro.core.ForegroundApp
import dev.ps5nitro.core.HexDump
import dev.ps5nitro.core.InterruptListener
import dev.ps5nitro.core.MapEntry
import dev.ps5nitro.core.ModuleMap
import dev.ps5nitro.core.PatchLine
import dev.ps5nitro.core.ProcessEntry
import dev.ps5nitro.core.Ps5Client
import dev.ps5nitro.core.Ps5Exception
import dev.ps5nitro.core.Ps5StatusException
import dev.ps5nitro.core.ScanCancelledException
import dev.ps5nitro.core.ScanEngine
import dev.ps5nitro.core.ScanEngines
import dev.ps5nitro.core.ScanProgress
import dev.ps5nitro.core.ScanRegions
import dev.ps5nitro.core.ScanSpec
import dev.ps5nitro.core.TooManyResultsException
import dev.ps5nitro.core.TraceResult
import dev.ps5nitro.core.ValueCodec
import dev.ps5nitro.core.ValueType
import dev.ps5nitro.core.WatchKind
import dev.ps5nitro.core.WriteEntry
import dev.ps5nitro.core.WriterTracer
import dev.ps5nitro.core.XrefHit
import dev.ps5nitro.core.hex64
import dev.ps5nitro.core.hexToBytes
import dev.ps5nitro.core.readMany
import dev.ps5nitro.core.staticAccessors
import dev.ps5nitro.core.toHex
import dev.ps5nitro.core.turboRegions
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Everything the screens show lives here (not in the composables) so that long jobs - a scan, a trace,
 * the freeze loop - keep running while the user switches tabs. All fields are Compose state; the
 * blocking :core calls run on Dispatchers.IO.
 */
object Session {
    private lateinit var app: Application

    lateinit var prefs: Prefs
        private set

    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(application: Application) {
        if (::app.isInitialized) return
        app = application
        prefs = Prefs(application)
        cheats.addAll(CheatStore.fromJson(prefs.cheatsJson))
        nextCheatId = (cheats.maxOfOrNull { it.id } ?: 0L) + 1L
    }

    // ------------------------------------------------------------------------------------------ UI plumbing

    /** Selected bottom tab (0 = Connect ... 5 = Cheats). */
    var tab by mutableStateOf(0)

    /** Sub-page of the "Game" tab: 0 processes, 1 memory map, 2 hex editor. */
    var gameSub by mutableStateOf(0)

    /** Address the hex editor opens with. */
    var hexAddress by mutableStateOf("")

    /** The one-line status/error banner above every screen. */
    var message by mutableStateOf("")

    fun info(text: String) {
        message = text
    }

    fun fail(e: Throwable) {
        message = explain(e)
    }

    fun explain(e: Throwable): String = when (e) {
        is ScanCancelledException -> "Scan cancelled."
        is TooManyResultsException -> "Too many results – narrow the scan (another value or type, or use an unknown-value scan)."
        is Ps5StatusException -> "The console rejected command ${e.op} (status 0x${Integer.toHexString(e.status)})."
        is Ps5Exception -> e.message ?: "ps5debug error."
        is java.net.SocketTimeoutException -> "Timed out waiting for the console."
        is IOException -> "Network error: ${e.message}"
        is IllegalArgumentException -> e.message ?: "Invalid value."
        is IllegalStateException -> e.message ?: "Invalid state."
        else -> "${e.javaClass.simpleName}: ${e.message}"
    }

    // ------------------------------------------------------------------------------------------ connection

    var client by mutableStateOf<Ps5Client?>(null)
        private set
    var connecting by mutableStateOf(false)
    var brandingText by mutableStateOf("")
    var firmware by mutableStateOf(0)
    var host by mutableStateOf("")
    var port by mutableStateOf(744)

    /**
     * Runs [block] on the main connection. After a network error the connection is re-opened so the
     * next attempt works; the original error is still reported (a write is never silently repeated).
     */
    fun <T> call(block: (Ps5Client) -> T): T {
        val c = client ?: throw Ps5Exception("Not connected to the console – go back to the Connect tab.")
        try {
            return block(c)
        } catch (e: Ps5StatusException) {
            throw e
        } catch (e: IOException) {
            if (!c.isConnected) {
                try { c.reconnect() } catch (_: Exception) {}
            }
            throw e
        }
    }

    fun connect(hostText: String, portValue: Int) {
        if (connecting) return
        val h = hostText.trim()
        if (h.isEmpty()) {
            info("Enter the console's IP address.")
            return
        }
        connecting = true
        message = ""
        io.launch {
            try {
                val c = Ps5Client(h, portValue)
                val b = c.connect()
                val old = client
                client = c
                try { old?.close() } catch (_: Exception) {}
                host = h
                port = portValue
                prefs.host = h
                prefs.port = portValue
                brandingText = "${b.text} (capability ${b.capability})"
                firmware = c.firmware
                resetProcessState()
                refreshProcessesInternal(autoSelect = true)
                info("Connected to $h.")
            } catch (e: Throwable) {
                fail(e)
            } finally {
                connecting = false
            }
        }
    }

    fun disconnect() {
        io.launch {
            unfreezeAll()
            resetScanInternal()
            val c = client
            client = null
            try { c?.close() } catch (_: Exception) {}
            resetProcessState()
            brandingText = ""
            info("Disconnected.")
        }
    }

    // ------------------------------------------------------------------------------------------ processes and memory map

    var processes by mutableStateOf<List<ProcessEntry>>(emptyList())
    var foreground by mutableStateOf<ForegroundApp?>(null)
    var pid by mutableStateOf(0)
    var procName by mutableStateOf("")
    var maps by mutableStateOf<List<MapEntry>>(emptyList())
    var modules by mutableStateOf(ModuleMap(emptyList()))

    private fun resetProcessState() {
        processes = emptyList()
        foreground = null
        pid = 0
        procName = ""
        maps = emptyList()
        modules = ModuleMap(emptyList())
        watch.clear()
        syncFreeze()
    }

    fun refreshProcesses() {
        io.launch {
            try {
                refreshProcessesInternal(autoSelect = false)
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    private fun refreshProcessesInternal(autoSelect: Boolean) {
        val list = call { it.processList() }
        val fg = try { call { it.foregroundApp() } } catch (_: Exception) { null }
        processes = list.sortedBy { it.name.lowercase() }
        foreground = fg
        if (autoSelect && fg != null && fg.pid > 0) {
            val name = list.firstOrNull { it.pid == fg.pid }?.name ?: fg.name
            selectInternal(fg.pid, name)
        }
    }

    fun selectProcess(newPid: Int, name: String) {
        io.launch {
            try {
                selectInternal(newPid, name)
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    private fun selectInternal(newPid: Int, name: String) {
        val m = call { it.maps(newPid) }
        resetScanInternal()
        watch.clear()
        syncFreeze()
        if (newPid != pid) {
            // the export form describes the game that was selected before
            exportGame = ""
            exportTitle = ""
            exportVersion = ""
        }
        pid = newPid
        procName = name
        maps = m
        modules = ModuleMap(m)
        fillExportFromForeground()
        info("Selected $name (pid $newPid): ${m.size} memory maps, ${modules.modules.size} modules.")
    }

    fun reloadMaps() {
        if (pid == 0) return
        io.launch {
            try {
                val m = call { it.maps(pid) }
                maps = m
                modules = ModuleMap(m)
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    fun describe(addr: Long): String = AddressText.describe(addr, modules)

    // ------------------------------------------------------------------------------------------ scanner

    var scanType by mutableStateOf(ValueType.I32)
    var scanCompare by mutableStateOf(Compare.EXACT)
    var scanValue by mutableStateOf("")
    var scanValue2 by mutableStateOf("")
    var scanExec by mutableStateOf(false)
    var scanReadOnly by mutableStateOf(false)
    var scanRunning by mutableStateOf(false)
    var scanDone by mutableStateOf(0L)
    var scanTotal by mutableStateOf(0L)
    var scanCount by mutableStateOf(0L)
    var scanHasFirst by mutableStateOf(false)
    var scanEngineName by mutableStateOf("")
    var scanRows by mutableStateOf<List<ScanRow>>(emptyList())
    var scanPage by mutableStateOf(0)

    /** Rows per page of the result list. */
    const val PAGE = 100

    private var scanClient: Ps5Client? = null
    private var engine: ScanEngine? = null

    private fun buildSpec(type: ValueType, cmp: Compare): ScanSpec {
        if (type == ValueType.BYTES) {
            val (pattern, mask) = ValueCodec.parseAob(scanValue)
            return ScanSpec(ValueType.BYTES, Compare.EXACT, pattern, null, mask)
        }
        val v = if (cmp.needsValue) ValueCodec.parse(type, scanValue) else null
        val v2 = if (cmp.isBetween) ValueCodec.parse(type, scanValue2) else null
        return ScanSpec(type, cmp, v, v2)
    }

    fun runScan(first: Boolean) {
        if (scanRunning) return
        if (pid == 0) {
            info("Pick the game process first (Game tab).")
            return
        }
        val type = if (first) scanType else (engine?.valueType ?: scanType)
        val cmp = if (type == ValueType.BYTES) Compare.EXACT else scanCompare
        val spec = try {
            buildSpec(type, cmp)
        } catch (e: Exception) {
            fail(e)
            return
        }
        scanRunning = true
        scanDone = 0
        scanTotal = 0
        message = ""
        val started = System.currentTimeMillis()
        io.launch {
            try {
                val eng: ScanEngine
                if (first) {
                    resetScanInternal()
                    val sc = Ps5Client(host, port)
                    sc.connect()
                    scanClient = sc
                    eng = ScanEngines.auto(sc)
                    engine = eng
                    scanEngineName = eng.name
                    val skip = if (eng.name == "turbo") {
                        try { ScanRegions.slowRegions(sc.turboRegions(pid)) } catch (_: Exception) { emptyList() }
                    } else {
                        emptyList()
                    }
                    val regions = ScanRegions.select(maps, scanExec, scanReadOnly, skip)
                    if (regions.isEmpty()) throw IllegalStateException("No memory to scan (check the process memory map).")
                    val progress = ScanProgress { done, total, _ -> scanDone = done; scanTotal = total }
                    scanCount = eng.first(pid, regions, spec, progress)
                } else {
                    eng = engine ?: throw IllegalStateException("Run a first scan first.")
                    val progress = ScanProgress { done, total, _ -> scanDone = done; scanTotal = total }
                    scanCount = eng.next(spec, progress)
                }
                scanHasFirst = true
                scanPage = 0
                // "unknown initial value" cannot narrow a scan: continue with "changed"
                if (first && scanCompare == Compare.UNKNOWN) scanCompare = Compare.CHANGED
                loadPage()
                info("Results: $scanCount (${System.currentTimeMillis() - started} ms, engine: ${eng.name}).")
            } catch (e: Throwable) {
                fail(e)
                // a failed first scan or a cancelled one leaves no usable session on the console
                if (first || e is ScanCancelledException) resetScanInternal()
            } finally {
                scanRunning = false
            }
        }
    }

    fun cancelScan() {
        engine?.cancel()
    }

    fun resetScan() {
        io.launch { resetScanInternal() }
    }

    private fun resetScanInternal() {
        val e = engine
        val c = scanClient
        engine = null
        scanClient = null
        try { e?.close() } catch (_: Exception) {}
        try { c?.close() } catch (_: Exception) {}
        scanHasFirst = false
        scanCount = 0
        scanRows = emptyList()
        scanPage = 0
        scanEngineName = ""
    }

    private fun loadPage() {
        val e = engine ?: return
        val type = e.valueType ?: scanType
        val hits = e.page(scanPage.toLong() * PAGE, PAGE)
        scanRows = hits.map { h ->
            ScanRow(h.address, type, ValueCodec.format(type, h.value), h.previous?.let { ValueCodec.format(type, it) })
        }
    }

    /** Bytes per value of the running scan (the pattern length for byte scans). */
    fun scanElementSize(): Int = engine?.elementSize ?: scanType.size

    fun addRowsToWatch(rows: List<ScanRow>) {
        var added = 0
        val size = scanElementSize()
        for (r in rows) {
            if (watch.none { it.address == r.address && it.type == r.type && it.length == size }) {
                watch.add(WatchItem(nextWatchId++, describe(r.address), r.address, r.type, size))
                added++
            }
        }
        info(if (added > 0) "Added $added addresses to the watch list (Watch tab)." else "Those addresses are already on the list.")
    }

    fun pageScan(delta: Int) {
        val last = if (scanCount <= 0) 0 else ((scanCount - 1) / PAGE).toInt()
        val target = (scanPage + delta).coerceIn(0, last)
        if (target == scanPage) return
        scanPage = target
        io.launch {
            try {
                loadPage()
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    /** Re-reads the values of the rows on screen (the console only reports values as of the last scan). */
    fun refreshScanRows() {
        val rows = scanRows
        if (rows.isEmpty() || pid == 0) return
        io.launch {
            try {
                val type = rows[0].type
                val size = engine?.elementSize ?: type.size
                val data = call { c ->
                    c.readMany(pid, LongArray(rows.size) { rows[it].address }, IntArray(rows.size) { size })
                }
                scanRows = rows.mapIndexed { i, r -> ScanRow(r.address, r.type, ValueCodec.format(r.type, data[i]), r.previous) }
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    // ------------------------------------------------------------------------------------------ watch list + freeze

    val watch = mutableStateListOf<WatchItem>()
    private var nextWatchId = 1L

    /** Number of values the freeze loop currently holds. */
    var frozenCount by mutableStateOf(0)
    var freezeStatus by mutableStateOf("")

    fun addWatch(address: Long, type: ValueType, length: Int = type.size, label: String = ""): Boolean {
        if (type == ValueType.BYTES && length <= 0) {
            info("Enter the length in bytes.")
            return false
        }
        if (watch.any { it.address == address && it.type == type && it.length == length }) {
            info("That address is already on the list.")
            return false
        }
        val shown = label.ifBlank { describe(address) }
        watch.add(WatchItem(nextWatchId++, shown, address, type, length))
        info("Added to the list: $shown")
        return true
    }

    fun removeWatch(item: WatchItem) {
        item.frozen = false
        watch.remove(item)
        syncFreeze()
    }

    /** One refresh round of the watch list (called every few hundred ms while the tab is visible). */
    fun refreshWatchBlocking() {
        val items = watch.toList()
        if (items.isEmpty() || pid == 0 || client == null) return
        val addrs = LongArray(items.size) { items[it].address }
        val sizes = IntArray(items.size) { items[it].length }
        try {
            val data = call { it.readMany(pid, addrs, sizes) }
            for (i in items.indices) {
                val item = items[i]
                item.liveBytes = data[i]
                item.live = ValueCodec.format(item.type, data[i])
                item.readFailed = false
            }
        } catch (e: Exception) {
            for (item in items) item.readFailed = true
            throw e
        }
    }

    fun writeWatch(item: WatchItem, text: String) {
        io.launch {
            try {
                val bytes = if (item.type == ValueType.BYTES) text.hexToBytes() else ValueCodec.parse(item.type, text)
                if (bytes.size != item.length) throw IllegalArgumentException("The value must be exactly ${item.length} bytes.")
                call { it.write(pid, item.address, bytes) }
                if (item.frozen) {
                    item.frozenBytes = bytes
                    syncFreeze()
                }
                refreshWatchBlocking()
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    fun setFrozen(item: WatchItem, on: Boolean) {
        if (on) {
            val b = item.liveBytes
            if (b == null) {
                info("Wait for the value to load, or set it by hand before freezing.")
                return
            }
            item.frozenBytes = b.copyOf()
        }
        item.frozen = on
        syncFreeze()
    }

    fun unfreezeAll() {
        for (w in watch) w.frozen = false
        syncFreeze()
    }

    private fun syncFreeze() {
        val list = ArrayList<WriteEntry>()
        for (w in watch) {
            val b = w.frozenBytes
            if (w.frozen && b != null) list.add(WriteEntry(w.address, b))
        }
        frozenCount = list.size
        Freezer.update(list)
        if (!::app.isInitialized) return
        val intent = Intent(app, FreezeService::class.java)
        try {
            if (list.isNotEmpty()) ContextCompat.startForegroundService(app, intent) else app.stopService(intent)
        } catch (e: Exception) {
            freezeStatus = "could not start the service: ${e.message}"
        }
        if (list.isEmpty()) freezeStatus = ""
    }

    // ------------------------------------------------------------------------------------------ memory access helpers

    fun readMemory(addr: Long, len: Int): ByteArray = call { it.read(pid, addr, len) }

    fun writeMemory(addr: Long, data: ByteArray) {
        call { it.write(pid, addr, data) }
    }

    // ------------------------------------------------------------------------------------------ "what writes to this address"

    var traceAddress by mutableStateOf("")
    var traceSize by mutableStateOf(4)
    var traceKind by mutableStateOf(WatchKind.WRITE)
    var traceSeconds by mutableStateOf(20)
    var traceRunning by mutableStateOf(false)
    var traceStops by mutableStateOf(0)
    var traceResult by mutableStateOf<TraceResult?>(null)
    var xrefBusy by mutableStateOf(false)
    var xrefHits by mutableStateOf<List<XrefHit>>(emptyList())

    @Volatile
    private var tracer: WriterTracer? = null

    fun startTrace() {
        if (traceRunning) return
        if (pid == 0) {
            info("Pick the game process first.")
            return
        }
        val addr = try {
            AddressText.parse(traceAddress, modules)
        } catch (e: Exception) {
            fail(e)
            return
        }
        val c = client
        if (c == null) {
            info("Not connected to the console.")
            return
        }
        traceRunning = true
        traceStops = 0
        traceResult = null
        message = ""
        val listenPort = prefs.interruptPort
        val size = traceSize
        val kind = traceKind
        val millis = traceSeconds * 1000L
        io.launch {
            val listener = InterruptListener(listenPort)
            try {
                listener.start()
                val t = WriterTracer(c, listener)
                tracer = t
                val r = t.run(pid, addr, size, kind, millis, 400) { n -> traceStops = n }
                traceResult = r
                info(
                    if (r.hits.isEmpty()) "No hits (${r.note})."
                    else "Found ${r.hits.size} instructions, ${r.stops} stops (${r.note}).",
                )
            } catch (e: Throwable) {
                fail(e)
            } finally {
                tracer = null
                listener.close()
                traceRunning = false
            }
        }
    }

    fun stopTrace() {
        tracer?.cancelled = true
    }

    fun findXrefs() {
        if (xrefBusy) return
        if (pid == 0) {
            info("Pick the game process first.")
            return
        }
        val addr = try {
            AddressText.parse(traceAddress, modules)
        } catch (e: Exception) {
            fail(e)
            return
        }
        xrefBusy = true
        xrefHits = emptyList()
        io.launch {
            try {
                val exec = maps.filter { it.executable && it.readable }
                val hits = call { it.staticAccessors(pid, exec, addr) }
                xrefHits = hits
                info("References to ${hex64(addr)} in code: ${hits.size}.")
            } catch (e: Throwable) {
                fail(e)
            } finally {
                xrefBusy = false
            }
        }
    }

    // ------------------------------------------------------------------------------------------ cheats

    val cheats = mutableStateListOf<SavedCheat>()
    private var nextCheatId = 1L

    var exportGame by mutableStateOf("")
    var exportTitle by mutableStateOf("")
    var exportVersion by mutableStateOf("")

    fun saveCheats() {
        prefs.cheatsJson = CheatStore.toJson(cheats)
    }

    /** Converts a live address to a module-relative patch. Heap/stack addresses cannot be exported. */
    fun makePatch(address: Long, on: ByteArray, off: ByteArray): CheatPatch {
        val m = modules.find(address)
            ?: throw IllegalArgumentException(
                "Address ${hex64(address)} is not inside any game module (heap and stack change on every " +
                    "launch). Only code and static data belong in a cheat file.",
            )
        return CheatPatch(m.name, m.offsetOf(address), on, off)
    }

    fun addCheat(name: String, description: String, patches: List<CheatPatch>) {
        cheats.add(SavedCheat(nextCheatId++, name, description, patches))
        saveCheats()
    }

    fun removeCheat(c: SavedCheat) {
        cheats.remove(c)
        saveCheats()
    }

    /** Adds a cheat that overwrites the instruction at [address] with NOPs (and optionally applies it right away). */
    fun nopCheat(address: Long, original: ByteArray, applyNow: Boolean) {
        io.launch {
            try {
                val patch = makePatch(address, HexDump.nops(original.size), original)
                val name = "NOP ${describe(address)}"
                addCheat(name, "Disables the instruction ${original.toHex(" ")}", listOf(patch))
                if (applyNow) {
                    call { it.write(pid, address, patch.on) }
                    cheats.lastOrNull()?.isOn = true
                }
                info("Added cheat \"$name\" (Cheats tab).")
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    /** Adds a cheat that writes [value] to a static address and restores the current bytes when switched off. */
    fun valueCheat(item: WatchItem, valueText: String) {
        io.launch {
            try {
                val bytes = if (item.type == ValueType.BYTES) valueText.hexToBytes() else ValueCodec.parse(item.type, valueText)
                val before = item.liveBytes ?: call { it.read(pid, item.address, item.length) }
                if (bytes.size != before.size) throw IllegalArgumentException("The value must be exactly ${before.size} bytes.")
                val patch = makePatch(item.address, bytes, before)
                addCheat("${item.label} = $valueText", "Writes the value $valueText", listOf(patch))
                info("Added a cheat (Cheats tab).")
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    private fun patchEntries(c: SavedCheat, on: Boolean): List<WriteEntry> =
        c.patches.map { p ->
            val m = modules.byName(p.module)
                ?: throw Ps5Exception("Module ${p.module} is not loaded in the selected process.")
            WriteEntry(m.base + p.offset, if (on) p.on else p.off)
        }

    fun toggleCheat(c: SavedCheat, on: Boolean) {
        if (pid == 0) {
            info("Pick the game process first.")
            return
        }
        io.launch {
            try {
                val status = call { it.writeMulti(pid, patchEntries(c, on)) }
                if (status.any { it.toInt() != 0 }) throw Ps5Exception("The console rejected some of the writes (address outside memory?).")
                c.isOn = on
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    fun fillExportFromForeground() {
        val fg = foreground
        if (fg != null && fg.pid == pid) {
            if (exportGame.isBlank()) exportGame = fg.name
            if (exportTitle.isBlank()) exportTitle = fg.titleId
            if (exportVersion.isBlank()) exportVersion = fg.appVersion
        }
    }

    fun refreshForeground() {
        io.launch {
            try {
                foreground = call { it.foregroundApp() }
                fillExportFromForeground()
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    /** Builds the cheat file content from the ticked cheats. */
    fun buildSet(): CheatSet {
        val chosen = cheats.filter { it.selected }
        if (chosen.isEmpty()) throw IllegalArgumentException("Select at least one cheat to export.")
        val mods = chosen.flatMap { it.patches }.map { it.module }.distinct()
        if (mods.isEmpty()) throw IllegalArgumentException("The selected cheats have no patches.")
        if (mods.size > 1) {
            throw IllegalArgumentException(
                "A cheat file patches a single module, but the selected cheats touch: ${mods.joinToString()}. Select cheats from one module only.",
            )
        }
        if (exportGame.isBlank()) throw IllegalArgumentException("Enter the game name.")
        val lines = chosen.map { c -> Cheat(c.name, c.description, c.patches.map { PatchLine(it.offset, it.on, it.off) }) }
        return CheatSet(exportGame.trim(), prefs.author.trim().ifEmpty { "PS5 Nitro Cheats" }, exportTitle.trim(), exportVersion.trim(), mods[0], lines)
    }

    fun pushToConsole(format: CheatFormat) {
        val set = try {
            buildSet()
        } catch (e: Exception) {
            fail(e)
            return
        }
        val target = prefs.ftpHost.trim().ifEmpty { host }
        val ftpPort = prefs.ftpPort
        info("Uploading to the console (FTP $target:$ftpPort)…")
        io.launch {
            try {
                val r = CheatPush.push(target, ftpPort, set, format)
                val extra = if (r.replaced) " (replaced the previous version)" else if (r.registered) " (added to the etaHEN list)" else ""
                info("Uploaded: ${r.remotePath}$extra. Restart the game / reload the cheats in etaHEN.")
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

}
