package com.example.vpn

import com.example.model.AdBlockCategory
import com.example.model.AdBlockRule
import com.example.model.AppSettings
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Local RFC 1035 DNS packet inspector and blocker.
 * Matches queried domains (exact + parent subdomains) against active Allowlist and Blocklists.
 */
class DnsAdBlocker {

  @Volatile private var blockedSet: Set<String> = emptySet()
  @Volatile private var allowedSet: Set<String> = emptySet()
  @Volatile private var enabled: Boolean = true

  val blockedQueriesCount = AtomicLong(0L)
  val totalQueriesCount = AtomicLong(0L)

  fun updateRules(rules: List<AdBlockRule>, settings: AppSettings) {
    enabled = settings.adBlockEnabled
    val allow = HashSet<String>()
    val block = HashSet<String>()

    for (rule in rules) {
      if (!rule.isEnabled) continue
      val domain = rule.domain.trim().lowercase().trimEnd('.')
      if (domain.isEmpty()) continue
      when (rule.category) {
        AdBlockCategory.ALLOWLIST -> allow.add(domain)
        AdBlockCategory.CUSTOM_BLOCK -> block.add(domain)
        AdBlockCategory.ADS -> if (settings.blockAds) block.add(domain)
        AdBlockCategory.TRACKERS -> if (settings.blockTrackers) block.add(domain)
        AdBlockCategory.MALWARE -> if (settings.blockMalware) block.add(domain)
      }
    }
    allowedSet = allow
    blockedSet = block
  }

  fun parseQueryDomain(dnsPayload: ByteArray): String? {
    if (dnsPayload.size < 12) return null
    val qdCount = ((dnsPayload[4].toInt() and 0xFF) shl 8) or (dnsPayload[5].toInt() and 0xFF)
    if (qdCount < 1) return null
    var idx = 12
    val sb = StringBuilder()
    while (idx < dnsPayload.size) {
      val labelLen = dnsPayload[idx].toInt() and 0xFF
      if (labelLen == 0) break
      if ((labelLen and 0xC0) == 0xC0) return null
      idx++
      if (idx + labelLen > dnsPayload.size) return null
      if (sb.isNotEmpty()) sb.append('.')
      sb.append(String(dnsPayload, idx, labelLen, Charsets.US_ASCII))
      idx += labelLen
    }
    return sb.toString().lowercase().trimEnd('.').takeIf { it.isNotEmpty() }
  }

  fun shouldBlockDomain(domain: String): Boolean {
    if (!enabled) return false
    val clean = domain.trim().lowercase().trimEnd('.')
    if (clean.isEmpty()) return false
    if (matchesDomainOrParent(clean, allowedSet)) return false
    return matchesDomainOrParent(clean, blockedSet)
  }

  private fun matchesDomainOrParent(domain: String, set: Set<String>): Boolean {
    if (set.isEmpty()) return false
    var current = domain
    while (true) {
      if (current in set) return true
      val dot = current.indexOf('.')
      if (dot < 0 || dot + 1 >= current.length) break
      current = current.substring(dot + 1)
    }
    return false
  }

  /**
   * Builds a valid RFC 1035 NXDOMAIN / REFUSED zero-IP DNS reply for blocked domains
   * so Android apps fail fast on ad/tracker requests without hanging.
   */
  fun buildBlockedDnsResponse(query: ByteArray): ByteArray? {
    if (query.size < 12) return null
    var idx = 12
    while (idx < query.size) {
      val len = query[idx].toInt() and 0xFF
      idx++
      if (len == 0) break
      idx += len
    }
    // Need 4 bytes for QTYPE + QCLASS
    if (idx + 4 > query.size) return null
    val questionEnd = idx + 4

    val out = ByteArrayOutputStream(questionEnd)
    // Transaction ID
    out.write(query[0].toInt() and 0xFF)
    out.write(query[1].toInt() and 0xFF)
    // Flags: QR=1 (response), RD copied, RA=1, RCODE=3 (NXDOMAIN)
    val rd = query[2].toInt() and 0x01
    out.write(0x80 or rd)
    out.write(0x83) // RA=1, RCODE=3 (NXDOMAIN)
    // QDCOUNT = 1, ANCOUNT = 0, NSCOUNT = 0, ARCOUNT = 0
    out.write(0); out.write(1)
    out.write(0); out.write(0)
    out.write(0); out.write(0)
    out.write(0); out.write(0)
    // Copy Question section verbatim
    out.write(query, 12, questionEnd - 12)
    return out.toByteArray()
  }

  fun activeRuleCount(): Int = blockedSet.size
}
