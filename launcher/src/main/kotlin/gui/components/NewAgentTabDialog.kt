package gui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import gui.data.AgentTypeDto

@Composable
fun NewAgentTabDialog(
    onDismiss: () -> Unit,
    onAgentSelected: (AgentTypeDto, String) -> Unit
) {
    var selectedAgentType by remember { mutableStateOf(AgentTypeDto.IMPLEMENTATION) }
    var customName by remember { mutableStateOf("") }
    var useCustomName by remember { mutableStateOf(false) }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            backgroundColor = Color(0xFF2D2D30),
            elevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Create New Agent Tab",
                        color = Color.White,
                        style = MaterialTheme.typography.h6
                    )
                    
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color(0xFF00FF00)
                        )
                    }
                }
                
                // Agent type selection
                Text(
                    text = "Select Agent Type:",
                    color = Color(0xFF00FF00),
                    style = MaterialTheme.typography.subtitle1
                )
                
                Column(
                    modifier = Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AgentTypeDto.values().forEach { agentType ->
                        val isSelected = selectedAgentType == agentType
                        val (name, description, color) = getAgentTypeInfo(agentType)
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = isSelected,
                                    onClick = { selectedAgentType = agentType },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { selectedAgentType = agentType },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = color,
                                    unselectedColor = Color.Gray
                                )
                            )
                            
                            Spacer(modifier = Modifier.width(12.dp))
                            
                            Column {
                                Text(
                                    text = name,
                                    color = Color.White,
                                    style = MaterialTheme.typography.body1
                                )
                                Text(
                                    text = description,
                                    color = Color.Gray,
                                    style = MaterialTheme.typography.caption
                                )
                            }
                        }
                    }
                }
                
                // Custom name option
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = useCustomName,
                        onCheckedChange = { useCustomName = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color(0xFF00FF00),
                            uncheckedColor = Color.Gray
                        )
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Text(
                        text = "Custom name:",
                        color = Color.White,
                        style = MaterialTheme.typography.body1
                    )
                }
                
                if (useCustomName) {
                    TextField(
                        value = customName,
                        onValueChange = { customName = it },
                        placeholder = { Text("Enter tab name", color = Color.Gray) },
                        colors = TextFieldDefaults.textFieldColors(
                            textColor = Color.White,
                            backgroundColor = Color(0xFF1E1E1E),
                            focusedIndicatorColor = Color(0xFF00FF00),
                            unfocusedIndicatorColor = Color(0xFF404040)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss
                    ) {
                        Text("Cancel", color = Color.Gray)
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Button(
                        onClick = {
                            val finalName = if (useCustomName && customName.isNotBlank()) {
                                customName
                            } else {
                                ""
                            }
                            onAgentSelected(selectedAgentType, finalName)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF0D7377))
                    ) {
                        Text("Create", color = Color.White)
                    }
                }
            }
        }
    }
}

private fun getAgentTypeInfo(agentType: AgentTypeDto): Triple<String, String, Color> {
    return when (agentType) {
        AgentTypeDto.IDEA -> Triple(
            "Idea Agent",
            "Brainstorms and generates creative solutions",
            Color(0xFF2196F3)
        )
        AgentTypeDto.ARCHITECTURE -> Triple(
            "Architecture Agent",
            "Designs system architecture and patterns",
            Color(0xFF9C27B0)
        )
        AgentTypeDto.MODULE -> Triple(
            "Module Agent",
            "Focuses on modular design and components",
            Color(0xFFFF9800)
        )
        AgentTypeDto.TEST -> Triple(
            "Test Agent",
            "Creates and manages test strategies",
            Color(0xFF4CAF50)
        )
        AgentTypeDto.IMPLEMENTATION -> Triple(
            "Implementation Agent",
            "Writes and implements code solutions",
            Color(0xFFF44336)
        )
    }
}
