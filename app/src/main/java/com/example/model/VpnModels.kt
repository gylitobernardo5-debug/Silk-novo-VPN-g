package com.example.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class ProtocolType(val label: String, val defaultPort: Int) {
  SSH("SSH Tunnel", 22),
  VLESS("VLESS", 443),
  VMESS("VMess", 443),
  SHADOWSOCKS("Shadowsocks", 8388),
  SOCKS5("SOCKS5", 1080),
}

@Serializable
enum class SshAuthMethod(val label: String) {
  PASSWORD("Password"),
  PUBLIC_KEY("SSH Key"),
  PASSWORD_AND_KEY("Password + SSH Key"),
}

@Serializable
enum class UpstreamProxyType(val label: String) {
  NONE("None"),
  HTTP("HTTP CONNECT"),
  SOCKS5("SOCKS5"),
}

@Serializable
enum class TransportType(val wireValue: String, val label: String) {
  TCP("tcp", "TCP"),
  WS("ws", "WebSocket"),
  HTTP_UPGRADE("httpupgrade", "HTTPUpgrade"),
}

@Serializable
enum class SecurityType(val wireValue: String, val label: String) {
  NONE("none", "None"),
  TLS("tls", "TLS"),
}

@Serializable
data class VpnProfile(
  val id: String = UUID.randomUUID().toString(),
  val name: String = "",
  val protocol: ProtocolType = ProtocolType.VLESS,
  val host: String = "",
  val port: Int = 443,
  val isFavorite: Boolean = false,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
  val notes: String = "",

  // SSH fields (secret password / private key stored in CredentialVault)
  val sshUsername: String = "",
  val sshAuthMethod: SshAuthMethod = SshAuthMethod.PASSWORD,
  val upstreamProxyType: UpstreamProxyType = UpstreamProxyType.NONE,
  val upstreamProxyHost: String = "",
  val upstreamProxyPort: Int = 8080,

  // Shared TLS & Transport fields (SSH-TLS, VLESS, VMess)
  val transport: TransportType = TransportType.TCP,
  val security: SecurityType = SecurityType.NONE,
  val sni: String = "",
  val alpn: String = "",
  val wsPath: String = "/",
  val wsHost: String = "",
  val allowInsecure: Boolean = false,
  val fingerprint: String = "",

  // VLESS / VMess extras
  val flow: String = "",
  val vmessAlterId: Int = 0,
  val vmessCipher: String = "auto",

  // Shadowsocks
  val ssMethod: String = "aes-256-gcm",

  // SOCKS5
  val socksUsername: String = "",
  val socksUdpEnabled: Boolean = true,
) {
  fun displayTitle(): String =
    name.ifBlank {
      if (host.isNotBlank()) "${protocol.label} • $host:$port" else protocol.label
    }

  fun endpointText(): String = if (host.isNotBlank()) "$host:$port" else "—"
}

@Serializable
data class ProfileSecrets(
  val password: String = "",
  val uuid: String = "",
  val sshPrivateKey: String = "",
  val sshPassphrase: String = "",
)

enum class VpnConnectionState {
  DISCONNECTED,
  CONNECTING,
  CONNECTED,
  ERROR,
}

data class VpnRuntimeStats(
  val state: VpnConnectionState = VpnConnectionState.DISCONNECTED,
  val errorMessage: String? = null,
  val activeProfileId: String? = null,
  val activeProfileName: String = "",
  val activeServerAddress: String = "",
  val activeProtocol: ProtocolType? = null,
  val tunnelIp: String = "",
  val connectedSinceMs: Long = 0L,
  val bytesSent: Long = 0L,
  val bytesReceived: Long = 0L,
  val uploadSpeedBps: Long = 0L,
  val downloadSpeedBps: Long = 0L,
  val blockedDnsCount: Long = 0L,
  val totalDnsQueries: Long = 0L,
  val externalVpnDetected: Boolean = false,
)

enum class DiagnosticStepStatus {
  PENDING,
  RUNNING,
  PASSED,
  FAILED,
  WARNING,
}

data class DiagnosticStep(
  val id: String,
  val title: String,
  val status: DiagnosticStepStatus = DiagnosticStepStatus.PENDING,
  val detail: String = "",
  val latencyMs: Long? = null,
)

data class DiagnosticReport(
  val isRunning: Boolean = false,
  val steps: List<DiagnosticStep> = emptyList(),
  val measuredLatencyMs: Long? = null,
  val summary: String = "",
  val completedAtMs: Long? = null,
)

@Serializable
enum class AdBlockCategory {
  ADS,
  TRACKERS,
  MALWARE,
  CUSTOM_BLOCK,
  ALLOWLIST,
}

@Serializable
data class AdBlockRule(
  val id: String = UUID.randomUUID().toString(),
  val domain: String,
  val category: AdBlockCategory,
  val isEnabled: Boolean = true,
  val source: String = "builtin",
)

enum class ThemeMode {
  SYSTEM,
  LIGHT,
  DARK,
}

enum class AccentPreset(val label: String, val primaryHex: Long, val secondaryHex: Long) {
  CYAN_TEAL("Cyan Teal", 0xFF0EA5E9, 0xFF10B981),
  EMERALD("Emerald", 0xFF10B981, 0xFF14B8A6),
  COBALT("Cobalt", 0xFF3B82F6, 0xFF6366F1),
  AMBER("Amber", 0xFFF59E0B, 0xFFEA580C),
  CORAL("Coral", 0xFFF43F5E, 0xFFFB7185),
  GRAPHITE("Graphite", 0xFF64748B, 0xFF475569),
}

enum class AppLanguage(val tag: String) {
  SYSTEM(""),
  PT("pt"),
  EN("en"),
}

enum class AppsFilterMode {
  ALL,
  BLACKLIST,
  WHITELIST,
}

data class InstalledAppInfo(
  val packageName: String,
  val label: String,
  val isSystemApp: Boolean,
)

data class AppSettings(
  val themeMode: ThemeMode = ThemeMode.SYSTEM,
  val accentPreset: AccentPreset = AccentPreset.CYAN_TEAL,
  val language: AppLanguage = AppLanguage.SYSTEM,
  val animationsEnabled: Boolean = true,
  val autoReconnect: Boolean = true,
  val connectTimeoutSeconds: Int = 12,
  val customDns: String = "1.1.1.1",
  val ipv4Enabled: Boolean = true,
  val ipv6Enabled: Boolean = false,
  val killSwitch: Boolean = false,
  val keepAliveInBackground: Boolean = true,
  val startOnBoot: Boolean = false,
  val appsFilterMode: AppsFilterMode = AppsFilterMode.ALL,
  val selectedAppPackages: Set<String> = emptySet(),
  val adBlockEnabled: Boolean = true,
  val blockAds: Boolean = true,
  val blockTrackers: Boolean = true,
  val blockMalware: Boolean = true,
  val adBlockSelectedAppsOnly: Boolean = false,
  val lastBlocklistUpdateMs: Long = 0L,
)
