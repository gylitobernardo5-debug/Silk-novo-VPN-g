package com.example.parser

import android.util.Base64
import com.example.model.ProfileSecrets
import com.example.model.ProtocolType
import com.example.model.SecurityType
import com.example.model.SshAuthMethod
import com.example.model.TransportType
import com.example.model.VpnProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

data class ParsedProfileResult(
  val profile: VpnProfile,
  val secrets: ProfileSecrets,
)

data class BatchImportResult(
  val imported: List<ParsedProfileResult>,
  val errors: List<String>,
)

@Serializable
private data class PortableProfileBundle(
  val format: String = "gvpn-v1",
  val profile: VpnProfile,
  val secrets: ProfileSecrets = ProfileSecrets(),
)

object ProfileParser {

  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
  }

  fun parseBatch(rawInput: String): BatchImportResult {
    val trimmed = rawInput.trim()
    if (trimmed.isEmpty()) {
      return BatchImportResult(emptyList(), listOf("Input is empty."))
    }

    // Check if the input is a JSON object or array exported from G-VPN
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
      return parseJsonInput(trimmed)
    }

    val lines =
      trimmed
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }

    if (lines.isEmpty()) {
      return BatchImportResult(emptyList(), listOf("No valid configuration links found."))
    }

    val imported = mutableListOf<ParsedProfileResult>()
    val errors = mutableListOf<String>()

    lines.forEachIndexed { index, line ->
      val result = parseSingleLink(line)
      result.fold(
        onSuccess = { imported.add(it) },
        onFailure = { err ->
          val lineHint = if (lines.size > 1) "Line ${index + 1}: " else ""
          errors.add("$lineHint${err.message ?: "Invalid configuration link"}")
        },
      )
    }

    return BatchImportResult(imported, errors)
  }

  fun parseSingleLink(rawLink: String): Result<ParsedProfileResult> = runCatching {
    val link = rawLink.trim()
    require(link.isNotEmpty()) { "Empty link" }
    val lower = link.lowercase()
    when {
      lower.startsWith("vless://") -> parseVless(link)
      lower.startsWith("vmess://") -> parseVmess(link)
      lower.startsWith("ss://") -> parseShadowsocks(link)
      lower.startsWith("ssh://") -> parseSsh(link)
      lower.startsWith("socks5://") || lower.startsWith("socks://") -> parseSocks5(link)
      else -> error("Unsupported URI scheme. Expected vless://, vmess://, ss://, ssh://, or socks5://")
    }
  }

  private fun parseVless(uri: String): ParsedProfileResult {
    val withoutScheme = uri.substringAfter("://")
    val fragment = withoutScheme.substringAfter("#", "").let(::urlDecode)
    val beforeFrag = withoutScheme.substringBefore("#")
    val queryStr = beforeFrag.substringAfter("?", "")
    val authority = beforeFrag.substringBefore("?")

    val uuid = authority.substringBefore("@", "").trim()
    require(uuid.isNotEmpty()) { "VLESS link missing UUID" }
    val hostPort = authority.substringAfter("@", "")
    val (host, port) = splitHostPort(hostPort, 443)

    val params = parseQueryParams(queryStr)
    val typeParam = (params["type"] ?: "tcp").lowercase()
    val transport =
      when (typeParam) {
        "ws", "websocket" -> TransportType.WS
        "httpupgrade" -> TransportType.HTTP_UPGRADE
        "tcp", "raw" -> TransportType.TCP
        else -> error("Unsupported VLESS transport '$typeParam'. Supported: tcp, ws, httpupgrade")
      }

    val securityParam = (params["security"] ?: "none").lowercase()
    val security =
      when (securityParam) {
        "tls" -> SecurityType.TLS
        "none", "" -> SecurityType.NONE
        "reality" -> error("VLESS 'reality' security requires Xray core; use TLS or None transport")
        else -> error("Unsupported VLESS security '$securityParam'")
      }

    val sni = params["sni"] ?: params["peer"] ?: ""
    val wsHost = params["host"] ?: ""
    val wsPath = params["path"]?.let(::urlDecode) ?: "/"
    val alpn = params["alpn"]?.let(::urlDecode) ?: ""
    val fp = params["fp"] ?: ""
    val flow = params["flow"] ?: ""
    val allowInsecure = params["allowInsecure"] == "1" || params["allowInsecure"] == "true"

    val profile =
      VpnProfile(
        id = UUID.randomUUID().toString(),
        name = fragment.ifBlank { "VLESS • $host" },
        protocol = ProtocolType.VLESS,
        host = host,
        port = port,
        transport = transport,
        security = security,
        sni = sni,
        alpn = alpn,
        wsPath = wsPath,
        wsHost = wsHost,
        allowInsecure = allowInsecure,
        fingerprint = fp,
        flow = flow,
      )
    return ParsedProfileResult(profile, ProfileSecrets(uuid = uuid))
  }

  private fun parseVmess(uri: String): ParsedProfileResult {
    val payloadB64 = uri.substringAfter("://").substringBefore("#").trim()
    require(payloadB64.isNotEmpty()) { "VMess link has empty payload" }

    val decodedJson =
      runCatching { decodeBase64Flexible(payloadB64).toString(Charsets.UTF_8) }
        .getOrElse { error("VMess link is not valid Base64") }

    val obj =
      runCatching { JSONObject(decodedJson) }
        .getOrElse { error("VMess payload is not valid JSON") }

    val host = obj.optString("add", "").trim()
    require(host.isNotEmpty()) { "VMess JSON missing server 'add'" }
    val port = obj.optString("port", "443").toIntOrNull() ?: obj.optInt("port", 443)
    require(port in 1..65535) { "VMess port out of range: $port" }
    val uuid = obj.optString("id", "").trim()
    require(uuid.isNotEmpty()) { "VMess JSON missing UUID 'id'" }

    val ps = obj.optString("ps", "").trim()
    val net = obj.optString("net", "tcp").lowercase()
    val transport =
      when (net) {
        "ws", "websocket" -> TransportType.WS
        "httpupgrade" -> TransportType.HTTP_UPGRADE
        "tcp", "" -> TransportType.TCP
        else -> error("Unsupported VMess network '$net'. Supported: tcp, ws, httpupgrade")
      }
    val tlsVal = obj.optString("tls", "").lowercase()
    val security = if (tlsVal == "tls") SecurityType.TLS else SecurityType.NONE
    val sni = obj.optString("sni", "")
    val wsHost = obj.optString("host", "")
    val wsPath = obj.optString("path", "/").ifBlank { "/" }
    val alpn = obj.optString("alpn", "")
    val fp = obj.optString("fp", "")
    val scy = obj.optString("scy", "auto").ifBlank { "auto" }
    val aid = obj.optString("aid", "0").toIntOrNull() ?: obj.optInt("aid", 0)
    val allowInsecure = obj.optBoolean("allowInsecure", false) || obj.optInt("allowInsecure", 0) == 1

    val profile =
      VpnProfile(
        id = UUID.randomUUID().toString(),
        name = ps.ifBlank { "VMess • $host" },
        protocol = ProtocolType.VMESS,
        host = host,
        port = port,
        transport = transport,
        security = security,
        sni = sni,
        alpn = alpn,
        wsPath = wsPath,
        wsHost = wsHost,
        allowInsecure = allowInsecure,
        fingerprint = fp,
        vmessAlterId = aid,
        vmessCipher = scy,
      )
    return ParsedProfileResult(profile, ProfileSecrets(uuid = uuid))
  }

  private fun parseShadowsocks(uri: String): ParsedProfileResult {
    val withoutScheme = uri.substringAfter("://")
    val fragment = withoutScheme.substringAfter("#", "").let(::urlDecode)
    val beforeFrag = withoutScheme.substringBefore("#").substringBefore("?").trim()
    require(beforeFrag.isNotEmpty()) { "Shadowsocks link is empty" }

    val method: String
    val password: String
    val host: String
    val port: Int

    if (beforeFrag.contains("@")) {
      // SIP002: ss://base64(method:password)@host:port or ss://method:password@host:port
      val userInfoPart = beforeFrag.substringBefore("@")
      val hostPortPart = beforeFrag.substringAfter("@")
      val decodedUserInfo =
        if (userInfoPart.contains(":")) userInfoPart
        else runCatching { decodeBase64Flexible(userInfoPart).toString(Charsets.UTF_8) }
          .getOrElse { error("Invalid Shadowsocks credential encoding") }

      val colonIdx = decodedUserInfo.indexOf(':')
      require(colonIdx > 0) { "Shadowsocks credentials missing method:password separator" }
      method = decodedUserInfo.substring(0, colonIdx).trim().lowercase()
      password = decodedUserInfo.substring(colonIdx + 1)
      val hp = splitHostPort(hostPortPart, 8388)
      host = hp.first
      port = hp.second
    } else {
      // Legacy: ss://base64(method:password@host:port)
      val decoded =
        runCatching { decodeBase64Flexible(beforeFrag).toString(Charsets.UTF_8) }
          .getOrElse { error("Invalid Shadowsocks Base64 payload") }
      val atIdx = decoded.lastIndexOf('@')
      require(atIdx > 0) { "Shadowsocks legacy link missing '@' separator" }
      val userInfo = decoded.substring(0, atIdx)
      val hostPortPart = decoded.substring(atIdx + 1)
      val colonIdx = userInfo.indexOf(':')
      require(colonIdx > 0) { "Shadowsocks credentials missing method:password separator" }
      method = userInfo.substring(0, colonIdx).trim().lowercase()
      password = userInfo.substring(colonIdx + 1)
      val hp = splitHostPort(hostPortPart, 8388)
      host = hp.first
      port = hp.second
    }

    require(method in SUPPORTED_SS_METHODS) {
      "Unsupported Shadowsocks cipher '$method'. Supported: ${SUPPORTED_SS_METHODS.joinToString()}"
    }

    val profile =
      VpnProfile(
        id = UUID.randomUUID().toString(),
        name = fragment.ifBlank { "SS • $host" },
        protocol = ProtocolType.SHADOWSOCKS,
        host = host,
        port = port,
        ssMethod = method,
      )
    return ParsedProfileResult(profile, ProfileSecrets(password = password))
  }

  private fun parseSsh(uri: String): ParsedProfileResult {
    val withoutScheme = uri.substringAfter("://")
    val fragment = withoutScheme.substringAfter("#", "").let(::urlDecode)
    val beforeFrag = withoutScheme.substringBefore("#")
    val queryStr = beforeFrag.substringAfter("?", "")
    val authority = beforeFrag.substringBefore("?")
    val params = parseQueryParams(queryStr)

    var username = ""
    var password = ""
    val hostPortPart: String

    if (authority.contains("@")) {
      val userInfo = authority.substringBefore("@")
      hostPortPart = authority.substringAfter("@")
      if (userInfo.contains(":")) {
        username = urlDecode(userInfo.substringBefore(":"))
        password = urlDecode(userInfo.substringAfter(":"))
      } else {
        username = urlDecode(userInfo)
      }
    } else {
      hostPortPart = authority
    }

    val (host, port) = splitHostPort(hostPortPart, 22)
    val tlsEnabled = params["tls"] == "1" || params["tls"] == "true"
    val sni = params["sni"] ?: ""

    val profile =
      VpnProfile(
        id = UUID.randomUUID().toString(),
        name = fragment.ifBlank { "SSH • $host" },
        protocol = ProtocolType.SSH,
        host = host,
        port = port,
        sshUsername = username,
        sshAuthMethod = SshAuthMethod.PASSWORD,
        security = if (tlsEnabled) SecurityType.TLS else SecurityType.NONE,
        sni = sni,
      )
    return ParsedProfileResult(profile, ProfileSecrets(password = password))
  }

  private fun parseSocks5(uri: String): ParsedProfileResult {
    val withoutScheme = uri.substringAfter("://")
    val fragment = withoutScheme.substringAfter("#", "").let(::urlDecode)
    val beforeFrag = withoutScheme.substringBefore("#").substringBefore("?")

    var username = ""
    var password = ""
    val hostPortPart: String

    if (beforeFrag.contains("@")) {
      val userInfo = beforeFrag.substringBefore("@")
      hostPortPart = beforeFrag.substringAfter("@")
      if (userInfo.contains(":")) {
        username = urlDecode(userInfo.substringBefore(":"))
        password = urlDecode(userInfo.substringAfter(":"))
      } else {
        username = urlDecode(userInfo)
      }
    } else {
      hostPortPart = beforeFrag
    }

    val (host, port) = splitHostPort(hostPortPart, 1080)
    val profile =
      VpnProfile(
        id = UUID.randomUUID().toString(),
        name = fragment.ifBlank { "SOCKS5 • $host" },
        protocol = ProtocolType.SOCKS5,
        host = host,
        port = port,
        socksUsername = username,
      )
    return ParsedProfileResult(profile, ProfileSecrets(password = password))
  }

  private fun parseJsonInput(raw: String): BatchImportResult {
    val imported = mutableListOf<ParsedProfileResult>()
    val errors = mutableListOf<String>()
    runCatching {
        if (raw.startsWith("[")) {
          val arr = JSONArray(raw)
          for (i in 0 until arr.length()) {
            val itemStr = arr.getJSONObject(i).toString()
            parseSingleJsonBundle(itemStr)
              .fold(
                onSuccess = { imported.add(it) },
                onFailure = { errors.add("Item ${i + 1}: ${it.message}") },
              )
          }
        } else {
          parseSingleJsonBundle(raw)
            .fold(
              onSuccess = { imported.add(it) },
              onFailure = { errors.add(it.message ?: "Invalid JSON profile") },
            )
        }
      }
      .onFailure { errors.add("Malformed JSON: ${it.message}") }
    return BatchImportResult(imported, errors)
  }

  private fun parseSingleJsonBundle(raw: String): Result<ParsedProfileResult> = runCatching {
    val bundle = json.decodeFromString<PortableProfileBundle>(raw)
    require(bundle.profile.host.isNotBlank()) { "Profile missing host" }
    require(bundle.profile.port in 1..65535) { "Invalid port ${bundle.profile.port}" }
    ParsedProfileResult(
      profile = bundle.profile.copy(id = UUID.randomUUID().toString()),
      secrets = bundle.secrets,
    )
  }

  fun exportAsUri(profile: VpnProfile, secrets: ProfileSecrets): String {
    val tag = urlEncode(profile.displayTitle())
    return when (profile.protocol) {
      ProtocolType.VLESS -> {
        val params = buildList {
          add("type=${profile.transport.wireValue}")
          add("security=${profile.security.wireValue}")
          if (profile.sni.isNotBlank()) add("sni=${urlEncode(profile.sni)}")
          if (profile.wsHost.isNotBlank()) add("host=${urlEncode(profile.wsHost)}")
          if (profile.wsPath.isNotBlank() && profile.wsPath != "/") add("path=${urlEncode(profile.wsPath)}")
          if (profile.alpn.isNotBlank()) add("alpn=${urlEncode(profile.alpn)}")
          if (profile.allowInsecure) add("allowInsecure=1")
        }
        "vless://${secrets.uuid}@${formatHost(profile.host)}:${profile.port}?${params.joinToString("&")}#$tag"
      }
      ProtocolType.VMESS -> {
        val obj =
          JSONObject().apply {
            put("v", "2")
            put("ps", profile.displayTitle())
            put("add", profile.host)
            put("port", profile.port.toString())
            put("id", secrets.uuid)
            put("aid", profile.vmessAlterId.toString())
            put("scy", profile.vmessCipher)
            put("net", profile.transport.wireValue)
            put("type", "none")
            put("host", profile.wsHost)
            put("path", profile.wsPath)
            put("tls", if (profile.security == SecurityType.TLS) "tls" else "")
            if (profile.sni.isNotBlank()) put("sni", profile.sni)
            if (profile.alpn.isNotBlank()) put("alpn", profile.alpn)
            if (profile.allowInsecure) put("allowInsecure", 1)
          }
        val b64 = Base64.encodeToString(obj.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        "vmess://$b64"
      }
      ProtocolType.SHADOWSOCKS -> {
        val userInfo = "${profile.ssMethod}:${secrets.password}".toByteArray(Charsets.UTF_8)
        val b64 = Base64.encodeToString(userInfo, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
        "ss://$b64@${formatHost(profile.host)}:${profile.port}#$tag"
      }
      ProtocolType.SOCKS5 -> {
        val auth =
          if (profile.socksUsername.isNotBlank()) {
            "${urlEncode(profile.socksUsername)}:${urlEncode(secrets.password)}@"
          } else ""
        "socks5://$auth${formatHost(profile.host)}:${profile.port}#$tag"
      }
      ProtocolType.SSH -> {
        val auth =
          if (profile.sshUsername.isNotBlank()) {
            val passPart = if (secrets.password.isNotBlank()) ":${urlEncode(secrets.password)}" else ""
            "${urlEncode(profile.sshUsername)}$passPart@"
          } else ""
        val q = if (profile.security == SecurityType.TLS) "?tls=1&sni=${urlEncode(profile.sni)}" else ""
        "ssh://$auth${formatHost(profile.host)}:${profile.port}$q#$tag"
      }
    }
  }

  fun exportAsJson(profile: VpnProfile, secrets: ProfileSecrets, includeSecrets: Boolean): String {
    val safeSecrets = if (includeSecrets) secrets else ProfileSecrets()
    return json.encodeToString(PortableProfileBundle(profile = profile, secrets = safeSecrets))
  }

  private fun splitHostPort(hostPortRaw: String, defaultPort: Int): Pair<String, Int> {
    val clean = hostPortRaw.trim()
    require(clean.isNotEmpty()) { "Missing server host" }
    return if (clean.startsWith("[")) {
      val endBracket = clean.indexOf(']')
      require(endBracket > 1) { "Malformed IPv6 host" }
      val host = clean.substring(1, endBracket)
      val portPart = clean.substring(endBracket + 1)
      val port =
        if (portPart.startsWith(":")) portPart.substring(1).toIntOrNull() ?: error("Invalid port")
        else defaultPort
      require(port in 1..65535) { "Port out of range: $port" }
      host to port
    } else {
      val colon = clean.lastIndexOf(':')
      if (colon > 0) {
        val host = clean.substring(0, colon).trim()
        val port = clean.substring(colon + 1).trim().toIntOrNull() ?: error("Invalid port")
        require(host.isNotEmpty()) { "Missing server host" }
        require(port in 1..65535) { "Port out of range: $port" }
        host to port
      } else {
        clean to defaultPort
      }
    }
  }

  private fun formatHost(host: String): String =
    if (host.contains(":") && !host.startsWith("[")) "[$host]" else host

  private fun parseQueryParams(query: String): Map<String, String> {
    if (query.isBlank()) return emptyMap()
    return query.split("&").mapNotNull { pair ->
      val idx = pair.indexOf('=')
      if (idx > 0) pair.substring(0, idx) to pair.substring(idx + 1)
      else null
    }.toMap()
  }

  private fun decodeBase64Flexible(input: String): ByteArray {
    var normalized = input.trim().replace('-', '+').replace('_', '/')
    val mod = normalized.length % 4
    if (mod != 0) normalized += "=".repeat(4 - mod)
    return Base64.decode(normalized, Base64.DEFAULT)
  }

  private fun urlDecode(value: String): String =
    runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

  private fun urlEncode(value: String): String =
    runCatching { URLEncoder.encode(value, "UTF-8").replace("+", "%20") }.getOrDefault(value)

  val SUPPORTED_SS_METHODS =
    listOf(
      "aes-256-gcm",
      "aes-128-gcm",
      "chacha20-ietf-poly1305",
      "xchacha20-ietf-poly1305",
    )
}
