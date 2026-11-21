package com.example.ui.screens

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.AdBlockCategory
import com.example.model.AdBlockRule
import com.example.model.VpnProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(
  profiles: List<VpnProfile>,
  activeProfileId: String?,
  adBlockRules: List<AdBlockRule>,
  onSelectActiveProfile: (String) -> Unit,
  onToggleFavorite: (String) -> Unit,
  onEditProfile: (String) -> Unit,
  onCreateProfile: () -> Unit,
  onDuplicateProfile: (String) -> Unit,
  onExportProfile: (VpnProfile) -> Unit,
  onDeleteProfile: (String) -> Unit,
  onOpenImportDialog: () -> Unit,
  onAddDomainRule: (String, AdBlockCategory) -> Unit,
  onToggleDomainRule: (AdBlockRule) -> Unit,
  onDeleteDomainRule: (String) -> Unit,
  onBack: () -> Unit,
) {
  var selectedTab by remember { mutableIntStateOf(0) }
  var domainInput by remember { mutableStateOf("") }

  val tabs =
    listOf(
      stringResource(R.string.lists_tab_profiles),
      stringResource(R.string.lists_tab_favorites),
      stringResource(R.string.lists_tab_blocklist),
      stringResource(R.string.lists_tab_allowlist),
    )

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.lists_title)) },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("lists_back_button")) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          IconButton(onClick = onOpenImportDialog, modifier = Modifier.testTag("lists_import_button")) {
            Icon(Icons.Rounded.ContentPaste, contentDescription = stringResource(R.string.home_paste_import))
          }
        },
      )
    },
    floatingActionButton = {
      if (selectedTab <= 1) {
        FloatingActionButton(
          onClick = onCreateProfile,
          modifier = Modifier.testTag("lists_add_profile_fab"),
        ) {
          Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.editor_new_profile))
        }
      }
    },
  ) { innerPadding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(innerPadding).navigationBarsPadding(),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(modifier = Modifier.fillMaxSize().widthIn(max = 600.dp)) {
        SecondaryTabRow(selectedTabIndex = selectedTab) {
          tabs.forEachIndexed { idx, title ->
            Tab(
              selected = selectedTab == idx,
              onClick = { selectedTab = idx },
              text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
          }
        }

        when (selectedTab) {
          0,
          1 -> {
            val shown =
              if (selectedTab == 1) profiles.filter { it.isFavorite } else profiles
            if (shown.isEmpty()) {
              EmptyListNotice(
                if (selectedTab == 1) stringResource(R.string.lists_empty_favorites)
                else stringResource(R.string.lists_empty_profiles)
              )
            } else {
              LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
              ) {
                items(shown, key = { it.id }) { profile ->
                  ProfileListCard(
                    profile = profile,
                    isActive = profile.id == activeProfileId,
                    onSelect = { onSelectActiveProfile(profile.id) },
                    onToggleFav = { onToggleFavorite(profile.id) },
                    onEdit = { onEditProfile(profile.id) },
                    onDuplicate = { onDuplicateProfile(profile.id) },
                    onExport = { onExportProfile(profile) },
                    onDelete = { onDeleteProfile(profile.id) },
                  )
                }
              }
            }
          }
          2,
          3 -> {
            val isAllow = selectedTab == 3
            val targetCategory =
              if (isAllow) AdBlockCategory.ALLOWLIST else AdBlockCategory.CUSTOM_BLOCK
            val filteredRules =
              if (isAllow) adBlockRules.filter { it.category == AdBlockCategory.ALLOWLIST }
              else adBlockRules.filter { it.category != AdBlockCategory.ALLOWLIST }

            Row(
              modifier = Modifier.fillMaxWidth().padding(16.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              OutlinedTextField(
                value = domainInput,
                onValueChange = { domainInput = it },
                label = { Text(stringResource(R.string.lists_domain_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("lists_domain_input"),
              )
              IconButton(
                onClick = {
                  if (domainInput.isNotBlank()) {
                    onAddDomainRule(domainInput, targetCategory)
                    domainInput = ""
                  }
                },
                modifier = Modifier.testTag("lists_add_domain_button"),
              ) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.lists_add_domain))
              }
            }

            if (filteredRules.isEmpty()) {
              EmptyListNotice(stringResource(R.string.lists_empty_domains))
            } else {
              LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filteredRules, key = { it.id }) { rule ->
                  Row(
                    modifier =
                      Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Column(modifier = Modifier.weight(1f)) {
                      Text(rule.domain, style = MaterialTheme.typography.bodyLarge)
                      Text(
                        "${rule.category.name} • ${rule.source}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    }
                    Switch(
                      checked = rule.isEnabled,
                      onCheckedChange = { onToggleDomainRule(rule) },
                    )
                    if (rule.source == "user") {
                      IconButton(onClick = { onDeleteDomainRule(rule.id) }) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete")
                      }
                    }
                  }
                  HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ProfileListCard(
  profile: VpnProfile,
  isActive: Boolean,
  onSelect: () -> Unit,
  onToggleFav: () -> Unit,
  onEdit: () -> Unit,
  onDuplicate: () -> Unit,
  onExport: () -> Unit,
  onDelete: () -> Unit,
) {
  var menuOpen by remember { mutableStateOf(false) }
  Surface(
    shape = RoundedCornerShape(14.dp),
    color =
      if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
      else MaterialTheme.colorScheme.surface,
    modifier =
      Modifier.fillMaxWidth()
        .border(
          width = if (isActive) 1.5.dp else 1.dp,
          color =
            if (isActive) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
          shape = RoundedCornerShape(14.dp),
        )
        .clip(RoundedCornerShape(14.dp))
        .clickable(onClick = onSelect),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (isActive) {
        Icon(
          Icons.Rounded.CheckCircle,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(end = 10.dp),
        )
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = profile.displayTitle(),
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
          text = "${profile.protocol.label} • ${profile.endpointText()}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      IconButton(onClick = onToggleFav) {
        Icon(
          imageVector = if (profile.isFavorite) Icons.Rounded.Star else Icons.Outlined.StarBorder,
          contentDescription = "Favorite",
          tint =
            if (profile.isFavorite) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Box {
        IconButton(onClick = { menuOpen = true }) {
          Icon(Icons.Rounded.MoreVert, contentDescription = "Options")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
          DropdownMenuItem(
            text = { Text(stringResource(R.string.editor_edit_profile)) },
            leadingIcon = { Icon(Icons.Rounded.Edit, null) },
            onClick = {
              menuOpen = false
              onEdit()
            },
          )
          DropdownMenuItem(
            text = { Text(stringResource(R.string.editor_duplicate)) },
            leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
            onClick = {
              menuOpen = false
              onDuplicate()
            },
          )
          DropdownMenuItem(
            text = { Text(stringResource(R.string.editor_export)) },
            leadingIcon = { Icon(Icons.Rounded.ContentPaste, null) },
            onClick = {
              menuOpen = false
              onExport()
            },
          )
          DropdownMenuItem(
            text = { Text(stringResource(R.string.editor_delete)) },
            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
            onClick = {
              menuOpen = false
              onDelete()
            },
          )
        }
      }
    }
  }
}

@Composable
private fun EmptyListNotice(message: String) {
  Box(
    modifier = Modifier.fillMaxSize().padding(32.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = message,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
