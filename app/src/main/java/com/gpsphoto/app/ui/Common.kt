package com.gpsphoto.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.gpsphoto.app.data.localTime
import java.time.format.DateTimeFormatter

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Shown in the phone's own time zone, with the zone name (e.g. "EAT"). */
private val dateTimeFormat = DateTimeFormatter.ofPattern("EEE, d MMM yyyy · HH:mm z")
private val shortDay = DateTimeFormatter.ofPattern("d MMM yyyy")

fun formatDateTime(millis: Long): String = dateTimeFormat.format(localTime(millis))
fun formatDay(millis: Long): String = shortDay.format(localTime(millis))

@Composable
fun <T> MenuChip(label: String, options: List<T>, optionLabel: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = { onPick(option); open = false })
            }
        }
    }
}

/** One-field dialog used by the bulk actions. Tapping a suggestion fills or appends it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    hint: String,
    confirmLabel: String,
    allowEmpty: Boolean,
    suggestions: List<String>,
    appendSuggestions: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(label) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(hint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                if (suggestions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        suggestions.take(12).forEach { s ->
                            SuggestionChip(
                                onClick = {
                                    text = if (appendSuggestions && text.isNotBlank()) "${text.trimEnd().trimEnd(',')}, $s" else s
                                },
                                label = { Text(s) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = allowEmpty || text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun EditDetailsDialog(
    title: String,
    description: String,
    category: String,
    tags: List<String>,
    onDismiss: () -> Unit,
    onSave: (title: String, description: String, category: String, tags: String) -> Unit,
) {
    var t by remember { mutableStateOf(title) }
    var d by remember { mutableStateOf(description) }
    var c by remember { mutableStateOf(category) }
    var g by remember { mutableStateOf(tags.joinToString(", ")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit details") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(t, { t = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(d, { d = it }, label = { Text("Description") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(c, { c = it }, label = { Text("Category") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    g, { g = it }, label = { Text("Tags") }, singleLine = true,
                    supportingText = { Text("Separate with commas") }, modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Your original photo and its GPS location are never changed.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(enabled = t.isNotBlank(), onClick = { onSave(t, d, c, g) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
