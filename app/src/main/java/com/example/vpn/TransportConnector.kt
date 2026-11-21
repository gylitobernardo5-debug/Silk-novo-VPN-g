package com.example.vpn

import android.util.Base64
import com.example.model.SecurityType
import com.example.model.TransportType
import com.example.model.VpnProfile
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object TransportConnector {

  fun openRawSocket(
    host: String,
    port: Int,
    protector: VpnSocketProtector,
    timeoutMs: Int,
  ): Socket {
    val socket = Socket()
    check(protector.protectSocket(socket)) { "VpnService.protect() failed for socket" }
    socket.tcpNoDelay = true
    socket.soTimeout = timeoutMs
    socket.connect(InetSocketAddress(host, port), timeoutMs)
    return socket
  }

  fun openTransportStream(
    profile: VpnProfile,
    protector: VpnSocketProtector,
    timeoutMs: Int,
  ): TunnelStream {
    val rawSocket = openRawSocket(profile.host, profile.port, protector, timeoutMs)
    val socket: Socket =
      if (profile.security == SecurityType.TLS) {
        wrapTls(
          rawSocket = rawSocket,
          host = profile.host,
          port = profile.port,
          sni = profile.sni,
          alpn = profile.alpn,
          allowInsecure = profile.allowInsecure,
        )
      } else {
        rawSocket
      }

    return when (profile.transport) {
      TransportType.TCP -> SocketTunnelStream(socket)
      TransportType.WS -> performWebSocketUpgrade(socket, profile)
      TransportType.HTTP_UPGRADE -> performHttpUpgrade(socket, profile)
    }
  }

  fun wrapTls(
    rawSocket: Socket,
    host: String,
    port: Int,
    sni: String,
    alpn: String,
    allowInsecure: Boolean,
  ): SSLSocket {
    val sslContext =
      if (allowInsecure) {
        SSLContext.getInstance("TLS").apply {
          init(
            null,
            arrayOf<TrustManager>(
              object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) = Unit
                override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
              }
            ),
            SecureRandom(),
          )
        }
      } else {
        SSLContext.getDefault()
      }

    val targetSni = sni.ifBlank { host }
    val sslSocket =
      sslContext.socketFactory.createSocket(rawSocket, targetSni, port, true) as SSLSocket
    val params: SSLParameters = sslSocket.sslParameters
    if (targetSni.isNotBlank() && !isIpLiteral(targetSni)) {
      params.serverNames = listOf(SNIHostName(targetSni))
    }
    if (!allowInsecure) {
      params.endpointIdentificationAlgorithm = "HTTPS"
    }
    if (alpn.isNotBlank()) {
      params.applicationProtocols =
        alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()
    }
    sslSocket.sslParameters = params
    sslSocket.startHandshake()
    return sslSocket
  }

  private fun performWebSocketUpgrade(socket: Socket, profile: VpnProfile): TunnelStream {
    val input = BufferedInputStream(socket.getInputStream())
    val output = socket.getOutputStream()
    val hostHeader = profile.wsHost.ifBlank { profile.sni.ifBlank { "${profile.host}:${profile.port}" } }
    val path = profile.wsPath.ifBlank { "/" }.let { if (it.startsWith("/")) it else "/$it" }
    val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
    val wsKey = Base64.encodeToString(keyBytes, Base64.NO_WRAP)

    val req =
      buildString {
        append("GET ").append(path).append(" HTTP/1.1\r\n")
        append("Host: ").append(hostHeader).append("\r\n")
        append("Upgrade: websocket\r\n")
        append("Connection: Upgrade\r\n")
        append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
        append("Sec-WebSocket-Version: 13\r\n")
        append("User-Agent: G-VPN/1.0\r\n\r\n")
      }
    output.write(req.toByteArray(Charsets.US_ASCII))
    output.flush()

    val statusLine = readHttpHeaders(input)
    check(statusLine.contains("101")) { "WebSocket upgrade rejected: $statusLine" }
    return WebSocketTunnelStream(socket, input, output)
  }

  private fun performHttpUpgrade(socket: Socket, profile: VpnProfile): TunnelStream {
    val input = BufferedInputStream(socket.getInputStream())
    val output = socket.getOutputStream()
    val hostHeader = profile.wsHost.ifBlank { profile.sni.ifBlank { "${profile.host}:${profile.port}" } }
    val path = profile.wsPath.ifBlank { "/" }.let { if (it.startsWith("/")) it else "/$it" }

    val req =
      buildString {
        append("GET ").append(path).append(" HTTP/1.1\r\n")
        append("Host: ").append(hostHeader).append("\r\n")
        append("Connection: Upgrade\r\n")
        append("Upgrade: websocket\r\n\r\n")
      }
    output.write(req.toByteArray(Charsets.US_ASCII))
    output.flush()

    val statusLine = readHttpHeaders(input)
    check(statusLine.contains("101")) { "HTTPUpgrade rejected: $statusLine" }
    return BufferedSocketTunnelStream(socket, input, output)
  }

  private fun readHttpHeaders(input: InputStream): String {
    val headerBuf = ByteArrayOutputStream()
    var prev = 0
    var state = 0
    while (true) {
      val b = input.read()
      if (b == -1) throw EOFException("Unexpected EOF during HTTP upgrade")
      headerBuf.write(b)
      state =
        when {
          state == 0 && b == '\r'.code -> 1
          state == 1 && b == '\n'.code -> 2
          state == 2 && b == '\r'.code -> 3
          state == 3 && b == '\n'.code -> 4
          else -> 0
        }
      if (state == 4) break
      if (headerBuf.size() > 16384) error("HTTP upgrade response headers too large")
      prev = b
    }
    val text = headerBuf.toString("ISO_8859_1")
    return text.lineSequence().firstOrNull()?.trim().orEmpty()
  }

  private fun isIpLiteral(value: String): Boolean =
    value.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) || value.contains(":")
}

