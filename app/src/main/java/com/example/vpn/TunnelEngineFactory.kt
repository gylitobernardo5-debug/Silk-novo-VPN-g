package com.example.vpn

import com.example.model.ProfileSecrets
import com.example.model.ProtocolType
import com.example.model.VpnProfile

object TunnelEngineFactory {
  fun create(profile: VpnProfile, secrets: ProfileSecrets): TunnelEngine =
    when (profile.protocol) {
      ProtocolType.SSH -> SshTunnelEngine(profile, secrets)
      ProtocolType.VLESS -> VlessTunnelEngine(profile, secrets)
      ProtocolType.VMESS -> VmessTunnelEngine(profile, secrets)
      ProtocolType.SHADOWSOCKS -> ShadowsocksTunnelEngine(profile, secrets)
      ProtocolType.SOCKS5 -> Socks5TunnelEngine(profile, secrets)
    }
}
