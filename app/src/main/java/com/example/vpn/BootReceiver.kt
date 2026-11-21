package com.example.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.example.data.VpnRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
    val pendingResult = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val repo = VpnRepository(context.applicationContext)
        val settings = repo.settingsFlow.first()
        val activeId = repo.activeProfileIdFlow.first()
        if (settings.startOnBoot && !activeId.isNullOrBlank()) {
          if (VpnService.prepare(context) == null) {
            GVpnService.start(context, activeId)
          }
        }
      } finally {
        pendingResult.finish()
      }
    }
  }
}
