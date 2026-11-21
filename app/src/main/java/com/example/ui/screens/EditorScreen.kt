package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.ProfileSecrets
import com.example.model.ProtocolType
import com.example.model.SecurityType
import com.example.model.SshAuthMethod
import com.example.model.TransportType
import com.example.model.UpstreamProxyType
import com.example.model.VpnProfile
import com.example.parser.ProfileParser
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
  profileId: String?,
  isExistingProfile: Boolean,
  loadProfile: suspend (String?) -> Pair<VpnProfile, ProfileSecrets>,
  onSave: (VpnProfile, ProfileSecrets, Boolean) -> Unit,
  onSaveAndConnect: (VpnProfile, ProfileSecrets) -> Unit,
  onSaveAndTest: (VpnProfile, ProfileSecrets) -> Unit,
  onDuplicate: (String) -> Unit,
  onDelete: (String) -> Unit,
  onExportUri: (VpnProfile) -> Unit,
  onOpenImportDialog: () -> Unit,
  onBack: () -> Unit,
) {
  var loaded by remember(profileId) { mutableStateOf(false) }
  var profile by remember(profileId) { mutableStateOf(VpnProfile()) }
  var secrets by remember(profileId) { mutableStateOf(ProfileSecrets()) }
  var portText by remember(profileId) { mutableStateOf("443") }
  var proxyPortText by remember(profileId) { mutableStateOf("8080") }
  var alterIdText by remember(profileId) { mutableStateOf("0") }
  var showSecret by remember { mutableStateOf(false) }
  var showMoreMenu by remember { mutableStateOf(false) }
  var validationError by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(profileId) {
    val (p, s) = loadProfile(profileId)
    profile = p
    secrets = s
    portText = p.port.toString()
    proxyPortText = p.upstreamProxyPort.toString()
    alterIdText = p.vmessAlterId.toString()
    loaded = true
  }

  val validationErrMsg = stringResource(R.string.editor_validation_error)

  fun validateAndBuild(): Pair<VpnProfile, ProfileSecrets>? {
    val portNum = portText.trim().toIntOrNull()
    if (profile.host.isBlank() || portNum == null || portNum !in 1..65535) {
      validationError = validationErrMsg
      return null
    }
    val proxyPortNum = proxyPortText.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 8080
    val aidNum = alterIdText.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0

    when (profile.protocol) {
      ProtocolType.SSH -> {
        if (profile.sshUsername.isBlank()) {
          validationError = validationErrMsg
          return null
        }
        val needsPass =
          profile.sshAuthMethod == SshAuthMethod.PASSWORD ||
            profile.sshAuthMethod == SshAuthMethod.PASSWORD_AND_KEY
        val needsKey =
          profile.sshAuthMethod == SshAuthMethod.PUBLIC_KEY ||
            profile.sshAuthMethod == SshAuthMethod.PASSWORD_AND_KEY
        if ((needsPass && secrets.password.isBlank()) || (needsKey && secrets.sshPrivateKey.isBlank())) {
          validationError = validationErrMsg
          return null
        }
      }
      ProtocolType.VLESS,
      ProtocolType.VMESS -> {
        val validUuid = runCatching { UUID.fromString(secrets.uuid.trim()) }.isSuccess
        if (!validUuid) {
          validationError = "Valid UUID required (e.g. xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx)"
          return null
        }
      }
      ProtocolType.SHADOWSOCKS -> {
        if (secrets.password.isEmpty()) {
          validationError = validationErrMsg
          return null
        }
      }
      ProtocolType.SOCKS5 -> Unit
    }

    validationError = null
    return profile.copy(
      host = profile.host.trim(),
      port = portNum,
      upstreamProxyPort = proxyPortNum,
      vmessAlterId = aidNum,
    ) to secrets
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(
            text =
              if (isExistingProfile) stringResource(R.string.editor_edit_profile)
              else stringResource(R.string.editor_new_profile)
          )
        },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("editor_back_button")) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          IconButton(
            onClick = onOpenImportDialog,
            modifier = Modifier.testTag("editor_import_button"),
          ) {
            Icon(Icons.Rounded.ContentPaste, contentDescription = stringResource(R.string.editor_paste_link))
          }
          if (isExistingProfile) {
            Box {
              IconButton(onClick = { showMoreMenu = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "More")
              }
              DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                DropdownMenuItem(
                  text = { Text(stringResource(R.string.editor_duplicate)) },
                  leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                  onClick = {
                    showMoreMenu = false
                    onDuplicate(profile.id)
                  },
                )
                DropdownMenuItem(
                  text = { Text(stringResource(R.string.editor_export)) },
                  leadingIcon = { Icon(Icons.Rounded.ContentPaste, null) },
                  onClick = {
                    showMoreMenu = false
                    validateAndBuild()?.let { (p, _) -> onExportUri(p) }
                  },
                )
                DropdownMenuItem(
                  text = { Text(stringResource(R.string.editor_delete)) },
                  leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                  onClick = {
                    showMoreMenu = false
                    onDelete(profile.id)
                    onBack()
                  },
                )
              }
            }
          }
        },
      )
    }
  ) { innerPadding ->
    if (!loaded) return@Scaffold

    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(innerPadding)
          .navigationBarsPadding(),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Column(
        modifier =
          Modifier.weight(1f)
            .widthIn(max = 600.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        // Protocol tabs
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          ProtocolType.entries.forEach { proto ->
            FilterChip(
              selected = profile.protocol == proto,
              onClick = {
                val updatedPort =
                  if (portText == profile.protocol.defaultPort.toString() || portText.isBlank()) {
                    proto.defaultPort.toString()
                  } else portText
                portText = updatedPort
                profile = profile.copy(protocol = proto, port = updatedPort.toIntOrNull() ?: proto.defaultPort)
              },
              label = { Text(proto.label) },
              modifier = Modifier.testTag("editor_protocol_${proto.name.lowercase()}"),
            )
          }
        }

        // Common fields
        OutlinedTextField(
          value = profile.name,
          onValueChange = { profile = profile.copy(name = it) },
          label = { Text(stringResource(R.string.editor_profile_name)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("editor_name_input"),
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          OutlinedTextField(
            value = profile.host,
            onValueChange = { profile = profile.copy(host = it) },
            label = { Text(stringResource(R.string.editor_server_host)) },
            singleLine = true,
            modifier = Modifier.weight(2.2f).testTag("editor_host_input"),
          )
          OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter(Char::isDigit).take(5) },
            label = { Text(stringResource(R.string.editor_port)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f).testTag("editor_port_input"),
          )
        }

        // Protocol-specific fields (cleanly separated per protocol)
        when (profile.protocol) {
          ProtocolType.SSH -> SshProtocolFields(
            profile = profile,
            secrets = secrets,
            proxyPortText = proxyPortText,
            showSecret = showSecret,
            onToggleSecret = { showSecret = !showSecret },
            onProfileChange = { profile = it },
            onSecretsChange = { secrets = it },
            onProxyPortChange = { proxyPortText = it },
          )
          ProtocolType.VLESS -> VlessProtocolFields(
            profile = profile,
            secrets = secrets,
            showSecret = showSecret,
            onToggleSecret = { showSecret = !showSecret },
            onProfileChange = { profile = it },
            onSecretsChange = { secrets = it },
          )
          ProtocolType.VMESS -> VmessProtocolFields(
            profile = profile,
            secrets = secrets,
            alterIdText = alterIdText,
            showSecret = showSecret,
            onToggleSecret = { showSecret = !showSecret },
            onProfileChange = { profile = it },
            onSecretsChange = { secrets = it },
            onAlterIdChange = { alterIdText = it },
          )
          ProtocolType.SHADOWSOCKS -> ShadowsocksProtocolFields(
            profile = profile,
            secrets = secrets,
            showSecret = showSecret,
            onToggleSecret = { showSecret = !showSecret },
            onProfileChange = { profile = it },
            onSecretsChange = { secrets = it },
          )
          ProtocolType.SOCKS5 -> Socks5ProtocolFields(
            profile = profile,
            secrets = secrets,
            showSecret = showSecret,
            onToggleSecret = { showSecret = !showSecret },
            onProfileChange = { profile = it },
            onSecretsChange = { secrets = it },
          )
        }

        if (validationError != null) {
          Text(
            text = validationError!!,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }

        Spacer(Modifier.height(12.dp))
      }

      // Bottom action bar: Save, Test, Save & Connect
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .widthIn(max = 600.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedButton(
          onClick = { validateAndBuild()?.let { (p, s) -> onSaveAndTest(p, s) } },
          modifier = Modifier.testTag("editor_test_button"),
        ) {
          Icon(Icons.Rounded.Speed, contentDescription = null)
          Text(stringResource(R.string.editor_test_profile), modifier = Modifier.padding(start = 4.dp))
        }
        OutlinedButton(
          onClick = { validateAndBuild()?.let { (p, s) -> onSave(p, s, true) } },
          modifier = Modifier.weight(1f).testTag("editor_save_button"),
        ) {
          Icon(Icons.Rounded.Save, contentDescription = null)
          Text(stringResource(R.string.editor_save), modifier = Modifier.padding(start = 4.dp))
        }
        Button(
          onClick = { validateAndBuild()?.let { (p, s) -> onSaveAndConnect(p, s) } },
          modifier = Modifier.weight(1.2f).testTag("editor_connect_button"),
        ) {
          Icon(Icons.Rounded.PlayArrow, contentDescription = null)
          Text(stringResource(R.string.editor_save_and_connect), modifier = Modifier.padding(start = 4.dp))
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SshProtocolFields(
  profile: VpnProfile,
  secrets: ProfileSecrets,
  proxyPortText: String,
  showSecret: Boolean,
  onToggleSecret: () -> Unit,
  onProfileChange: (VpnProfile) -> Unit,
  onSecretsChange: (ProfileSecrets) -> Unit,
  onProxyPortChange: (String) -> Unit,
) {
  OutlinedTextField(
    value = profile.sshUsername,
    onValueChange = { onProfileChange(profile.copy(sshUsername = it)) },
    label = { Text(stringResource(R.string.editor_username)) },
    singleLine = true,
    modifier = Modifier.fillMaxWidth().testTag("ssh_username_input"),
  )

  Text(stringResource(R.string.editor_ssh_auth_method), style = MaterialTheme.typography.labelLarge)
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    SshAuthMethod.entries.forEach { method ->
      FilterChip(
        selected = profile.sshAuthMethod == method,
        onClick = { onProfileChange(profile.copy(sshAuthMethod = method)) },
        label = { Text(method.label) },
      )
    }
  }

  if (
    profile.sshAuthMethod == SshAuthMethod.PASSWORD ||
      profile.sshAuthMethod == SshAuthMethod.PASSWORD_AND_KEY
  ) {
    SecretTextField(
      value = secrets.password,
      onValueChange = { onSecretsChange(secrets.copy(password = it)) },
      label = stringResource(R.string.editor_password),
      showSecret = showSecret,
      onToggleSecret = onToggleSecret,
      testTag = "ssh_password_input",
    )
  }

  if (
    profile.sshAuthMethod == SshAuthMethod.PUBLIC_KEY ||
      profile.sshAuthMethod == SshAuthMethod.PASSWORD_AND_KEY
  ) {
    OutlinedTextField(
      value = secrets.sshPrivateKey,
      onValueChange = { onSecretsChange(secrets.copy(sshPrivateKey = it)) },
      label = { Text(stringResource(R.string.editor_ssh_key)) },
      minLines = 3,
      maxLines = 6,
      textStyle = MaterialTheme.typography.labelSmall,
      modifier = Modifier.fillMaxWidth().testTag("ssh_key_input"),
    )
    SecretTextField(
      value = secrets.sshPassphrase,
      onValueChange = { onSecretsChange(secrets.copy(sshPassphrase = it)) },
      label = stringResource(R.string.editor_ssh_passphrase),
      showSecret = showSecret,
      onToggleSecret = onToggleSecret,
      testTag = "ssh_passphrase_input",
    )
  }

  Text(stringResource(R.string.editor_upstream_proxy), style = MaterialTheme.typography.labelLarge)
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    UpstreamProxyType.entries.forEach { proxy ->
      FilterChip(
        selected = profile.upstreamProxyType == proxy,
        onClick = { onProfileChange(profile.copy(upstreamProxyType = proxy)) },
        label = { Text(proxy.label) },
      )
    }
  }

  if (profile.upstreamProxyType != UpstreamProxyType.NONE) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
      OutlinedTextField(
        value = profile.upstreamProxyHost,
        onValueChange = { onProfileChange(profile.copy(upstreamProxyHost = it)) },
        label = { Text(stringResource(R.string.editor_proxy_host)) },
        singleLine = true,
        modifier = Modifier.weight(2f),
      )
      OutlinedTextField(
        value = proxyPortText,
        onValueChange = { onProxyPortChange(it.filter(Char::isDigit).take(5)) },
        label = { Text(stringResource(R.string.editor_proxy_port)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.weight(1f),
      )
    }
  }

  TlsSectionFields(profile = profile, onProfileChange = onProfileChange)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VlessProtocolFields(
  profile: VpnProfile,
  secrets: ProfileSecrets,
  showSecret: Boolean,
  onToggleSecret: () -> Unit,
  onProfileChange: (VpnProfile) -> Unit,
  onSecretsChange: (ProfileSecrets) -> Unit,
) {
  SecretTextField(
    value = secrets.uuid,
    onValueChange = { onSecretsChange(secrets.copy(uuid = it)) },
    label = stringResource(R.string.editor_uuid),
    showSecret = showSecret,
    onToggleSecret = onToggleSecret,
    testTag = "vless_uuid_input",
  )

  Text(stringResource(R.string.editor_transport), style = MaterialTheme.typography.labelLarge)
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    TransportType.entries.forEach { t ->
      FilterChip(
        selected = profile.transport == t,
        onClick = { onProfileChange(profile.copy(transport = t)) },
        label = { Text(t.label) },
      )
    }
  }

  if (profile.transport != TransportType.TCP) {
    OutlinedTextField(
      value = profile.wsPath,
      onValueChange = { onProfileChange(profile.copy(wsPath = it)) },
      label = { Text(stringResource(R.string.editor_ws_path)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = profile.wsHost,
      onValueChange = { onProfileChange(profile.copy(wsHost = it)) },
      label = { Text(stringResource(R.string.editor_ws_host)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
  }

  TlsSectionFields(profile = profile, onProfileChange = onProfileChange)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VmessProtocolFields(
  profile: VpnProfile,
  secrets: ProfileSecrets,
  alterIdText: String,
  showSecret: Boolean,
  onToggleSecret: () -> Unit,
  onProfileChange: (VpnProfile) -> Unit,
  onSecretsChange: (ProfileSecrets) -> Unit,
  onAlterIdChange: (String) -> Unit,
) {
  SecretTextField(
    value = secrets.uuid,
    onValueChange = { onSecretsChange(secrets.copy(uuid = it)) },
    label = stringResource(R.string.editor_uuid),
    showSecret = showSecret,
    onToggleSecret = onToggleSecret,
    testTag = "vmess_uuid_input",
  )

  Text(stringResource(R.string.editor_vmess_cipher), style = MaterialTheme.typography.labelLarge)
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none").forEach { cipher ->
      FilterChip(
        selected = profile.vmessCipher == cipher,
        onClick = { onProfileChange(profile.copy(vmessCipher = cipher)) },
        label = { Text(cipher) },
      )
    }
  }

  OutlinedTextField(
    value = alterIdText,
    onValueChange = { onAlterIdChange(it.filter(Char::isDigit).take(4)) },
    label = { Text(stringResource(R.string.editor_vmess_alter_id)) },
    singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    modifier = Modifier.fillMaxWidth(),
  )

  Text(stringResource(R.string.editor_transport), style = MaterialTheme.typography.labelLarge)
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    TransportType.entries.forEach { t ->
      FilterChip(
        selected = profile.transport == t,
        onClick = { onProfileChange(profile.copy(transport = t)) },
        label = { Text(t.label) },
      )
    }
  }

  if (profile.transport != TransportType.TCP) {
    OutlinedTextField(
      value = profile.wsPath,
      onValueChange = { onProfileChange(profile.copy(wsPath = it)) },
      label = { Text(stringResource(R.string.editor_ws_path)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = profile.wsHost,
      onValueChange = { onProfileChange(profile.copy(wsHost = it)) },
      label = { Text(stringResource(R.string.editor_ws_host)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
  }

  TlsSectionFields(profile = profile, onProfileChange = onProfileChange)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShadowsocksProtocolFields(
  profile: VpnProfile,
  secrets: ProfileSecrets,
  showSecret: Boolean,
  onToggleSecret: () -> Unit,
  onProfileChange: (VpnProfile) -> Unit,
  onSecretsChange: (ProfileSecrets) -> Unit,
) {
  Text(stringResource(R.string.editor_ss_method), style = MaterialTheme.typography.labelLarge)
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    ProfileParser.SUPPORTED_SS_METHODS.forEach { method ->
      FilterChip(
        selected = profile.ssMethod == method,
        onClick = { onProfileChange(profile.copy(ssMethod = method)) },
        label = { Text(method) },
      )
    }
  }

  SecretTextField(
    value = secrets.password,
    onValueChange = { onSecretsChange(secrets.copy(password = it)) },
    label = stringResource(R.string.editor_password),
    showSecret = showSecret,
    onToggleSecret = onToggleSecret,
    testTag = "ss_password_input",
  )
}

@Composable
private fun Socks5ProtocolFields(
  profile: VpnProfile,
  secrets: ProfileSecrets,
  showSecret: Boolean,
  onToggleSecret: () -> Unit,
  onProfileChange: (VpnProfile) -> Unit,
  onSecretsChange: (ProfileSecrets) -> Unit,
) {
  OutlinedTextField(
    value = profile.socksUsername,
    onValueChange = { onProfileChange(profile.copy(socksUsername = it)) },
    label = { Text("${stringResource(R.string.editor_username)} (optional)") },
    singleLine = true,
    modifier = Modifier.fillMaxWidth(),
  )
  SecretTextField(
    value = secrets.password,
    onValueChange = { onSecretsChange(secrets.copy(password = it)) },
    label = "${stringResource(R.string.editor_password)} (optional)",
    showSecret = showSecret,
    onToggleSecret = onToggleSecret,
    testTag = "socks_password_input",
  )
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(stringResource(R.string.editor_socks_udp), style = MaterialTheme.typography.bodyLarge)
    Switch(
      checked = profile.socksUdpEnabled,
      onCheckedChange = { onProfileChange(profile.copy(socksUdpEnabled = it)) },
    )
  }
}

@Composable
private fun TlsSectionFields(
  profile: VpnProfile,
  onProfileChange: (VpnProfile) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(stringResource(R.string.editor_tls_enabled), style = MaterialTheme.typography.bodyLarge)
    Switch(
      checked = profile.security == SecurityType.TLS,
      onCheckedChange = { enabled ->
        onProfileChange(profile.copy(security = if (enabled) SecurityType.TLS else SecurityType.NONE))
      },
    )
  }

  if (profile.security == SecurityType.TLS) {
    OutlinedTextField(
      value = profile.sni,
      onValueChange = { onProfileChange(profile.copy(sni = it)) },
      label = { Text(stringResource(R.string.editor_sni)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = profile.alpn,
      onValueChange = { onProfileChange(profile.copy(alpn = it)) },
      label = { Text(stringResource(R.string.editor_alpn)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(stringResource(R.string.editor_allow_insecure), style = MaterialTheme.typography.bodyMedium)
      Switch(
        checked = profile.allowInsecure,
        onCheckedChange = { onProfileChange(profile.copy(allowInsecure = it)) },
      )
    }
  }
}

@Composable
private fun SecretTextField(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  showSecret: Boolean,
  onToggleSecret: () -> Unit,
  testTag: String,
) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    label = { Text(label) },
    singleLine = true,
    visualTransformation =
      if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
    trailingIcon = {
      IconButton(onClick = onToggleSecret) {
        Icon(
          imageVector = if (showSecret) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
          contentDescription = null,
        )
      }
    },
    modifier = Modifier.fillMaxWidth().testTag(testTag),
  )
}
