package gui

import androidx.compose.ui.window.application

fun launch() {
    application {
        MainWindow(::exitApplication)
    }
}
