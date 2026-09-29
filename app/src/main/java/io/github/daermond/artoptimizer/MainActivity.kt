package io.github.daermond.artoptimizer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OptimizerApp() }
    }
}

@Composable
private fun OptimizerApp(model: OptimizerViewModel = viewModel()) {
    val state by model.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                Text("Android ART Optimizer", style = MaterialTheme.typography.headlineSmall)
                Text("Wireless Debugging: ${state.connection.name.replace('_', ' ').lowercase()}")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Device", "Apps", "Diagnostics", "Advanced").forEachIndexed { index, label ->
                        if (index == tab) Button(onClick = { tab = index }) { Text(label) }
                        else OutlinedButton(onClick = { tab = index }) { Text(label) }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                when (tab) {
                    0 -> DevicePage(state, model)
                    1 -> AppsPage(state, model)
                    2 -> DiagnosticsPage(state, model)
                    else -> ConsolePage(state, model)
                }
            }
        }
    }
}

@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

@Composable
private fun DevicePage(state: UiState, model: OptimizerViewModel) = Page {
    Text("Device and connection", style = MaterialTheme.typography.titleLarge)
    state.profile?.let { Text(it.displayName) }
    Text(state.detail.ifBlank { "Enable Wireless Debugging in Developer Options." })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = model::retry) { Text("Retry") }
        OutlinedButton(onClick = model::pairAgain) { Text("Pair Again") }
    }
    if (state.connection != ConnectionPhase.CONNECTED) {
        Text("1. Enable Developer Options and Wireless Debugging.\n" +
            "2. Choose Pair device with pairing code in Android settings.\n" +
            "3. Enter the six-digit code and select the discovered pairing endpoint.\n" +
            "If the endpoint disappears when switching apps, keep Settings and this app open in split screen while pairing.")
        var code by rememberSaveable { mutableStateOf("") }
        OutlinedTextField(value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) },
            label = { Text("Pairing code") }, modifier = Modifier.fillMaxWidth())
        if (state.pairingEndpoints.isEmpty()) Text("Searching for pairing endpoint: ${state.discoveryStatus}")
        state.pairingEndpoints.forEach { endpoint ->
            Button(onClick = { model.pair(endpoint, code); code = "" }, enabled = code.length == 6) {
                Text("Pair ${endpoint.name} (${endpoint.host}:${endpoint.port})")
            }
        }
    }
}

@Composable
private fun AppsPage(state: UiState, model: OptimizerViewModel) = Page {
    val connected = state.connection == ConnectionPhase.CONNECTED
    val context = LocalContext.current
    Text("Configured applications", style = MaterialTheme.typography.titleLarge)
    var entry by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(value = entry, onValueChange = { entry = it }, modifier = Modifier.fillMaxWidth(),
        label = { Text("Package ID or comma-separated list") }, minLines = 2)
    Button(onClick = { model.addPackages(entry); entry = "" }, enabled = entry.isNotBlank()) { Text("Add packages") }
    if (state.entryFeedback.isNotBlank()) Text(state.entryFeedback)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { model.optimize(state.configured.filter { it in state.installed }) },
            enabled = connected && state.compileAvailable && !state.running &&
                state.configured.any { it in state.installed }) { Text("Optimize All eligible") }
        OutlinedButton(onClick = model::refreshApps, enabled = connected) { Text("Refresh") }
    }
    state.activeEvent?.let { event ->
        Text("${event.completed}/${event.total} completed • ${event.packageId.value} • ${event.phase}")
        if (event.message.isNotBlank()) Text(event.message)
        if (event.phase == OptimizationPhase.COMPILING && state.running) {
            CircularProgressIndicator()
            ElapsedText(event.atMillis, true)
        }
    }
    if (state.configured.isEmpty()) Text("Add a package manually or choose an installed app below.")
    state.configured.forEach { id ->
        val installed = state.versions[id]
        val record = state.records[id]
        val label = remember(id) {
            runCatching {
                val info = context.packageManager.getApplicationInfo(id.value, 0)
                context.packageManager.getApplicationLabel(info).toString()
            }.getOrNull()?.takeUnless { it == id.value }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (label != null) Text(label, style = MaterialTheme.typography.titleMedium)
                Text(id.value, style = MaterialTheme.typography.titleMedium)
                Text(if (id in state.installed) "Installed • ${installed?.versionName ?: "version unknown"}"
                    else "Missing / not installed")
                if (record != null) {
                    Text("Last: ${record.result} • " +
                        DateFormat.getDateTimeInstance().format(Date(record.timestampMillis)))
                    if (record.result == "Success") Text("Validation: ${record.validationLevel}")
                    if (record.lastSuccessfulAtMillis != null && record.lastSuccessfulAtMillis != record.timestampMillis)
                        Text("Last successful optimization: " + DateFormat.getDateTimeInstance()
                            .format(Date(record.lastSuccessfulAtMillis)))
                    if (updatedSinceOptimization(installed?.versionCode, record))
                        Text("Updated since optimization — run it again")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { model.optimize(listOf(id)) },
                        enabled = connected && state.compileAvailable && !state.running && id in state.installed) {
                        Text("Optimize")
                    }
                    OutlinedButton(onClick = { model.removePackage(id) }) { Text("Remove") }
                }
            }
        }
    }
    HorizontalDivider()
    Text("Installed third-party packages", style = MaterialTheme.typography.titleMedium)
    if (!connected) Text("Connect to discover installed applications.")
    var filter by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(value = filter, onValueChange = { filter = it },
        label = { Text("Filter packages") }, modifier = Modifier.fillMaxWidth())
    state.installed.filter { it !in state.configured && it.value.contains(filter, ignoreCase = true) }
        .take(100).forEach { id ->
            OutlinedButton(onClick = { model.addPackages(id.value) }) { Text("Add ${id.value}") }
        }
    if (state.installed.size > 100) Text("Showing up to 100 results. Filter to narrow the list.")
}

