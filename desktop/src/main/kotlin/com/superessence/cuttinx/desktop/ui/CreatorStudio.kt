package com.superessence.cuttinx.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.superessence.cuttinx.core.creator.BgKind
import com.superessence.cuttinx.core.creator.CreatorDesign
import com.superessence.cuttinx.core.creator.CreatorRenderer
import com.superessence.cuttinx.core.overlay.ItemAnim
import com.superessence.cuttinx.creator.CreatorSpace
import com.superessence.cuttinx.creator.PickedPicture
import com.superessence.cuttinx.desktop.AppState
import com.superessence.cuttinx.desktop.render.SkiaGfx

/** Studio Créateur on PC: the shared editor, pictures from the disk, PNG export at the exact size. */
@Composable
fun CreatorStudio(state: AppState) {
    val creator = state.creator
    val renderer = remember { CreatorRenderer(state.renderer) }
    fun pick(multiple: Boolean): List<PickedPicture> {
        val files = com.superessence.cuttinx.desktop.editor.openFiles(if (multiple) "Ajouter des images" else "Choisir une image", listOf("png", "jpg", "jpeg", "webp", "gif", "bmp"), multiple)
        val pics = files.mapNotNull { f -> encodePicture(f)?.let { (b64, w, h) -> PickedPicture(b64, w, h, f.nameWithoutExtension.take(40)) } }
        if (pics.size < files.size) creator.message = "${files.size - pics.size} image(s) illisible(s)"
        return pics
    }
    CreatorSpace(creator, state.brandKit,
        preview = { d, m, animated -> DesignCanvas(state, renderer, d, animated, m) },
        pickPictures = { rid -> pick(rid == null).takeIf { it.isNotEmpty() }?.let { creator.addPictures(it, rid) } },
        pickBackground = { pick(false).firstOrNull()?.let { creator.setBackgroundPicture(it) } },
        export = { d ->
            val file = chooseFile("Enregistrer le visuel", "${d.name.ifBlank { "visuel" }}.png") ?: return@CreatorSpace
            val target = if (file.extension.lowercase() == "png") file else java.io.File(file.path + ".png")
            runCatching {
                val fr = state.raster.frame(d.format.width, d.format.height)
                fr.gfx.clear(0)
                renderer.draw(fr.gfx, d, d.format.width.toFloat(), d.format.height.toFloat(), 0.0)
                target.writeBytes(fr.png())
            }.onSuccess { creator.message = "Exporté : ${target.absolutePath}"; openFolder(target.parentFile) }
                .onFailure { creator.message = "Export impossible : ${it.message}" }
        })
}

/** Vector preview drawn straight with Skia (sharp at any zoom), animated in the editor. */
@Composable
private fun DesignCanvas(state: AppState, renderer: CreatorRenderer, d: CreatorDesign, animated: Boolean, modifier: Modifier) {
    val moving = animated && (d.bg.kind == BgKind.MOTIF || d.items.any { it.anim != ItemAnim.NONE })
    var time by remember { mutableStateOf(0.0) }
    LaunchedEffect(moving) {
        if (!moving) { time = 0.0; return@LaunchedEffect }
        val start = withFrameNanos { it }
        while (true) withFrameNanos { now -> time = (now - start) / 1e9 }
    }
    Box(modifier.background(Color(0xFF111216))) {
        Canvas(Modifier.fillMaxSize()) {
            val t = time
            drawIntoCanvas { canvas ->
                val g = SkiaGfx(canvas.nativeCanvas, state.fonts)
                g.save()
                g.scale(size.width / d.format.width, size.height / d.format.height)
                renderer.draw(g, d, d.format.width.toFloat(), d.format.height.toFloat(), t)
                g.restore()
            }
        }
    }
}
