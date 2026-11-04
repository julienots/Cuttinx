package com.superessence.cuttinx.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.datatransfer.DataFlavor
import java.io.File

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
fun main() {
    // Labo CUTTINX: unlock times kept between launches
    val labPrefs = java.util.prefs.Preferences.userRoot().node("superessence/cuttinx/lab")
    com.superessence.cuttinx.core.smart.TechVault.store = object : com.superessence.cuttinx.core.smart.TechVault.Store {
        override fun get(key: String) = labPrefs.getLong(key, 0L)
        override fun put(key: String, value: Long) { labPrefs.putLong(key, value); runCatching { labPrefs.flush() } }
    }
    app()
}

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun app() = application {
    val state = remember { AppState() }
    Window(
        onCloseRequest = { state.shutdown(); exitApplication() },
        title = "Cuttinx PRO — Éditeur vidéo · Twitch Editor · ID Influenceur",
        state = rememberWindowState(width = 1500.dp, height = 940.dp),
        onKeyEvent = { state.onKey(it) },
    ) {
        // files dropped on the window are imported into the open project
        val drop = remember {
            object : DragAndDropTarget {
                override fun onDrop(event: DragAndDropEvent): Boolean {
                    val t = event.awtTransferable
                    if (!t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
                    @Suppress("UNCHECKED_CAST")
                    val files = (t.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>) ?: return false
                    return state.onFilesDropped(files)
                }
            }
        }
        Box(Modifier.fillMaxSize().dragAndDropTarget(shouldStartDragAndDrop = { true }, target = drop)) {
            App(state)
            SponsorOverlay(Cx.Yellow)
        }
    }
}
