package com.example.vpn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight user-space IPv4 -> TCP/UDP/DNS multiplexer over Android VpnService's TUN fd.
 * Intercepts DNS (port 53) for local AdBlock filtering and tunnels TCP/UDP traffic
 * through the active TunnelEngine.
 */
class TunPacketRouter(
  private val tunFd: FileDescriptor,
  private val engine: TunnelEngine,
  private val dnsAdBlocker: DnsAdBlocker,
  private val timeoutMs: Int,
) : Closeable {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val running = AtomicBoolean(true)
  private val tunOut = FileOutputStream(tunFd)
  private val writeLock = Any()

  val bytesSent = AtomicLong(0L)
  val bytesReceived = AtomicLong(0L)

  private val tcpSessions = ConcurrentHashMap<SessionKey, TcpSession>()

  private data class SessionKey(
    val srcIp: String,
    val srcPort: Int,
    val dstIp: String,
    val dstPort: Int,
  )

  fun start() {
    scope.launch {
      val tunIn = FileInputStream(tunFd)
      val buffer = ByteArray(32767)
      while (running.get()) {
        val len =
          runCatching { tunIn.read(buffer) }
            .getOrElse { -1 }
        if (len < 0) break
        if (len == 0) continue

        bytesSent.addAndGet(len.toLong())
        val packet = buffer.copyOf(len)
        val version = PacketUtils.ipVersion(packet, len)
        if (version != 4 || len < 20) continue

        val ihl = PacketUtils.ipv4HeaderLength(packet)
        if (len < ihl) continue
        when (PacketUtils.ipv4Protocol(packet)) {
          PacketUtils.PROTO_UDP -> handleUdpPacket(packet, ihl, len)
          PacketUtils.PROTO_TCP -> handleTcpPacket(packet, ihl, len)
        }
      }
    }
  }

  private fun handleUdpPacket(packet: ByteArray, ihl: Int, totalLen: Int) {
    if (totalLen < ihl + 8) return
    val srcIpBytes = packet.copyOfRange(12, 16)
    val dstIpBytes = packet.copyOfRange(16, 20)
    val srcPort = PacketUtils.readUInt16(packet, ihl)
    val dstPort = PacketUtils.readUInt16(packet, ihl + 2)
    val udpLen = PacketUtils.readUInt16(packet, ihl + 4)
    val payloadOffset = ihl + 8
    val payloadLen = (udpLen - 8).coerceAtMost(totalLen - payloadOffset)
    if (payloadLen <= 0) return
    val payload = packet.copyOfRange(payloadOffset, payloadOffset + payloadLen)
    val dstIp = PacketUtils.ipv4AddressString(packet, 16)

    scope.launch {
      if (dstPort == 53) {
        dnsAdBlocker.totalQueriesCount.incrementAndGet()
        val domain = dnsAdBlocker.parseQueryDomain(payload)
        if (domain != null && dnsAdBlocker.shouldBlockDomain(domain)) {
          dnsAdBlocker.blockedQueriesCount.incrementAndGet()
          val blockedReply = dnsAdBlocker.buildBlockedDnsResponse(payload)
          if (blockedReply != null) {
            writeToTun(
              PacketUtils.buildIpv4UdpPacket(
                srcIp = dstIpBytes,
                srcPort = dstPort,
                dstIp = srcIpBytes,
                dstPort = srcPort,
                payload = blockedReply,
              )
            )
          }
          return@launch
        }
      }

      val reply =
        runCatching { engine.sendUdpQuery(dstIp, dstPort, payload, timeoutMs) }.getOrNull()
      if (reply != null && reply.isNotEmpty() && running.get()) {
        writeToTun(
          PacketUtils.buildIpv4UdpPacket(
            srcIp = dstIpBytes,
            srcPort = dstPort,
            dstIp = srcIpBytes,
            dstPort = srcPort,
            payload = reply,
          )
        )
      }
    }
  }

  private fun handleTcpPacket(packet: ByteArray, ihl: Int, totalLen: Int) {
    if (totalLen < ihl + 20) return
    val srcIpBytes = packet.copyOfRange(12, 16)
    val dstIpBytes = packet.copyOfRange(16, 20)
    val srcIp = PacketUtils.ipv4AddressString(packet, 12)
    val dstIp = PacketUtils.ipv4AddressString(packet, 16)
    val srcPort = PacketUtils.readUInt16(packet, ihl)
    val dstPort = PacketUtils.readUInt16(packet, ihl + 2)
    val seq = PacketUtils.readUInt32(packet, ihl + 4)
    val dataOffset = ((packet[ihl + 12].toInt() ushr 4) and 0x0F) * 4
    val flags = packet[ihl + 13].toInt() and 0xFF
    val payloadStart = ihl + dataOffset
    val payloadLen = (totalLen - payloadStart).coerceAtLeast(0)
    val key = SessionKey(srcIp, srcPort, dstIp, dstPort)

    val isSyn = (flags and PacketUtils.TCP_SYN) != 0 && (flags and PacketUtils.TCP_ACK) == 0
    val isFin = (flags and PacketUtils.TCP_FIN) != 0
    val isRst = (flags and PacketUtils.TCP_RST) != 0

    if (isSyn) {
      tcpSessions.remove(key)?.close()
      val session = TcpSession(key, srcIpBytes, dstIpBytes, seq + 1)
      tcpSessions[key] = session
      session.start()
      return
    }

    val session = tcpSessions[key] ?: return
    if (isRst) {
      tcpSessions.remove(key)?.close()
      return
    }
    if (payloadLen > 0) {
      val data = packet.copyOfRange(payloadStart, payloadStart + payloadLen)
      session.onClientData(seq, data)
    }
    if (isFin) {
      session.onClientFin(seq + payloadLen)
      tcpSessions.remove(key)
    }
  }

  private fun writeToTun(packet: ByteArray) {
    if (!running.get()) return
    synchronized(writeLock) {
      runCatching {
        tunOut.write(packet)
        bytesReceived.addAndGet(packet.size.toLong())
      }
    }
  }

  override fun close() {
    running.set(false)
    tcpSessions.values.forEach { it.close() }
    tcpSessions.clear()
    scope.cancel()
  }

  private inner class TcpSession(
    private val key: SessionKey,
    private val clientIp: ByteArray,
    private val remoteIp: ByteArray,
    initialClientAck: Long,
  ) : Closeable {

    private var clientNextSeq = initialClientAck
    private var serverSeq = 1000L
    @Volatile private var stream: TunnelStream? = null
    private val closed = AtomicBoolean(false)
    private val pendingWrites = ArrayList<ByteArray>()
    private val streamLock = Any()

    fun start() {
      scope.launch {
        runCatching {
            val s = engine.openTcpStream(key.dstIp, key.dstPort, timeoutMs)
            synchronized(streamLock) {
              if (closed.get()) {
                s.close()
                return@launch
              }
              stream = s
            }
            // Send SYN-ACK once upstream tunnel stream is genuinely open
            writeToTun(
              PacketUtils.buildIpv4TcpPacket(
                srcIp = remoteIp,
                srcPort = key.dstPort,
                dstIp = clientIp,
                dstPort = key.srcPort,
                seq = serverSeq,
                ack = clientNextSeq,
                flags = PacketUtils.TCP_SYN or PacketUtils.TCP_ACK,
              )
            )
            serverSeq += 1

            // Flush any buffered early payload
            synchronized(streamLock) {
              if (pendingWrites.isNotEmpty()) {
                pendingWrites.forEach { chunk -> s.write(chunk) }
                s.flush()
                pendingWrites.clear()
              }
            }

            // Pump downstream data from tunnel -> TUN
            val buf = ByteArray(16384)
            while (!closed.get() && running.get()) {
              val n = s.read(buf, 0, buf.size)
              if (n <= 0) break
              var offset = 0
              while (offset < n) {
                val segLen = minOf(1360, n - offset)
                val seg = buf.copyOfRange(offset, offset + segLen)
                writeToTun(
                  PacketUtils.buildIpv4TcpPacket(
                    srcIp = remoteIp,
                    srcPort = key.dstPort,
                    dstIp = clientIp,
                    dstPort = key.srcPort,
                    seq = serverSeq,
                    ack = clientNextSeq,
                    flags = PacketUtils.TCP_PSH or PacketUtils.TCP_ACK,
                    payload = seg,
                  )
                )
                serverSeq += segLen
                offset += segLen
              }
            }
            sendFin()
          }
          .onFailure {
            sendRst()
          }
        tcpSessions.remove(key)
        close()
      }
    }

    fun onClientData(seq: Long, data: ByteArray) {
      if (closed.get()) return
      if (seq < clientNextSeq) return
      clientNextSeq = seq + data.size
      // Acknowledge client payload immediately
      writeToTun(
        PacketUtils.buildIpv4TcpPacket(
          srcIp = remoteIp,
          srcPort = key.dstPort,
          dstIp = clientIp,
          dstPort = key.srcPort,
          seq = serverSeq,
          ack = clientNextSeq,
          flags = PacketUtils.TCP_ACK,
        )
      )
      scope.launch {
        synchronized(streamLock) {
          val active = stream
          if (active == null) {
            pendingWrites.add(data)
          } else {
            runCatching {
                active.write(data)
                active.flush()
              }
              .onFailure { close() }
          }
        }
      }
    }

    fun onClientFin(finSeq: Long) {
      clientNextSeq = maxOf(clientNextSeq, finSeq + 1)
      writeToTun(
        PacketUtils.buildIpv4TcpPacket(
          srcIp = remoteIp,
          srcPort = key.dstPort,
          dstIp = clientIp,
          dstPort = key.srcPort,
          seq = serverSeq,
          ack = clientNextSeq,
          flags = PacketUtils.TCP_FIN or PacketUtils.TCP_ACK,
        )
      )
      close()
    }

    private fun sendFin() {
      if (closed.get()) return
      writeToTun(
        PacketUtils.buildIpv4TcpPacket(
          srcIp = remoteIp,
          srcPort = key.dstPort,
          dstIp = clientIp,
          dstPort = key.srcPort,
          seq = serverSeq,
          ack = clientNextSeq,
          flags = PacketUtils.TCP_FIN or PacketUtils.TCP_ACK,
        )
      )
    }

    private fun sendRst() {
      if (closed.get()) return
      writeToTun(
        PacketUtils.buildIpv4TcpPacket(
          srcIp = remoteIp,
          srcPort = key.dstPort,
          dstIp = clientIp,
          dstPort = key.srcPort,
          seq = serverSeq,
          ack = clientNextSeq,
          flags = PacketUtils.TCP_RST or PacketUtils.TCP_ACK,
        )
      )
    }

    override fun close() {
      if (closed.compareAndSet(false, true)) {
        runCatching { stream?.close() }
        stream = null
      }
    }
  }
}
