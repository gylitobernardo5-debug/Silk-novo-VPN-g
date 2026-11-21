package com.example.vpn

import java.io.Closeable
import java.net.DatagramSocket
import java.net.Socket

fun interface VpnSocketProtector {
  fun protectSocket(socket: Socket): Boolean

  fun protectDatagram(socket: DatagramSocket): Boolean = true
}

object NoOpSocketProtector : VpnSocketProtector {
  override fun protectSocket(socket: Socket): Boolean = true
  override fun protectDatagram(socket: DatagramSocket): Boolean = true
}

interface TunnelStream : Closeable {
  fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): Int
  fun write(data: ByteArray, offset: Int = 0, length: Int = data.size)
  fun flush()
}

data class DiagnosticHandshakeResult(
  val ok: Boolean,
  val latencyMs: Long,
  val message: String,
)

interface TunnelEngine : Closeable {
  val name: String

  suspend fun connect(protector: VpnSocketProtector, timeoutMs: Int): Result<Unit>

  suspend fun openTcpStream(destHost: String, destPort: Int, timeoutMs: Int): TunnelStream

  suspend fun sendUdpQuery(
    destHost: String,
    destPort: Int,
    payload: ByteArray,
    timeoutMs: Int,
  ): ByteArray?

  suspend fun testHandshake(protector: VpnSocketProtector, timeoutMs: Int): DiagnosticHandshakeResult
}
