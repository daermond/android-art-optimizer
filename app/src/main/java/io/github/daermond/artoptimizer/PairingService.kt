package io.github.daermond.artoptimizer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.UUID

enum class PairingMode { NOTIFICATION, WEB }
enum class PairingStatus { IDLE, STARTING, READY, PAIRING, FAILED, PAIRED, EXPIRED }

data class PairingSessionState(
    val mode: PairingMode? = null,
    val status: PairingStatus = PairingStatus.IDLE,
    val url: String? = null,
    val detail: String = "",
)

object PairingSessionStore {
    private val mutable = MutableStateFlow(PairingSessionState())
    val state = mutable.asStateFlow()
    internal fun update(value: PairingSessionState) { mutable.value = value }
}

class PairingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var transport: DeviceTransport? = null
    private var webServer: PairingWebServer? = null
    private var expiryJob: Job? = null
    private var pairingJob: Job? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var mode: PairingMode? = null
    @Volatile private var attempts = 0
    @Volatile private var accepting = false
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "Wireless Debugging pairing", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_NOTIFICATION -> start(PairingMode.NOTIFICATION)
            ACTION_START_WEB -> start(PairingMode.WEB)
            ACTION_SUBMIT -> {
                val code = intent.getStringExtra(EXTRA_CODE).orEmpty()
                val id = intent.getStringExtra(EXTRA_SESSION)
                if (id != null && mode == PairingMode.NOTIFICATION) submit(id, code)
                else if (sessionId == null) stopSelf(startId)
            }
            ACTION_STOP -> finish(PairingStatus.IDLE, "Pairing cancelled")
        }
        return START_NOT_STICKY
    }

    private fun start(selected: PairingMode) {
        clearResources()
        val id = UUID.randomUUID().toString()
        sessionId = id
        mode = selected
        attempts = 0
        accepting = false
        PairingSessionStore.update(PairingSessionState(selected, PairingStatus.STARTING, detail = "Starting pairing"))
        try {
            showForegroundNotification(selected, id)
            foregroundStarted = true
            if (selected == PairingMode.NOTIFICATION && !notificationsAllowed()) {
                finish(PairingStatus.FAILED, "Allow notifications to enter the code this way")
                return
            }
            transport = WirelessAdbTransport(applicationContext).also { it.startDiscovery() }
            val url = if (selected == PairingMode.WEB) {
                val address = localLanAddress() ?: throw IllegalStateException("Connect to a local Wi-Fi or Ethernet network")
                PairingWebServer(address, onCode = { code -> submit(id, code) }).also { webServer = it }.url
            } else null
            accepting = true
            PairingSessionStore.update(PairingSessionState(selected, PairingStatus.READY, url,
                if (selected == PairingMode.WEB) "Scan the QR code with another device on the same network"
                else "Open the pairing-code dialog, then reply from the notification shade"))
            expiryJob = scope.launch {
                delay(180_000)
                finish(PairingStatus.EXPIRED, "Pairing timed out. Start again when ready")
            }
        } catch (_: Exception) {
            finish(PairingStatus.FAILED, "Could not start pairing. Check notifications and local network access")
        }
    }

    @Synchronized
    private fun submit(id: String, code: String): Boolean {
        if (sessionId != id || !accepting || pairingJob?.isActive == true ||
            attempts >= 3 || !CODE.matches(code)) return false
        attempts++
        pairingJob = scope.launch { performPair(id, code) }
        return true
    }

    private suspend fun performPair(id: String, code: String) {
        val selected = mode ?: return
        var endpointFound = false
        PairingSessionStore.update(PairingSessionState(selected, PairingStatus.PAIRING,
            webServer?.url, "Pairing with this device"))
        if (sessionId == id) showForegroundNotification(selected, id, "Pairing with this device")
        try {
            val activeTransport = transport ?: error("Pairing stopped")
            val endpoint = withTimeoutOrNull(20_000) {
                activeTransport.discovery.first { state ->
                    state.pairDevices.any(activeTransport::isLocalEndpoint)
                }.pairDevices.first(activeTransport::isLocalEndpoint)
            } ?: error("Pairing endpoint unavailable")
            endpointFound = true
            activeTransport.pair(endpoint, code)
            AppPersistence(applicationContext).paired = true
            if (sessionId == id) finish(PairingStatus.PAIRED, "Paired. Connecting to Wireless Debugging")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (sessionId != id) return
            if (!endpointFound) attempts = (attempts - 1).coerceAtLeast(0)
            if (attempts >= 3) finish(PairingStatus.FAILED, "Pairing failed three times. Start a new session")
            else {
                val message = if (endpointFound)
                    "Pairing failed. Check the code and keep the Settings dialog open"
                else "Could not find the pairing dialog. Keep it open and try again"
                PairingSessionStore.update(PairingSessionState(selected, PairingStatus.FAILED,
                    webServer?.url, message))
                showForegroundNotification(selected, id, message)
            }
        }
    }

    private fun showForegroundNotification(selected: PairingMode, id: String, message: String? = null) {
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("ART Optimizer pairing")
            .setContentText(message ?: if (selected == PairingMode.WEB) "Local pairing page is active"
                else "Tap Enter code while the Settings dialog is open")
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        if (selected == PairingMode.NOTIFICATION) {
            val actionIntent = Intent(this, PairingReplyReceiver::class.java).apply {
                putExtra(EXTRA_SESSION, id)
            }
            val pending = PendingIntent.getBroadcast(this, 1, actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            val reply = RemoteInput.Builder(REMOTE_INPUT_KEY).setLabel("Six-digit pairing code").build()
            builder.addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_edit),
                "Enter code", pending).addRemoteInput(reply).build())
        }
        startForeground(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    private fun notificationsAllowed(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
    }

    private fun localLanAddress(): InetAddress? = runCatching {
        val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        manager.allNetworks.firstNotNullOfOrNull { network ->
            val capabilities = manager.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true &&
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) != true) null
            else manager.getLinkProperties(network)?.linkAddresses?.map { it.address }
                ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        } ?: NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual &&
                listOf("wlan", "eth", "end", "ap").any(it.name::startsWith) }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    }.getOrNull()

    private fun finish(status: PairingStatus, detail: String) {
        val previous = mode
        clearResources()
        PairingSessionStore.update(PairingSessionState(previous, status, detail = detail))
        if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    private fun clearResources() {
        accepting = false
        sessionId = null
        mode = null
        expiryJob?.cancel(); expiryJob = null
        pairingJob?.cancel(); pairingJob = null
        webServer?.close(); webServer = null
        transport?.close(); transport = null
    }

    override fun onDestroy() {
        clearResources()
        scope.cancel()
        val session = PairingSessionStore.state.value
        if (session.status in setOf(PairingStatus.STARTING, PairingStatus.READY,
                PairingStatus.PAIRING) || (session.status == PairingStatus.FAILED && session.url != null)) {
            PairingSessionStore.update(PairingSessionState(status = PairingStatus.FAILED,
                detail = "Pairing stopped. Start again"))
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_NOTIFICATION = "io.github.daermond.artoptimizer.START_NOTIFICATION_PAIRING"
        const val ACTION_START_WEB = "io.github.daermond.artoptimizer.START_WEB_PAIRING"
        const val ACTION_SUBMIT = "io.github.daermond.artoptimizer.SUBMIT_PAIRING_CODE"
        const val ACTION_STOP = "io.github.daermond.artoptimizer.STOP_PAIRING"
        const val EXTRA_CODE = "pairing_code"
        const val EXTRA_SESSION = "pairing_session"
        const val REMOTE_INPUT_KEY = "reply_code"
        private const val CHANNEL = "pairing"
        private const val NOTIFICATION_ID = 101
        private val CODE = Regex("^[0-9]{6}$")

        fun start(context: Context, mode: PairingMode) {
            val action = if (mode == PairingMode.WEB) ACTION_START_WEB else ACTION_START_NOTIFICATION
            context.startForegroundService(Intent(context, PairingService::class.java).setAction(action))
        }

        fun stop(context: Context) {
            val session = PairingSessionStore.state.value
            if (session.status in setOf(PairingStatus.STARTING, PairingStatus.READY,
                    PairingStatus.PAIRING) || (session.status == PairingStatus.FAILED && session.url != null)) {
                context.startService(Intent(context, PairingService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}

class PairingReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PairingService.REMOTE_INPUT_KEY)?.toString() ?: return
        val id = intent.getStringExtra(PairingService.EXTRA_SESSION) ?: return
        runCatching {
            context.startService(Intent(context, PairingService::class.java).apply {
                action = PairingService.ACTION_SUBMIT
                putExtra(PairingService.EXTRA_SESSION, id)
                putExtra(PairingService.EXTRA_CODE, code)
            })
        }
    }
}
