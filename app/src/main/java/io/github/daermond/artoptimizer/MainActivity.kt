package io.github.daermond.artoptimizer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

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
    val isTv = LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK ==
        Configuration.UI_MODE_TYPE_TELEVISION
    val tabFocus = remember { List(4) { FocusRequester() } }
    LaunchedEffect(isTv, tab) { if (isTv) tabFocus[tab].requestFocus() }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(if (isTv) 32.dp else 16.dp)) {
                Text("Android ART Optimizer", style = MaterialTheme.typography.headlineSmall)
                Text("Wireless Debugging: ${state.connection.name.replace('_', ' ').lowercase()}")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Device", "Apps", "Diagnostics", "Advanced").forEachIndexed { index, label ->
                        val focusModifier = Modifier.focusRequester(tabFocus[index])
                        if (index == tab) RemoteButton(onClick = { tab = index }, modifier = focusModifier) { Text(label) }
                        else RemoteOutlinedButton(onClick = { tab = index }, modifier = focusModifier) { Text(label) }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                when (tab) {
                    0 -> DevicePage(state, model, isTv)
                    1 -> AppsPage(state, model)
                    2 -> DiagnosticsPage(state, model)
                    else -> ConsolePage(state, model)
                }
            }
        }
    }
}

/** Material buttons accept D-pad focus; this ring makes the active target obvious on a TV. */
@Composable
private fun remoteFocusModifier(): Modifier {
    var focused by remember { mutableStateOf(false) }
    return Modifier
        .onFocusChanged { focused = it.isFocused }
        .border(if (focused) 3.dp else 0.dp,
            if (focused) ComposeColor(0xFFFFD54F) else ComposeColor.Transparent,
            RoundedCornerShape(28.dp))
        .defaultMinSize(minHeight = 48.dp)
}

@Composable
private fun RemoteButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = Button(onClick = onClick, modifier = modifier.then(remoteFocusModifier()), enabled = enabled,
    content = content)

@Composable
private fun RemoteOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = OutlinedButton(onClick = onClick, modifier = modifier.then(remoteFocusModifier()),
    enabled = enabled, content = content)

@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

@Composable
private fun DevicePage(state: UiState, model: OptimizerViewModel, isTv: Boolean) = Page {
    Text("Device and connection", style = MaterialTheme.typography.titleLarge)
    state.profile?.let { Text(it.displayName) }
    Text(state.detail.ifBlank { "Enable Wireless Debugging in Developer Options." })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RemoteOutlinedButton(onClick = model::retry) { Text("Retry") }
        RemoteOutlinedButton(onClick = model::pairAgain,
            enabled = state.pairingSession.status !in setOf(PairingStatus.STARTING,
                PairingStatus.READY, PairingStatus.PAIRING)) { Text("Pair Again") }
    }
    if (state.connection != ConnectionPhase.CONNECTED) {
        PairingChoices(state, model, isTv)
    }
}

@Composable
private fun PairingChoices(state: UiState, model: OptimizerViewModel, isTv: Boolean) {
    val context = LocalContext.current
    var notificationDenied by rememberSaveable { mutableStateOf(false) }
    var settingsUnavailable by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationDenied = !granted
        if (granted) model.startPairing(PairingMode.NOTIFICATION)
    }
    val session = state.pairingSession
    val webUrl = session.url?.takeIf { session.mode == PairingMode.WEB &&
        session.status in setOf(PairingStatus.READY, PairingStatus.PAIRING, PairingStatus.FAILED) }
    @Composable fun Actions() {
        Text("Enable Developer Options and Wireless Debugging. Choose a method below first, then open Wireless Debugging → Pair device with pairing code in Android settings. Leave that dialog open while entering its code.")
        RemoteButton(onClick = {
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else model.startPairing(PairingMode.NOTIFICATION)
        }) { Text("Enter code in notification") }
        Text("Keep the Settings pairing dialog open. Pull down the notification shade and reply with its six-digit code.")
        if (notificationDenied) Text("Notification permission is needed for this method. You can use the web page instead.")
        RemoteOutlinedButton(onClick = { model.startPairing(PairingMode.WEB) }) {
            Text("Enter code on another device")
        }
        Text("Scan the QR code with another phone, tablet, or computer on the same local network, including an iPhone.")
        RemoteOutlinedButton(onClick = {
            settingsUnavailable = runCatching {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            }.isFailure
        }) { Text("Open Developer Options") }
        if (settingsUnavailable) Text("Open Developer Options from Android Settings on this device.")
        if (session.status != PairingStatus.IDLE) {
            HorizontalDivider()
            Text("${session.mode?.name?.lowercase()?.replaceFirstChar(Char::uppercase) ?: "Pairing"}: ${session.detail}")
            if (session.status in setOf(PairingStatus.STARTING, PairingStatus.READY,
                    PairingStatus.PAIRING) || (session.status == PairingStatus.FAILED && session.url != null)) {
                RemoteOutlinedButton(onClick = model::stopPairing) { Text("Cancel pairing") }
            }
        }
    }
    @Composable fun WebDetails(url: String) {
        PairingQr(url)
        SelectionContainer { Text(url) }
        Text("This local HTTP page is temporary. Use it only on a trusted home network. The other device needs to reach this device directly.")
    }
    if (isTv) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) { Actions() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (webUrl != null) WebDetails(webUrl)
            }
        }
    } else {
        Actions()
        if (webUrl != null) WebDetails(webUrl)
    }
}

