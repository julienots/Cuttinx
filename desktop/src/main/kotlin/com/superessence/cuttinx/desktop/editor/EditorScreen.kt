package com.superessence.cuttinx.desktop.editor

import com.superessence.cuttinx.icons.CxIcons
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.cuttinx.core.model.AspectPreset
import com.superessence.cuttinx.core.model.ClipKind
import com.superessence.cuttinx.core.model.Container
import com.superessence.cuttinx.desktop.Cx
import com.superessence.cuttinx.desktop.engine.EditorModel
import com.superessence.cuttinx.desktop.engine.exportSize
import com.superessence.cuttinx.desktop.ui.Chips
import com.superessence.cuttinx.desktop.ui.Hint
import com.superessence.cuttinx.desktop.ui.LocalAccent
import com.superessence.cuttinx.desktop.ui.Title
import com.superessence.cuttinx.desktop.ui.openFolder

/** The video editor: library on the left, preview in the middle, inspector on the right, timeline below. */
@Composable
fun EditorScreen(m: EditorModel) {
    var showExport by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopBar(m) { showExport = true }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Library(m, Modifier.width(330.dp).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight()) {
                PreviewPane(m, Modifier.weight(1f).fillMaxWidth())
                Transport(m)
            }
            Inspector(m, Modifier.width(340.dp).fillMaxHeight())
        }
        Timeline(m, Modifier.fillMaxWidth().height(300.dp))
    }
    if (showExport) ExportDialog(m) { showExport = false }
}

