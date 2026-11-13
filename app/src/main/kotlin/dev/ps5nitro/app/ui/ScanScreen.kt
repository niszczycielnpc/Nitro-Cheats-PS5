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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ps5nitro.app.Session
import dev.ps5nitro.core.Compare
import dev.ps5nitro.core.ValueType
import dev.ps5nitro.core.hex64

private fun compareText(c: Compare): String = when (c) {
    Compare.EXACT -> "exact value"
    Compare.FUZZY -> "approximately"
    Compare.BIGGER -> "bigger than"
    Compare.SMALLER -> "smaller than"
    Compare.BETWEEN -> "between"
    Compare.INCREASED -> "increased"
    Compare.INCREASED_BY -> "increased by"
    Compare.DECREASED -> "decreased"
    Compare.DECREASED_BY -> "decreased by"
    Compare.CHANGED -> "changed"
    Compare.UNCHANGED -> "unchanged"
    Compare.UNKNOWN -> "unknown initial value"
}

@Composable
fun ScanScreen() {
    val s = Session
    val firstCompares = Compare.values().filter { it.canStartScan }
    val nextCompares = Compare.values().filter { it != Compare.UNKNOWN }
    val compares = if (s.scanHasFirst) nextCompares else firstCompares
    val idle = !s.scanRunning

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (s.pid == 0) item { Note("Pick a game in the Game tab first.") }

        item {
            Picker(
                "Type", s.scanType, ValueType.values().toList(), { it.label },
                enabled = !s.scanHasFirst && idle,
            ) { s.scanType = it }
        }
        if (s.scanType != ValueType.BYTES) {
            item {
                Picker("Compare", s.scanCompare, compares, { compareText(it) }, enabled = idle) { s.scanCompare = it }
            }
        }
        item {
            if (s.scanType == ValueType.BYTES) {
                Field(
                    "Byte pattern (?? = any byte), e.g. 48 8B ?? 05",
                    s.scanValue, { s.scanValue = it }, enabled = idle,
                )
            } else if (s.scanCompare.needsValue) {
                if (s.scanCompare.isBetween) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("From", s.scanValue, { s.scanValue = it }, Modifier.weight(1f), enabled = idle)
                        Field("To", s.scanValue2, { s.scanValue2 = it }, Modifier.weight(1f), enabled = idle)
                    }
                } else {
                    Field("Value", s.scanValue, { s.scanValue = it }, enabled = idle)
                }
            } else {
                Text("This comparison needs no value.", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = s.scanExec, onCheckedChange = { s.scanExec = it }, enabled = !s.scanHasFirst && idle)
                    Text("also search code (executable memory)", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = s.scanReadOnly, onCheckedChange = { s.scanReadOnly = it }, enabled = !s.scanHasFirst && idle)
                    Text("also search read-only memory", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { s.runScan(true) }, enabled = idle && s.pid != 0) { Text("First scan") }
                Button(onClick = { s.runScan(false) }, enabled = idle && s.scanHasFirst) { Text("Next scan") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { s.resetScan() }, enabled = idle && s.scanHasFirst) { Text("New scan") }
                if (s.scanRunning) {
                    OutlinedButton(onClick = { s.cancelScan() }) { Text("Cancel scan") }
                }
            }
        }
        if (s.scanRunning) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (s.scanTotal > 0) {
                        LinearProgressIndicator(
                            progress = { (s.scanDone.toFloat() / s.scanTotal.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text("Scanning…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (s.scanHasFirst) {
            item {
                val pages = if (s.scanCount <= 0L) 1 else ((s.scanCount - 1) / Session.PAGE + 1).toInt()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Results: ${s.scanCount}  (engine: ${s.scanEngineName})", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { s.pageScan(-1) }, enabled = s.scanPage > 0) { Text("◀") }
                        Text("${s.scanPage + 1} / $pages")
                        OutlinedButton(onClick = { s.pageScan(1) }, enabled = s.scanPage + 1 < pages) { Text("▶") }
                        OutlinedButton(onClick = { s.refreshScanRows() }, enabled = idle) { Text("Refresh") }
                    }
                    OutlinedButton(onClick = { s.addRowsToWatch(s.scanRows) }, enabled = s.scanRows.isNotEmpty()) {
                        Text("Add visible to watch list")
                    }
                    Text(
                        "Tap a result to add it to the watch list. " +
                            "With many results, change the value in the game and press \"Next scan\".",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(s.scanRows) { r ->
                Column {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { s.addWatch(r.address, r.type, s.scanElementSize()) }
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Mono(s.describe(r.address), size = 12)
                            Mono(hex64(r.address), size = 11)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Mono(r.value, size = 14)
                            if (r.previous != null) Mono("was: ${r.previous}", size = 11)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
