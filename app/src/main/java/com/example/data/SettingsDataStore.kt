package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AccentPreset
import com.example.model.AppLanguage
import com.example.model.AppSettings
import com.example.model.AppsFilterMode
import com.example.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "gvpn_prefs")

class SettingsDataStore(private val context: Context) {

  val settingsFlow: Flow<AppSettings> =
    context.dataStore.data.map { prefs ->
      AppSettings(
        themeMode =
          prefs[KEY_THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.SYSTEM,
        accentPreset =
          prefs[KEY_ACCENT]?.let { runCatching { AccentPreset.valueOf(it) }.getOrNull() }
            ?: AccentPreset.CYAN_TEAL,
        language =
          prefs[KEY_LANGUAGE]?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() }
            ?: AppLanguage.SYSTEM,
        animationsEnabled = prefs[KEY_ANIMATIONS] ?: true,
        autoReconnect = prefs[KEY_AUTO_RECONNECT] ?: true,
        connectTimeoutSeconds = prefs[KEY_TIMEOUT_SEC] ?: 12,
        customDns = prefs[KEY_CUSTOM_DNS] ?: "1.1.1.1",
        ipv4Enabled = prefs[KEY_IPV4] ?: true,
        ipv6Enabled = prefs[KEY_IPV6] ?: false,
        killSwitch = prefs[KEY_KILL_SWITCH] ?: false,
        keepAliveInBackground = prefs[KEY_KEEP_ALIVE] ?: true,
        startOnBoot = prefs[KEY_START_ON_BOOT] ?: false,
        appsFilterMode =
          prefs[KEY_APPS_FILTER_MODE]?.let {
            runCatching { AppsFilterMode.valueOf(it) }.getOrNull()
          } ?: AppsFilterMode.ALL,
        selectedAppPackages = prefs[KEY_SELECTED_APPS] ?: emptySet(),
        adBlockEnabled = prefs[KEY_ADBLOCK_ENABLED] ?: true,
        blockAds = prefs[KEY_ADBLOCK_ADS] ?: true,
        blockTrackers = prefs[KEY_ADBLOCK_TRACKERS] ?: true,
        blockMalware = prefs[KEY_ADBLOCK_MALWARE] ?: true,
        adBlockSelectedAppsOnly = prefs[KEY_ADBLOCK_SELECTED_ONLY] ?: false,
        lastBlocklistUpdateMs = prefs[KEY_LAST_BLOCKLIST_UPDATE] ?: 0L,
      )
    }

  val activeProfileIdFlow: Flow<String?> =
    context.dataStore.data.map { prefs -> prefs[KEY_ACTIVE_PROFILE_ID] }

  suspend fun setActiveProfileId(profileId: String?) {
    context.dataStore.edit { prefs ->
      if (profileId == null) prefs.remove(KEY_ACTIVE_PROFILE_ID)
      else prefs[KEY_ACTIVE_PROFILE_ID] = profileId
    }
  }

  suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
    context.dataStore.edit { prefs ->
      val current =
        AppSettings(
          themeMode =
            prefs[KEY_THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
              ?: ThemeMode.SYSTEM,
          accentPreset =
            prefs[KEY_ACCENT]?.let { runCatching { AccentPreset.valueOf(it) }.getOrNull() }
              ?: AccentPreset.CYAN_TEAL,
          language =
            prefs[KEY_LANGUAGE]?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() }
              ?: AppLanguage.SYSTEM,
          animationsEnabled = prefs[KEY_ANIMATIONS] ?: true,
          autoReconnect = prefs[KEY_AUTO_RECONNECT] ?: true,
          connectTimeoutSeconds = prefs[KEY_TIMEOUT_SEC] ?: 12,
          customDns = prefs[KEY_CUSTOM_DNS] ?: "1.1.1.1",
          ipv4Enabled = prefs[KEY_IPV4] ?: true,
          ipv6Enabled = prefs[KEY_IPV6] ?: false,
          killSwitch = prefs[KEY_KILL_SWITCH] ?: false,
          keepAliveInBackground = prefs[KEY_KEEP_ALIVE] ?: true,
          startOnBoot = prefs[KEY_START_ON_BOOT] ?: false,
          appsFilterMode =
            prefs[KEY_APPS_FILTER_MODE]?.let {
              runCatching { AppsFilterMode.valueOf(it) }.getOrNull()
            } ?: AppsFilterMode.ALL,
          selectedAppPackages = prefs[KEY_SELECTED_APPS] ?: emptySet(),
          adBlockEnabled = prefs[KEY_ADBLOCK_ENABLED] ?: true,
          blockAds = prefs[KEY_ADBLOCK_ADS] ?: true,
          blockTrackers = prefs[KEY_ADBLOCK_TRACKERS] ?: true,
          blockMalware = prefs[KEY_ADBLOCK_MALWARE] ?: true,
          adBlockSelectedAppsOnly = prefs[KEY_ADBLOCK_SELECTED_ONLY] ?: false,
          lastBlocklistUpdateMs = prefs[KEY_LAST_BLOCKLIST_UPDATE] ?: 0L,
        )
      val next = transform(current)
      prefs[KEY_THEME_MODE] = next.themeMode.name
      prefs[KEY_ACCENT] = next.accentPreset.name
      prefs[KEY_LANGUAGE] = next.language.name
      prefs[KEY_ANIMATIONS] = next.animationsEnabled
      prefs[KEY_AUTO_RECONNECT] = next.autoReconnect
      prefs[KEY_TIMEOUT_SEC] = next.connectTimeoutSeconds
      prefs[KEY_CUSTOM_DNS] = next.customDns
      prefs[KEY_IPV4] = next.ipv4Enabled
      prefs[KEY_IPV6] = next.ipv6Enabled
      prefs[KEY_KILL_SWITCH] = next.killSwitch
      prefs[KEY_KEEP_ALIVE] = next.keepAliveInBackground
      prefs[KEY_START_ON_BOOT] = next.startOnBoot
      prefs[KEY_APPS_FILTER_MODE] = next.appsFilterMode.name
      prefs[KEY_SELECTED_APPS] = next.selectedAppPackages
      prefs[KEY_ADBLOCK_ENABLED] = next.adBlockEnabled
      prefs[KEY_ADBLOCK_ADS] = next.blockAds
      prefs[KEY_ADBLOCK_TRACKERS] = next.blockTrackers
      prefs[KEY_ADBLOCK_MALWARE] = next.blockMalware
      prefs[KEY_ADBLOCK_SELECTED_ONLY] = next.adBlockSelectedAppsOnly
      prefs[KEY_LAST_BLOCKLIST_UPDATE] = next.lastBlocklistUpdateMs
    }
  }

  companion object {
    private val KEY_ACTIVE_PROFILE_ID = stringPreferencesKey("active_profile_id")
    private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    private val KEY_ACCENT = stringPreferencesKey("accent_preset")
    private val KEY_LANGUAGE = stringPreferencesKey("language")
    private val KEY_ANIMATIONS = booleanPreferencesKey("animations_enabled")
    private val KEY_AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
    private val KEY_TIMEOUT_SEC = intPreferencesKey("connect_timeout_sec")
    private val KEY_CUSTOM_DNS = stringPreferencesKey("custom_dns")
    private val KEY_IPV4 = booleanPreferencesKey("ipv4_enabled")
    private val KEY_IPV6 = booleanPreferencesKey("ipv6_enabled")
    private val KEY_KILL_SWITCH = booleanPreferencesKey("kill_switch")
    private val KEY_KEEP_ALIVE = booleanPreferencesKey("keep_alive")
    private val KEY_START_ON_BOOT = booleanPreferencesKey("start_on_boot")
    private val KEY_APPS_FILTER_MODE = stringPreferencesKey("apps_filter_mode")
    private val KEY_SELECTED_APPS = stringSetPreferencesKey("selected_app_packages")
    private val KEY_ADBLOCK_ENABLED = booleanPreferencesKey("adblock_enabled")
    private val KEY_ADBLOCK_ADS = booleanPreferencesKey("adblock_ads")
    private val KEY_ADBLOCK_TRACKERS = booleanPreferencesKey("adblock_trackers")
    private val KEY_ADBLOCK_MALWARE = booleanPreferencesKey("adblock_malware")
    private val KEY_ADBLOCK_SELECTED_ONLY = booleanPreferencesKey("adblock_selected_only")
    private val KEY_LAST_BLOCKLIST_UPDATE = longPreferencesKey("last_blocklist_update")
  }
}
