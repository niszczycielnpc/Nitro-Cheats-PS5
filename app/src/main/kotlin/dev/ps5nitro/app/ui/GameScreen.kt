package dev.ps5nitro.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ps5nitro.app.Session
import dev.ps5nitro.core.AddressText
import dev.ps5nitro.core.HexDump
import dev.ps5nitro.core.ValueType
import dev.ps5nitro.core.hex64
import dev.ps5nitro.core.hexToBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "Game" tab: process list, memory map and a small hex editor. */
@Composable
fun GameScreen() {
    val s = Session
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            ChoiceRow(listOf(0, 1, 2), s.gameSub, { listOf("Processes", "Memory map", "Hex")[it] }) { s.gameSub = it }
        }
        when (s.gameSub) {
            0 -> ProcessPage()
            1 -> MapsPage()
            else -> HexPage()
        }
    }
}

@Composable
private fun ProcessPage() {
    val s = Session
    var filter by remember { mutableStateOf("") }
    val fg = s.foreground
    val list = s.processes.filter {
        filter.isBlank() || it.name.contains(filter, ignoreCase = true) || it.pid.toString() == filter.trim()
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { s.refreshProcesses() }, enabled = s.client != null) { Text("Refresh list") }
            }
        }
        if (s.pid != 0) {
            item {
                Note("Selected process: ${s.procName} (pid ${s.pid}), ${s.modules.modules.size} modules.")
            }
        }
        if (fg != null && fg.pid > 0) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Game in the foreground", style = MaterialTheme.typography.labelMedium)
                        Text(fg.name, style = MaterialTheme.typography.titleMedium)
                        Mono("${fg.titleId}  version ${fg.appVersion}  pid ${fg.pid}")
                        Button(
                            onClick = {
                                val name = s.processes.firstOrNull { it.pid == fg.pid }?.name ?: fg.name
                                s.selectProcess(fg.pid, name)
                            },
                        ) { Text("Use this game") }
                    }
                }
            }
        }
        item { Field("Search processes (name or pid)", filter, { filter = it }) }
        items(list) { p ->
            val selected = p.pid == s.pid
            // several children of one lazy item would be drawn on top of each other: wrap them
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable { s.selectProcess(p.pid, p.name) }.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        (if (selected) "▶ " else "") + p.name,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    Mono(p.pid.toString())
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun MapsPage() {
    val s = Session
    var filter by remember { mutableStateOf("") }
    val list = s.maps.filter {
        filter.isBlank() || it.name.contains(filter, ignoreCase = true) || it.protText() == filter.trim()
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Filter: module or protection (rw-)", filter, { filter = it }, Modifier.weight(1f))
                OutlinedButton(onClick = { s.reloadMaps() }, enabled = s.pid != 0) { Text("↻") }
            }
        }
        item {
            Text(
                "${s.modules.modules.size} modules, ${s.maps.size} memory maps. Tap a row to open it in the hex editor.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(list) { m ->
            Column {
                Column(
                    Modifier.fillMaxWidth()
                        .clickable {
                            s.hexAddress = hex64(m.start)
                            s.gameSub = 2
                        }
                        .padding(vertical = 4.dp),
                ) {
                    Mono("${hex64(m.start)} – ${hex64(m.end)}  ${m.protText()}", size = 12)
                    Text(
                        (if (m.name.isEmpty()) "(anonymous)" else m.name) + "  ·  " + sizeText(m.size),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun HexPage() {
    val s = Session
    val scope = rememberCoroutineScope()
    var addr by remember(s.hexAddress) { mutableStateOf(s.hexAddress) }
    var len by remember { mutableStateOf("128") }
    var dump by remember { mutableStateOf("") }
    var dumpBase by remember { mutableStateOf(0L) }
    var toWrite by remember { mutableStateOf("") }
    var confirmWrite by remember { mutableStateOf(false) }
    var addType by remember { mutableStateOf(ValueType.I32) }

    fun read() {
        scope.launchSafe {
            val a = AddressText.parse(addr, s.modules)
            val n = (len.toIntOrNull() ?: 128).coerceIn(1, 4096)
            val data = withContext(Dispatchers.IO) { s.readMemory(a, n) }
            dumpBase = a
            dump = HexDump.format(a, data, 8)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (s.pid == 0) item { Note("Pick a game first (Processes).") }
        item { Field("Address (hex or module+offset, e.g. eboot.bin+1234)", addr, { addr = it }) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Bytes", len, { len = it }, Modifier.weight(1f), numeric = true)
                Button(onClick = { read() }, enabled = s.pid != 0) { Text("Read") }
            }
        }
        if (dump.isNotEmpty()) {
            item { Mono(dump, size = 11) }
        }
        item { SectionTitle("Write bytes") }
        item { Field("Hex bytes, e.g. 90 90 90", toWrite, { toWrite = it }) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { confirmWrite = true }, enabled = s.pid != 0 && toWrite.isNotBlank()) { Text("Write") }
                OutlinedButton(
                    onClick = {
                        val n = (len.toIntOrNull() ?: 1).coerceIn(1, 64)
                        toWrite = HexDump.nops(n).joinToString(" ") { "90" }
                    },
                ) { Text("NOP × length") }
            }
        }
        item { SectionTitle("Add to watch list") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Picker("Type", addType, ValueType.values().filter { it.isFixed }, { it.label }) { addType = it }
                Button(
                    onClick = {
                        try {
                            val a = AddressText.parse(addr, s.modules)
                            if (s.addWatch(a, addType)) s.info("Added to the watch list (Watch tab).")
                        } catch (e: Exception) {
                            s.fail(e)
                        }
                    },
                    enabled = s.pid != 0,
                ) { Text("Add") }
            }
        }
    }

    if (confirmWrite) {
        ConfirmDialog(
            title = "Write to game memory",
            text = "Write bytes \"${toWrite.trim()}\" to $addr? A bad write can crash the game.",
            confirmLabel = "Write",
            onConfirm = {
                confirmWrite = false
                scope.launchSafe {
                    val a = AddressText.parse(addr, s.modules)
                    val bytes = toWrite.hexToBytes()
                    withContext(Dispatchers.IO) { s.writeMemory(a, bytes) }
                    s.info("Wrote ${bytes.size} bytes to ${hex64(a)}.")
                    if (dump.isNotEmpty() && dumpBase == a) read()
                }
            },
            onDismiss = { confirmWrite = false },
        )
    }
}
