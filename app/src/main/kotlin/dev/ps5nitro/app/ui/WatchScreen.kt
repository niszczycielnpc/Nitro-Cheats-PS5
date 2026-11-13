package dev.ps5nitro.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ps5nitro.app.Session
import dev.ps5nitro.app.WatchItem
import dev.ps5nitro.core.AddressText
import dev.ps5nitro.core.ValueType
import dev.ps5nitro.core.hex64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** "Watch" tab: watched addresses with live values, editing and freezing. */
@Composable
fun WatchScreen() {
    val s = Session
    var showAdd by remember { mutableStateOf(false) }

    // live refresh only while this tab is on screen
    LaunchedEffect(Unit) {
        while (isActive) {
            try {
                withContext(Dispatchers.IO) { Session.refreshWatchBlocking() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // the rows show a warning sign; the next round tries again
            }
            delay(600)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showAdd = true }, enabled = s.pid != 0) { Text("Add address") }
                OutlinedButton(onClick = { s.unfreezeAll() }, enabled = s.frozenCount > 0) { Text("Unfreeze all") }
            }
        }
        if (s.frozenCount > 0) {
            item {
                Note(
                    "Frozen values: ${s.frozenCount}. The phone writes them to the game every ${s.prefs.freezeMs} ms" +
                        (if (s.freezeStatus.isNotEmpty()) " (${s.freezeStatus})" else "") +
                        ". Keeps running in the background thanks to the service notification.",
                )
            }
        }
        if (s.watch.isEmpty()) {
            item {
                Note(
                    "The list is empty. Add an address by hand or tap a result in the Scanner tab. " +
                        "Each entry can be edited, frozen, traced (what writes to it) or turned into a cheat.",
                )
            }
        }
        items(s.watch, key = { it.id }) { w -> WatchRow(w) }
    }

    if (showAdd) AddWatchDialog { showAdd = false }
}

@Composable
private fun WatchRow(w: WatchItem) {
    val s = Session
    var edit by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var cheat by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(w.label, style = MaterialTheme.typography.titleSmall)
                    Mono("${hex64(w.address)}  ${w.type.label}", size = 11)
                }
                Box {
                    TextButton(onClick = { menu = true }) { Text("⋮") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                menu = false
                                rename = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Show in hex editor") },
                            onClick = {
                                menu = false
                                s.hexAddress = hex64(w.address)
                                s.gameSub = 2
                                s.tab = 1
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("What writes to this address?") },
                            onClick = {
                                menu = false
                                s.traceAddress = hex64(w.address)
                                s.traceSize = if (w.length in intArrayOf(1, 2, 4, 8)) w.length else 1
                                s.tab = 4
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Make a cheat with a value…") },
                            onClick = {
                                menu = false
                                cheat = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove from list") },
                            onClick = {
                                menu = false
                                s.removeWatch(w)
                            },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Mono(
                    if (w.readFailed) "⚠ read error" else w.live,
                    modifier = Modifier.weight(1f),
                    size = 20,
                )
                OutlinedButton(onClick = { edit = true }) { Text("Change") }
                Text("Freeze", style = MaterialTheme.typography.bodySmall)
                Switch(checked = w.frozen, onCheckedChange = { s.setFrozen(w, it) })
            }
        }
    }

    if (edit) {
        InputDialog(
            title = "New value",
            label = if (w.type == ValueType.BYTES) "Hex bytes" else "Value (${w.type.label})",
            initial = w.live,
            confirmLabel = "Write",
            onConfirm = { v ->
                edit = false
                s.writeWatch(w, v)
            },
            onDismiss = { edit = false },
        )
    }
    if (rename) {
        InputDialog(
            title = "Name",
            label = "Entry name",
            initial = w.label,
            confirmLabel = "OK",
            onConfirm = { v ->
                rename = false
                if (v.isNotBlank()) w.label = v.trim()
            },
            onDismiss = { rename = false },
        )
    }
    if (cheat) {
        InputDialog(
            title = "Cheat with a value",
            label = "Value the cheat should write",
            initial = w.live,
            confirmLabel = "Add",
            onConfirm = { v ->
                cheat = false
                s.valueCheat(w, v)
            },
            onDismiss = { cheat = false },
        )
    }
}

@Composable
private fun AddWatchDialog(onDismiss: () -> Unit) {
    val s = Session
    var addr by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ValueType.I32) }
    var len by remember { mutableStateOf("4") }
    var label by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Address (hex or module+offset)", addr, { addr = it })
                Picker("Type", type, ValueType.values().toList(), { it.label }) { type = it }
                if (type == ValueType.BYTES) Field("Length (bytes)", len, { len = it }, numeric = true)
                Field("Name (optional)", label, { label = it })
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    try {
                        val a = AddressText.parse(addr, s.modules)
                        val n = if (type == ValueType.BYTES) (len.toIntOrNull() ?: 0) else type.size
                        if (s.addWatch(a, type, n, label.trim())) onDismiss()
                    } catch (e: Exception) {
                        err = s.explain(e)
                    }
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
