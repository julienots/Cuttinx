package com.superessence.cuttinx.desktop.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.superessence.cuttinx.desktop.AppState

@Composable
fun EditorSpace(state: AppState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Éditeur vidéo PC") }
}
