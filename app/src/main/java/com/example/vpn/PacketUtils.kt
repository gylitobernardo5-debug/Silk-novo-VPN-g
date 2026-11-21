package com.example.vpn

/**
 * IPv4, UDP, and TCP raw packet utilities for Android VpnService TUN packet routing.
 */
object PacketUtils {
  const val PROTO_ICMP = 1
  const val PROTO_TCP = 6
  const val PROTO_UDP = 17

  const val TCP_FIN = 0x01
  const val TCP_SYN = 0x02
  const val TCP_RST = 0x04
  const val TCP_PSH = 0x08
  const val TCP_ACK = 0x10

  fun ipVersion(packet: ByteArray, length: Int): Int =
    if (length < 1) 0 else (packet[0].toInt() ushr 4) and 0x0F

  fun ipv4HeaderLength(packet: ByteArray): Int = (packet[0].toInt() and 0x0F) * 4

  fun ipv4Protocol(packet: ByteArray): Int = packet[9].toInt() and 0xFF

  fun ipv4AddressString(packet: ByteArray, offset: Int): String =
    "${packet[offset].toInt() and 0xFF}.${packet[offset + 1].toInt() and 0xFF}." +
      "${packet[offset + 2].toInt() and 0xFF}.${packet[offset + 3].toInt() and 0xFF}"

  fun readUInt16(buf: ByteArray, offset: Int): Int =
    ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

  fun writeUInt16(buf: ByteArray, offset: Int, value: Int) {
    buf[offset] = ((value ushr 8) and 0xFF).toByte()
    buf[offset + 1] = (value and 0xFF).toByte()
  }

  fun readUInt32(buf: ByteArray, offset: Int): Long =
    ((buf[offset].toLong() and 0xFF) shl 24) or
      ((buf[offset + 1].toLong() and 0xFF) shl 16) or
      ((buf[offset + 2].toLong() and 0xFF) shl 8) or
      (buf[offset + 3].toLong() and 0xFF)

  fun writeUInt32(buf: ByteArray, offset: Int, value: Long) {
    buf[offset] = ((value ushr 24) and 0xFF).toByte()
    buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
    buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
    buf[offset + 3] = (value and 0xFF).toByte()
  }

  fun buildIpv4UdpPacket(
    srcIp: ByteArray,
    srcPort: Int,
    dstIp: ByteArray,
    dstPort: Int,
    payload: ByteArray,
  ): ByteArray {
    val totalLen = 20 + 8 + payload.size
    val pkt = ByteArray(totalLen)
    // IPv4 Header
    pkt[0] = 0x45.toByte()
    pkt[1] = 0x00
    writeUInt16(pkt, 2, totalLen)
    writeUInt16(pkt, 4, (System.nanoTime() and 0xFFFF).toInt())
    pkt[6] = 0x40.toByte() // Don't fragment
    pkt[7] = 0x00
    pkt[8] = 64 // TTL
    pkt[9] = PROTO_UDP.toByte()
    System.arraycopy(srcIp, 0, pkt, 12, 4)
    System.arraycopy(dstIp, 0, pkt, 16, 4)
    writeUInt16(pkt, 10, checksum(pkt, 0, 20))

    // UDP Header
    writeUInt16(pkt, 20, srcPort)
    writeUInt16(pkt, 22, dstPort)
    writeUInt16(pkt, 24, 8 + payload.size)
    System.arraycopy(payload, 0, pkt, 28, payload.size)
    writeUInt16(pkt, 26, transportChecksum(pkt, 12, 16, PROTO_UDP, 20, 8 + payload.size))
    return pkt
  }

  fun buildIpv4TcpPacket(
    srcIp: ByteArray,
    srcPort: Int,
    dstIp: ByteArray,
    dstPort: Int,
    seq: Long,
    ack: Long,
    flags: Int,
    window: Int = 65535,
    payload: ByteArray = ByteArray(0),
  ): ByteArray {
    val totalLen = 20 + 20 + payload.size
    val pkt = ByteArray(totalLen)
    // IPv4 Header
    pkt[0] = 0x45.toByte()
    pkt[1] = 0x00
    writeUInt16(pkt, 2, totalLen)
    writeUInt16(pkt, 4, (System.nanoTime() and 0xFFFF).toInt())
    pkt[6] = 0x40.toByte()
    pkt[7] = 0x00
    pkt[8] = 64
    pkt[9] = PROTO_TCP.toByte()
    System.arraycopy(srcIp, 0, pkt, 12, 4)
    System.arraycopy(dstIp, 0, pkt, 16, 4)
    writeUInt16(pkt, 10, checksum(pkt, 0, 20))

    // TCP Header (20 bytes)
    writeUInt16(pkt, 20, srcPort)
    writeUInt16(pkt, 22, dstPort)
    writeUInt32(pkt, 24, seq)
    writeUInt32(pkt, 28, ack)
    pkt[32] = 0x50.toByte() // data offset 5 (20 bytes)
    pkt[33] = flags.toByte()
    writeUInt16(pkt, 34, window)
    if (payload.isNotEmpty()) {
      System.arraycopy(payload, 0, pkt, 40, payload.size)
    }
    writeUInt16(pkt, 36, transportChecksum(pkt, 12, 16, PROTO_TCP, 20, 20 + payload.size))
    return pkt
  }

  private fun checksum(buf: ByteArray, offset: Int, length: Int): Int {
    var sum = 0L
    var i = offset
    val end = offset + length
    while (i + 1 < end) {
      sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
      i += 2
    }
    if (i < end) {
      sum += (buf[i].toInt() and 0xFF) shl 8
    }
    while ((sum ushr 16) != 0L) {
      sum = (sum and 0xFFFF) + (sum ushr 16)
    }
    return (sum.inv() and 0xFFFF).toInt()
  }

  private fun transportChecksum(
    pkt: ByteArray,
    srcIpOffset: Int,
    dstIpOffset: Int,
    proto: Int,
    segmentOffset: Int,
    segmentLength: Int,
  ): Int {
    var sum = 0L
    for (i in 0 until 4 step 2) {
      sum +=
        ((pkt[srcIpOffset + i].toInt() and 0xFF) shl 8) or
          (pkt[srcIpOffset + i + 1].toInt() and 0xFF)
      sum +=
        ((pkt[dstIpOffset + i].toInt() and 0xFF) shl 8) or
          (pkt[dstIpOffset + i + 1].toInt() and 0xFF)
    }
    sum += proto.toLong()
    sum += segmentLength.toLong()

    var i = segmentOffset
    val end = segmentOffset + segmentLength
    while (i + 1 < end) {
      sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
      i += 2
    }
    if (i < end) {
      sum += (pkt[i].toInt() and 0xFF) shl 8
    }
    while ((sum ushr 16) != 0L) {
      sum = (sum and 0xFFFF) + (sum ushr 16)
    }
    return (sum.inv() and 0xFFFF).toInt()
  }
}