@Composable
private fun PairingQr(url: String) {
    val bitmap = remember(url) {
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 320, 320,
            mapOf(EncodeHintType.MARGIN to 2))
        Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
            val pixels = IntArray(matrix.width * matrix.height) { index ->
                if (matrix[index % matrix.width, index / matrix.width]) Color.BLACK else Color.WHITE
            }
            setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
        }.asImageBitmap()
    }
    Image(bitmap = bitmap, contentDescription = "QR code for the temporary pairing page",
        modifier = Modifier.size(240.dp))
}

@Composable
private fun AppsPage(state: UiState, model: OptimizerViewModel) = Page {
    val connected = state.connection == ConnectionPhase.CONNECTED
    val context = LocalContext.current
    Text("Configured applications", style = MaterialTheme.typography.titleLarge)
    var entry by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(value = entry, onValueChange = { entry = it }, modifier = Modifier.fillMaxWidth(),
        label = { Text("Package ID or comma-separated list") }, minLines = 2)
    RemoteButton(onClick = { model.addPackages(entry); entry = "" }, enabled = entry.isNotBlank()) { Text("Add packages") }
    if (state.entryFeedback.isNotBlank()) Text(state.entryFeedback)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RemoteButton(onClick = { model.optimize(state.configured.filter { it in state.installed }) },
            enabled = connected && state.compileAvailable && !state.running &&
                state.configured.any { it in state.installed }) { Text("Optimize All eligible") }
        RemoteOutlinedButton(onClick = model::refreshApps, enabled = connected) { Text("Refresh") }
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
                    RemoteButton(onClick = { model.optimize(listOf(id)) },
                        enabled = connected && state.compileAvailable && !state.running && id in state.installed) {
                        Text("Optimize")
                    }
                    RemoteOutlinedButton(onClick = { model.removePackage(id) }) { Text("Remove") }
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
            RemoteOutlinedButton(onClick = { model.addPackages(id.value) }) { Text("Add ${id.value}") }
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
    RemoteOutlinedButton(onClick = model::retry) { Text("Retry") }
}

@Composable
private fun ConsolePage(state: UiState, model: OptimizerViewModel) = Page {
    Text("Advanced • ADB Console", style = MaterialTheme.typography.titleLarge)
    if (!state.consoleWarningAccepted) {
        Text("Expert feature: shell commands can change device settings or packages. Run only commands you understand.")
        RemoteButton(onClick = model::acceptConsoleWarning) { Text("I understand") }
        return@Page
    }
    var command by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(value = command, onValueChange = { command = it },
        label = { Text("Command after adb shell") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RemoteButton(onClick = { model.runConsole(command) },
            enabled = !state.consoleRunning && !state.running && state.connection == ConnectionPhase.CONNECTED) { Text("Run") }
        RemoteOutlinedButton(onClick = model::cancelConsole, enabled = state.consoleRunning) { Text("Cancel") }
        RemoteOutlinedButton(onClick = model::clearConsoleOutput) { Text("Clear output") }
    }
    if (state.consoleRunning) { CircularProgressIndicator(); ElapsedText(state.consoleStartedAt, true) }
    Text(state.consoleResult)
    SelectionContainer { Text(state.consoleOutput.ifEmpty { "No output" }, fontFamily = FontFamily.Monospace) }
    HorizontalDivider()
    Text("Local history (up to 20 commands)")
    RemoteOutlinedButton(onClick = model::clearConsoleHistory) { Text("Clear history") }
    state.consoleHistory.forEach { previous ->
        RemoteOutlinedButton(onClick = { command = previous }) { Text(previous.take(80), fontFamily = FontFamily.Monospace) }
    }
}
