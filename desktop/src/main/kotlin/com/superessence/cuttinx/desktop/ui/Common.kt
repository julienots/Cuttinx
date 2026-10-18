package com.superessence.cuttinx.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.cuttinx.desktop.Cx
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.JFileChooser

@Composable
fun Title(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier.padding(top = 14.dp, bottom = 6.dp), color = Cx.Yellow, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)

@Composable
fun Hint(text: String) = Text(text, color = Cx.Dim, fontSize = 12.sp, lineHeight = 16.sp)

/** Single-choice chips on a scrollable row. */
@Composable
fun <T> Chips(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> Chip(label(o), o == selected) { onSelect(o) } }
    }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(label, Modifier.clip(RoundedCornerShape(50)).background(if (selected) Cx.Yellow else Cx.Panel2).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        color = if (selected) Color.Black else MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
}

fun copyToClipboard(text: String) = runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }

/** Native folder picker (Swing). */
fun chooseFolder(title: String, start: File? = null): File? {
    val ch = JFileChooser(start ?: File(System.getProperty("user.home"))).apply { dialogTitle = title; fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; isAcceptAllFileFilterUsed = false }
    return if (ch.showDialog(null, "Choisir ce dossier") == JFileChooser.APPROVE_OPTION) ch.selectedFile else null
}

/** Native "save as" for a single file. */
fun chooseFile(title: String, suggested: String): File? {
    val ch = JFileChooser(File(System.getProperty("user.home"))).apply { dialogTitle = title; selectedFile = File(currentDirectory, suggested) }
    return if (ch.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) ch.selectedFile else null
}

fun openFolder(f: File) = runCatching { java.awt.Desktop.getDesktop().open(f) }
