package com.superessence.cuttinx.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Cuttinx PRO — Studio créateur", state = rememberWindowState(width = 1440.dp, height = 900.dp)) {
        App()
    }
}
