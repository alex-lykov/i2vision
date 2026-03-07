package gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Base card component for right panel elements.
 * Provides consistent styling and contract for all right panel cards.
 */
@Composable
fun RightPanelCard(
    title: String,
    modifier: Modifier = Modifier,
    headerContent: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        elevation = 2.dp,
        backgroundColor = MaterialTheme.colors.surface
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Header row with title and optional header content
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.subtitle1,
                    color = MaterialTheme.colors.onSurface
                )
                headerContent?.invoke()
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Content area
            content()
        }
    }
}

/**
 * Extension: Card with action button in header
 */
@Composable
fun RightPanelCardWithAction(
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    RightPanelCard(
        title = title,
        modifier = modifier,
        headerContent = {
            androidx.compose.material.Button(
                onClick = onAction,
                modifier = Modifier.height(28.dp)
            ) {
                Text(actionLabel, style = MaterialTheme.typography.caption)
            }
        },
        content = content
    )
}

/**
 * Extension: Card with status badge in header
 */
@Composable
fun RightPanelCardWithStatus(
    title: String,
    statusText: String,
    statusColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    RightPanelCard(
        title = title,
        modifier = modifier,
        headerContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .shadow(1.dp, RoundedCornerShape(4.dp))
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.caption,
                    color = statusColor
                )
            }
        },
        content = content
    )
}

/**
 * Extension: Card with count badge in header
 */
@Composable
fun RightPanelCardWithCount(
    title: String,
    count: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    RightPanelCard(
        title = "$title ($count)",
        modifier = modifier,
        content = content
    )
}
