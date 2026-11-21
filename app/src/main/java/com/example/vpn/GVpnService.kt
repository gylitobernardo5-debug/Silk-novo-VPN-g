package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.VpnRepository
import com.example.model.AppSettings
import com.example.model.AppsFilterMode
import com.example.model.ProfileSecrets
import com.example.model.VpnConnectionState
import com.example.model.VpnProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GVpnService : VpnService() {

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var vpnJob: Job? = null
  private var statsJob: Job? = null
  private var tunInterface: ParcelFileDescriptor? = null
  private var packetRouter: TunPacketRouter? = null
  private var activeEngine: TunnelEngine? = null
  @Volatile private var userRequestedDisconnect = false

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_DISCONNECT -> {
        userRequestedDisconnect = true
        disconnectAndStop()
        return START_NOT_STICKY
      }
      else -> {
        val profileId = intent?.getStringExtra(EXTRA_PROFILE_ID)
        userRequestedDisconnect = false
        startForegroundCompat(buildNotification(getString(R.string.notif_connecting_title), ""))
        vpnJob?.cancel()
        vpnJob = serviceScope.launch { runTunnelLifecycle(profileId) }
        return if (VpnStateHolder.stats.value.state == VpnConnectionState.DISCONNECTED) {
          START_NOT_STICKY
        } else {
          START_STICKY
        }
      }
    }
  }

  private suspend fun runTunnelLifecycle(explicitProfileId: String?) {
    val repo = VpnRepository(applicationContext)
    repo.ensureSeededBlocklists()

    val settings = repo.settingsFlow.first()
    val rules = repo.adBlockRulesFlow.first()
    VpnStateHolder.dnsAdBlocker.updateRules(rules, settings)

    val targetId = explicitProfileId ?: repo.activeProfileIdFlow.first()
    if (targetId.isNullOrBlank()) {
      failWithError(getString(R.string.home_no_profile_selected))
      return
    }
    val pair = repo.getProfileWithSecrets(targetId)
    if (pair == null) {
      failWithError(getString(R.string.home_no_profile_selected))
      return
    }

    val (profile, secrets) = pair
    var attempt = 0
    while (!userRequestedDisconnect && serviceScope.isActive) {
      val outcome = establishSingleSession(profile, secrets, settings)
      if (outcome.isSuccess) {
        // Session ended cleanly or dropped; check autoReconnect
        if (userRequestedDisconnect || !settings.autoReconnect) break
      } else {
        val errMsg = outcome.exceptionOrNull()?.message ?: getString(R.string.state_error)
        if (!settings.autoReconnect || attempt >= 2 || userRequestedDisconnect) {
          failWithError(errMsg)
          return
        }
      }
      attempt++
      VpnStateHolder.update {
        it.copy(
          state = VpnConnectionState.CONNECTING,
          errorMessage = null,
        )
      }
      delay(1500L)
    }

    if (userRequestedDisconnect) {
      disconnectAndStop()
    }
  }

  private suspend fun establishSingleSession(
    profile: VpnProfile,
    secrets: ProfileSecrets,
    settings: AppSettings,
  ): Result<Unit> {
    VpnStateHolder.update {
      it.copy(
        state = VpnConnectionState.CONNECTING,
        errorMessage = null,
        activeProfileId = profile.id,
        activeProfileName = profile.displayTitle(),
        activeServerAddress = profile.endpointText(),
        activeProtocol = profile.protocol,
        tunnelIp = "",
      )
    }

    val protector = VpnSocketProtector { socket -> this@GVpnService.protect(socket) }
    val engine = TunnelEngineFactory.create(profile, secrets)
    activeEngine = engine

    // Perform real protocol/server connection BEFORE marking Connected
    val timeoutMs = (settings.connectTimeoutSeconds.coerceIn(4, 60)) * 1000
    val connectRes = engine.connect(protector, timeoutMs)
    if (connectRes.isFailure) {
      engine.close()
      activeEngine = null
      return Result.failure(connectRes.exceptionOrNull() ?: IllegalStateException("Connection failed"))
    }

    val tun =
      runCatching { buildTunInterface(profile, settings) }
        .getOrElse { err ->
          engine.close()
          activeEngine = null
          return Result.failure(err)
        }
    tunInterface = tun

    val router = TunPacketRouter(tun.fileDescriptor, engine, VpnStateHolder.dnsAdBlocker, timeoutMs)
    packetRouter = router
    router.start()

    val connectedAt = System.currentTimeMillis()
    VpnStateHolder.update {
      it.copy(
        state = VpnConnectionState.CONNECTED,
        errorMessage = null,
        activeProfileId = profile.id,
        activeProfileName = profile.displayTitle(),
        activeServerAddress = profile.endpointText(),
        activeProtocol = profile.protocol,
        tunnelIp = "10.8.0.2",
        connectedSinceMs = connectedAt,
        bytesSent = 0L,
        bytesReceived = 0L,
        uploadSpeedBps = 0L,
        downloadSpeedBps = 0L,
        externalVpnDetected = false,
      )
    }

    updateNotification(
      getString(R.string.notif_connected_title),
      "${profile.displayTitle()} • ${profile.protocol.label}",
    )

    statsJob?.cancel()
    statsJob =
      serviceScope.launch {
        var prevTx = 0L
        var prevRx = 0L
        while (isActive && !userRequestedDisconnect && tunInterface != null) {
          delay(1000L)
          val tx = router.bytesSent.get()
          val rx = router.bytesReceived.get()
          val up = (tx - prevTx).coerceAtLeast(0L)
          val down = (rx - prevRx).coerceAtLeast(0L)
          prevTx = tx
          prevRx = rx
          VpnStateHolder.update {
            it.copy(
              bytesSent = tx,
              bytesReceived = rx,
              uploadSpeedBps = up,
              downloadSpeedBps = down,
              blockedDnsCount = VpnStateHolder.dnsAdBlocker.blockedQueriesCount.get(),
              totalDnsQueries = VpnStateHolder.dnsAdBlocker.totalQueriesCount.get(),
            )
          }
        }
      }

    // Keep coroutine alive while tunnel is open
    while (!userRequestedDisconnect && tunInterface != null && serviceScope.isActive) {
      delay(500L)
    }

    cleanupTunnelResources()
    return Result.success(Unit)
  }

  private fun buildTunInterface(profile: VpnProfile, settings: AppSettings): ParcelFileDescriptor {
    val builder =
      Builder()
        .setSession("G-VPN (${profile.displayTitle()})")
        .setMtu(1500)

    if (settings.ipv4Enabled || !settings.ipv6Enabled) {
      builder.addAddress("10.8.0.2", 32)
      builder.addRoute("0.0.0.0", 0)
    }
    if (settings.ipv6Enabled) {
      builder.addAddress("fd00:8::2", 128)
      builder.addRoute("::", 0)
    }

    val dnsServer = settings.customDns.trim().ifBlank { "1.1.1.1" }
    runCatching { builder.addDnsServer(dnsServer) }
      .onFailure { builder.addDnsServer("1.1.1.1") }

    if (!settings.killSwitch) {
      builder.allowBypass()
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      builder.setMetered(false)
    }

    when (settings.appsFilterMode) {
      AppsFilterMode.ALL -> {
        runCatching { builder.addDisallowedApplication(packageName) }
      }
      AppsFilterMode.BLACKLIST -> {
        runCatching { builder.addDisallowedApplication(packageName) }
        settings.selectedAppPackages.forEach { pkg ->
          if (isPackageInstalled(pkg)) {
            runCatching { builder.addDisallowedApplication(pkg) }
          }
        }
      }
      AppsFilterMode.WHITELIST -> {
        if (settings.selectedAppPackages.isEmpty()) {
          runCatching { builder.addDisallowedApplication(packageName) }
        } else {
          settings.selectedAppPackages.forEach { pkg ->
            if (pkg != packageName && isPackageInstalled(pkg)) {
              runCatching { builder.addAllowedApplication(pkg) }
            }
          }
        }
      }
    }

    return builder.establish() ?: error("Android denied or failed to establish VPN TUN interface")
  }

  private fun isPackageInstalled(pkg: String): Boolean =
    runCatching {
        packageManager.getPackageInfo(pkg, 0)
        true
      }
      .getOrDefault(false)

  private fun failWithError(message: String) {
    cleanupTunnelResources()
    VpnStateHolder.update {
      it.copy(
        state = VpnConnectionState.ERROR,
        errorMessage = message,
        uploadSpeedBps = 0L,
        downloadSpeedBps = 0L,
        connectedSinceMs = 0L,
      )
    }
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun disconnectAndStop() {
    vpnJob?.cancel()
    cleanupTunnelResources()
    VpnStateHolder.update {
      it.copy(
        state = VpnConnectionState.DISCONNECTED,
        errorMessage = null,
        tunnelIp = "",
        connectedSinceMs = 0L,
        uploadSpeedBps = 0L,
        downloadSpeedBps = 0L,
      )
    }
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun cleanupTunnelResources() {
    statsJob?.cancel()
    statsJob = null
    runCatching { packetRouter?.close() }
    packetRouter = null
    runCatching { activeEngine?.close() }
    activeEngine = null
    runCatching { tunInterface?.close() }
    tunInterface = null
  }

  override fun onRevoke() {
    userRequestedDisconnect = true
    disconnectAndStop()
    super.onRevoke()
  }

  override fun onDestroy() {
    cleanupTunnelResources()
    serviceScope.cancel()
    super.onDestroy()
  }

  private fun startForegroundCompat(notification: Notification) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val mgr = getSystemService(NotificationManager::class.java) ?: return
      val ch =
        NotificationChannel(
          CHANNEL_ID,
          getString(R.string.notif_channel_name),
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = getString(R.string.notif_channel_desc)
          setShowBadge(false)
        }
      mgr.createNotificationChannel(ch)
    }
  }

  private fun updateNotification(title: String, text: String) {
    val mgr = getSystemService(NotificationManager::class.java) ?: return
    mgr.notify(NOTIFICATION_ID, buildNotification(title, text))
  }

  private fun buildNotification(title: String, text: String): Notification {
    val openIntent =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    val disconnectIntent =
      PendingIntent.getService(
        this,
        1,
        Intent(this, GVpnService::class.java).setAction(ACTION_DISCONNECT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher_round)
      .setContentTitle(title)
      .setContentText(text.ifBlank { getString(R.string.dev_signature) })
      .setContentIntent(openIntent)
      .setOngoing(true)
      .addAction(0, getString(R.string.notif_disconnect_action), disconnectIntent)
      .build()
  }

  companion object {
    const val ACTION_CONNECT = "com.example.vpn.ACTION_CONNECT"
    const val ACTION_DISCONNECT = "com.example.vpn.ACTION_DISCONNECT"
    const val EXTRA_PROFILE_ID = "extra_profile_id"
    private const val CHANNEL_ID = "gvpn_tunnel_channel"
    private const val NOTIFICATION_ID = 7001

    fun start(context: Context, profileId: String) {
      val intent =
        Intent(context, GVpnService::class.java).apply {
          action = ACTION_CONNECT
          putExtra(EXTRA_PROFILE_ID, profileId)
        }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      val intent = Intent(context, GVpnService::class.java).setAction(ACTION_DISCONNECT)
      context.startService(intent)
    }
  }
}