class SocketTunnelStream(private val socket: Socket) : TunnelStream {
  private val input = socket.getInputStream()
  private val output = socket.getOutputStream()

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
    input.read(buffer, offset, length)

  override fun write(data: ByteArray, offset: Int, length: Int) {
    output.write(data, offset, length)
  }

  override fun flush() {
    output.flush()
  }

  override fun close() {
    runCatching { socket.close() }
  }
}

class BufferedSocketTunnelStream(
  private val socket: Socket,
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
    runCatching { socket.close() }
  }
}

/**
 * RFC 6455 binary WebSocket stream for VLESS/VMess over WebSocket.
 */
class WebSocketTunnelStream(
  private val socket: Socket,
  private val input: InputStream,
  private val output: OutputStream,
) : TunnelStream {

  private val writeLock = Any()
  private val rng = SecureRandom()
  private var currentFrameRemaining = 0L

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    while (currentFrameRemaining <= 0L) {
      val b1 = input.read()
      if (b1 == -1) return -1
      val b2 = input.read()
      if (b2 == -1) return -1

      val opcode = b1 and 0x0F
      val masked = (b2 and 0x80) != 0
      var payloadLen = (b2 and 0x7F).toLong()
      if (payloadLen == 126L) {
        val hi = readByteOrThrow()
        val lo = readByteOrThrow()
        payloadLen = ((hi shl 8) or lo).toLong()
      } else if (payloadLen == 127L) {
        val lenBytes = ByteArray(8)
        readFully(lenBytes)
        payloadLen = ByteBuffer.wrap(lenBytes).long
      }

      val maskKey = if (masked) ByteArray(4).also { readFully(it) } else null

      when (opcode) {
        0x8 -> return -1 // Close frame
        0x9 -> {
          // Ping -> reply with Pong
          val pingData = ByteArray(payloadLen.toInt())
          if (pingData.isNotEmpty()) {
            readFully(pingData)
            if (maskKey != null) unmask(pingData, maskKey)
          }
          sendFrame(0xA, pingData, 0, pingData.size)
        }
        0xA -> {
          // Pong -> discard
          val skip = ByteArray(payloadLen.toInt())
          if (skip.isNotEmpty()) readFully(skip)
        }
        else -> {
          currentFrameRemaining = payloadLen
          if (payloadLen == 0L) continue
        }
      }
    }

    val toRead = minOf(length.toLong(), currentFrameRemaining).toInt()
    val read = input.read(buffer, offset, toRead)
    if (read > 0) {
      currentFrameRemaining -= read
    }
    return read
  }

  override fun write(data: ByteArray, offset: Int, length: Int) {
    if (length <= 0) return
    sendFrame(0x2, data, offset, length)
  }

  private fun sendFrame(opcode: Int, data: ByteArray, offset: Int, length: Int) {
    synchronized(writeLock) {
      val header = ByteArrayOutputStream(14)
      header.write(0x80 or (opcode and 0x0F))
      when {
        length <= 125 -> header.write(0x80 or length)
        length <= 65535 -> {
          header.write(0x80 or 126)
          header.write((length ushr 8) and 0xFF)
          header.write(length and 0xFF)
        }
        else -> {
          header.write(0x80 or 127)
          val lenBytes = ByteBuffer.allocate(8).putLong(length.toLong()).array()
          header.write(lenBytes)
        }
      }
      val mask = ByteArray(4).also { rng.nextBytes(it) }
      header.write(mask)
      output.write(header.toByteArray())

      val maskedPayload = ByteArray(length)
      for (i in 0 until length) {
        maskedPayload[i] = (data[offset + i].toInt() xor mask[i and 3].toInt()).toByte()
      }
      output.write(maskedPayload)
    }
  }

  override fun flush() {
    synchronized(writeLock) { output.flush() }
  }

  override fun close() {
    runCatching { socket.close() }
  }

  private fun readByteOrThrow(): Int {
    val b = input.read()
    if (b == -1) throw EOFException("Unexpected EOF in WebSocket frame")
    return b
  }

  private fun readFully(dest: ByteArray) {
    var pos = 0
    while (pos < dest.size) {
      val n = input.read(dest, pos, dest.size - pos)
      if (n == -1) throw EOFException("Unexpected EOF in WebSocket frame")
      pos += n
    }
  }

  private fun unmask(data: ByteArray, mask: ByteArray) {
    for (i in data.indices) {
      data[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
    }
  }
}
