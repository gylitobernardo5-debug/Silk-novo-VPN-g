package com.example.vpn

import android.content.Context
import com.example.model.AppSettings
import com.example.model.DiagnosticReport
import com.example.model.DiagnosticStep
import com.example.model.DiagnosticStepStatus
import com.example.model.ProfileSecrets
import com.example.model.VpnConnectionState
import com.example.model.VpnProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

object DiagnosticRunner {

  suspend fun runFullDiagnostic(
    context: Context,
    profile: VpnProfile,
    secrets: ProfileSecrets,
    settings: AppSettings,
    onProgress: (DiagnosticReport) -> Unit,
  ): DiagnosticReport = withContext(Dispatchers.IO) {
    val timeoutMs = settings.connectTimeoutSeconds.coerceIn(4, 30) * 1000
    val steps =
      mutableListOf(
        DiagnosticStep("tcp", "Server TCP Reachability (${profile.endpointText()})", DiagnosticStepStatus.RUNNING),
        DiagnosticStep("handshake", "${profile.protocol.label} Handshake & Auth", DiagnosticStepStatus.PENDING),
        DiagnosticStep("dns", "DNS (${settings.customDns}) & Local AdBlock", DiagnosticStepStatus.PENDING),
        DiagnosticStep("tunnel", "TUN Interface & Conflict Check", DiagnosticStepStatus.PENDING),
      )

    fun publish(running: Boolean = true, latency: Long? = null, summary: String = "") {
      onProgress(
        DiagnosticReport(
          isRunning = running,
          steps = steps.toList(),
          measuredLatencyMs = latency,
          summary = summary,
          completedAtMs = if (running) null else System.currentTimeMillis(),
        )
      )
    }

    publish(running = true)

    // 1. Server TCP reachability + real RTT measurement
    var tcpLatency: Long? = null
    val tcpRes = runCatching {
      val t0 = System.currentTimeMillis()
      Socket().use { sock ->
        sock.connect(InetSocketAddress(profile.host, profile.port), timeoutMs)
      }
      System.currentTimeMillis() - t0
    }
    if (tcpRes.isSuccess) {
      tcpLatency = tcpRes.getOrNull()
      steps[0] =
        steps[0].copy(
          status = DiagnosticStepStatus.PASSED,
          detail = "Connected to ${profile.host}:${profile.port} in ${tcpLatency} ms",
          latencyMs = tcpLatency,
        )
    } else {
      val err = tcpRes.exceptionOrNull()?.message ?: "Unreachable"
      steps[0] =
        steps[0].copy(
          status = DiagnosticStepStatus.FAILED,
          detail = "Failed to reach ${profile.host}:${profile.port}: $err",
        )
    }

    // 2. Real protocol handshake & credential check
    steps[1] = steps[1].copy(status = DiagnosticStepStatus.RUNNING)
    publish(running = true, latency = tcpLatency)
    val engine = TunnelEngineFactory.create(profile, secrets)
    val handshake = engine.testHandshake(NoOpSocketProtector, timeoutMs)
    engine.close()
    steps[1] =
      steps[1].copy(
        status = if (handshake.ok) DiagnosticStepStatus.PASSED else DiagnosticStepStatus.FAILED,
        detail = handshake.message,
        latencyMs = handshake.latencyMs,
      )

    // 3. DNS resolution & AdBlock rule engine test
    steps[2] = steps[2].copy(status = DiagnosticStepStatus.RUNNING)
    publish(running = true, latency = tcpLatency)
    val dnsTestRes = runCatching {
      val t0 = System.currentTimeMillis()
      val addr = InetAddress.getByName(profile.host)
      val rtt = System.currentTimeMillis() - t0
      val adTestBlocked =
        if (settings.adBlockEnabled) {
          VpnStateHolder.dnsAdBlocker.shouldBlockDomain("doubleclick.net")
        } else {
          false
        }
      Triple(addr.hostAddress ?: profile.host, rtt, adTestBlocked)
    }
    if (dnsTestRes.isSuccess) {
      val (resolvedIp, rtt, adBlocked) = dnsTestRes.getOrThrow()
      val adStatus =
        if (settings.adBlockEnabled) "AdBlock active (${VpnStateHolder.dnsAdBlocker.activeRuleCount()} rules, sample ad domain blocked=$adBlocked)"
        else "AdBlock disabled"
      steps[2] =
        steps[2].copy(
          status = DiagnosticStepStatus.PASSED,
          detail = "Resolved ${profile.host} -> $resolvedIp (${rtt} ms) • $adStatus",
          latencyMs = rtt,
        )
    } else {
      steps[2] =
        steps[2].copy(
          status = DiagnosticStepStatus.FAILED,
          detail = "DNS resolution failed: ${dnsTestRes.exceptionOrNull()?.message}",
        )
    }

    // 4. Tunnel & external VPN conflict check
    val runtime = VpnStateHolder.stats.value
    val externalVpn =
      runtime.state != VpnConnectionState.CONNECTED && VpnStateHolder.detectExternalVpn(context)
    steps[3] =
      when {
        externalVpn ->
          steps[3].copy(
            status = DiagnosticStepStatus.WARNING,
            detail = "External VPN detected on device; local G-VPN tunnel/AdBlocker cannot run simultaneously.",
          )
        runtime.state == VpnConnectionState.CONNECTED ->
          steps[3].copy(
            status = DiagnosticStepStatus.PASSED,
            detail = "TUN active (${runtime.tunnelIp}) • TX ${runtime.bytesSent} B / RX ${runtime.bytesReceived} B",
          )
        else ->
          steps[3].copy(
            status = DiagnosticStepStatus.PASSED,
            detail = "TUN idle and ready (${profile.protocol.label})",
          )
      }

    val allPassed = steps.none { it.status == DiagnosticStepStatus.FAILED }
    val finalReport =
      DiagnosticReport(
        isRunning = false,
        steps = steps.toList(),
        measuredLatencyMs = handshake.latencyMs.takeIf { handshake.ok } ?: tcpLatency,
        summary =
          if (allPassed) "All checks passed for ${profile.displayTitle()}."
          else "Diagnostics reported one or more connection/auth errors.",
        completedAtMs = System.currentTimeMillis(),
      )
    onProgress(finalReport)
    finalReport
  }
}
