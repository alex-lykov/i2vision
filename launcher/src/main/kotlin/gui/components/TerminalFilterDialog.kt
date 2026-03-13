package gui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.alyk.ai.koog.database.settings.TerminalSettingKey
import com.alyk.ai.koog.database.settings.TerminalSettingState
import gui.viewmodel.TerminalViewModel

@Composable
fun TerminalFilterDialog(
    viewModel: TerminalViewModel,
    onDismiss: () -> Unit
) {
    val filterSettings by viewModel.filterSettings.collectAsState()
    var selectedCategory by remember { mutableStateOf<TerminalSettingKey.Category?>(null) }
    val categories = filterSettings.keys.toList()
    if (selectedCategory == null && categories.isNotEmpty()) {
        selectedCategory = categories.first()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colors.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Terminal output filter")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                Text(
                    "Choose what appears in the terminal. Changes apply immediately.",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                // Category pills
                if (categories.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        items(categories) { category ->
                            val selected = category == selectedCategory
                            Button(
                                onClick = { selectedCategory = category },
                                modifier = Modifier.padding(vertical = 2.dp),
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = if (selected) MaterialTheme.colors.primary else MaterialTheme.colors.surface,
                                    contentColor = if (selected) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface
                                ),
                                elevation = ButtonDefaults.elevation(defaultElevation = 0.dp, pressedElevation = 1.dp)
                            ) {
                                Text(
                                    category.displayName,
                                    style = MaterialTheme.typography.body2
                                )
                            }
                        }
                    }
                }
                // Settings list for selected category
                if (filterSettings.isEmpty()) {
                    Text(
                        "No filter settings available. Terminal output uses defaults.",
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        val list = selectedCategory?.let { filterSettings[it] } ?: emptyList()
                        items(list) { setting ->
                            FilterSettingRow(
                                setting = setting,
                                onToggle = { viewModel.toggleFilterSetting(setting.definition.key) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = MaterialTheme.colors.primary)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = {
                    viewModel.resetFilterToDefaults()
                }
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Reset to defaults")
            }
        },
        backgroundColor = MaterialTheme.colors.surface,
        contentColor = MaterialTheme.colors.onSurface
    )
}

@Composable
private fun FilterSettingRow(
    setting: TerminalSettingState,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text = setting.definition.key.replace("_", " ").replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.body2,
                color = if (setting.isOverridden) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface
            )
            Text(
                text = setting.definition.description,
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Switch(
            checked = setting.enabled,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF0D7377),
                checkedTrackColor = Color(0xFF0D7377).copy(alpha = 0.5f)
            )
        )
    }
    Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.08f))
}
