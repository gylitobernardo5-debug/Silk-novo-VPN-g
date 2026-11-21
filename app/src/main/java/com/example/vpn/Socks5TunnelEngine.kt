package com.example.vpn

import com.example.model.ProfileSecrets
import com.example.model.VpnProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class Socks5TunnelEngine(
  private val profile: VpnProfile,
  private val secrets: ProfileSecrets,
) : TunnelEngine {

  override val name: String = "SOCKS5"
  @Volatile private var protector: VpnSocketProtector = NoOpSocketProtector

  override suspend fun connect(protector: VpnSocketProtector, timeoutMs: Int): Result<Unit> =
    withContext(Dispatchers.IO) {
      this@Socks5TunnelEngine.protector = protector
      runCatching {
        require(profile.host.isNotBlank()) { "SOCKS5 host is empty" }
        val sock = TransportConnector.openRawSocket(profile.host, profile.port, protector, timeoutMs)
        sock.use { s ->
          negotiateAuth(s.getInputStream(), s.getOutputStream())
        }
      }
    }

  override suspend fun openTcpStream(
    destHost: String,
    destPort: Int,
    timeoutMs: Int,
  ): TunnelStream = withContext(Dispatchers.IO) {
    val socket = TransportConnector.openRawSocket(profile.host, profile.port, protector, timeoutMs)
    try {
      val input = socket.getInputStream()
      val output = socket.getOutputStream()
      negotiateAuth(input, output)
      sendConnectRequest(input, output, cmd = 0x01, destHost = destHost, destPort = destPort)
      SocketTunnelStream(socket)
    } catch (t: Throwable) {
      runCatching { socket.close() }
      throw t
    }
  }

  override suspend fun sendUdpQuery(
    destHost: String,
    destPort: Int,
    payload: ByteArray,
    timeoutMs: Int,
  ): ByteArray? = withContext(Dispatchers.IO) {
    if (!profile.socksUdpEnabled) return@withContext null
    val controlSocket =
      TransportConnector.openRawSocket(profile.host, profile.port, protector, timeoutMs)
    controlSocket.use { ctrl ->
      val input = ctrl.getInputStream()
      val output = ctrl.getOutputStream()
      negotiateAuth(input, output)
      val relayEndpoint = sendConnectRequest(input, output, cmd = 0x03, destHost = "0.0.0.0", destPort = 0)
      val relayHost =
        if (relayEndpoint.first == "0.0.0.0" || relayEndpoint.first == "::") profile.host
        else relayEndpoint.first
      val relayPort = relayEndpoint.second

      val udpSocket = DatagramSocket()
      udpSocket.use { ds ->
        check(protector.protectDatagram(ds)) { "Failed to protect SOCKS5 UDP socket" }
        ds.soTimeout = timeoutMs
        val header = buildUdpHeader(destHost, destPort)
        val packetBytes = header + payload
        val outPacket =
          DatagramPacket(packetBytes, packetBytes.size, InetAddress.getByName(relayHost), relayPort)
        ds.send(outPacket)

        val recvBuf = ByteArray(4096)
        val inPacket = DatagramPacket(recvBuf, recvBuf.size)
        ds.receive(inPacket)
        stripUdpHeader(inPacket.data, inPacket.length)
      }
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
          message = "SOCKS5 handshake & auth succeeded (${elapsed} ms)",
        )
      },
      onFailure = { err ->
        DiagnosticHandshakeResult(
          ok = false,
          latencyMs = elapsed,
          message = err.message ?: "SOCKS5 negotiation failed",
        )
      },
    )
  }

  override fun close() = Unit

  private fun negotiateAuth(input: InputStream, output: OutputStream) {
    val hasAuth = profile.socksUsername.isNotBlank() || secrets.password.isNotBlank()
    if (hasAuth) {
      output.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
    } else {
      output.write(byteArrayOf(0x05, 0x01, 0x00))
    }
    output.flush()

    val choice = ByteArray(2)
    readFully(input, choice)
    require(choice[0] == 0x05.toByte()) { "Invalid SOCKS version ${choice[0]}" }
    when (choice[1].toInt() and 0xFF) {
      0x00 -> Unit
      0x02 -> {
        val u = profile.socksUsername.toByteArray(Charsets.UTF_8)
        val p = secrets.password.toByteArray(Charsets.UTF_8)
        val authReq = ByteArray(3 + u.size + p.size)
        authReq[0] = 0x01
        authReq[1] = u.size.toByte()
        System.arraycopy(u, 0, authReq, 2, u.size)
        authReq[2 + u.size] = p.size.toByte()
        System.arraycopy(p, 0, authReq, 3 + u.size, p.size)
        output.write(authReq)
        output.flush()

        val authResp = ByteArray(2)
        readFully(input, authResp)
        require(authResp[1] == 0x00.toByte()) { "SOCKS5 authentication rejected" }
      }
      0xFF -> error("SOCKS5 server rejected authentication methods")
      else -> error("Unsupported SOCKS5 auth method: ${choice[1]}")
    }
  }

  private fun sendConnectRequest(
    input: InputStream,
    output: OutputStream,
    cmd: Int,
    destHost: String,
    destPort: Int,
  ): Pair<String, Int> {
    val buf = ByteArrayOutputStream()
    buf.write(0x05)
    buf.write(cmd)
    buf.write(0x00)
    writeSocksAddress(buf, destHost, destPort)
    output.write(buf.toByteArray())
    output.flush()

    val head = ByteArray(4)
    readFully(input, head)
    val rep = head[1].toInt() and 0xFF
    require(rep == 0x00) { "SOCKS5 request failed with reply code 0x${rep.toString(16)}" }

    val atyp = head[3].toInt() and 0xFF
    val bindHost =
      when (atyp) {
        0x01 -> {
          val b = ByteArray(4)
          readFully(input, b)
          InetAddress.getByAddress(b).hostAddress ?: "0.0.0.0"
        }
        0x03 -> {
          val len = input.read()
          if (len <= 0) "" else ByteArray(len).also { readFully(input, it) }.toString(Charsets.UTF_8)
        }
        0x04 -> {
          val b = ByteArray(16)
          readFully(input, b)
          InetAddress.getByAddress(b).hostAddress ?: "::"
        }
        else -> error("Invalid SOCKS5 ATYP $atyp")
      }
    val portBytes = ByteArray(2)
    readFully(input, portBytes)
    val bindPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
    return bindHost to bindPort
  }

  private fun buildUdpHeader(host: String, port: Int): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(0x00)
    out.write(0x00)
    out.write(0x00)
    writeSocksAddress(out, host, port)
    return out.toByteArray()
  }

  private fun stripUdpHeader(data: ByteArray, length: Int): ByteArray? {
    if (length < 10) return null
    val atyp = data[3].toInt() and 0xFF
    val payloadOffset =
      when (atyp) {
        0x01 -> 4 + 4 + 2
        0x03 -> 4 + 1 + (data[4].toInt() and 0xFF) + 2
        0x04 -> 4 + 16 + 2
        else -> return null
      }
    if (payloadOffset >= length) return null
    return data.copyOfRange(payloadOffset, length)
  }

  private fun writeSocksAddress(out: ByteArrayOutputStream, host: String, port: Int) {
    val ipv4 = parseIpv4(host)
    if (ipv4 != null) {
      out.write(0x01)
      out.write(ipv4)
    } else {
      val hostBytes = host.toByteArray(Charsets.UTF_8)
      out.write(0x03)
      out.write(hostBytes.size)
      out.write(hostBytes)
    }
    out.write((port ushr 8) and 0xFF)
    out.write(port and 0xFF)
  }

  private fun readFully(input: InputStream, dest: ByteArray) {
    var pos = 0
    while (pos < dest.size) {
      val n = input.read(dest, pos, dest.size - pos)
      if (n == -1) throw EOFException("Unexpected EOF from SOCKS5 server")
      pos += n
    }
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
}
