package com.superessence.cuttinx.ui.creator

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.superessence.cuttinx.core.creator.CreatorDesign
import com.superessence.cuttinx.core.creator.BgKind
import com.superessence.cuttinx.core.overlay.ItemAnim
import com.superessence.cuttinx.creator.CreatorSpace
import com.superessence.cuttinx.ui.twitch.StudioViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Studio Créateur on Android: the shared editor + pictures from the gallery + PNG export. */
@Composable
fun CreatorScreen(vm: StudioViewModel) {
    val kit by vm.brandKit.collectAsStateWithLifecycle()
    val state = vm.creator
    var replaceId by remember { mutableStateOf<String?>(null) }
    var toExport by remember { mutableStateOf<CreatorDesign?>(null) }
    val addPics = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) vm.pickPictures(uris) { state.addPictures(it, null) }
    }
    val replacePic = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val id = replaceId
        if (uri != null && id != null) vm.pickPictures(listOf(uri)) { state.addPictures(it, id) }
    }
    val bgPic = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.pickPictures(listOf(uri)) { state.setBackgroundPicture(it.first()) }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val d = toExport
        if (uri != null && d != null) vm.exportDesign(uri, d)
    }
    val msg by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(msg) { msg?.let { state.message = it; vm.message.value = null } }
    CreatorSpace(state, kit,
        preview = { d, m, animated -> DesignPreview(vm, d, animated, m) },
        pickPictures = { rid -> if (rid == null) addPics.launch("image/*") else { replaceId = rid; replacePic.launch("image/*") } },
        pickBackground = { bgPic.launch("image/*") },
        export = { d -> toExport = d; save.launch("${d.name}.png") })
}

/** Software rendering of a visual at the size it is shown (animated in the editor only). */
@Composable
private fun DesignPreview(vm: StudioViewModel, d: CreatorDesign, animated: Boolean, modifier: Modifier) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    val latest by rememberUpdatedState(d)
    // render size: what is on screen, capped (sharp enough, light enough for phones)
    val k = if (size.width == 0) 0f else minOf(1f, 1600f / size.width, 1600f / maxOf(1, size.height))
    val w = (size.width * k).toInt(); val h = (size.height * k).toInt()
    val moving = animated && (d.bg.kind == BgKind.MOTIF || d.items.any { it.anim != ItemAnim.NONE })
    LaunchedEffect(w, h, if (animated) Unit else d, moving) {
        if (w <= 0 || h <= 0) return@LaunchedEffect
        val frames = List(if (animated) 3 else 1) { vm.raster.frame(w, h) }
        val start = System.nanoTime(); var i = 0
        var drawn: CreatorDesign? = null
        while (isActive) {
            val cur = latest
            if (cur === drawn && !moving) { delay(30); continue }
            val f = frames[i % frames.size]
            val t = if (moving) (System.nanoTime() - start) / 1e9 else 0.0
            withContext(Dispatchers.Default) {
                f.gfx.clear(0)
                vm.creatorRenderer.draw(f.gfx, cur, w.toFloat(), h.toFloat(), t)
            }
            image = f.bitmap.asImageBitmap()
            drawn = cur; i++
            if (!animated) break
            delay(if (moving) 16 else 30)
        }
    }
    Box(modifier.background(Color(0xFF111216)).onSizeChanged { size = it }) {
        image?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds) }
    }
}
