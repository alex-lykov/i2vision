package com.alyk.ai.koog.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alyk.ai.koog.database.settings.TerminalSettingKey
import com.alyk.ai.koog.database.settings.TerminalSettingState

@Composable
fun TerminalSettingsPanel(
    viewModel: TerminalViewModel
) {
    val categories by viewModel.settingsCategories.collectAsState()
    var selectedCategory by remember { mutableStateOf<TerminalSettingKey.Category?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    
    // Select the first category by default if not already selected
    if (selectedCategory == null && categories.isNotEmpty()) {
        selectedCategory = categories.keys.first()
    }
    
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search settings...") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
        
        // Category tabs
        ScrollableTabRow(
            selectedTabIndex = categories.keys.indexOfFirst { it == selectedCategory }.coerceAtLeast(0),
            edgePadding = 16.dp
        ) {
            categories.keys.forEach { category ->
                Tab(
                    selected = category == selectedCategory,
                    onClick = { selectedCategory = category },
                    text = { Text(category.displayName) }
                )
            }
        }
        
        // Settings list
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp)
        ) {
            val filteredSettings = (if (selectedCategory != null) {
                categories[selectedCategory] ?: emptyList()
            } else {
                categories.values.flatten()
            }).filter {
                searchQuery.isEmpty() || 
                it.definition.key.contains(searchQuery, ignoreCase = true) ||
                it.definition.description.contains(searchQuery, ignoreCase = true)
            }
            
            items(filteredSettings) { setting ->
                SettingRow(
                    setting = setting,
                    onToggle = { viewModel.toggleSetting(setting.definition.key) }
                )
            }
            
            item {
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { /* TODO: Save as profile */ },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Settings, null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Save as Profile")
                    }
                    
                    Button(
                        onClick = { viewModel.resetToDefaults() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            // Consider defining colors in a Theme
                        )
                    ) {
                        Icon(Icons.Default.Refresh, null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reset to Defaults")
                    }
                }
            }
        }
    }
}

@Composable
fun SettingRow(
    setting: TerminalSettingState,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(end = 16.dp)
        ) {
            Text(
                text = setting.definition.key.split("_").joinToString(" ") { it.replaceFirstChar(Char::titlecase) },
                style = MaterialTheme.typography.body1,
                color = if (setting.isOverridden) 
                    MaterialTheme.colors.primary 
                else 
                    LocalContentColor.current
            )
            Text(
                text = setting.definition.description,
                style = MaterialTheme.typography.caption,
                color = LocalContentColor.current.copy(alpha = 0.7f)
            )
        }
        
        Switch(
            checked = setting.enabled,
            onCheckedChange = { onToggle() }
        )
    }
    
    Divider()
}
