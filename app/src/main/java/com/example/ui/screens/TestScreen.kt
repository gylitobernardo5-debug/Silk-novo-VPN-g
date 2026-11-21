package com.example.ui.screens

import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.DiagnosticReport
import com.example.model.DiagnosticStepStatus
import com.example.model.VpnProfile
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StatusConnected
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusWarning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestScreen(
  activeProfile: VpnProfile?,
  report: DiagnosticReport,
  onRunDiagnostics: () -> Unit,
  onBack: () -> Unit,
) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.test_title)) },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("test_back_button")) {
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = MaterialTheme.colorScheme.surface,
          modifier =
            Modifier.fillMaxWidth().border(
              1.dp,
              MaterialTheme.colorScheme.outlineVariant,
              RoundedCornerShape(14.dp),
            ),
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Text(
              text = activeProfile?.displayTitle() ?: stringResource(R.string.home_no_profile_selected),
              style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
              text =
                if (activeProfile != null) {
                  "${activeProfile.protocol.label} • ${activeProfile.endpointText()}"
                } else {
                  stringResource(R.string.test_no_profile)
                },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Button(
              onClick = onRunDiagnostics,
              enabled = activeProfile != null && !report.isRunning,
              modifier = Modifier.fillMaxWidth().testTag("test_run_button"),
            ) {
              if (report.isRunning) {
                CircularProgressIndicator(
                  modifier = Modifier.size(18.dp),
                  strokeWidth = 2.dp,
                  color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.test_running))
              } else {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.test_run_all))
              }
            }
          }
        }

        if (report.measuredLatencyMs != null) {
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(stringResource(R.string.test_latency), style = MaterialTheme.typography.labelLarge)
              Text(
                text = "${report.measuredLatencyMs} ms",
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFamily),
                color = MaterialTheme.colorScheme.primary,
              )
            }
          }
        }

        report.steps.forEach { step ->
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier =
              Modifier.fillMaxWidth().border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(14.dp),
              ),
          ) {
            Row(
              modifier = Modifier.padding(14.dp),
              verticalAlignment = Alignment.Top,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              when (step.status) {
                DiagnosticStepStatus.PENDING ->
                  Icon(
                    Icons.Rounded.HourglassTop,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                DiagnosticStepStatus.RUNNING ->
                  CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                DiagnosticStepStatus.PASSED ->
                  Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = StatusConnected)
                DiagnosticStepStatus.FAILED ->
                  Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = StatusError)
                DiagnosticStepStatus.WARNING ->
                  Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = StatusWarning)
              }
              Column(modifier = Modifier.weight(1f)) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Text(step.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                  if (step.latencyMs != null) {
                    Text(
                      text = "${step.latencyMs} ms",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.primary,
                    )
                  }
                }
                if (step.detail.isNotBlank()) {
                  Spacer(Modifier.height(4.dp))
                  Text(
                    text = step.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
        }

        if (report.summary.isNotBlank()) {
          Text(
            text = report.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}
