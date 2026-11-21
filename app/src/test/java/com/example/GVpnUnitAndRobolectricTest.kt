package com.example

import android.content.Context
import android.util.Base64
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.example.model.AdBlockCategory
import com.example.model.AdBlockRule
import com.example.model.AppSettings
import com.example.model.ProfileSecrets
import com.example.model.ProtocolType
import com.example.model.SecurityType
import com.example.model.TransportType
import com.example.parser.ProfileParser
import com.example.security.CredentialVault
import com.example.vpn.DnsAdBlocker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GVpnUnitAndRobolectricTest {

  @Test
  fun `parse vless vmess ss ssh socks5 links and batch safely`() {
    val vless =
      "vless://11111111-2222-3333-4444-555555555555@vpn.example.com:443?type=ws&security=tls&sni=cdn.example.com&path=%2Fws#VLESS-Node"
    val vmessJson =
      """{"v":"2","ps":"VMess-Node","add":"vm.example.com","port":"8443","id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","aid":"0","scy":"aes-128-gcm","net":"ws","host":"ws.example.com","path":"/v2","tls":"tls","sni":"vm.example.com"}"""
    val vmess =
      "vmess://" + Base64.encodeToString(vmessJson.toByteArray(), Base64.NO_WRAP)
    val ssCred =
      Base64.encodeToString("aes-256-gcm:MySecretPass".toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    val ss = "ss://$ssCred@ss.example.com:8388#SS-Node"
    val ssh = "ssh://gylito:sshpass@ssh.example.com:2222?tls=1&sni=ssh.example.com#SSH-Node"
    val socks = "socks5://user1:pass1@socks.example.com:1080#SOCKS-Node"
    val malformed = "invalid://broken"

    val batch = ProfileParser.parseBatch(listOf(vless, vmess, ss, ssh, socks, malformed).joinToString("\n"))
    assertEquals(5, batch.imported.size)
    assertEquals(1, batch.errors.size)

    val pVless = batch.imported[0]
    assertEquals(ProtocolType.VLESS, pVless.profile.protocol)
    assertEquals(TransportType.WS, pVless.profile.transport)
    assertEquals(SecurityType.TLS, pVless.profile.security)
    assertEquals("cdn.example.com", pVless.profile.sni)
    assertEquals("/ws", pVless.profile.wsPath)
    assertEquals("11111111-2222-3333-4444-555555555555", pVless.secrets.uuid)

    val pVmess = batch.imported[1]
    assertEquals(ProtocolType.VMESS, pVmess.profile.protocol)
    assertEquals("vm.example.com", pVmess.profile.host)
    assertEquals(8443, pVmess.profile.port)

    val pSs = batch.imported[2]
    assertEquals(ProtocolType.SHADOWSOCKS, pSs.profile.protocol)
    assertEquals("aes-256-gcm", pSs.profile.ssMethod)
    assertEquals("MySecretPass", pSs.secrets.password)

    val pSsh = batch.imported[3]
    assertEquals(ProtocolType.SSH, pSsh.profile.protocol)
    assertEquals("gylito", pSsh.profile.sshUsername)
    assertEquals(SecurityType.TLS, pSsh.profile.security)

    val pSocks = batch.imported[4]
    assertEquals(ProtocolType.SOCKS5, pSocks.profile.protocol)
    assertEquals("user1", pSocks.profile.socksUsername)
  }

  @Test
  fun `dns adblocker parses query blocks subdomain and respects allowlist`() {
    val blocker = DnsAdBlocker()
    val rules =
      listOf(
        AdBlockRule(id = "1", domain = "doubleclick.net", category = AdBlockCategory.ADS),
        AdBlockRule(id = "2", domain = "safe.doubleclick.net", category = AdBlockCategory.ALLOWLIST),
      )
    blocker.updateRules(rules, AppSettings(adBlockEnabled = true, blockAds = true))

    assertTrue(blocker.shouldBlockDomain("ad.doubleclick.net"))
    assertFalse(blocker.shouldBlockDomain("safe.doubleclick.net"))
    assertFalse(blocker.shouldBlockDomain("wikipedia.org"))

    // Build minimal DNS query for "ad.doubleclick.net"
    val q =
      byteArrayOf(
        0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        2, 'a'.code.toByte(), 'd'.code.toByte(),
        11, 'd'.code.toByte(), 'o'.code.toByte(), 'u'.code.toByte(), 'b'.code.toByte(),
        'l'.code.toByte(), 'e'.code.toByte(), 'c'.code.toByte(), 'l'.code.toByte(),
        'i'.code.toByte(), 'c'.code.toByte(), 'k'.code.toByte(),
        3, 'n'.code.toByte(), 'e'.code.toByte(), 't'.code.toByte(),
        0, 0x00, 0x01, 0x00, 0x01,
      )
    assertEquals("ad.doubleclick.net", blocker.parseQueryDomain(q))
    val resp = blocker.buildBlockedDnsResponse(q)
    assertNotNull(resp)
    assertEquals(0x12.toByte(), resp!![0])
    assertEquals(0x34.toByte(), resp[1])
    assertEquals(0x83.toByte(), resp[3]) // RCODE=3 NXDOMAIN
  }

  @Test
  fun `credential vault encrypts stores and wipes secrets`() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val vault = CredentialVault(ctx)
    vault.saveSecrets(
      "prof_1",
      ProfileSecrets(password = "topSecret123", uuid = "11111111-2222-3333-4444-555555555555"),
    )
    val loaded = vault.getSecrets("prof_1")
    assertEquals("topSecret123", loaded.password)
    assertEquals("11111111-2222-3333-4444-555555555555", loaded.uuid)

    vault.wipeAll()
    val afterWipe = vault.getSecrets("prof_1")
    assertEquals("", afterWipe.password)
  }

  @get:org.junit.Rule
  val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun `home screen renders power button and bye-style bottom options`() {
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("main_power_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("bottom_nav_editor").assertIsDisplayed()
    composeTestRule.onNodeWithTag("bottom_nav_settings").assertIsDisplayed()
    composeTestRule.onNodeWithTag("bottom_nav_test").assertIsDisplayed()
    composeTestRule.onNodeWithTag("bottom_nav_lists").assertIsDisplayed()
  }
}
