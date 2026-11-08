package com.superessence.cuttinx.ui.create

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.cuttinx.core.create.CreateKit
import com.superessence.cuttinx.core.create.PhotoAdjust
import com.superessence.cuttinx.core.create.PhotoFrame
import com.superessence.cuttinx.core.create.PhotoKit
import com.superessence.cuttinx.core.effects.EffectPreset
import com.superessence.cuttinx.core.effects.Presets
import com.superessence.cuttinx.core.smart.Magic
import com.superessence.cuttinx.core.smart.Plus
import com.superessence.cuttinx.core.smart.Tech
import com.superessence.cuttinx.design.CxDesign
import com.superessence.cuttinx.design.cxChip
import com.superessence.cuttinx.engine.FilterPreviews
import com.superessence.cuttinx.lab.TechGate
import com.superessence.cuttinx.ui.components.ValueSlider
import com.superessence.cuttinx.ui.theme.LocalCx
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class PhotoTab(val label: String) { LOOKS("Filtres"), ADJUST("Réglages"), CROP("Recadrer") }

/**
 * Studio Photo: the GPU filters and effects of the video editor on a photo, pro adjustments,
 * crops for every network, automatic enhancement (CUTTINX Plus), and a full-size JPEG export.
 * Touch and hold the picture to see the original.
 */
