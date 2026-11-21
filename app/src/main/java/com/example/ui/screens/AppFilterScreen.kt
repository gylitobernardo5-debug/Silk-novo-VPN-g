package com.example.ui.screens

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.AppSettings
import com.example.model.InstalledAppInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppFilterScreen(
  apps: List<InstalledAppInfo>,
  settings: AppSettings,
  onLoadApps: () -> Unit,
  onTogglePackage: (String) -> Unit,
  onBack: () -> Unit,
) {
  var query by remember { mutableStateOf("") }
  var showSystem by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) { onLoadApps() }

  val filtered =
    remember(apps, query, showSystem) {
      apps.filter { app ->
        (showSystem || !app.isSystemApp) &&
          (query.isBlank() ||
            app.label.contains(query, ignoreCase = true) ||
            app.packageName.contains(query, ignoreCase = true))
      }
    }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.settings_apps_select)) },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("app_filter_back_button")) {
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
      Column(modifier = Modifier.fillMaxSize().widthIn(max = 600.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            placeholder = { Text("Search apps…") },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
          FilterChip(
            selected = showSystem,
            onClick = { showSystem = !showSystem },
            label = { Text("System") },
          )
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
          items(filtered, key = { it.packageName }) { app ->
            val checked = app.packageName in settings.selectedAppPackages
            Row(
              modifier =
                Modifier.fillMaxWidth()
                  .clickable { onTogglePackage(app.packageName) }
                  .minimumInteractiveComponentSize()
                  .padding(horizontal = 16.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Checkbox(checked = checked, onCheckedChange = { onTogglePackage(app.packageName) })
              Column(modifier = Modifier.padding(start = 10.dp)) {
                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(2.dp))
                Text(
                  app.packageName,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
          }
        }
      }
    }
  }
}
