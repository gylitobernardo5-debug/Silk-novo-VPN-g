package com.example.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.model.VpnRuntimeStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object VpnStateHolder {
  private val _stats = MutableStateFlow(VpnRuntimeStats())
  val stats: StateFlow<VpnRuntimeStats> = _stats.asStateFlow()

  val dnsAdBlocker = DnsAdBlocker()

  fun update(transform: (VpnRuntimeStats) -> VpnRuntimeStats) {
    _stats.update(transform)
  }

  fun detectExternalVpn(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    return runCatching {
        @Suppress("DEPRECATION")
        cm.allNetworks.any { network ->
          val caps = cm.getNetworkCapabilities(network)
          caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
      }
      .getOrDefault(false)
  }
}
