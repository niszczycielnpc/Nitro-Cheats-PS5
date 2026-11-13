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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import dev.ps5nitro.app.BuildConfig
import dev.ps5nitro.app.Session
import dev.ps5nitro.core.Discovery
import dev.ps5nitro.core.firmwareText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ConnectScreen() {
    val s = Session
    var host by remember { mutableStateOf(s.prefs.host) }
    var port by remember { mutableStateOf(s.prefs.port.toString()) }
    var found by remember { mutableStateOf<List<String>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var author by remember { mutableStateOf(s.prefs.author) }
    var ftpHost by remember { mutableStateOf(s.prefs.ftpHost) }
    var ftp by remember { mutableStateOf(s.prefs.ftpPort.toString()) }
    var irq by remember { mutableStateOf(s.prefs.interruptPort.toString()) }
    var freezeMs by remember { mutableStateOf(s.prefs.freezeMs.toString()) }
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("PS5 Nitro Cheats", style = MaterialTheme.typography.headlineSmall) }
        item {
            Note(
                "ps5debug-NG client for your phone: memory scanner, watch list with value freezing, " +
                    "write tracing and cheat export for etaHEN. Load the ps5debug payload on the PS5 " +
                    "and put the phone on the same Wi-Fi network.",
            )
        }
        item { Field("Console IP address", host, { host = it }) }
        item { Field("ps5debug port", port, { port = it }, numeric = true) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { s.connect(host, port.toIntOrNull() ?: 744) },
                    enabled = !s.connecting,
                ) {
                    Text(if (s.connecting) "Connecting…" else if (s.client == null) "Connect" else "Reconnect")
                }
                OutlinedButton(onClick = { s.disconnect() }, enabled = s.client != null) { Text("Disconnect") }
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    searching = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            try {
                                Discovery.discover(2000).map { it.host }
                            } catch (e: Exception) {
                                emptyList<String>()
                            }
                        }
                        found = r
                        searching = false
                        if (r.isEmpty()) s.info("No console found (UDP 1010). Enter the address by hand.")
                    }
                },
                enabled = !searching,
            ) {
                Text(if (searching) "Searching…" else "Find console on the network")
            }
        }
        if (found.isNotEmpty()) {
            items(found) { ip ->
                OutlinedButton(onClick = { host = ip }) { Text("Found: $ip") }
            }
        }

        if (s.client != null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("✅ Connected to ${s.host}", style = MaterialTheme.typography.titleSmall)
                        Text(s.brandingText, style = MaterialTheme.typography.bodySmall)
                        Text("Firmware ${firmwareText(s.firmware)}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (s.pid != 0) "Game: ${s.procName} (pid ${s.pid})" else "Game: none selected – see the Game tab",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        item { SectionTitle("Settings") }
        item { Field("Author name in cheat files", author, { v -> author = v; s.prefs.author = v }) }
        item {
            Field(
                "etaHEN FTP address (empty = the console address above)", ftpHost,
                { v -> ftpHost = v; s.prefs.ftpHost = v.trim() },
            )
        }
        item {
            Field(
                "etaHEN FTP port (cheat upload)", ftp,
                { v -> ftp = v; v.toIntOrNull()?.let { n -> s.prefs.ftpPort = n } },
                numeric = true,
            )
        }
        item {
            Field(
                "Freezing: pause between writes (ms)", freezeMs,
                { v -> freezeMs = v; v.toIntOrNull()?.let { n -> s.prefs.freezeMs = n.coerceIn(10, 2000) } },
                numeric = true,
            )
        }
        item {
            Field(
                "Interrupt port on this phone (write tracing)", irq,
                { v -> irq = v; v.toIntOrNull()?.let { n -> s.prefs.interruptPort = n } },
                numeric = true,
            )
        }
        item {
            Note(
                "Tracing \"what writes to this address\" needs the console to connect back to the client on port 755, " +
                    "and Android does not let apps listen on ports below 1024. Two ways around that:\n" +
                    "• no root: on a PC in the same network run  python relay.py --console <PS5 IP> --phone <phone IP>  " +
                    "(tools/relay.py), then enter the PC's IP as \"Console IP address\" and the PS5's IP as " +
                    "\"etaHEN FTP address\", and leave the interrupt port at 7755;\n" +
                    "• with root: set the interrupt port to 755.\n" +
                    "Searching for references in code (no debugger involved) always works.",
            )
        }
        item { SectionTitle("About") }
        item { AboutCard() }
    }
}

@Composable
private fun AboutCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("PS5 Nitro Cheats v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
            Text(
                "Phone client for ps5debug-NG: memory scanner, watch list, write tracer and etaHEN cheat export.",
                style = MaterialTheme.typography.bodySmall,
            )
            SectionTitle("Credits")
            CreditLine("Claude (Anthropic)", "AI developer")
            CreditLine("Holendersky", "assisting Claude")
            SectionTitle("Built on")
            CreditLine("ps5debug-NG by Pharaoh2k", "the payload this app talks to (GPL-3.0)")
            CreditLine("ps5debug by Ctn & SiSTRo, ps4debug by jogolden", "where the wire protocol comes from")
            CreditLine("etaHEN", "the cheat loader the exported files are made for")
            CreditLine("PS5_Cheats community repository", "reference for the SHN and JSON cheat formats")
            Text(
                "Use at your own risk and only on your own console. Not affiliated with Sony, etaHEN or the " +
                    "ps5debug-NG authors.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CreditLine(name: String, role: String) {
    Column {
        Text(name, style = MaterialTheme.typography.bodyMedium)
        Text(role, style = MaterialTheme.typography.bodySmall)
    }
}
