package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R

@Composable
fun SmartImportDialog(
  initialText: String = "",
  onDismiss: () -> Unit,
  onPasteClipboard: () -> String,
  onPickFile: () -> Unit,
  onImportText: (String) -> Unit,
) {
  var rawText by remember { mutableStateOf(initialText) }
  var clipboardNotice by remember { mutableStateOf<String?>(null) }
  val emptyMsg = stringResource(R.string.import_clipboard_empty)

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = stringResource(R.string.import_dialog_title),
        style = MaterialTheme.typography.titleLarge,
      )
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          text = stringResource(R.string.import_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedButton(
            onClick = {
              val clip = onPasteClipboard()
              if (clip.isBlank()) {
                clipboardNotice = emptyMsg
              } else {
                rawText = clip
                clipboardNotice = null
              }
            },
            modifier = Modifier.weight(1f).testTag("import_paste_clipboard_button"),
          ) {
            Icon(Icons.Rounded.ContentPaste, contentDescription = null)
            Text(
              text = stringResource(R.string.editor_paste_link),
              modifier = Modifier.padding(start = 6.dp),
            )
          }
          OutlinedButton(
            onClick = {
              onPickFile()
              onDismiss()
            },
            modifier = Modifier.weight(1f).testTag("import_file_button"),
          ) {
            Icon(Icons.Rounded.FolderOpen, contentDescription = null)
            Text(
              text = stringResource(R.string.editor_import_file),
              modifier = Modifier.padding(start = 6.dp),
            )
          }
        }
        OutlinedTextField(
          value = rawText,
          onValueChange = {
            rawText = it
            clipboardNotice = null
          },
          label = { Text(stringResource(R.string.import_raw_label)) },
          minLines = 4,
          maxLines = 7,
          textStyle = MaterialTheme.typography.labelSmall,
          modifier = Modifier.fillMaxWidth().testTag("import_raw_input"),
        )
        if (clipboardNotice != null) {
          Text(
            text = clipboardNotice!!,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { onImportText(rawText) },
        enabled = rawText.isNotBlank(),
        modifier = Modifier.testTag("import_confirm_button"),
      ) {
        Text(stringResource(R.string.import_action))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.cancel))
      }
    },
  )
}