@Composable
private fun TopBar(m: EditorModel, onExport: () -> Unit) {
    val p by m.project.collectAsState()
    val canUndo by m.canUndo.collectAsState()
    val canRedo by m.canRedo.collectAsState()
    var renaming by remember { mutableStateOf(false) }
    var name by remember(p?.id) { mutableStateOf(p?.name ?: "") }
    Row(Modifier.fillMaxWidth().background(Cx.Panel).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton({ m.close() }) { Icon(CxIcons.Back, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Projets") }
        if (renaming) {
            OutlinedTextField(name, { name = it }, Modifier.width(260.dp), singleLine = true)
            TextButton({ m.rename(name); renaming = false }) { Text("OK") }
        } else Text(p?.name ?: "", Modifier.clickable { renaming = true }.padding(horizontal = 8.dp), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("${p?.format?.width}×${p?.format?.height} · ${p?.format?.frameRate} i/s", color = Cx.Dim, fontSize = 12.sp)
        Spacer(Modifier.width(16.dp))
        var formatMenu by remember { mutableStateOf(false) }
        Box {
            TextButton({ formatMenu = true }) { Text("Format"); Icon(CxIcons.ChevronDown, null, Modifier.size(16.dp)) }
            DropdownMenu(formatMenu, { formatMenu = false }) {
                listOf(AspectPreset.PORTRAIT_9_16, AspectPreset.LANDSCAPE_16_9, AspectPreset.SQUARE_1_1, AspectPreset.PORTRAIT_4_5, AspectPreset.CINEMA_21_9).forEach { a ->
                    DropdownMenuItem({ Text(a.label) }, { m.changeFormat(a); formatMenu = false })
                }
            }
        }
        Spacer(Modifier.weight(1f))
        // one-click keyframe (◇+) next to undo / redo, like the pro editors
        val sel by m.selection.collectAsState()
        m.player.position.collectAsState().value
        val kfOn = sel.isNotEmpty() && m.anyKeyframeAtPlayhead()
        IconButton({ m.toggleQuickKeyframe() }, enabled = sel.isNotEmpty()) {
            Icon(if (kfOn) CxIcons.KeyframeRemove else CxIcons.KeyframeAdd, if (kfOn) "Retirer l'image clé" else "Ajouter une image clé", tint = if (kfOn) LocalAccent.current else if (sel.isNotEmpty()) Color.White else Cx.Dim)
        }
        TextButton({ m.undo() }, enabled = canUndo) { Icon(CxIcons.Undo, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Annuler") }
        TextButton({ m.redo() }, enabled = canRedo) { Icon(CxIcons.Redo, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Rétablir") }
        var more by remember { mutableStateOf(false) }
        Box {
            IconButton({ more = true }) { Icon(CxIcons.More, "Plus") }
            DropdownMenu(more, { more = false }) {
                DropdownMenuItem({ Text("Enregistrer le projet pour un autre appareil (.cuttinx)") }, {
                    more = false
                    saveFile("Enregistrer le projet", "${p?.name ?: "projet"}.cuttinx")?.let { m.exportArchive(it, includeMedia = true) }
                })
                DropdownMenuItem({ Text("Ouvrir le dossier CuttinxPRO") }, { more = false; openFolder(m.home) })
            }
        }
        Spacer(Modifier.width(8.dp))
        Button(onExport) { Icon(CxIcons.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Exporter la vidéo", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun PreviewPane(m: EditorModel, modifier: Modifier) {
    val frame by m.player.frame.collectAsState()
    val p by m.project.collectAsState()
    val sel by m.selection.collectAsState()
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.background(Color(0xFF08090B)).padding(12.dp), contentAlignment = Alignment.Center) {
        val pr = p ?: return@Box
        val ar = pr.format.width.toFloat() / pr.format.height
        Box(Modifier.fillMaxHeight().aspectRatio(ar, matchHeightConstraintsFirst = true).background(Color.Black).onSizeChanged { size = it }
            // drag on the picture moves the selected element (one undo step per drag)
            .pointerInput(sel) {
                detectDragGestures { ch, d ->
                    ch.consume()
                    val c = m.selectedClip ?: return@detectDragGestures
                    if (c.kind == ClipKind.ADJUSTMENT || size.width == 0) return@detectDragGestures
                    m.updateClip("Position", "pos_${c.id}") { cl -> cl.copy(transform = cl.transform.copy(posX = cl.transform.posX + d.x / size.width, posY = cl.transform.posY + d.y / size.height)) }
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { m.player.toggle() }) }
        ) {
            frame?.let { img ->
                val bmp = remember(img) { img.toComposeImageBitmap() }
                Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            if (pr.durationUs == 0L) Column(Modifier.align(Alignment.Center).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Importe tes vidéos pour commencer", fontWeight = FontWeight.Bold, color = Color.White)
                Hint("Bouton « Importer » à gauche, ou glisse-dépose des fichiers")
            }
        }
    }
}

@Composable
private fun Transport(m: EditorModel) {
    val pos by m.player.position.collectAsState()
    val playing by m.player.playing.collectAsState()
    val p by m.project.collectAsState()
    val fps = p?.format?.frameRate ?: 30
    val frameUs = 1_000_000L / fps
    Row(Modifier.fillMaxWidth().background(Cx.Panel).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text(timecode(pos, true, fps), Modifier.width(110.dp), fontWeight = FontWeight.Bold, color = LocalAccent.current)
        IconButton({ m.player.seek(0) }) { Icon(CxIcons.SkipStart, "Début") }
        IconButton({ m.player.seek(pos - frameUs) }) { Icon(CxIcons.Prev, "Image précédente") }
        Button({ m.player.toggle() }, Modifier.width(120.dp)) { Icon(if (playing) CxIcons.Pause else CxIcons.Play, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(if (playing) "Pause" else "Lecture", fontWeight = FontWeight.Bold) }
        IconButton({ m.player.seek(pos + frameUs) }) { Icon(CxIcons.Next, "Image suivante") }
        IconButton({ m.player.seek(p?.durationUs ?: 0) }) { Icon(CxIcons.SkipEnd, "Fin") }
        Text(timecode(p?.durationUs ?: 0, true, fps), Modifier.width(110.dp), color = Cx.Dim)
        Spacer(Modifier.width(18.dp))
        var q by remember { mutableStateOf(m.player.previewSide) }
        Text("Aperçu", color = Cx.Dim, fontSize = 12.sp)
        Spacer(Modifier.width(6.dp))
        Chips(listOf(540, 960, 1440), q, { when (it) { 540 -> "Fluide"; 960 -> "Normal"; else -> "HD" } }, { q = it; m.player.previewSide = it })
    }
}

@Composable
private fun ExportDialog(m: EditorModel, onClose: () -> Unit) {
    val p by m.project.collectAsState()
    val progress by m.export.collectAsState()
    val pr = p ?: return
    var side by remember { mutableStateOf(1080) }
    var fps by remember { mutableStateOf(pr.format.frameRate) }
    var webm by remember { mutableStateOf(pr.export.container == Container.WEBM) }
    var lastFile by remember { mutableStateOf<java.io.File?>(null) }
    Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable(enabled = progress == null) { onClose() }, contentAlignment = Alignment.Center) {
        Column(Modifier.width(520.dp).clip(RoundedCornerShape(18.dp)).background(Cx.Panel).clickable(enabled = false) {}.padding(24.dp)) {
            Text("Exporter la vidéo", fontSize = 22.sp, fontWeight = FontWeight.Black)
            Hint("Rendu image par image en qualité maximale, avec tous les effets, textes et sons.")
            Title("RÉSOLUTION")
            Chips(listOf(720, 1080, 1440, 2160), side, { when (it) { 720 -> "720p"; 1080 -> "1080p (recommandé)"; 1440 -> "2K"; else -> "4K" } }, { side = it })
            Title("IMAGES PAR SECONDE")
            Chips(listOf(24, 25, 30, 50, 60), fps, { "$it" }, { fps = it })
            Title("FORMAT")
            Chips(listOf(false, true), webm, { if (it) "WebM (VP9)" else "MP4 (H.264) — TikTok, YouTube, Insta" }, { webm = it })
            val (w, h) = exportSize(pr, side)
            Spacer(Modifier.height(10.dp))
            Hint("Sortie : $w×$h · durée ${timecode(pr.durationUs)}")
            Spacer(Modifier.height(16.dp))
            val pg = progress
            if (pg != null) {
                LinearProgressIndicator(progress = { pg.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pg.label, Modifier.weight(1f))
                    TextButton({ m.cancelExport() }) { Text("Annuler") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button({
                        val ext = if (webm) "webm" else "mp4"
                        saveFile("Enregistrer la vidéo", "${pr.name}.$ext")?.let { f ->
                            val target = if (f.extension.lowercase() == ext) f else java.io.File(f.path + ".$ext")
                            m.edit("Réglages d'export") { it.copy(export = it.export.copy(container = if (webm) Container.WEBM else Container.MP4, resolutionShortSide = side, frameRate = fps)) }
                            m.startExport(target, side, fps) { lastFile = it }
                        }
                    }) { Text("Choisir l'emplacement et exporter", fontWeight = FontWeight.Bold) }
                    OutlinedButton(onClose) { Text("Fermer") }
                }
                lastFile?.let { f ->
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(CxIcons.Check, null, tint = LocalAccent.current); Spacer(Modifier.width(6.dp)); Text(f.name, Modifier.weight(1f), color = LocalAccent.current)
                        TextButton({ openFolder(f.parentFile) }) { Text("Ouvrir le dossier") }
                    }
                }
            }
        }
    }
}
