package dev.ps5nitro.app.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ps5nitro.app.Session
import dev.ps5nitro.core.AccessHit
import dev.ps5nitro.core.DisasmEntry
import dev.ps5nitro.core.InstrCandidate
import dev.ps5nitro.core.WatchKind
import dev.ps5nitro.core.XrefHit
import dev.ps5nitro.core.hex64
import dev.ps5nitro.core.toHex

/** "Trace" tab: which instruction writes (or reads) an address - hardware watchpoint or a static search. */
@Composable
fun TraceScreen() {
    val s = Session
    var nop by remember { mutableStateOf<Pair<Long, ByteArray>?>(null) }
    val result = s.traceResult

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Note(
                "Finds the code that writes to a chosen address (a lives or ammo counter, say). " +
                    "Tracing (debugger) uses a hardware watchpoint: the console connects back to the debugger " +
                    "port, so you need the PC helper (tools/relay.py) or root - see the README. " +
                    "Searching in code works without that, but only finds globals and static variables.",
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Address (hex or module+offset)", s.traceAddress, { s.traceAddress = it }, enabled = !s.traceRunning)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Picker(
                        "Size",
                        s.traceSize,
                        listOf(1, 2, 4, 8),
                        { "$it B" },
                        enabled = !s.traceRunning,
                    ) { s.traceSize = it }
                    Picker(
                        "Catch",
                        s.traceKind,
                        WatchKind.values().toList(),
                        { if (it == WatchKind.WRITE) "writes" else "reads and writes" },
                        enabled = !s.traceRunning,
                    ) { s.traceKind = it }
                }
                Field(
                    "Listen time (seconds)",
                    s.traceSeconds.toString(),
                    { v -> s.traceSeconds = (v.toIntOrNull() ?: 0).coerceIn(0, 600) },
                    numeric = true,
                    enabled = !s.traceRunning,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.traceRunning) {
                    Button(onClick = { s.stopTrace() }) { Text("Stop") }
                } else {
                    Button(
                        onClick = { s.startTrace() },
                        enabled = s.pid != 0 && s.traceSeconds > 0,
                    ) { Text("Trace (debugger)") }
                }
                OutlinedButton(
                    onClick = { s.findXrefs() },
                    enabled = s.pid != 0 && !s.xrefBusy && !s.traceRunning,
                ) { Text(if (s.xrefBusy) "Searching…" else "Search in code") }
            }
        }
        if (s.traceRunning) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "Listening… stops: ${s.traceStops}. Change the value in the game now " +
                            "(lose a life, fire a shot) to trigger the write.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (s.pid == 0) {
            item { Note("Pick the game process in the Game tab first.") }
        }

        if (result != null) {
            item {
                SectionTitle("Trace result (${result.hits.size} instructions, ${result.stops} stops)")
            }
            if (result.hits.isEmpty()) {
                item {
                    Note(
                        "Nothing caught (${result.note}). Check that the phone is reachable from the console " +
                            "(port ${s.prefs.interruptPort} + relay), that the value really changed while " +
                            "listening and that the size matches the type. Also try \"reads and writes\".",
                    )
                }
            }
            items(result.hits, key = { it.rip }) { hit ->
                HitCard(hit, onNop = { addr, bytes -> nop = addr to bytes })
            }
        }

        if (s.xrefHits.isNotEmpty() || s.xrefBusy) {
            item { SectionTitle("References in code (${s.xrefHits.size})") }
        }
        items(s.xrefHits) { x ->
            XrefCard(x, onNop = { addr, bytes -> nop = addr to bytes })
        }
    }

    val pending = nop
    if (pending != null) {
        NopDialog(pending.first, pending.second) { nop = null }
    }
}

private fun accessText(e: DisasmEntry): String = when {
    e.writesMemory && e.readsMemory -> "read and write"
    e.writesMemory -> "write"
    e.readsMemory -> "read"
    else -> "access"
}

@Composable
private fun HitCard(hit: AccessHit, onNop: (Long, ByteArray) -> Unit) {
    val s = Session
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("RIP ${s.describe(hit.rip)}  ×${hit.count}", style = MaterialTheme.typography.titleSmall)
            Mono("stopped after the instruction, RIP = ${hex64(hit.rip)}", size = 11)
            if (hit.candidates.isEmpty()) {
                Text(
                    "Could not read an instruction ending at this address.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            for ((i, c) in hit.candidates.take(3).withIndex()) {
                CandidateRow(c, best = i == 0, onNop = onNop)
            }
        }
    }
}

@Composable
private fun CandidateRow(c: InstrCandidate, best: Boolean, onNop: (Long, ByteArray) -> Unit) {
    val s = Session
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            (if (best) "Most likely" else "Another possibility") +
                (if (c.matchesTarget) " (address matches)" else ""),
            style = MaterialTheme.typography.labelMedium,
            color = if (c.matchesTarget) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Mono("${s.describe(c.address)}  ${c.length} B", size = 12)
        Mono(c.bytes.toHex(" "), size = 12)
        Text(
            "${accessText(c.entry)}: ${c.operandText()}" +
                (c.effectiveAddress?.let { " = ${hex64(it)}" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onNop(c.address, c.bytes) },
                enabled = c.bytes.isNotEmpty(),
            ) { Text("NOP…") }
            TextButton(
                onClick = {
                    s.hexAddress = hex64(c.address)
                    s.gameSub = 2
                    s.tab = 1
                },
            ) { Text("Show in hex") }
        }
    }
}

@Composable
private fun XrefCard(x: XrefHit, onNop: (Long, ByteArray) -> Unit) {
    val s = Session
    val e = x.entry
    val bytes = x.bytes
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Mono(s.describe(x.address), size = 13)
            if (e != null && bytes != null) {
                Mono(bytes.toHex(" "), size = 12)
                Text(
                    "${accessText(e)}: ${InstrCandidate(e, bytes, null, false).operandText()}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onNop(x.address, bytes) }) { Text("NOP…") }
                    TextButton(
                        onClick = {
                            s.hexAddress = hex64(x.address)
                            s.gameSub = 2
                            s.tab = 1
                        },
                    ) { Text("Show in hex") }
                }
            } else {
                Text("Could not decode the instruction.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun NopDialog(address: Long, original: ByteArray, onDismiss: () -> Unit) {
    val s = Session
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Disable this instruction?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Mono("${s.describe(address)}\n${original.toHex(" ")}", size = 12)
                Text(
                    "The instruction will be replaced with ${original.size} NOP bytes (0x90), so it stops " +
                        "writing the value. You get a cheat with a switch that restores the original bytes. " +
                        "A wrong instruction can crash the game - use at your own risk.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = {
                    s.nopCheat(address, original, applyNow = true)
                    onDismiss()
                }) { Text("Add cheat and enable") }
                TextButton(onClick = {
                    s.nopCheat(address, original, applyNow = false)
                    onDismiss()
                }) { Text("Add cheat only") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
