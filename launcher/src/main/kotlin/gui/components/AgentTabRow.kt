package gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gui.data.AgentTab
import gui.data.AgentTypeDto

@Composable
fun AgentTabRow(
    tabs: List<AgentTab>,
    activeTabId: String?,
    onTabSelected: (String) -> Unit,
    onTabClosed: (String) -> Unit,
    onNewTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Debug logging
    println("[TABROW] AgentTabRow received ${tabs.size} tabs, active: $activeTabId")
    tabs.forEach { tab ->
        println("[TABROW] TabRow Tab: ${tab.id} - ${tab.name} (${tab.agentType}) active=${tab.id == activeTabId}")
    }
    
    val scrollState = rememberScrollState()
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF252526))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tab scroll area - takes available space
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { tab ->
                AgentTabItem(
                    tab = tab,
                    isActive = tab.id == activeTabId,
                    onClick = { onTabSelected(tab.id) },
                    onClose = { onTabClosed(tab.id) }
                )
            }
        }
        
        // New tab button - fixed width
        IconButton(
            onClick = onNewTab,
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF3C3C3C))
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "New Agent Tab",
                tint = Color(0xFF00FF00),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun AgentTabItem(
    tab: AgentTab,
    isActive: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit
) {
    val backgroundColor = if (isActive) {
        Color(0xFF1E1E1E)
    } else {
        Color(0xFF3C3C3C)
    }
    
    val textColor = if (isActive) {
        Color(0xFF00FF00)
    } else {
        Color(0xFFCCCCCC)
    }
    
    val agentTypeColor = when (tab.agentType) {
        AgentTypeDto.IDEA -> Color(0xFF2196F3)
        AgentTypeDto.ARCHITECTURE -> Color(0xFF9C27B0)
        AgentTypeDto.MODULE -> Color(0xFFFF9800)
        AgentTypeDto.TEST -> Color(0xFF4CAF50)
        AgentTypeDto.IMPLEMENTATION -> Color(0xFFF44336)
    }
    
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .height(32.dp)
            .defaultMinSize(minWidth = 120.dp), // Minimum width for tabs
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Agent type indicator
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(agentTypeColor, RoundedCornerShape(4.dp))
        )
        
        // Tab name
        Text(
            text = tab.name,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        
        // Unread indicator
        if (tab.unreadCount > 0) {
            Text(
                text = "${tab.unreadCount}",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(Color(0xFFFF5722), RoundedCornerShape(10.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
        
        // Close button
        IconButton(
            onClick = onClose,
            modifier = Modifier.size(20.dp)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Close Tab",
                tint = textColor.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}
