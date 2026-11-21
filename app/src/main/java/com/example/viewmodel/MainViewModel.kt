package com.example.viewmodel

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.VpnRepository
import com.example.model.AdBlockCategory
import com.example.model.AdBlockRule
import com.example.model.AppSettings
import com.example.model.DiagnosticReport
import com.example.model.InstalledAppInfo
import com.example.model.ProfileSecrets
import com.example.model.VpnConnectionState
import com.example.model.VpnProfile
import com.example.model.VpnRuntimeStats
import com.example.parser.BatchImportResult
import com.example.vpn.DiagnosticRunner
import com.example.vpn.GVpnService
import com.example.vpn.VpnStateHolder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
  private val repo = VpnRepository(application)

  val profiles: StateFlow<List<VpnProfile>> =
    repo.profilesFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val adBlockRules: StateFlow<List<AdBlockRule>> =
    repo.adBlockRulesFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val settings: StateFlow<AppSettings> =
    repo.settingsFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

  val activeProfileId: StateFlow<String?> =
    repo.activeProfileIdFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

  val activeProfile: StateFlow<VpnProfile?> =
    combine(profiles, activeProfileId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull()
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

  val vpnStats: StateFlow<VpnRuntimeStats> = VpnStateHolder.stats

  private val _diagnosticReport = MutableStateFlow(DiagnosticReport())
  val diagnosticReport: StateFlow<DiagnosticReport> = _diagnosticReport.asStateFlow()

  private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
  val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

  private val _isUpdatingBlocklist = MutableStateFlow(false)
  val isUpdatingBlocklist: StateFlow<Boolean> = _isUpdatingBlocklist.asStateFlow()

  private val _uiMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
  val uiMessages = _uiMessages.asSharedFlow()

  init {
    viewModelScope.launch {
      repo.ensureSeededBlocklists()
      combine(adBlockRules, settings) { rules, cfg -> rules to cfg }
        .collect { (rules, cfg) ->
          VpnStateHolder.dnsAdBlocker.updateRules(rules, cfg)
        }
    }
    refreshExternalVpnStatus()
  }

  fun refreshExternalVpnStatus() {
    val current = VpnStateHolder.stats.value
    if (current.state != VpnConnectionState.CONNECTED) {
      val external = VpnStateHolder.detectExternalVpn(getApplication())
      VpnStateHolder.update { it.copy(externalVpnDetected = external) }
    }
  }

  fun prepareVpnIntent(): android.content.Intent? = VpnService.prepare(getApplication())

  fun connectActiveOrProfile(profileId: String? = null) {
    viewModelScope.launch {
      val target =
        if (profileId != null) {
          profiles.value.firstOrNull { it.id == profileId }
        } else {
          activeProfile.value
        }
      if (target == null) {
        _uiMessages.tryEmit("Select or create a VPN profile first.")
        return@launch
      }
      repo.setActiveProfileId(target.id)
      GVpnService.start(getApplication(), target.id)
    }
  }

  fun disconnectVpn() {
    GVpnService.stop(getApplication())
  }

  fun selectActiveProfile(profileId: String) {
    viewModelScope.launch { repo.setActiveProfileId(profileId) }
  }

  suspend fun loadProfileForEditor(profileId: String?): Pair<VpnProfile, ProfileSecrets> {
    if (profileId.isNullOrBlank()) return VpnProfile() to ProfileSecrets()
    return repo.getProfileWithSecrets(profileId) ?: (VpnProfile() to ProfileSecrets())
  }

  fun saveProfile(
    profile: VpnProfile,
    secrets: ProfileSecrets,
    selectActive: Boolean = true,
    onSaved: (VpnProfile) -> Unit = {},
  ) {
    viewModelScope.launch {
      repo.saveProfile(profile, secrets, selectActive)
      onSaved(profile)
    }
  }

  fun duplicateProfile(id: String) {
    viewModelScope.launch {
      val copy = repo.duplicateProfile(id)
      if (copy != null) {
        _uiMessages.tryEmit("Duplicated ${copy.displayTitle()}")
      }
    }
  }

  fun toggleFavorite(id: String) {
    viewModelScope.launch { repo.toggleFavorite(id) }
  }

  fun deleteProfile(id: String) {
    viewModelScope.launch {
      if (vpnStats.value.activeProfileId == id && vpnStats.value.state == VpnConnectionState.CONNECTED) {
        GVpnService.stop(getApplication())
      }
      repo.deleteProfile(id)
    }
  }

  fun importFromRawText(
    rawText: String,
    activateFirst: Boolean = true,
    onResult: (BatchImportResult) -> Unit = {},
  ) {
    viewModelScope.launch {
      val res = repo.importFromText(rawText, activateFirst)
      onResult(res)
    }
  }

  fun readClipboardText(): String {
    val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val item = cm?.primaryClip?.getItemAt(0)
    return item?.coerceToText(getApplication())?.toString()?.trim().orEmpty()
  }

  fun copyTextToClipboard(label: String, text: String) {
    val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText(label, text))
    _uiMessages.tryEmit("Copied $label to clipboard")
  }

  fun exportProfileToClipboard(profile: VpnProfile) {
    viewModelScope.launch {
      val uri = repo.exportProfileUri(profile)
      copyTextToClipboard(profile.displayTitle(), uri)
    }
  }

  suspend fun exportProfileJson(profile: VpnProfile, includeSecrets: Boolean): String =
    repo.exportProfileJson(profile, includeSecrets)

  fun updateSettings(transform: (AppSettings) -> AppSettings) {
    viewModelScope.launch { repo.updateSettings(transform) }
  }

  fun toggleAdBlockQuick() {
    viewModelScope.launch {
      repo.updateSettings { it.copy(adBlockEnabled = !it.adBlockEnabled) }
    }
  }

  fun addDomainRule(domain: String, category: AdBlockCategory) {
    viewModelScope.launch { repo.addDomainRule(domain, category) }
  }

  fun toggleDomainRule(rule: AdBlockRule) {
    viewModelScope.launch { repo.toggleDomainRule(rule) }
  }

  fun deleteDomainRule(id: String) {
    viewModelScope.launch { repo.deleteDomainRule(id) }
  }

  fun updateRemoteBlocklists() {
    if (_isUpdatingBlocklist.value) return
    viewModelScope.launch {
      _isUpdatingBlocklist.value = true
      val res = repo.updateRemoteBlocklist()
      _isUpdatingBlocklist.value = false
      res.fold(
        onSuccess = { count -> _uiMessages.tryEmit("Updated $count AdBlock rules") },
        onFailure = { err -> _uiMessages.tryEmit("Blocklist update failed: ${err.message}") },
      )
    }
  }

  fun runDiagnostics(profileId: String? = null) {
    if (_diagnosticReport.value.isRunning) return
    viewModelScope.launch {
      val target =
        if (profileId != null) profiles.value.firstOrNull { it.id == profileId }
        else activeProfile.value
      if (target == null) {
        _uiMessages.tryEmit("Select a profile first to run diagnostics.")
        return@launch
      }
      val secrets = repo.getSecrets(target.id)
      val cfg = repo.settingsFlow.first()
      DiagnosticRunner.runFullDiagnostic(
        context = getApplication(),
        profile = target,
        secrets = secrets,
        settings = cfg,
      ) { progress ->
        _diagnosticReport.value = progress
      }
    }
  }

  fun loadInstalledApps() {
    viewModelScope.launch {
      if (_installedApps.value.isEmpty()) {
        _installedApps.value = repo.loadLaunchableApps()
      }
    }
  }

  fun wipeAllData() {
    viewModelScope.launch {
      if (vpnStats.value.state != VpnConnectionState.DISCONNECTED) {
        GVpnService.stop(getApplication())
      }
      repo.wipeAllProfilesAndCredentials()
      _uiMessages.tryEmit("All profiles and encrypted credentials deleted")
    }
  }
}
