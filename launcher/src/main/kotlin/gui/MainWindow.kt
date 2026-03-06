package gui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import gui.components.OutputConsole
import gui.components.QuickActions
import gui.components.StatusBar
import gui.components.TaskInput

@Composable
fun MainWindow(onCloseRequest: () -> Unit) {
    Window(
        onCloseRequest = onCloseRequest,
        title = "KOOG Coding Agent",
        state = rememberWindowState(width = 900.dp, height = 600.dp, position = WindowPosition.Aligned(Alignment.Center))
    ) {
        MaterialTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                StatusBar(
                    status = TODO(),
                    onSwitchClick = TODO()
                )
                TaskInput(
                    onProcess = TODO(),
                    onAnalyze = TODO(),
                    onDebug = TODO()
                )
                QuickActions(
                    onAction = TODO()
                )
                OutputConsole(
                    events = TODO(),
                    onClear = TODO()
                )
            }
        }
    }
}
