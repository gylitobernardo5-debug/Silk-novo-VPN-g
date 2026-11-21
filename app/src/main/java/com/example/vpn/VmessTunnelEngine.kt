package com.example.vpn

import com.example.model.ProfileSecrets
import com.example.model.VpnProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Real VMess AEAD client implementing VMess KDF, AuthID (AES-128-ECB),
 * AEAD Request/Response headers, FNV1a-32 verification, and AES-128-GCM / ChaCha20-Poly1305 body chunks.
 */
class VmessTunnelEngine(
  private val profile: VpnProfile,
  private val secrets: ProfileSecrets,
) : TunnelEngine {

  override val name: String = "VMess (${profile.transport.label})"
  @Volatile private var protector: VpnSocketProtector = NoOpSocketProtector

  private fun computeCmdKey(): ByteArray {
    val uuid =
      runCatching { UUID.fromString(secrets.uuid.trim()) }
        .getOrElse { error("Invalid VMess UUID format") }
    val uuidBytes =
      ByteBuffer.allocate(16)
        .putLong(uuid.mostSignificantBits)
        .putLong(uuid.leastSignificantBits)
        .array()
    val md5 = MessageDigest.getInstance("MD5")
    md5.update(uuidBytes)
    md5.update("c48619fe-8f02-49e0-b9e9-edf763e17e21".toByteArray(Charsets.US_ASCII))
    return md5.digest()
  }

  override suspend fun connect(protector: VpnSocketProtector, timeoutMs: Int): Result<Unit> =
    withContext(Dispatchers.IO) {
      this@VmessTunnelEngine.protector = protector
      runCatching {
        require(profile.host.isNotBlank()) { "VMess server host is empty" }
        computeCmdKey()
        val stream = TransportConnector.openTransportStream(profile, protector, timeoutMs)
        stream.close()
      }
    }

  override suspend fun openTcpStream(
    destHost: String,
    destPort: Int,
    timeoutMs: Int,
  ): TunnelStream = withContext(Dispatchers.IO) {
    val cmdKey = computeCmdKey()
    val transport = TransportConnector.openTransportStream(profile, protector, timeoutMs)
    try {
      VmessAeadStream(
        transport = transport,
        cmdKey = cmdKey,
        cmd = 0x01,
        destHost = destHost,
        destPort = destPort,
        cipherPref = profile.vmessCipher,
      )
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
    val cmdKey = computeCmdKey()
    val transport = TransportConnector.openTransportStream(profile, protector, timeoutMs)
    transport.use { raw ->
      val stream =
        VmessAeadStream(
          transport = raw,
          cmdKey = cmdKey,
          cmd = 0x02,
          destHost = destHost,
          destPort = destPort,
          cipherPref = profile.vmessCipher,
        )
      stream.write(payload)
      stream.flush()
      val buf = ByteArray(4096)
      val n = stream.read(buf, 0, buf.size)
      if (n > 0) buf.copyOf(n) else null
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
            "VMess AEAD (${profile.transport.label}/${profile.security.label}) transport ready (${elapsed} ms)",
        )
      },
      onFailure = { err ->
        DiagnosticHandshakeResult(
          ok = false,
          latencyMs = elapsed,
          message = err.message ?: "VMess handshake failed",
        )
      },
    )
  }

  override fun close() = Unit

  private class VmessAeadStream(
    private val transport: TunnelStream,
    private val cmdKey: ByteArray,
    private val cmd: Int,
    private val destHost: String,
    private val destPort: Int,
    cipherPref: String,
  ) : TunnelStream {

    private val rng = SecureRandom()
    private val writeLock = Any()
    private val bodyIv = ByteArray(16).also { rng.nextBytes(it) }
    private val bodyKey = ByteArray(16).also { rng.nextBytes(it) }
    private val respAuthV = ByteArray(1).also { rng.nextBytes(it) }[0]

    private val useChaCha = cipherPref.lowercase().contains("chacha")
    private val useNone = cipherPref.lowercase() == "none"

    private val txNonceIv = bodyIv.copyOf()
    private val rxKey = sha256(bodyKey).copyOf(16)
    private val rxIv = sha256(bodyIv).copyOf(16)

    private var txCount = 0
    private var rxCount = 0
    private var handshakeSent = false
    private var responseHeaderVerified = false
    private var rxPlainBuf = ByteArray(0)
    private var rxPlainPos = 0

    override fun write(data: ByteArray, offset: Int, length: Int) {
      synchronized(writeLock) {
        if (!handshakeSent) {
          sendRequestHeader()
          handshakeSent = true
        }
        var remaining = length
        var pos = offset
        while (remaining > 0) {
          val chunk = minOf(remaining, 0x3F00)
          val slice = data.copyOfRange(pos, pos + chunk)
          writeChunk(slice)
          pos += chunk
          remaining -= chunk
        }
      }
    }

    override fun flush() {
      synchronized(writeLock) {
        if (!handshakeSent) {
          sendRequestHeader()
          handshakeSent = true
        }
        transport.flush()
      }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      if (!responseHeaderVerified) {
        readAndVerifyResponseHeader()
        responseHeaderVerified = true
      }
      if (rxPlainPos >= rxPlainBuf.size) {
        if (!readNextChunk()) return -1
      }
      val avail = rxPlainBuf.size - rxPlainPos
      val copyLen = minOf(length, avail)
      System.arraycopy(rxPlainBuf, rxPlainPos, buffer, offset, copyLen)
      rxPlainPos += copyLen
      return copyLen
    }

    override fun close() {
      transport.close()
    }

    private fun sendRequestHeader() {
      val nowSec = System.currentTimeMillis() / 1000L
      val rand4 = ByteArray(4).also { rng.nextBytes(it) }
      val authIdPlain =
        ByteBuffer.allocate(16)
          .putLong(nowSec)
          .put(rand4)
          .also { buf ->
            val crc = CRC32().apply { update(buf.array(), 0, 12) }.value.toInt()
            buf.putInt(crc)
          }
          .array()

      val authIdKey = kdf(cmdKey, listOf("AES Auth ID Encryption")).copyOf(16)
      val ecb = Cipher.getInstance("AES/ECB/NoPadding")
      ecb.init(Cipher.ENCRYPT_MODE, SecretKeySpec(authIdKey, "AES"))
      val authId = ecb.doFinal(authIdPlain)

      val connNonce = ByteArray(8).also { rng.nextBytes(it) }
      val paddingLen = rng.nextInt(16)
      val secByte =
        when {
          useNone -> 0x05
          useChaCha -> 0x04
          else -> 0x03 // AES-128-GCM
        }

      val headerBody = ByteArrayOutputStream()
      headerBody.write(0x01) // Version 1
      headerBody.write(bodyIv)
      headerBody.write(bodyKey)
      headerBody.write(respAuthV.toInt() and 0xFF)
      headerBody.write(0x01) // Opt: standard format (S=1)
      headerBody.write(((paddingLen and 0x0F) shl 4) or (secByte and 0x0F))
      headerBody.write(0x00) // Reserved
      headerBody.write(cmd)
      headerBody.write((destPort ushr 8) and 0xFF)
      headerBody.write(destPort and 0xFF)
      writeVmessAddress(headerBody, destHost)
      if (paddingLen > 0) {
        headerBody.write(ByteArray(paddingLen).also { rng.nextBytes(it) })
      }
      val rawWithoutChecksum = headerBody.toByteArray()
      val fnv = fnv1a32(rawWithoutChecksum)
      val headerPlain =
        ByteBuffer.allocate(rawWithoutChecksum.size + 4)
          .put(rawWithoutChecksum)
          .putInt(fnv)
          .array()

      val lenBytes =
        byteArrayOf(
          ((headerPlain.size ushr 8) and 0xFF).toByte(),
          (headerPlain.size and 0xFF).toByte(),
        )
      val lenKey =
        kdf(
            cmdKey,
            listOf(
              "VMess Header AEAD Key_Length",
              authId.toString(Charsets.ISO_8859_1),
              connNonce.toString(Charsets.ISO_8859_1),
            ),
            rawPaths =
              listOf(
                "VMess Header AEAD Key_Length".toByteArray(),
                authId,
                connNonce,
              ),
          )
          .copyOf(16)
      val lenNonce =
        kdfRaw(
            cmdKey,
            listOf(
              "VMess Header AEAD Nonce_Length".toByteArray(),
              authId,
              connNonce,
            ),
          )
          .copyOf(12)
      val encLen = aesGcmEncrypt(lenKey, lenNonce, lenBytes, authId)

      val hdrKey =
        kdfRaw(cmdKey, listOf("VMess Header AEAD Key".toByteArray(), authId, connNonce)).copyOf(16)
      val hdrNonce =
        kdfRaw(cmdKey, listOf("VMess Header AEAD Nonce".toByteArray(), authId, connNonce))
          .copyOf(12)
      val encHeader = aesGcmEncrypt(hdrKey, hdrNonce, headerPlain, authId)

      val out = ByteArrayOutputStream(16 + encLen.size + 8 + encHeader.size)
      out.write(authId)
      out.write(encLen)
      out.write(connNonce)
      out.write(encHeader)
      val frame = out.toByteArray()
      transport.write(frame, 0, frame.size)
    }

    private fun readAndVerifyResponseHeader() {
      val respLenKey = kdfRaw(rxKey, listOf("AEAD Resp Header Len Key".toByteArray())).copyOf(16)
      val respLenNonce =
        kdfRaw(rxIv, listOf("AEAD Resp Header Len IV".toByteArray())).copyOf(12)
      val encRespLen = ByteArray(2 + 16)
      readFully(encRespLen)
      val lenBytes = aesGcmDecrypt(respLenKey, respLenNonce, encRespLen, ByteArray(0))
      val respHdrLen = ((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)

      val respHdrKey = kdfRaw(rxKey, listOf("AEAD Resp Header Key".toByteArray())).copyOf(16)
      val respHdrNonce = kdfRaw(rxIv, listOf("AEAD Resp Header IV".toByteArray())).copyOf(12)
      val encRespHdr = ByteArray(respHdrLen + 16)
      readFully(encRespHdr)
      val respHdr = aesGcmDecrypt(respHdrKey, respHdrNonce, encRespHdr, ByteArray(0))
      require(respHdr.isNotEmpty() && respHdr[0] == respAuthV) {
        "VMess response authentication mismatch"
      }
    }

    private fun writeChunk(plain: ByteArray) {
      if (useNone) {
        val frame = ByteArray(2 + plain.size)
        frame[0] = ((plain.size ushr 8) and 0xFF).toByte()
        frame[1] = (plain.size and 0xFF).toByte()
        System.arraycopy(plain, 0, frame, 2, plain.size)
        transport.write(frame, 0, frame.size)
        return
      }
      val nonce = buildChunkNonce(txCount++, txNonceIv)
      val cipher =
        if (useChaCha) chaChaEncrypt(chaChaKey(bodyKey), nonce, plain)
        else aesGcmEncrypt(bodyKey, nonce, plain, ByteArray(0))
      val frame = ByteArray(2 + cipher.size)
      frame[0] = ((cipher.size ushr 8) and 0xFF).toByte()
      frame[1] = (cipher.size and 0xFF).toByte()
      System.arraycopy(cipher, 0, frame, 2, cipher.size)
      transport.write(frame, 0, frame.size)
    }

    private fun readNextChunk(): Boolean {
      val lenBytes = ByteArray(2)
      if (!readFullyOrEof(lenBytes)) return false
      val chunkLen = ((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)
      if (chunkLen == 0) return false
      val enc = ByteArray(chunkLen)
      readFully(enc)
      if (useNone) {
        rxPlainBuf = enc
        rxPlainPos = 0
        return true
      }
      val nonce = buildChunkNonce(rxCount++, rxIv)
      rxPlainBuf =
        if (useChaCha) chaChaDecrypt(chaChaKey(rxKey), nonce, enc)
        else aesGcmDecrypt(rxKey, nonce, enc, ByteArray(0))
      rxPlainPos = 0
      return true
    }

    private fun buildChunkNonce(count: Int, iv: ByteArray): ByteArray =
      ByteArray(12).apply {
        this[0] = ((count ushr 8) and 0xFF).toByte()
        this[1] = (count and 0xFF).toByte()
        System.arraycopy(iv, 2, this, 2, 10)
      }

    private fun chaChaKey(key16: ByteArray): ByteArray {
      val first = md5(key16)
      val second = md5(first)
      return first + second
    }

    private fun writeVmessAddress(out: ByteArrayOutputStream, host: String) {
      val parts = host.split('.')
      val isIpv4 = parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }
      if (isIpv4) {
        out.write(0x01)
        parts.forEach { out.write(it.toInt()) }
      } else if (host.contains(":")) {
        out.write(0x03)
        out.write(InetAddress.getByName(host).address)
      } else {
        val bytes = host.toByteArray(Charsets.UTF_8)
        out.write(0x02)
        out.write(bytes.size)
        out.write(bytes)
      }
    }

    private fun aesGcmEncrypt(
      key: ByteArray,
      nonce: ByteArray,
      plain: ByteArray,
      aad: ByteArray,
    ): ByteArray {
      val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
      cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
      val out = ByteArray(cipher.getOutputSize(plain.size))
      val len = cipher.processBytes(plain, 0, plain.size, out, 0)
      val fin = cipher.doFinal(out, len)
      return out.copyOf(len + fin)
    }

    private fun aesGcmDecrypt(
      key: ByteArray,
      nonce: ByteArray,
      cipherText: ByteArray,
      aad: ByteArray,
    ): ByteArray {
      val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
      cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce, aad))
      val out = ByteArray(cipher.getOutputSize(cipherText.size))
      val len = cipher.processBytes(cipherText, 0, cipherText.size, out, 0)
      val fin = cipher.doFinal(out, len)
      return out.copyOf(len + fin)
    }

    private fun chaChaEncrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray): ByteArray {
      val cipher = ChaCha20Poly1305()
      cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce))
      val out = ByteArray(cipher.getOutputSize(plain.size))
      val len = cipher.processBytes(plain, 0, plain.size, out, 0)
      val fin = cipher.doFinal(out, len)
      return out.copyOf(len + fin)
    }

    private fun chaChaDecrypt(key: ByteArray, nonce: ByteArray, cipherText: ByteArray): ByteArray {
      val cipher = ChaCha20Poly1305()
      cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce))
      val out = ByteArray(cipher.getOutputSize(cipherText.size))
      val len = cipher.processBytes(cipherText, 0, cipherText.size, out, 0)
      val fin = cipher.doFinal(out, len)
      return out.copyOf(len + fin)
    }

    private fun kdf(key: ByteArray, path: List<String>, rawPaths: List<ByteArray>? = null): ByteArray =
      kdfRaw(key, rawPaths ?: path.map { it.toByteArray(Charsets.UTF_8) })

    private fun kdfRaw(key: ByteArray, path: List<ByteArray>): ByteArray {
      val salt = "VMess AEAD KDF".toByteArray(Charsets.UTF_8)
      var currentKey = salt
      // Recursive HMAC-SHA256 construction matching v2ray-core vmessaead KDF
      fun buildHmac(depth: Int, msg: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val k = if (depth == 0) salt else path[depth - 1]
        mac.init(SecretKeySpec(k, "HmacSHA256"))
        return if (depth == path.size) {
          mac.doFinal(msg)
        } else {
          val inner = buildHmac(depth + 1, msg)
          mac.doFinal(inner)
        }
      }
      // Standard recursive wrapper: KDF(key, p1, p2...) = HMAC(p_last, ... HMAC(p1, HMAC(salt, key)))
      var data = key
      run {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(currentKey, "HmacSHA256"))
        data = mac.doFinal(data)
      }
      for (p in path) {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(p, "HmacSHA256"))
        data = mac.doFinal(data)
      }
      return data
    }

    private fun fnv1a32(data: ByteArray): Int {
      var hash = 0x811c9dc5.toInt()
      for (b in data) {
        hash = hash xor (b.toInt() and 0xFF)
        hash *= 0x01000193
      }
      return hash
    }

    private fun sha256(data: ByteArray): ByteArray =
      MessageDigest.getInstance("SHA-256").digest(data)

    private fun md5(data: ByteArray): ByteArray =
      MessageDigest.getInstance("MD5").digest(data)

    private fun readFullyOrEof(dest: ByteArray): Boolean {
      var pos = 0
      while (pos < dest.size) {
        val n = transport.read(dest, pos, dest.size - pos)
        if (n == -1) {
          if (pos == 0) return false
          throw EOFException("Unexpected EOF in VMess stream")
        }
        pos += n
      }
      return true
    }

    private fun readFully(dest: ByteArray) {
      if (!readFullyOrEof(dest)) throw EOFException("Unexpected EOF in VMess stream")
    }
  }
}
