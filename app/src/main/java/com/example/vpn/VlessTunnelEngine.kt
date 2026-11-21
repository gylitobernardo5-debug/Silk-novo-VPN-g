package com.example.vpn

import com.example.model.ProfileSecrets
import com.example.model.VpnProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Real VLESS v0 client over TransportConnector (TCP, WebSocket, HTTPUpgrade + TLS/SNI/ALPN).
 */
class VlessTunnelEngine(
  private val profile: VpnProfile,
  private val secrets: ProfileSecrets,
) : TunnelEngine {

  override val name: String = "VLESS (${profile.transport.label})"
  @Volatile private var protector: VpnSocketProtector = NoOpSocketProtector

  private fun parseUuidBytes(): ByteArray {
    val uuid =
      runCatching { UUID.fromString(secrets.uuid.trim()) }
        .getOrElse { error("Invalid VLESS UUID format") }
    return ByteBuffer.allocate(16)
      .putLong(uuid.mostSignificantBits)
      .putLong(uuid.leastSignificantBits)
      .array()
  }

  override suspend fun connect(protector: VpnSocketProtector, timeoutMs: Int): Result<Unit> =
    withContext(Dispatchers.IO) {
      this@VlessTunnelEngine.protector = protector
      runCatching {
        require(profile.host.isNotBlank()) { "VLESS server host is empty" }
        parseUuidBytes()
        val testStream = TransportConnector.openTransportStream(profile, protector, timeoutMs)
        testStream.close()
      }
    }

  override suspend fun openTcpStream(
    destHost: String,
    destPort: Int,
    timeoutMs: Int,
  ): TunnelStream = withContext(Dispatchers.IO) {
    val uuidBytes = parseUuidBytes()
    val transport = TransportConnector.openTransportStream(profile, protector, timeoutMs)
    try {
      val header = buildVlessHeader(uuidBytes, cmd = 0x01, destHost = destHost, destPort = destPort)
      VlessStream(transport, header)
    } catch (t: Throwable) {
      runCatching { transport.close() }
      throw t
    }
  }

  override suspend fun sendUdpQuery(
    destHost: String,
    destPort: Int,
    payload: ByteArray,
    timeoutMs: Int,
  ): ByteArray? = withContext(Dispatchers.IO) {
    val uuidBytes = parseUuidBytes()
    val transport = TransportConnector.openTransportStream(profile, protector, timeoutMs)
    transport.use { raw ->
      val header = buildVlessHeader(uuidBytes, cmd = 0x02, destHost = destHost, destPort = destPort)
      val stream = VlessStream(raw, header)
      val packet =
        ByteArray(2 + payload.size).apply {
          this[0] = ((payload.size ushr 8) and 0xFF).toByte()
          this[1] = (payload.size and 0xFF).toByte()
          System.arraycopy(payload, 0, this, 2, payload.size)
        }
      stream.write(packet)
      stream.flush()

      val lenBytes = ByteArray(2)
      var got = 0
      while (got < 2) {
        val n = stream.read(lenBytes, got, 2 - got)
        if (n <= 0) return@withContext null
        got += n
      }
      val respLen = ((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)
      if (respLen <= 0 || respLen > 8192) return@withContext null
      val out = ByteArray(respLen)
      var pos = 0
      while (pos < respLen) {
        val n = stream.read(out, pos, respLen - pos)
        if (n <= 0) break
        pos += n
      }
      if (pos == respLen) out else null
    }
  }

  override suspend fun testHandshake(
    protector: VpnSocketProtector,
    timeoutMs: Int,
  ): DiagnosticHandshakeResult {
    val start = System.currentTimeMillis()
    val res = connect(protector, timeoutMs)
    val elapsed = System.currentTimeMillis() - start
    return res.fold(
      onSuccess = {
        DiagnosticHandshakeResult(
          ok = true,
          latencyMs = elapsed,
          message =
            "VLESS ${profile.transport.label} + ${profile.security.label} transport handshake verified (${elapsed} ms)",
        )
      },
      onFailure = { err ->
        DiagnosticHandshakeResult(
          ok = false,
          latencyMs = elapsed,
          message = err.message ?: "VLESS transport handshake failed",
        )
      },
    )
  }

  override fun close() = Unit

  private fun buildVlessHeader(
    uuidBytes: ByteArray,
    cmd: Int,
    destHost: String,
    destPort: Int,
  ): ByteArray {
    val out = ByteArrayOutputStream(64)
    out.write(0x00) // Version 0
    out.write(uuidBytes)
    out.write(0x00) // Addons length 0
    out.write(cmd) // 0x01 TCP, 0x02 UDP
    out.write((destPort ushr 8) and 0xFF)
    out.write(destPort and 0xFF)

    val ipv4 = parseIpv4(destHost)
    if (ipv4 != null) {
      out.write(0x01)
      out.write(ipv4)
    } else if (destHost.contains(":")) {
      val ipv6 = InetAddress.getByName(destHost).address
      out.write(0x03)
      out.write(ipv6)
    } else {
      val hostBytes = destHost.toByteArray(Charsets.UTF_8)
      out.write(0x02)
      out.write(hostBytes.size)
      out.write(hostBytes)
    }
    return out.toByteArray()
  }

  private fun parseIpv4(host: String): ByteArray? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    val out = ByteArray(4)
    for (i in 0..3) {
      val v = parts[i].toIntOrNull() ?: return null
      if (v !in 0..255) return null
      out[i] = v.toByte()
    }
    return out
  }

  private class VlessStream(
    private val transport: TunnelStream,
    private val initialHeader: ByteArray,
  ) : TunnelStream {

    private var headerSent = false
    private var responseHeaderRead = false
    private val writeLock = Any()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      if (!responseHeaderRead) {
        val respHead = ByteArray(2)
        readFully(respHead)
        require(respHead[0] == 0x00.toByte()) { "Unexpected VLESS response version ${respHead[0]}" }
        val addonLen = respHead[1].toInt() and 0xFF
        if (addonLen > 0) {
          readFully(ByteArray(addonLen))
        }
        responseHeaderRead = true
      }
      return transport.read(buffer, offset, length)
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
      synchronized(writeLock) {
        if (!headerSent) {
          val combined = ByteArray(initialHeader.size + length)
          System.arraycopy(initialHeader, 0, combined, 0, initialHeader.size)
          System.arraycopy(data, offset, combined, initialHeader.size, length)
          transport.write(combined, 0, combined.size)
          headerSent = true
        } else {
          transport.write(data, offset, length)
        }
      }
    }

    override fun flush() {
      synchronized(writeLock) {
        if (!headerSent) {
          transport.write(initialHeader, 0, initialHeader.size)
          headerSent = true
        }
        transport.flush()
      }
    }

    override fun close() {
      transport.close()
    }

    private fun readFully(dest: ByteArray) {
      var pos = 0
      while (pos < dest.size) {
        val n = transport.read(dest, pos, dest.size - pos)
        if (n == -1) throw EOFException("Unexpected EOF reading VLESS response header")
        pos += n
      }
    }
  }
}
