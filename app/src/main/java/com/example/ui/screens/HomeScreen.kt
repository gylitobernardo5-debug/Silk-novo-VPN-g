package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.AppSettings
import com.example.model.VpnConnectionState
import com.example.model.VpnProfile
import com.example.model.VpnRuntimeStats
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StatusConnected
import com.example.ui.theme.StatusConnecting
import com.example.ui.theme.StatusError
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
  stats: VpnRuntimeStats,
  activeProfile: VpnProfile?,
  allProfiles: List<VpnProfile>,
  settings: AppSettings,
  onToggleConnection: () -> Unit,
  onSelectProfile: (String) -> Unit,
  onToggleAdBlock: () -> Unit,
  onOpenImport: () -> Unit,
  onOpenEditor: (String?) -> Unit,
  onOpenSettings: () -> Unit,
  onOpenTest: () -> Unit,
  onOpenLists: () -> Unit,
) {
  var showProfilePicker by remember { mutableStateOf(false) }

  // Live uptime ticker while connected
  var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(stats.state, stats.connectedSinceMs) {
    if (stats.state == VpnConnectionState.CONNECTED && stats.connectedSinceMs > 0L) {
      while (true) {
        nowMs = System.currentTimeMillis()
        delay(1000L)
      }
    }
  }

  val elapsedSeconds =
    if (stats.state == VpnConnectionState.CONNECTED && stats.connectedSinceMs > 0L) {
      ((nowMs - stats.connectedSinceMs) / 1000L).coerceAtLeast(0L)
    } else 0L

  val stateLabel =
    when (stats.state) {
      VpnConnectionState.DISCONNECTED -> stringResource(R.string.state_disconnected)
      VpnConnectionState.CONNECTING -> stringResource(R.string.state_connecting)
      VpnConnectionState.CONNECTED -> stringResource(R.string.state_connected)
      VpnConnectionState.ERROR -> stringResource(R.string.state_error)
    }

  val stateColor by
    animateColorAsState(
      targetValue =
        when (stats.state) {
          VpnConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
          VpnConnectionState.CONNECTING -> StatusConnecting
          VpnConnectionState.CONNECTED -> StatusConnected
          VpnConnectionState.ERROR -> StatusError
        },
      animationSpec = tween(220),
      label = "vpnStateColor",
    )

  Column(
    modifier =
      Modifier.fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .statusBarsPadding(),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    // Clean minimal top header
    Row(
      modifier =
        Modifier.fillMaxWidth()
          .widthIn(max = 600.dp)
          .padding(horizontal = 20.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
          )
          Spacer(Modifier.width(8.dp))
          Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(6.dp),
          ) {
            Text(
              text = stringResource(R.string.dev_signature),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            )
          }
        }
      }

      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // AdBlock quick toggle pill
        Surface(
          shape = RoundedCornerShape(50),
          color =
            if (settings.adBlockEnabled) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
          modifier =
            Modifier.clip(RoundedCornerShape(50))
              .clickable(onClick = onToggleAdBlock)
              .minimumInteractiveComponentSize()
              .testTag("home_adblock_pill"),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.Shield,
              contentDescription = null,
              tint =
                if (settings.adBlockEnabled) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(16.dp),
            )
            Text(
              text =
                if (settings.adBlockEnabled) stringResource(R.string.home_adblock_badge)
                else stringResource(R.string.home_adblock_off_badge),
              style = MaterialTheme.typography.labelMedium,
              color =
                if (settings.adBlockEnabled) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        IconButton(
          onClick = onOpenImport,
          modifier = Modifier.testTag("home_paste_import_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.ContentPaste,
            contentDescription = stringResource(R.string.home_paste_import),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    // Center focus area: Profile selector + Giant circular power button + Essential live readout
    Column(
      modifier =
        Modifier.weight(1f)
          .fillMaxWidth()
          .widthIn(max = 600.dp)
          .padding(horizontal = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      // Selected server / profile selector pill
      Box {
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.surface,
          tonalElevation = 1.dp,
          modifier =
            Modifier.border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp),
              )
              .clip(RoundedCornerShape(16.dp))
              .clickable {
                if (allProfiles.isEmpty()) {
                  onOpenEditor(null)
                } else {
                  showProfilePicker = true
                }
              }
              .testTag("home_profile_selector"),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Column(modifier = Modifier.widthIn(max = 230.dp)) {
              Text(
                text = activeProfile?.displayTitle() ?: stringResource(R.string.home_no_profile_selected),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                text =
                  if (activeProfile != null) {
                    "${activeProfile.protocol.label} • ${activeProfile.endpointText()}"
                  } else {
                    stringResource(R.string.home_select_or_create_profile)
                  },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            Icon(
              imageVector = Icons.Rounded.ExpandMore,
              contentDescription = stringResource(R.string.home_change_profile),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        DropdownMenu(
          expanded = showProfilePicker,
          onDismissRequest = { showProfilePicker = false },
        ) {
          allProfiles.forEach { profile ->
            DropdownMenuItem(
              text = {
                Column {
                  Text(profile.displayTitle(), style = MaterialTheme.typography.bodyMedium)
                  Text(
                    "${profile.protocol.label} • ${profile.endpointText()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              },
              onClick = {
                showProfilePicker = false
                onSelectProfile(profile.id)
              },
            )
          }
          HorizontalDivider()
          DropdownMenuItem(
            text = { Text(stringResource(R.string.editor_new_profile)) },
            onClick = {
              showProfilePicker = false
              onOpenEditor(null)
            },
          )
        }
      }

      Spacer(Modifier.height(34.dp))

      // Giant Circular Power Connect/Disconnect Button
      PowerConnectButton(
        state = stats.state,
        animationsEnabled = settings.animationsEnabled,
        onClick = onToggleConnection,
      )

      Spacer(Modifier.height(28.dp))

      // Status Text
      Text(
        text = stateLabel,
        style = MaterialTheme.typography.headlineSmall,
        color = stateColor,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.testTag("home_status_label"),
      )

      Spacer(Modifier.height(6.dp))

      if (stats.state == VpnConnectionState.ERROR && !stats.errorMessage.isNullOrBlank()) {
        Text(
          text = stats.errorMessage,
          style = MaterialTheme.typography.bodySmall,
          color = StatusError,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(horizontal = 16.dp),
        )
      } else if (stats.externalVpnDetected && stats.state != VpnConnectionState.CONNECTED) {
        Text(
          text = stringResource(R.string.vpn_external_conflict),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(horizontal = 16.dp),
        )
      } else {
        Text(
          text =
            if (stats.state == VpnConnectionState.CONNECTED) stringResource(R.string.home_tap_to_disconnect)
            else stringResource(R.string.home_tap_to_connect),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Spacer(Modifier.height(24.dp))

      // Minimal, essential connection telemetry strip (Server, Protocol, Duration)
      val displayServer =
        when {
          stats.state == VpnConnectionState.CONNECTED && stats.activeServerAddress.isNotBlank() ->
            stats.activeServerAddress
          activeProfile != null -> activeProfile.endpointText()
          else -> "—"
        }
      val displayProtocol =
        when {
          stats.state == VpnConnectionState.CONNECTED && stats.activeProtocol != null ->
            stats.activeProtocol.label
          activeProfile != null -> activeProfile.protocol.label
          else -> "—"
        }
      val displayDuration =
        if (stats.state == VpnConnectionState.CONNECTED) formatDuration(elapsedSeconds) else "00:00:00"

      Row(
        modifier =
          Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
              1.dp,
              MaterialTheme.colorScheme.outlineVariant,
              RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        CompactInfoItem(
          label = stringResource(R.string.home_server_address),
          value = displayServer,
          modifier = Modifier.weight(1.2f),
        )
        CompactInfoItem(
          label = stringResource(R.string.home_protocol),
          value = displayProtocol,
          modifier = Modifier.weight(1f),
        )
        CompactInfoItem(
          label = stringResource(R.string.home_duration),
          value = displayDuration,
          useMono = true,
          modifier = Modifier.weight(1f),
        )
      }
    }

    // ByeByeDPI-inspired clean 4-action bottom navigation bar: Editor, Settings, Test, Lists
    Surface(
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 2.dp,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(modifier = Modifier.navigationBarsPadding()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .widthIn(max = 600.dp)
              .align(Alignment.CenterHorizontally)
              .padding(horizontal = 8.dp, vertical = 6.dp),
          horizontalArrangement = Arrangement.SpaceEvenly,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          HomeBottomAction(
            icon = Icons.Outlined.Edit,
            label = stringResource(R.string.nav_editor),
            testTag = "bottom_nav_editor",
            onClick = { onOpenEditor(activeProfile?.id) },
            modifier = Modifier.weight(1f),
          )
          HomeBottomAction(
            icon = Icons.Outlined.Settings,
            label = stringResource(R.string.nav_settings),
            testTag = "bottom_nav_settings",
            onClick = onOpenSettings,
            modifier = Modifier.weight(1f),
          )
          HomeBottomAction(
            icon = Icons.Outlined.BugReport,
            label = stringResource(R.string.nav_test),
            testTag = "bottom_nav_test",
            onClick = onOpenTest,
            modifier = Modifier.weight(1f),
          )
          HomeBottomAction(
            icon = Icons.AutoMirrored.Outlined.ListAlt,
            label = stringResource(R.string.nav_lists),
            testTag = "bottom_nav_lists",
            onClick = onOpenLists,
            modifier = Modifier.weight(1f),
          )
        }
      }
    }
  }
}

@Composable
private fun PowerConnectButton(
  state: VpnConnectionState,
  animationsEnabled: Boolean,
  onClick: () -> Unit,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val pressed by interactionSource.collectIsPressedAsState()
  val scale by
    animateFloatAsState(
      targetValue = if (pressed && animationsEnabled) 0.94f else 1f,
      animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
      label = "powerPressScale",
    )

  val primary = MaterialTheme.colorScheme.primary
  val ringColor by
    animateColorAsState(
      targetValue =
        when (state) {
          VpnConnectionState.CONNECTED -> StatusConnected
          VpnConnectionState.CONNECTING -> StatusConnecting
          VpnConnectionState.ERROR -> StatusError
          VpnConnectionState.DISCONNECTED -> primary.copy(alpha = 0.45f)
        },
      animationSpec = tween(250),
      label = "ringColor",
    )

  val buttonBg by
    animateColorAsState(
      targetValue =
        when (state) {
          VpnConnectionState.CONNECTED -> StatusConnected
          VpnConnectionState.CONNECTING -> MaterialTheme.colorScheme.surface
          VpnConnectionState.ERROR -> MaterialTheme.colorScheme.surface
          VpnConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.surface
        },
      animationSpec = tween(250),
      label = "buttonBg",
    )

  val iconTint by
    animateColorAsState(
      targetValue =
        when (state) {
          VpnConnectionState.CONNECTED -> Color.White
          VpnConnectionState.CONNECTING -> StatusConnecting
          VpnConnectionState.ERROR -> StatusError
          VpnConnectionState.DISCONNECTED -> primary
        },
      animationSpec = tween(250),
      label = "iconTint",
    )

  val infinite = rememberInfiniteTransition(label = "connectSpin")
  val sweepAngle by
    infinite.animateFloat(
      initialValue = 0f,
      targetValue = 360f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(1100, easing = LinearEasing),
          repeatMode = RepeatMode.Restart,
        ),
      label = "connectRotation",
    )
  val connectedPulse by
    infinite.animateFloat(
      initialValue = 0.14f,
      targetValue = 0.30f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(1800, easing = FastOutSlowInEasing),
          repeatMode = RepeatMode.Reverse,
        ),
      label = "connectedPulse",
    )

  Box(
    modifier = Modifier.size(196.dp).scale(scale),
    contentAlignment = Alignment.Center,
  ) {
    // Lightweight Canvas ring for Connecting spinner / Connected subtle halo
    Canvas(modifier = Modifier.fillMaxSize()) {
      val strokePx = 4.dp.toPx()
      if (state == VpnConnectionState.CONNECTED && animationsEnabled) {
        drawCircle(
          brush =
            Brush.radialGradient(
              colors = listOf(StatusConnected.copy(alpha = connectedPulse), Color.Transparent)
            ),
          radius = size.minDimension / 2f,
        )
      }
      drawCircle(
        color = ringColor.copy(alpha = if (state == VpnConnectionState.CONNECTING) 0.18f else 0.35f),
        radius = (size.minDimension / 2f) - 10.dp.toPx(),
        style = Stroke(width = strokePx),
      )
      if (state == VpnConnectionState.CONNECTING) {
        drawArc(
          color = StatusConnecting,
          startAngle = if (animationsEnabled) sweepAngle else -90f,
          sweepAngle = 110f,
          useCenter = false,
          style = Stroke(width = strokePx * 1.3f, cap = StrokeCap.Round),
        )
      }
    }

    Surface(
      shape = CircleShape,
      color = buttonBg,
      shadowElevation = if (state == VpnConnectionState.CONNECTED) 10.dp else 4.dp,
      modifier =
        Modifier.size(148.dp)
          .clip(CircleShape)
          .border(
            width = 1.5.dp,
            color = ringColor.copy(alpha = 0.65f),
            shape = CircleShape,
          )
          .clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
          )
          .testTag("main_power_button"),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          imageVector = Icons.Rounded.PowerSettingsNew,
          contentDescription = stringResource(R.string.home_tap_to_connect),
          tint = iconTint,
          modifier = Modifier.size(62.dp),
        )
      }
    }
  }
}

@Composable
private fun CompactInfoItem(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
  useMono: Boolean = false,
) {
  Column(
    modifier = modifier.padding(horizontal = 4.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(2.dp))
    Text(
      text = value,
      style =
        if (useMono) MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFamily)
        else MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun HomeBottomAction(
  icon: ImageVector,
  label: String,
  testTag: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier =
      modifier
        .clip(RoundedCornerShape(12.dp))
        .clickable(onClick = onClick)
        .minimumInteractiveComponentSize()
        .padding(vertical = 8.dp)
        .testTag(testTag),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = label,
      tint = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.size(22.dp),
    )
    Spacer(Modifier.height(4.dp))
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

fun formatDuration(totalSeconds: Long): String {
  val h = totalSeconds / 3600
  val m = (totalSeconds % 3600) / 60
  val s = totalSeconds % 60
  return "%02d:%02d:%02d".format(h, m, s)
}
