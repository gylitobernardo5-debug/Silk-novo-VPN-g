package com.example.vpn

import com.example.model.ProfileSecrets
import com.example.model.SecurityType
import com.example.model.SshAuthMethod
import com.example.model.UpstreamProxyType
import com.example.model.VpnProfile
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.ProxyHTTP
import com.jcraft.jsch.ProxySOCKS5
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Properties

class SshTunnelEngine(
  private val profile: VpnProfile,
  private val secrets: ProfileSecrets,
) : TunnelEngine {

  override val name: String = "SSH2"

  @Volatile private var session: Session? = null

  override suspend fun connect(protector: VpnSocketProtector, timeoutMs: Int): Result<Unit> =
    withContext(Dispatchers.IO) {
      runCatching {
        close()
        require(profile.host.isNotBlank()) { "SSH server host is empty" }
        require(profile.sshUsername.isNotBlank()) { "SSH username is required" }

        val jsch = JSch()
        if (
          profile.sshAuthMethod == SshAuthMethod.PUBLIC_KEY ||
            profile.sshAuthMethod == SshAuthMethod.PASSWORD_AND_KEY
        ) {
          val keyBytes = secrets.sshPrivateKey.trim().toByteArray(Charsets.UTF_8)
          require(keyBytes.isNotEmpty()) { "SSH private key is required for key authentication" }
          val passBytes =
            secrets.sshPassphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
          jsch.addIdentity("gvpn_key", keyBytes, null, passBytes)
        }

        val newSession = jsch.getSession(profile.sshUsername, profile.host, profile.port)
        if (
          profile.sshAuthMethod == SshAuthMethod.PASSWORD ||
            profile.sshAuthMethod == SshAuthMethod.PASSWORD_AND_KEY
        ) {
          require(secrets.password.isNotEmpty()) { "SSH password is required" }
          newSession.setPassword(secrets.password)
        }

        val config =
          Properties().apply {
            put("StrictHostKeyChecking", "no")
            put("PreferredAuthentications", "publickey,password,keyboard-interactive")
            put("Compression", "none")
          }
        newSession.setConfig(config)
        newSession.timeout = timeoutMs
        newSession.setServerAliveInterval(20_000)
        newSession.setServerAliveCountMax(3)

        newSession.setSocketFactory(
          ProtectedSshSocketFactory(
            protector = protector,
            timeoutMs = timeoutMs,
            useTls = profile.security == SecurityType.TLS,
            sni = profile.sni,
            alpn = profile.alpn,
            allowInsecure = profile.allowInsecure,
          )
        )

        if (
          profile.upstreamProxyType != UpstreamProxyType.NONE &&
            profile.upstreamProxyHost.isNotBlank()
        ) {
          when (profile.upstreamProxyType) {
            UpstreamProxyType.HTTP ->
              newSession.setProxy(ProxyHTTP(profile.upstreamProxyHost, profile.upstreamProxyPort))
            UpstreamProxyType.SOCKS5 ->
              newSession.setProxy(ProxySOCKS5(profile.upstreamProxyHost, profile.upstreamProxyPort))
            UpstreamProxyType.NONE -> Unit
          }
        }

        newSession.connect(timeoutMs)
        check(newSession.isConnected) { "SSH session did not establish" }
        session = newSession
      }
    }

  override suspend fun openTcpStream(
    destHost: String,
    destPort: Int,
    timeoutMs: Int,
  ): TunnelStream = withContext(Dispatchers.IO) {
    val active = session ?: error("SSH session not connected")
    check(active.isConnected) { "SSH session disconnected" }
    val channel = active.openChannel("direct-tcpip") as ChannelDirectTCPIP
    channel.setHost(destHost)
    channel.setPort(destPort)
    channel.setOrgIPAddress("127.0.0.1")
    channel.setOrgPort(0)
    val input = channel.inputStream
    val output = channel.outputStream
    channel.connect(timeoutMs)
    SshChannelStream(channel, input, output)
  }

  /**
   * SSH direct-tcpip does not natively carry raw UDP, so DNS queries (port 53) are
   * forwarded as standard RFC 1035 DNS-over-TCP (2-byte length prefix) over SSH!
   */
  override suspend fun sendUdpQuery(
    destHost: String,
    destPort: Int,
    payload: ByteArray,
    timeoutMs: Int,
  ): ByteArray? = withContext(Dispatchers.IO) {
    if (destPort != 53) return@withContext null
    val stream = openTcpStream(destHost, 53, timeoutMs)
    stream.use { s ->
      val lenPrefix = ByteBuffer.allocate(2).putShort(payload.size.toShort()).array()
      s.write(lenPrefix)
      s.write(payload)
      s.flush()

      val respLenBytes = ByteArray(2)
      var got = 0
      while (got < 2) {
        val n = s.read(respLenBytes, got, 2 - got)
        if (n <= 0) return@withContext null
        got += n
      }
      val respLen = ((respLenBytes[0].toInt() and 0xFF) shl 8) or (respLenBytes[1].toInt() and 0xFF)
      if (respLen <= 0 || respLen > 4096) return@withContext null
      val resp = ByteArray(respLen)
      var pos = 0
      while (pos < respLen) {
        val n = s.read(resp, pos, respLen - pos)
        if (n <= 0) break
        pos += n
      }
      if (pos == respLen) resp else null
    }
  }

  override suspend fun testHandshake(
    protector: VpnSocketProtector,
    timeoutMs: Int,
  ): DiagnosticHandshakeResult {
    val start = System.currentTimeMillis()
    val res = connect(protector, timeoutMs)
    val elapsed = System.currentTimeMillis() - start
    close()
    return res.fold(
      onSuccess = {
        DiagnosticHandshakeResult(
          ok = true,
          latencyMs = elapsed,
          message = "SSH2 authentication & channel capability verified (${elapsed} ms)",
        )
      },
      onFailure = { err ->
        DiagnosticHandshakeResult(
          ok = false,
          latencyMs = elapsed,
          message = err.message ?: "SSH handshake failed",
        )
      },
    )
  }

  override fun close() {
    runCatching { session?.disconnect() }
    session = null
  }
}

private class ProtectedSshSocketFactory(
  private val protector: VpnSocketProtector,
  private val timeoutMs: Int,
  private val useTls: Boolean,
  private val sni: String,
  private val alpn: String,
  private val allowInsecure: Boolean,
) : SocketFactory {
  override fun createSocket(host: String, port: Int): Socket {
    val raw = Socket()
    check(protector.protectSocket(raw)) { "Failed to protect SSH socket" }
    raw.tcpNoDelay = true
    raw.soTimeout = timeoutMs
    raw.connect(InetSocketAddress(host, port), timeoutMs)
    return if (useTls) {
      TransportConnector.wrapTls(raw, host, port, sni, alpn, allowInsecure)
    } else {
      raw
    }
  }

  override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()

  override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
}

private class SshChannelStream(
  private val channel: ChannelDirectTCPIP,
  private val input: InputStream,
  private val output: OutputStream,
) : TunnelStream {
  override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
    input.read(buffer, offset, length)

  override fun write(data: ByteArray, offset: Int, length: Int) {
    output.write(data, offset, length)
  }

  override fun flush() {
    output.flush()
  }

  override fun close() {
    runCatching { channel.disconnect() }
  }
}