@Composable
private fun ElapsedText(startedAt: Long, active: Boolean) {
    var seconds by remember(startedAt) { mutableIntStateOf(0) }
    LaunchedEffect(startedAt, active) {
        while (active) {
            seconds = ((System.currentTimeMillis() - startedAt) / 1_000).coerceAtLeast(0).toInt()
            delay(1_000)
        }
    }
    Text("Elapsed: ${seconds}s")
}

@Composable
private fun DiagnosticsPage(state: UiState, model: OptimizerViewModel) = Page {
    Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
    Text("Connection: ${state.connection}")
    Text("Discovery: ${state.discoveryStatus}")
    Text("Pairing endpoints: ${state.pairingEndpoints.size}; connect endpoints: ${state.connectEndpoints.size}")
    state.profile?.let {
        Text("Device: ${it.displayName}; SDK ${it.sdk}")
        Text(if (it.strongIdentity) "Identity: stable shell identifier available"
            else "Identity: weaker manufacturer/model/SDK fingerprint")
    }
    Text("Shell UID: ${state.shellUid.ifBlank { "unavailable" }}")
    Text("Compile command: ${if (state.compileAvailable) "available" else "unavailable"}")
    Text("ART inspection: ${if (state.artAvailable) "available" else "unavailable"}")
    OutlinedButton(onClick = model::retry) { Text("Retry") }
}

@Composable
private fun ConsolePage(state: UiState, model: OptimizerViewModel) = Page {
    Text("Advanced • ADB Console", style = MaterialTheme.typography.titleLarge)
    if (!state.consoleWarningAccepted) {
        Text("Expert feature: shell commands can change device settings or packages. Run only commands you understand.")
        Button(onClick = model::acceptConsoleWarning) { Text("I understand") }
        return@Page
    }
    var command by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(value = command, onValueChange = { command = it },
        label = { Text("Command after adb shell") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { model.runConsole(command) },
            enabled = !state.consoleRunning && !state.running && state.connection == ConnectionPhase.CONNECTED) { Text("Run") }
        OutlinedButton(onClick = model::cancelConsole, enabled = state.consoleRunning) { Text("Cancel") }
        OutlinedButton(onClick = model::clearConsoleOutput) { Text("Clear output") }
    }
    if (state.consoleRunning) { CircularProgressIndicator(); ElapsedText(state.consoleStartedAt, true) }
    Text(state.consoleResult)
    SelectionContainer { Text(state.consoleOutput.ifEmpty { "No output" }, fontFamily = FontFamily.Monospace) }
    HorizontalDivider()
    Text("Local history (up to 20 commands)")
    OutlinedButton(onClick = model::clearConsoleHistory) { Text("Clear history") }
    state.consoleHistory.forEach { previous ->
        OutlinedButton(onClick = { command = previous }) { Text(previous.take(80), fontFamily = FontFamily.Monospace) }
    }
}
