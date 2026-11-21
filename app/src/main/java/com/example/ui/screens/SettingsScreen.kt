package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.AccentPreset
import com.example.model.AdBlockRule
import com.example.model.AppLanguage
import com.example.model.AppSettings
import com.example.model.AppsFilterMode
import com.example.model.ThemeMode
import com.example.model.VpnConnectionState
import com.example.model.VpnRuntimeStats
import com.example.ui.theme.JetBrainsMonoFamily
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  settings: AppSettings,
  stats: VpnRuntimeStats,
  adBlockRules: List<AdBlockRule>,
  isUpdatingBlocklist: Boolean,
  onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
  onUpdateBlocklists: () -> Unit,
  onOpenAppFilter: () -> Unit,
  onWipeAllData: () -> Unit,
  onBack: () -> Unit,
) {
  var showThemeDialog by remember { mutableStateOf(false) }
  var showLangDialog by remember { mutableStateOf(false) }
  var showDnsDialog by remember { mutableStateOf(false) }
  var showTimeoutDialog by remember { mutableStateOf(false) }
  var showAppsModeDialog by remember { mutableStateOf(false) }
  var showWipeDialog by remember { mutableStateOf(false) }

  var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
  LaunchedEffect(stats.state, stats.connectedSinceMs) {
    if (stats.state == VpnConnectionState.CONNECTED && stats.connectedSinceMs > 0L) {
      while (true) {
        nowMs = System.currentTimeMillis()
        delay(1000L)
      }
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.settings_title)) },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back_button")) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
          }
        },
      )
    }
  ) { innerPadding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(innerPadding).navigationBarsPadding(),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(
        modifier =
          Modifier.fillMaxSize()
            .widthIn(max = 600.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
      ) {
        // SECTION: GENERAL
        SettingsSectionHeader(stringResource(R.string.settings_sec_general))
        SettingsClickableRow(
          title = stringResource(R.string.settings_theme),
          subtitle =
            when (settings.themeMode) {
              ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
              ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
              ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
            },
          onClick = { showThemeDialog = true },
          testTag = "settings_theme_row",
        )
        // Accent color swatches
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
          Text(
            text = stringResource(R.string.settings_accent_color),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Spacer(Modifier.height(10.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AccentPreset.entries.forEach { preset ->
              val selected = settings.accentPreset == preset
              Box(
                modifier =
                  Modifier.size(36.dp)
                    .clip(CircleShape)
                    .background(Color(preset.primaryHex))
                    .border(
                      width = if (selected) 2.5.dp else 0.dp,
                      color =
                        if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                      shape = CircleShape,
                    )
                    .clickable { onUpdateSettings { it.copy(accentPreset = preset) } }
                    .testTag("accent_${preset.name.lowercase()}"),
                contentAlignment = Alignment.Center,
              ) {
                if (selected) {
                  Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = preset.label,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                  )
                }
              }
            }
          }
        }
        SettingsClickableRow(
          title = stringResource(R.string.settings_language),
          subtitle =
            when (settings.language) {
              AppLanguage.SYSTEM -> stringResource(R.string.settings_lang_system)
              AppLanguage.PT -> stringResource(R.string.settings_lang_pt)
              AppLanguage.EN -> stringResource(R.string.settings_lang_en)
            },
          onClick = { showLangDialog = true },
          testTag = "settings_language_row",
        )
        SettingsSwitchRow(
          title = stringResource(R.string.settings_animations),
          checked = settings.animationsEnabled,
          onCheckedChange = { v -> onUpdateSettings { it.copy(animationsEnabled = v) } },
          testTag = "settings_animations_switch",
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

        // SECTION: CONNECTION
        SettingsSectionHeader(stringResource(R.string.settings_sec_connection))
        SettingsSwitchRow(
          title = stringResource(R.string.settings_auto_reconnect),
          checked = settings.autoReconnect,
          onCheckedChange = { v -> onUpdateSettings { it.copy(autoReconnect = v) } },
          testTag = "settings_autoreconnect_switch",
        )
        SettingsClickableRow(
          title = stringResource(R.string.settings_timeout),
          subtitle = "${settings.connectTimeoutSeconds} s",
          onClick = { showTimeoutDialog = true },
          testTag = "settings_timeout_row",
        )
        SettingsClickableRow(
          title = stringResource(R.string.settings_custom_dns),
          subtitle = settings.customDns,
          onClick = { showDnsDialog = true },
          testTag = "settings_dns_row",
        )
        SettingsSwitchRow(
          title = stringResource(R.string.settings_ipv4),
          checked = settings.ipv4Enabled,
          onCheckedChange = { v -> onUpdateSettings { it.copy(ipv4Enabled = v) } },
        )
        SettingsSwitchRow(
          title = stringResource(R.string.settings_ipv6),
          checked = settings.ipv6Enabled,
          onCheckedChange = { v -> onUpdateSettings { it.copy(ipv6Enabled = v) } },
        )
        SettingsSwitchRow(
          title = stringResource(R.string.settings_kill_switch),
          subtitle = stringResource(R.string.settings_kill_switch_desc),
          checked = settings.killSwitch,
          onCheckedChange = { v -> onUpdateSettings { it.copy(killSwitch = v) } },
        )
        SettingsSwitchRow(
          title = stringResource(R.string.settings_keep_alive),
          checked = settings.keepAliveInBackground,
          onCheckedChange = { v -> onUpdateSettings { it.copy(keepAliveInBackground = v) } },
        )
        SettingsSwitchRow(
          title = stringResource(R.string.settings_start_on_boot),
          checked = settings.startOnBoot,
          onCheckedChange = { v -> onUpdateSettings { it.copy(startOnBoot = v) } },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

        // SECTION: ADBLOCKER
        SettingsSectionHeader(stringResource(R.string.settings_sec_adblock))
        if (stats.externalVpnDetected && stats.state != VpnConnectionState.CONNECTED) {
          Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
          ) {
            Row(
              modifier = Modifier.padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(
                Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
              )
              Text(
                text = stringResource(R.string.vpn_external_conflict),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
              )
            }
          }
        }
        SettingsSwitchRow(
          title = stringResource(R.string.settings_adblock_toggle),
          subtitle = stringResource(R.string.settings_adblock_desc),
          checked = settings.adBlockEnabled,
          onCheckedChange = { v -> onUpdateSettings { it.copy(adBlockEnabled = v) } },
          testTag = "settings_adblock_master_switch",
        )
        SettingsSwitchRow(
          title = stringResource(R.string.settings_adblock_ads),
          checked = settings.blockAds,
          onCheckedChange = { v -> onUpdateSettings { it.copy(blockAds = v) } },
        )
        SettingsSwitchRow(
          title = stringResource(R.string.settings_adblock_trackers),
          checked = settings.blockTrackers,
          onCheckedChange = { v -> onUpdateSettings { it.copy(blockTrackers = v) } },
        )
        SettingsSwitchRow(
          title = stringResource(R.string.settings_adblock_malware),
          checked = settings.blockMalware,
          onCheckedChange = { v -> onUpdateSettings { it.copy(blockMalware = v) } },
        )
        SettingsSwitchRow(
          title = stringResource(R.string.settings_adblock_selected_only),
          checked = settings.adBlockSelectedAppsOnly,
          onCheckedChange = { v -> onUpdateSettings { it.copy(adBlockSelectedAppsOnly = v) } },
        )

        val activeRuleCount = adBlockRules.count { it.isEnabled }
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text =
                "${stringResource(R.string.settings_adblock_blocked_stat)}: ${stats.blockedDnsCount} • ${stringResource(R.string.settings_adblock_rules_stat)}: $activeRuleCount",
              style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFamily),
              color = MaterialTheme.colorScheme.onSurface,
            )
            if (settings.lastBlocklistUpdateMs > 0L) {
              Text(
                text = DateFormat.getDateTimeInstance().format(Date(settings.lastBlocklistUpdateMs)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          OutlinedButton(
            onClick = onUpdateBlocklists,
            enabled = !isUpdatingBlocklist,
            modifier = Modifier.testTag("settings_update_blocklists_button"),
          ) {
            if (isUpdatingBlocklist) {
              CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
              Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.settings_adblock_update))
          }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

        // SECTION: APPS FILTER
        SettingsSectionHeader(stringResource(R.string.settings_sec_apps))
        SettingsClickableRow(
          title = stringResource(R.string.settings_apps_filter_mode),
          subtitle =
            when (settings.appsFilterMode) {
              AppsFilterMode.ALL -> stringResource(R.string.settings_apps_all)
              AppsFilterMode.BLACKLIST -> stringResource(R.string.settings_apps_blacklist)
              AppsFilterMode.WHITELIST -> stringResource(R.string.settings_apps_whitelist)
            },
          onClick = { showAppsModeDialog = true },
          testTag = "settings_apps_mode_row",
        )
        SettingsClickableRow(
          title = stringResource(R.string.settings_apps_select),
          subtitle = "${settings.selectedAppPackages.size} selected",
          onClick = onOpenAppFilter,
          trailingArrow = true,
          testTag = "settings_apps_select_row",
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

        // SECTION: VPN TELEMETRY
        SettingsSectionHeader(stringResource(R.string.settings_sec_vpn_stats))
        val elapsedSec =
          if (stats.state == VpnConnectionState.CONNECTED && stats.connectedSinceMs > 0L) {
            ((nowMs - stats.connectedSinceMs) / 1000L).coerceAtLeast(0L)
          } else 0L

        SettingsStatRow(stringResource(R.string.settings_stat_tunnel), stats.state.name)
        SettingsStatRow(stringResource(R.string.settings_stat_sent), formatBytes(stats.bytesSent))
        SettingsStatRow(
          stringResource(R.string.settings_stat_received),
          formatBytes(stats.bytesReceived),
        )
        SettingsStatRow(
          stringResource(R.string.settings_stat_speed),
          "↑ ${formatBytes(stats.uploadSpeedBps)}/s   ↓ ${formatBytes(stats.downloadSpeedBps)}/s",
        )
        SettingsStatRow(stringResource(R.string.settings_stat_uptime), formatDuration(elapsedSec))

        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

        // SECTION: PRIVACY & SECURITY
        SettingsSectionHeader(stringResource(R.string.settings_sec_privacy))
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .clickable { showWipeDialog = true }
              .minimumInteractiveComponentSize()
              .padding(horizontal = 20.dp, vertical = 14.dp)
              .testTag("settings_wipe_all_row"),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Icon(
            Icons.Rounded.DeleteForever,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
          )
          Text(
            text = stringResource(R.string.settings_wipe_all),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
          )
        }
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
          Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleMedium)
          Spacer(Modifier.height(2.dp))
          Text(
            text = stringResource(R.string.settings_about_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }

  if (showThemeDialog) {
    ChoiceDialog(
      title = stringResource(R.string.settings_theme),
      options =
        listOf(
          ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
          ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
          ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
        ),
      selected = settings.themeMode,
      onSelect = { mode ->
        onUpdateSettings { it.copy(themeMode = mode) }
        showThemeDialog = false
      },
      onDismiss = { showThemeDialog = false },
    )
  }

  if (showLangDialog) {
    ChoiceDialog(
      title = stringResource(R.string.settings_language),
      options =
        listOf(
          AppLanguage.SYSTEM to stringResource(R.string.settings_lang_system),
          AppLanguage.PT to stringResource(R.string.settings_lang_pt),
          AppLanguage.EN to stringResource(R.string.settings_lang_en),
        ),
      selected = settings.language,
      onSelect = { lang ->
        onUpdateSettings { it.copy(language = lang) }
        showLangDialog = false
      },
      onDismiss = { showLangDialog = false },
    )
  }

  if (showAppsModeDialog) {
    ChoiceDialog(
      title = stringResource(R.string.settings_apps_filter_mode),
      options =
        listOf(
          AppsFilterMode.ALL to stringResource(R.string.settings_apps_all),
          AppsFilterMode.BLACKLIST to stringResource(R.string.settings_apps_blacklist),
          AppsFilterMode.WHITELIST to stringResource(R.string.settings_apps_whitelist),
        ),
      selected = settings.appsFilterMode,
      onSelect = { mode ->
        onUpdateSettings { it.copy(appsFilterMode = mode) }
        showAppsModeDialog = false
      },
      onDismiss = { showAppsModeDialog = false },
    )
  }

  if (showDnsDialog) {
    var dnsInput by remember { mutableStateOf(settings.customDns) }
    AlertDialog(
      onDismissRequest = { showDnsDialog = false },
      title = { Text(stringResource(R.string.settings_custom_dns)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = dnsInput,
            onValueChange = { dnsInput = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("1.1.1.1", "8.8.8.8", "9.9.9.9").forEach { preset ->
              OutlinedButton(onClick = { dnsInput = preset }) { Text(preset) }
            }
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            if (dnsInput.isNotBlank()) {
              onUpdateSettings { it.copy(customDns = dnsInput.trim()) }
            }
            showDnsDialog = false
          }
        ) {
          Text(stringResource(R.string.editor_save))
        }
      },
      dismissButton = {
        TextButton(onClick = { showDnsDialog = false }) { Text(stringResource(R.string.cancel)) }
      },
    )
  }

  if (showTimeoutDialog) {
    var sliderVal by remember { mutableFloatStateOf(settings.connectTimeoutSeconds.toFloat()) }
    AlertDialog(
      onDismissRequest = { showTimeoutDialog = false },
      title = { Text(stringResource(R.string.settings_timeout)) },
      text = {
        Column {
          Text("${sliderVal.toInt()} s", style = MaterialTheme.typography.titleMedium)
          Slider(
            value = sliderVal,
            onValueChange = { sliderVal = it },
            valueRange = 4f..30f,
            steps = 25,
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            onUpdateSettings { it.copy(connectTimeoutSeconds = sliderVal.toInt()) }
            showTimeoutDialog = false
          }
        ) {
          Text(stringResource(R.string.editor_save))
        }
      },
      dismissButton = {
        TextButton(onClick = { showTimeoutDialog = false }) { Text(stringResource(R.string.cancel)) }
      },
    )
  }

  if (showWipeDialog) {
    AlertDialog(
      onDismissRequest = { showWipeDialog = false },
      title = { Text(stringResource(R.string.settings_wipe_all)) },
      text = { Text(stringResource(R.string.settings_wipe_confirm)) },
      confirmButton = {
        Button(
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
          onClick = {
            onWipeAllData()
            showWipeDialog = false
          },
        ) {
          Text(stringResource(R.string.editor_delete))
        }
      },
      dismissButton = {
        TextButton(onClick = { showWipeDialog = false }) { Text(stringResource(R.string.cancel)) }
      },
    )
  }
}

@Composable
private fun SettingsSectionHeader(title: String) {
  Text(
    text = title,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp),
  )
}

@Composable
private fun SettingsClickableRow(
  title: String,
  subtitle: String,
  onClick: () -> Unit,
  trailingArrow: Boolean = false,
  testTag: String? = null,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clickable(onClick = onClick)
        .minimumInteractiveComponentSize()
        .padding(horizontal = 20.dp, vertical = 12.dp)
        .let { if (testTag != null) it.testTag(testTag) else it },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
      Spacer(Modifier.height(2.dp))
      Text(
        subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (trailingArrow) {
      Icon(
        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun SettingsSwitchRow(
  title: String,
  subtitle: String? = null,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  testTag: String? = null,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clickable { onCheckedChange(!checked) }
        .minimumInteractiveComponentSize()
        .padding(horizontal = 20.dp, vertical = 10.dp)
        .let { if (testTag != null) it.testTag(testTag) else it },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
      Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
      if (!subtitle.isNullOrBlank()) {
        Spacer(Modifier.height(2.dp))
        Text(
          subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}

@Composable
private fun SettingsStatRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
      text = value,
      style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFamily),
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

@Composable
private fun <T> ChoiceDialog(
  title: String,
  options: List<Pair<T, String>>,
  selected: T,
  onSelect: (T) -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column {
        options.forEach { (value, label) ->
          Row(
            modifier =
              Modifier.fillMaxWidth()
                .clickable { onSelect(value) }
                .minimumInteractiveComponentSize()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(selected = value == selected, onClick = { onSelect(value) })
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge)
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
    },
  )
}

fun formatBytes(bytes: Long): String {
  if (bytes < 1024L) return "$bytes B"
  val kb = bytes / 1024.0
  if (kb < 1024.0) return "%.1f KB".format(kb)
  val mb = kb / 1024.0
  if (mb < 1024.0) return "%.2f MB".format(mb)
  return "%.2f GB".format(mb / 1024.0)
}
