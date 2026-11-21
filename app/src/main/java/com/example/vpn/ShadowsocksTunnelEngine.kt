package com.example.vpn

import com.example.model.ProfileSecrets
import com.example.model.VpnProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Real Shadowsocks AEAD client supporting aes-256-gcm, aes-128-gcm,
 * and chacha20-ietf-poly1305 (RFC 8439 + HKDF-SHA1).
 */
class ShadowsocksTunnelEngine(
  private val profile: VpnProfile,
  private val secrets: ProfileSecrets,
) : TunnelEngine {

  override val name: String = "Shadowsocks (${profile.ssMethod})"
  @Volatile private var protector: VpnSocketProtector = NoOpSocketProtector

  private enum class CipherKind(val keySize: Int, val saltSize: Int, val isChaCha: Boolean) {
    AES_128_GCM(16, 16, false),
    AES_256_GCM(32, 32, false),
    CHACHA20_POLY1305(32, 32, true),
  }

  private fun resolveCipherKind(): CipherKind =
    when (profile.ssMethod.lowercase()) {
      "aes-128-gcm" -> CipherKind.AES_128_GCM
      "aes-256-gcm" -> CipherKind.AES_256_GCM
      "chacha20-ietf-poly1305",
      "chacha20-poly1305" -> CipherKind.CHACHA20_POLY1305
      else -> error("Unsupported Shadowsocks AEAD cipher: ${profile.ssMethod}")
    }

  override suspend fun connect(protector: VpnSocketProtector, timeoutMs: Int): Result<Unit> =
    withContext(Dispatchers.IO) {
      this@ShadowsocksTunnelEngine.protector = protector
      runCatching {
        require(profile.host.isNotBlank()) { "Shadowsocks server host is empty" }
        require(secrets.password.isNotEmpty()) { "Shadowsocks password is required" }
        resolveCipherKind()
        val sock = TransportConnector.openRawSocket(profile.host, profile.port, protector, timeoutMs)
        sock.close()
      }
    }

  override suspend fun openTcpStream(
    destHost: String,
    destPort: Int,
    timeoutMs: Int,
  ): TunnelStream = withContext(Dispatchers.IO) {
    val kind = resolveCipherKind()
    val masterKey = evpBytesToKey(secrets.password, kind.keySize)
    val socket = TransportConnector.openRawSocket(profile.host, profile.port, protector, timeoutMs)
    val stream = ShadowsocksTcpStream(socket, kind, masterKey)
    // Send target address header as initial encrypted payload
    val addrHeader = buildTargetAddressHeader(destHost, destPort)
    stream.write(addrHeader)
    stream.flush()
    stream
  }

  override suspend fun sendUdpQuery(
    destHost: String,
    destPort: Int,
    payload: ByteArray,
    timeoutMs: Int,
  ): ByteArray? = withContext(Dispatchers.IO) {
    val kind = resolveCipherKind()
    val masterKey = evpBytesToKey(secrets.password, kind.keySize)
    val salt = ByteArray(kind.saltSize).also { SecureRandom().nextBytes(it) }
    val subKey = hkdfSha1(masterKey, salt, "ss-subkey".toByteArray(Charsets.US_ASCII), kind.keySize)
    val plain = buildTargetAddressHeader(destHost, destPort) + payload
    val zeroNonce = ByteArray(12)
    val cipherText = aeadEncrypt(kind, subKey, zeroNonce, plain)
    val packetBytes = salt + cipherText

    val ds = DatagramSocket()
    ds.use { sock ->
      check(protector.protectDatagram(sock)) { "Failed to protect Shadowsocks UDP socket" }
      sock.soTimeout = timeoutMs
      val serverAddr = InetAddress.getByName(profile.host)
      sock.send(DatagramPacket(packetBytes, packetBytes.size, serverAddr, profile.port))

      val recvBuf = ByteArray(4096)
      val inPkt = DatagramPacket(recvBuf, recvBuf.size)
      sock.receive(inPkt)
      if (inPkt.length <= kind.saltSize + 16) return@withContext null
      val respSalt = inPkt.data.copyOfRange(0, kind.saltSize)
      val respSubKey =
        hkdfSha1(masterKey, respSalt, "ss-subkey".toByteArray(Charsets.US_ASCII), kind.keySize)
      val decrypted =
        aeadDecrypt(
          kind,
          respSubKey,
          zeroNonce,
          inPkt.data.copyOfRange(kind.saltSize, inPkt.length),
        )
      stripTargetAddressHeader(decrypted)
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
          message = "Shadowsocks (${profile.ssMethod}) socket & key derivation ready (${elapsed} ms)",
        )
      },
      onFailure = { err ->
        DiagnosticHandshakeResult(
          ok = false,
          latencyMs = elapsed,
          message = err.message ?: "Shadowsocks test failed",
        )
      },
    )
  }

  override fun close() = Unit

  private inner class ShadowsocksTcpStream(
    private val socket: Socket,
    private val kind: CipherKind,
    private val masterKey: ByteArray,
  ) : TunnelStream {

    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()
    private val writeLock = Any()

    private val txSalt = ByteArray(kind.saltSize).also { SecureRandom().nextBytes(it) }
    private val txSubKey =
      hkdfSha1(masterKey, txSalt, "ss-subkey".toByteArray(Charsets.US_ASCII), kind.keySize)
    private val txNonce = ByteArray(12)
    private var txSaltSent = false

    private var rxSubKey: ByteArray? = null
    private val rxNonce = ByteArray(12)
    private var rxBuffer = ByteArray(0)
    private var rxPos = 0

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      if (rxPos >= rxBuffer.size) {
        if (!readNextChunk()) return -1
      }
      val avail = rxBuffer.size - rxPos
      val copyLen = minOf(length, avail)
      System.arraycopy(rxBuffer, rxPos, buffer, offset, copyLen)
      rxPos += copyLen
      return copyLen
    }

    private fun readNextChunk(): Boolean {
      if (rxSubKey == null) {
        val salt = ByteArray(kind.saltSize)
        if (!readFullyOrEof(input, salt)) return false
        rxSubKey =
          hkdfSha1(masterKey, salt, "ss-subkey".toByteArray(Charsets.US_ASCII), kind.keySize)
      }
      val key = rxSubKey!!
      val encLen = ByteArray(2 + 16)
      if (!readFullyOrEof(input, encLen)) return false
      val lenBytes = aeadDecrypt(kind, key, rxNonce, encLen)
      incrementNonce(rxNonce)
      val payloadLen = ((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)
      val encPayload = ByteArray(payloadLen + 16)
      readFully(input, encPayload)
      rxBuffer = aeadDecrypt(kind, key, rxNonce, encPayload)
      incrementNonce(rxNonce)
      rxPos = 0
      return true
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
      synchronized(writeLock) {
        if (!txSaltSent) {
          output.write(txSalt)
          txSaltSent = true
        }
        var remaining = length
        var pos = offset
        while (remaining > 0) {
          val chunkLen = minOf(remaining, 0x3FFF)
          val lenPlain =
            byteArrayOf(((chunkLen ushr 8) and 0xFF).toByte(), (chunkLen and 0xFF).toByte())
          val encLen = aeadEncrypt(kind, txSubKey, txNonce, lenPlain)
          incrementNonce(txNonce)
          val encPayload =
            aeadEncrypt(kind, txSubKey, txNonce, data.copyOfRange(pos, pos + chunkLen))
          incrementNonce(txNonce)
          output.write(encLen)
          output.write(encPayload)
          pos += chunkLen
          remaining -= chunkLen
        }
      }
    }

    override fun flush() {
      synchronized(writeLock) { output.flush() }
    }

    override fun close() {
      runCatching { socket.close() }
    }
  }

  private fun aeadEncrypt(
    kind: CipherKind,
    key: ByteArray,
    nonce: ByteArray,
    plain: ByteArray,
  ): ByteArray {
    if (kind.isChaCha) {
      val cipher = ChaCha20Poly1305()
      cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce))
      val out = ByteArray(cipher.getOutputSize(plain.size))
      val len = cipher.processBytes(plain, 0, plain.size, out, 0)
      val fin = cipher.doFinal(out, len)
      return out.copyOf(len + fin)
    } else {
      val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
      cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce))
      val out = ByteArray(cipher.getOutputSize(plain.size))
      val len = cipher.processBytes(plain, 0, plain.size, out, 0)
      val fin = cipher.doFinal(out, len)
      return out.copyOf(len + fin)
    }
  }

  private fun aeadDecrypt(
    kind: CipherKind,
    key: ByteArray,
    nonce: ByteArray,
    cipherText: ByteArray,
  ): ByteArray {
    if (kind.isChaCha) {
      val cipher = ChaCha20Poly1305()
      cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce))
      val out = ByteArray(cipher.getOutputSize(cipherText.size))
      val len = cipher.processBytes(cipherText, 0, cipherText.size, out, 0)
      val fin = cipher.doFinal(out, len)
      return out.copyOf(len + fin)
    } else {
      val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
      cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce))
      val out = ByteArray(cipher.getOutputSize(cipherText.size))
      val len = cipher.processBytes(cipherText, 0, cipherText.size, out, 0)
      val fin = cipher.doFinal(out, len)
      return out.copyOf(len + fin)
    }
  }

  private fun incrementNonce(nonce: ByteArray) {
    for (i in nonce.indices) {
      val v = (nonce[i].toInt() and 0xFF) + 1
      nonce[i] = v.toByte()
      if (v <= 0xFF) break
    }
  }

  private fun evpBytesToKey(password: String, keyLen: Int): ByteArray {
    val md5 = MessageDigest.getInstance("MD5")
    val passBytes = password.toByteArray(Charsets.UTF_8)
    val result = ByteArrayOutputStream()
    var prev: ByteArray? = null
    while (result.size() < keyLen) {
      md5.reset()
      if (prev != null) md5.update(prev)
      md5.update(passBytes)
      prev = md5.digest()
      result.write(prev)
    }
    return result.toByteArray().copyOf(keyLen)
  }

  private fun hkdfSha1(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(salt, "HmacSHA1"))
    val prk = mac.doFinal(ikm)

    val out = ByteArrayOutputStream()
    var t = ByteArray(0)
    var counter = 1
    while (out.size() < length) {
      mac.init(SecretKeySpec(prk, "HmacSHA1"))
      mac.update(t)
      mac.update(info)
      mac.update(counter.toByte())
      t = mac.doFinal()
      out.write(t)
      counter++
    }
    return out.toByteArray().copyOf(length)
  }

  private fun buildTargetAddressHeader(host: String, port: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val ipv4 = parseIpv4(host)
    if (ipv4 != null) {
      out.write(0x01)
      out.write(ipv4)
    } else {
      val domain = host.toByteArray(Charsets.UTF_8)
      out.write(0x03)
      out.write(domain.size)
      out.write(domain)
    }
    out.write((port ushr 8) and 0xFF)
    out.write(port and 0xFF)
    return out.toByteArray()
  }

  private fun stripTargetAddressHeader(data: ByteArray): ByteArray? {
    if (data.size < 4) return null
    val atyp = data[0].toInt() and 0xFF
    val offset =
      when (atyp) {
        0x01 -> 1 + 4 + 2
        0x03 -> 1 + 1 + (data[1].toInt() and 0xFF) + 2
        0x04 -> 1 + 16 + 2
        else -> return null
      }
    if (offset >= data.size) return null
    return data.copyOfRange(offset, data.size)
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

  private fun readFullyOrEof(input: InputStream, dest: ByteArray): Boolean {
    var pos = 0
    while (pos < dest.size) {
      val n = input.read(dest, pos, dest.size - pos)
      if (n == -1) {
        if (pos == 0) return false
        throw EOFException("Unexpected EOF in Shadowsocks stream")
      }
      pos += n
    }
    return true
  }

  private fun readFully(input: InputStream, dest: ByteArray) {
    if (!readFullyOrEof(input, dest)) throw EOFException("Unexpected EOF in Shadowsocks stream")
  }
}
