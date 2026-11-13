package dev.ps5nitro.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.ps5nitro.app.SavedCheat
import dev.ps5nitro.app.Session
import dev.ps5nitro.core.AddressText
import dev.ps5nitro.core.CheatFiles
import dev.ps5nitro.core.CheatFormat
import dev.ps5nitro.core.hexToBytes
import dev.ps5nitro.core.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** "Cheats" tab: the cheat list (switch them on in the running game) and export to etaHEN files. */
@Composable
fun CheatScreen() {
    val s = Session
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var format by remember { mutableStateOf(CheatFormat.SHN) }
    var showAdd by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<String?>(null) }
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }

    // the form describes the game that is currently selected; only blank fields are filled
    LaunchedEffect(s.pid, s.foreground) { s.fillExportFromForeground() }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val data = pendingBytes
        pendingBytes = null
        if (uri != null && data != null) {
            scope.launchSafe {
                withContext(Dispatchers.IO) {
                    val out = ctx.contentResolver.openOutputStream(uri)
                        ?: throw IOException("Cannot open the file for writing.")
                    out.use { it.write(data) }
                }
                s.info("File saved (${data.size} bytes).")
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Note(
                "A cheat is a memory patch stored as module + offset (e.g. eboot.bin+0x1A2B3C), so it keeps " +
                    "working after the game is restarted. The switch writes the patched bytes into the running " +
                    "game or restores the original ones. Heap and stack addresses (a scanner result, say) " +
                    "cannot go into a file - use the Trace tab to find the instruction in the game code.",
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showAdd = true }) { Text("Add cheat by hand") }
            }
        }
        if (s.cheats.isEmpty()) {
            item { Note("No cheats yet. Add them from the Watch tab (\"Make a cheat with a value\"), the Trace tab (\"NOP…\") or by hand.") }
        }
        items(s.cheats, key = { it.id }) { c -> CheatCard(c) }

        item { SectionTitle("Export to etaHEN") }
        item {
            Note(
                "The file goes to /data/etaHEN/cheats/shn (or /json) as TITLEID_VERSION_nitro.shn, so it " +
                    "does not overwrite community packs - etaHEN merges the files of the same game and version. " +
                    "Ticked cheats are exported; one file covers one module.",
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Game name", s.exportGame, { s.exportGame = it })
                Field("Title ID (e.g. PPSA01342)", s.exportTitle, { s.exportTitle = it.trim().uppercase() })
                Field("Game version (e.g. 01.000.000)", s.exportVersion, { s.exportVersion = it.trim() })
                OutlinedButton(onClick = { s.refreshForeground() }, enabled = s.client != null) {
                    Text("Fill in from the running game")
                }
                ChoiceRow(CheatFormat.values().toList(), format, { it.name }) { format = it }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { s.pushToConsole(format) },
                    enabled = s.client != null && s.cheats.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Send to console (FTP)") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            try {
                                val set = s.buildSet()
                                preview = String(CheatFiles.encode(set, format), Charsets.UTF_8)
                            } catch (e: Exception) {
                                s.fail(e)
                            }
                        },
                        enabled = s.cheats.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Preview") }
                    OutlinedButton(
                        onClick = {
                            try {
                                val set = s.buildSet()
                                val name = try {
                                    CheatFiles.fileName(set.titleId, set.appVersion, format.ext)
                                } catch (e: IllegalArgumentException) {
                                    "cheats.${format.ext}"
                                }
                                pendingBytes = CheatFiles.encode(set, format)
                                saver.launch(name)
                            } catch (e: Exception) {
                                s.fail(e)
                            }
                        },
                        enabled = s.cheats.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Save file…") }
                }
            }
        }
        item {
            Note(
                "After sending, restart the game or reload the cheats in the etaHEN menu. The etaHEN FTP port " +
                    "(1337 by default) can be changed in the Connect tab.",
            )
        }
    }

    if (showAdd) AddCheatDialog { showAdd = false }

    val text = preview
    if (text != null) {
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("File preview") },
            text = {
                Box(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    Mono(text, size = 11)
                }
            },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun CheatCard(c: SavedCheat) {
    val s = Session
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var describe by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = c.selected, onCheckedChange = { c.selected = it })
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleSmall)
                    if (c.description.isNotBlank()) Text(c.description, style = MaterialTheme.typography.bodySmall)
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
                            text = { Text("Edit description") },
                            onClick = {
                                menu = false
                                describe = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                menu = false
                                delete = true
                            },
                        )
                    }
                }
            }
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (p in c.patches) {
                    Mono(
                        "${p.module}+0x${java.lang.Long.toHexString(p.offset).uppercase()}  ${p.on.toHex(" ")}" +
                            "  (original ${p.off.toHex(" ")})",
                        size = 11,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
                Text(
                    if (c.isOn) "on in the game" else "off",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                Switch(checked = c.isOn, onCheckedChange = { s.toggleCheat(c, it) }, enabled = s.pid != 0)
            }
        }
    }

    if (rename) {
        InputDialog(
            title = "Cheat name",
            label = "Name",
            initial = c.name,
            confirmLabel = "OK",
            onConfirm = { v ->
                rename = false
                if (v.isNotBlank()) {
                    c.name = v.trim()
                    s.saveCheats()
                }
            },
            onDismiss = { rename = false },
        )
    }
    if (describe) {
        InputDialog(
            title = "Cheat description",
            label = "Description",
            initial = c.description,
            confirmLabel = "OK",
            onConfirm = { v ->
                describe = false
                c.description = v.trim()
                s.saveCheats()
            },
            onDismiss = { describe = false },
        )
    }
    if (delete) {
        ConfirmDialog(
            title = "Delete cheat?",
            text = if (c.isOn) "\"${c.name}\" is switched on - the original bytes are restored in the game first." else c.name,
            confirmLabel = "Delete",
            onConfirm = {
                delete = false
                if (c.isOn && s.pid != 0) s.toggleCheat(c, false)
                s.removeCheat(c)
            },
            onDismiss = { delete = false },
        )
    }
}

/** One hand-typed patch: an address in a game module, the bytes to write and the original bytes. */
@Composable
private fun AddCheatDialog(onDismiss: () -> Unit) {
    val s = Session
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var addr by remember { mutableStateOf("") }
    var on by remember { mutableStateOf("") }
    var off by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New cheat") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Field("Name", name, { name = it })
                Field("Description (optional)", desc, { desc = it })
                Field("Address (hex or module+offset)", addr, { addr = it })
                Field("Bytes when on (hex)", on, { on = it })
                Field("Original bytes (empty = read from the game)", off, { off = it })
                if (err.isNotEmpty()) {
                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    err = ""
                    scope.launchSafe {
                        try {
                            val a = AddressText.parse(addr, s.modules)
                            val onBytes = on.hexToBytes()
                            if (onBytes.isEmpty()) throw IllegalArgumentException("Enter the bytes to write (hex).")
                            val offBytes = if (off.isBlank()) {
                                withContext(Dispatchers.IO) { s.readMemory(a, onBytes.size) }
                            } else {
                                off.hexToBytes()
                            }
                            if (offBytes.size != onBytes.size) {
                                throw IllegalArgumentException("The \"on\" and original bytes must have the same length.")
                            }
                            val patch = s.makePatch(a, onBytes, offBytes)
                            s.addCheat(name.trim().ifEmpty { "Cheat ${s.describe(a)}" }, desc.trim(), listOf(patch))
                            onDismiss()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            err = s.explain(e)
                        }
                    }
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