@Composable
fun PhotoStudio(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cx = LocalCx.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var work by remember { mutableStateOf<Bitmap?>(null) }        // ≤ 1440 px, the base of the preview
    var thumb by remember { mutableStateOf<Bitmap?>(null) }       // 200 px, the base of the filter pictures
    var look by remember { mutableStateOf<EffectPreset?>(null) }
    var lookAmount by remember { mutableFloatStateOf(1f) }
    var adj by remember { mutableStateOf(PhotoAdjust()) }
    var frame by remember { mutableStateOf(PhotoFrame.ORIGINAL) }
    var rotation by remember { mutableIntStateOf(0) }
    var tab by remember { mutableStateOf(PhotoTab.LOOKS) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var original by remember { mutableStateOf<ImageBitmap?>(null) }
    var showOriginal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var cat by remember { mutableStateOf<String?>(null) }
    val snack = remember { SnackbarHostState() }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        if (u != null) scope.launch {
            busy = "Ouverture de la photo…"
            val b = withContext(kotlinx.coroutines.Dispatchers.IO) { CreateIo.loadBitmap(ctx, u, 1440) }
            if (b == null) snack.showSnackbar("Impossible d'ouvrir cette photo") else {
                uri = u; work = b; look = null; adj = PhotoAdjust(); frame = PhotoFrame.ORIGINAL; rotation = 0
                val k = 200f / maxOf(b.width, b.height)
                thumb = Bitmap.createScaledBitmap(b, maxOf(2, (b.width * k).toInt()), maxOf(2, (b.height * k).toInt()), true)
            }
            busy = null
        }
    }
    LaunchedEffect(Unit) { if (uri == null) pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    // the crop and rotation come first, then the GPU chain (so the vignette follows the final frame)
    val framed = remember(work, frame, rotation) {
        work?.let { w -> val r = CreateIo.rotate(w, rotation); CreateIo.cropRotate(r, CreateKit.centerCrop(r.width, r.height, frame), 0) }
    }
    LaunchedEffect(framed, look, lookAmount, adj) {
        val src = framed ?: return@LaunchedEffect
        original = src.asImageBitmap()
        delay(40)
        val out = withContext(FilterPreviews.dispatcher) { FilterPreviews.renderFull(src, PhotoKit.effects(look, adj, lookAmount.toDouble())) }
        preview = (out ?: src).asImageBitmap()
    }

    fun export() {
        val u = uri ?: return
        scope.launch {
            busy = "Enregistrement en pleine qualité…"
            val saved = withContext(kotlinx.coroutines.Dispatchers.Default) {
                val max = withContext(FilterPreviews.dispatcher) { FilterPreviews.maxSide() }.coerceAtMost(4096)
                val big = CreateIo.loadBitmap(ctx, u, max) ?: return@withContext null
                val r = CreateIo.rotate(big, rotation)
                val src = CreateIo.cropRotate(r, CreateKit.centerCrop(r.width, r.height, frame), 0)
                val out = withContext(FilterPreviews.dispatcher) { FilterPreviews.renderFull(src, PhotoKit.effects(look, adj, lookAmount.toDouble())) } ?: src
                CreateIo.saveJpeg(ctx, out, "CUTTINX_${CreateIo.stamp()}")
            }
            busy = null
            snack.showSnackbar(if (saved != null) "Photo enregistrée dans la galerie (dossier Cuttinx)" else "Enregistrement impossible")
        }
    }

    fun autoEnhance() {
        val w = work ?: return
        TechGate.request(Tech.SHOT_MATCH) {
            val s = Bitmap.createScaledBitmap(w, 64, maxOf(8, 64 * w.height / w.width), true)
            val px = IntArray(s.width * s.height); s.getPixels(px, 0, s.width, 0, 0, s.width, s.height)
            Magic.measure(listOf(com.superessence.cuttinx.core.analysis.Frame(0, s.width, s.height, px)))?.let { adj = PhotoKit.auto(it); tab = PhotoTab.ADJUST }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snack) },
        topBar = { StudioBar("Studio Photo", "NOUVEAU", onBack, if (work != null) "Exporter" else null, busy == null) { export() } }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (work == null) StudioEmpty("Retouche tes photos", "Les filtres et effets de CUTTINX, des réglages pro et des formats prêts pour les réseaux.", "Choisir une photo") {
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            } else Column(Modifier.fillMaxSize()) {
                // ---- picture (hold to compare)
                Box(Modifier.fillMaxWidth().weight(1f).background(androidx.compose.ui.graphics.Color.Black)
                    .pointerInput(Unit) { detectTapGestures(onPress = { showOriginal = true; tryAwaitRelease(); showOriginal = false }) },
                    contentAlignment = Alignment.Center) {
                    val img = if (showOriginal) original else preview
                    if (img != null) Image(img, null, Modifier.fillMaxSize().padding(10.dp), contentScale = ContentScale.Fit)
                    Text(if (showOriginal) "Original" else "Maintiens pour comparer", Modifier.align(Alignment.TopStart).padding(12.dp)
                        .clip(CxDesign.Chip).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 3.dp),
                        color = androidx.compose.ui.graphics.Color.White, fontSize = 11.sp)
                }
                // ---- tabs
                Row(Modifier.fillMaxWidth().background(cx.panel).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (t in PhotoTab.entries) Text(t.label, Modifier.cxChip(t == tab, cx.accent) { tab = t }, color = if (t == tab) cx.accent else MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    Text("Auto" + if (Plus.active) "" else " · PLUS", Modifier.cxChip(false, cx.accent) { autoEnhance() }, color = cx.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Column(Modifier.fillMaxWidth().height(260.dp).background(cx.panel).padding(horizontal = 12.dp)) {
                    when (tab) {
                        PhotoTab.LOOKS -> {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (c in listOf<String?>(null) + Presets.CATEGORIES.take(5)) Text(c ?: "Tous", Modifier.cxChip(c == cat, cx.accent) { cat = c }, fontSize = 11.sp,
                                    color = if (c == cat) cx.accent else MaterialTheme.colorScheme.onSurface, maxLines = 1)
                            }
                            Spacer(Modifier.height(8.dp))
                            val list = Presets.builtIn.filter { cat == null || it.category == cat }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item { LookTile("Aucun", null, thumb, look == null) { look = null } }
                                items(list, key = { it.id }) { pr -> LookTile(pr.name, pr, thumb, look?.id == pr.id) { look = pr; lookAmount = 1f } }
                            }
                            if (look != null) ValueSlider("Intensité du filtre", lookAmount, 0f..1f, { lookAmount = it }, unit = "", decimals = 2)
                        }
                        PhotoTab.ADJUST -> Column(Modifier.verticalScroll(rememberScrollState())) {
                            @Composable fun s(label: String, v: Double, range: ClosedFloatingPointRange<Float> = -1f..1f, set: (Double) -> PhotoAdjust) =
                                ValueSlider(label, v.toFloat(), range, { adj = set(it.toDouble()) }, default = 0f)
                            s("Exposition", adj.exposure, -2f..2f) { adj.copy(exposure = it) }
                            s("Contraste", adj.contrast) { adj.copy(contrast = it) }
                            s("Hautes lumières", adj.highlights) { adj.copy(highlights = it) }
                            s("Ombres", adj.shadows) { adj.copy(shadows = it) }
                            s("Saturation", adj.saturation) { adj.copy(saturation = it) }
                            s("Vibrance", adj.vibrance) { adj.copy(vibrance = it) }
                            s("Température", adj.temperature) { adj.copy(temperature = it) }
                            s("Teinte", adj.tint) { adj.copy(tint = it) }
                            s("Clarté", adj.clarity) { adj.copy(clarity = it) }
                            s("Peau lissée", adj.skin, 0f..1f) { adj.copy(skin = it) }
                            s("Vignettage", adj.vignette, 0f..1f) { adj.copy(vignette = it) }
                            s("Grain", adj.grain, 0f..1f) { adj.copy(grain = it) }
                            if (!adj.untouched) TextButton({ adj = PhotoAdjust() }) { Text("Tout remettre à zéro") }
                        }
                        PhotoTab.CROP -> Column {
                            Text("Format", color = cx.dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (f in PhotoFrame.entries) Text(f.label, Modifier.cxChip(f == frame, cx.accent) { frame = f }, fontSize = 12.sp,
                                    color = if (f == frame) cx.accent else MaterialTheme.colorScheme.onSurface)
                            }
                            Spacer(Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton({ rotation = (rotation + 270) % 360 }, shape = CxDesign.Button) { Text("↺ 90°") }
                                OutlinedButton({ rotation = (rotation + 90) % 360 }, shape = CxDesign.Button) { Text("↻ 90°") }
                                OutlinedButton({ pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, shape = CxDesign.Button) { Text("Autre photo") }
                            }
                            Spacer(Modifier.height(10.dp))
                            Text("1:1 et 4:5 pour Instagram, 9:16 pour les stories et TikTok, 16:9 pour YouTube. L'export garde la pleine définition de ta photo.", color = cx.dim, fontSize = 12.sp)
                        }
                    }
                }
            }
            busy?.let { BusyVeil(it) }
        }
    }
}

/** A filter shown on the photo itself (GPU picture computed once, then cached). */
@Composable
private fun LookTile(name: String, pr: EffectPreset?, thumb: Bitmap?, selected: Boolean, onClick: () -> Unit) {
    val cx = LocalCx.current
    val key = "photo_${thumb?.generationId}"
    val img by produceState<ImageBitmap?>(null, thumb, pr?.id) {
        val t = thumb ?: return@produceState
        value = if (pr == null) t.asImageBitmap()
        else runCatching { withContext(FilterPreviews.dispatcher) { FilterPreviews.render(t, key, pr.id, pr.effects) } }.getOrNull()?.asImageBitmap()
    }
    Column(Modifier.width(78.dp).clip(CxDesign.Card).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(78.dp).clip(CxDesign.Card).background(cx.panel2).border(2.dp, if (selected) cx.accent else cx.line, CxDesign.Card)) {
            img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Text(name, Modifier.padding(top = 4.dp), fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            color = if (selected) cx.accent else MaterialTheme.colorScheme.onSurface)
    }
}
