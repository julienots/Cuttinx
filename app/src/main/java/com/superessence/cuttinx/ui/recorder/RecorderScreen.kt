package com.superessence.cuttinx.ui.recorder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.util.Range
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.superessence.cuttinx.core.smart.Plus
import com.superessence.cuttinx.core.smart.Tech
import com.superessence.cuttinx.design.CxDesign
import com.superessence.cuttinx.lab.TechGate
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File

/** What the recorder hands back: the takes (in order) and whether their silences should be cut. */
class RecordedTakes(val uris: List<Uri>, val autoCut: Boolean)

/** Takes waiting to be added to an open project (the recorder was opened from the editor). */
object PendingTakes {
    var forProject: String? = null
    var takes: RecordedTakes? = null
    /** A new project made of takes whose silences must be cut when the editor opens. */
    var autoCutProject: String? = null
}

private enum class Res(val label: String, val quality: Quality) { HD("720p", Quality.HD), FHD("1080p", Quality.FHD), UHD("4K", Quality.UHD) }

/**
 * The CUTTINX camera: films takes in up to 4K with stabilisation, flash, zoom, tap-to-focus,
 * exposure, grid, timer, pause and a teleprompter. "Monter" puts every take straight into the
 * edit; with "Couper les blancs" (CUTTINX Plus) the silences are removed right away.
 */
@SuppressLint("MissingPermission")
@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun RecorderScreen(onClose: () -> Unit, onDone: (RecordedTakes) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val accent = Color(0xFFFFCC1F)
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    var camOk by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    var micOk by remember { mutableStateOf(granted(Manifest.permission.RECORD_AUDIO)) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        camOk = r[Manifest.permission.CAMERA] ?: camOk; micOk = r[Manifest.permission.RECORD_AUDIO] ?: micOk
    }
    LaunchedEffect(Unit) { if (!camOk || !micOk) perms.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }

    // ---- options
    var back by remember { mutableStateOf(true) }
    var res by remember { mutableStateOf(Res.FHD) }
    var stabilize by remember { mutableStateOf(true) }
    var torch by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(true) }
    var timer by remember { mutableIntStateOf(0) }
    var sound by remember { mutableStateOf(true) }
    var autoCut by remember { mutableStateOf(false) }
    var prompter by remember { mutableStateOf(false) }
    var prompterText by remember { mutableStateOf("Écris ici ton texte : il défile pendant que tu filmes.") }
    var prompterSpeed by remember { mutableFloatStateOf(1f) }
    var exposure by remember { mutableFloatStateOf(0f) }

    // ---- camera state
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var capture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var paused by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var countdown by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var focus by remember { mutableStateOf<Offset?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val takes = remember { mutableStateListOf<File>() }

    LaunchedEffect(camOk, back, res, stabilize) {
        if (!camOk) return@LaunchedEffect
        val provider = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ProcessCameraProvider.getInstance(ctx).get() } }.getOrNull() ?: run { error = "Caméra indisponible"; return@LaunchedEffect }
        val pb = Preview.Builder()
        if (stabilize) Camera2Interop.Extender(pb).setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        val preview = pb.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(res.quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build()
        val vc = VideoCapture.withOutput(recorder)
        val selector = if (back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        runCatching {
            provider.unbindAll()
            camera = provider.bindToLifecycle(owner, selector, preview, vc)
            capture = vc
            zoom = 1f; torch = false; exposure = 0f; error = null
        }.onFailure { error = "Cette caméra ne prend pas en charge ce réglage (${it.message})" }
    }
    DisposableEffect(Unit) { onDispose { runCatching { recording?.stop() }; runCatching { ProcessCameraProvider.getInstance(ctx).get().unbindAll() } } }
    LaunchedEffect(torch, camera) { runCatching { camera?.cameraControl?.enableTorch(torch) } }
    LaunchedEffect(zoom, camera) { runCatching { camera?.cameraControl?.setZoomRatio(zoom) } }
    LaunchedEffect(exposure, camera) {
        val c = camera ?: return@LaunchedEffect
        val r: Range<Int> = c.cameraInfo.exposureState.exposureCompensationRange
        if (r.lower < r.upper) runCatching { c.cameraControl.setExposureCompensationIndex((exposure * if (exposure >= 0) r.upper else -r.lower).toInt()) }
    }
    LaunchedEffect(recording, paused) {
        if (recording == null) { elapsedMs = 0; return@LaunchedEffect }
        while (isActive && recording != null && !paused) { delay(100); elapsedMs += 100 }
    }

    fun startRecording() {
        val vc = capture ?: return
        val dir = File(ctx.filesDir, "recordings").apply { mkdirs() }
        val file = File(dir, "prise_${System.currentTimeMillis()}.mp4")
        val pending = vc.output.prepareRecording(ctx, FileOutputOptions.Builder(file).build())
        val rec = (if (sound && micOk) pending.withAudioEnabled() else pending).start(ContextCompat.getMainExecutor(ctx)) { e ->
            if (e is VideoRecordEvent.Finalize) {
                if (!e.hasError() || e.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) { if (file.length() > 0) takes += file }
                else { error = "Prise non enregistrée (code ${e.error})"; file.delete() }
            }
        }
        recording = rec; paused = false
    }
    fun stopRecording() { runCatching { recording?.stop() }; recording = null; paused = false }

    LaunchedEffect(countdown) {
        if (countdown <= 0) return@LaunchedEffect
        delay(1000)
        if (countdown == 1) { countdown = 0; startRecording() } else countdown--
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (camOk) AndroidView({ previewView }, Modifier.fillMaxSize()
            .pointerInput(camera) {
                detectTapGestures { o ->
                    val c = camera ?: return@detectTapGestures
                    focus = o
                    val pt = previewView.meteringPointFactory.createPoint(o.x, o.y)
                    runCatching { c.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(pt).build()) }
                }
            }
            .pointerInput(camera) {
                detectTransformGestures { _, _, z, _ ->
                    val st = camera?.cameraInfo?.zoomState?.value ?: return@detectTransformGestures
                    zoom = (zoom * z).coerceIn(st.minZoomRatio, st.maxZoomRatio)
                }
            })
        else Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("CUTTINX a besoin de la caméra pour filmer.", color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Button({ perms.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }, shape = CxDesign.Button,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black)) { Text("Autoriser la caméra") }
        }

        // rule-of-thirds grid
        if (grid) Canvas(Modifier.fillMaxSize()) {
            val c = Color.White.copy(alpha = 0.28f)
            for (i in 1..2) {
                drawLine(c, Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height), 1.5f)
                drawLine(c, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), 1.5f)
            }
        }
        focus?.let { f ->
            LaunchedEffect(f) { delay(900); focus = null }
            Canvas(Modifier.fillMaxSize()) { drawCircle(accent, 46f, f, style = androidx.compose.ui.graphics.drawscope.Stroke(3f)) }
        }

        // teleprompter: scrolls by itself while recording
        if (prompter) {
            val scroll = rememberScrollState()
            LaunchedEffect(recording, paused, prompterSpeed) {
                while (isActive && recording != null && !paused) { delay(40); scroll.scrollTo((scroll.value + (2 * prompterSpeed).toInt()).coerceAtMost(scroll.maxValue)) }
            }
            Column(Modifier.align(Alignment.TopCenter).padding(top = 110.dp, start = 16.dp, end = 16.dp).fillMaxWidth().height(170.dp)
                .clip(CxDesign.Card).background(Color.Black.copy(alpha = 0.55f)).padding(12.dp)) {
                BasicTextField(prompterText, { prompterText = it }, Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll),
                    textStyle = TextStyle(color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 30.sp, textAlign = TextAlign.Center),
                    cursorBrush = SolidColor(accent), enabled = recording == null)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Vitesse", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                    Slider(prompterSpeed, { prompterSpeed = it }, Modifier.weight(1f).height(24.dp), valueRange = 0.3f..3f,
                        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent))
                }
            }
        }

        if (countdown > 0) Text("$countdown", Modifier.align(Alignment.Center), color = Color.White, fontSize = 120.sp, fontWeight = FontWeight.Black)

        // ---- top: options
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.35f)).statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.Default.Close, "Fermer", tint = Color.White) }
                Spacer(Modifier.weight(1f))
                if (recording != null) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (paused) Color.Gray else Color(0xFFFF3B30)))
                    Spacer(Modifier.width(6.dp))
                    Text(clock(elapsedMs), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(Modifier.weight(1f))
                } else {
                    Text(res.label + if (stabilize) " · stabilisé" else "", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                }
                IconButton({ torch = !torch }, enabled = camera?.cameraInfo?.hasFlashUnit() == true) {
                    Icon(if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff, "Lampe", tint = if (torch) accent else Color.White)
                }
            }
            AnimatedVisibility(recording == null) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Opt(Icons.Default.HighQuality, res.label, true, accent) { res = Res.entries[(res.ordinal + 1) % Res.entries.size] }
                    Opt(Icons.Default.Vibration, "Stabilisation", stabilize, accent) { stabilize = !stabilize }
                    Opt(Icons.Default.Timer, if (timer == 0) "Retardateur" else "$timer s", timer > 0, accent) { timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 } }
                    Opt(Icons.Default.Grid3x3, "Grille", grid, accent) { grid = !grid }
                    Opt(Icons.Default.TextFields, "Prompteur", prompter, accent) { prompter = !prompter }
                    Opt(if (sound) Icons.Default.Mic else Icons.Default.MicOff, if (sound) "Son" else "Muet", sound, accent) { sound = !sound }
                    Opt(Icons.Default.ContentCut, "Couper les blancs" + if (Plus.active) "" else " · PLUS", autoCut, accent) {
                        if (autoCut) autoCut = false else TechGate.request(Tech.JUMP_CUT) { autoCut = true }
                    }
                }
            }
        }

        // ---- right: exposure
        if (camera != null) Column(Modifier.align(Alignment.CenterEnd).padding(end = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.WbSunny, "Exposition", tint = Color.White, modifier = Modifier.size(18.dp))
            Slider(exposure, { exposure = it }, Modifier.width(150.dp).graphicsRotate(), valueRange = -1f..1f,
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = Color.White.copy(alpha = 0.6f), inactiveTrackColor = Color.White.copy(alpha = 0.25f)))
        }

        // ---- bottom: zoom, record, flip, finish
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.35f)).navigationBarsPadding().padding(bottom = 14.dp, top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp)) }
            val zs = camera?.cameraInfo?.zoomState?.value
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (z in listOf(0.5f, 1f, 2f, 5f).filter { zs == null || it in zs.minZoomRatio..zs.maxZoomRatio }) {
                    val on = kotlin.math.abs(zoom - z) < 0.05f
                    Text(if (z < 1f) ".5×" else "${z.toInt()}×", Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).border(1.dp, if (on) accent else Color.Transparent, CircleShape)
                        .clickable { zoom = z }.padding(horizontal = 10.dp, vertical = 6.dp), color = if (on) accent else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                // takes / finish
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (takes.isNotEmpty() && recording == null) Button({ onDone(RecordedTakes(takes.map { Uri.fromFile(it) }, autoCut)) }, shape = CxDesign.Button,
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                        Text("Monter (${takes.size})", fontWeight = FontWeight.SemiBold)
                    } else if (recording != null) IconButton({ val r = recording ?: return@IconButton; if (paused) { r.resume(); paused = false } else { r.pause(); paused = true } }) {
                        Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (paused) "Reprendre" else "Pause", tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                }
                // record button
                Box(Modifier.size(78.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).clickable(enabled = camera != null && countdown == 0) {
                    if (recording != null) stopRecording() else if (timer > 0) countdown = timer else startRecording()
                }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(if (recording != null) 30.dp else 62.dp).clip(if (recording != null) RoundedCornerShape(6.dp) else CircleShape).background(Color(0xFFFF3B30)))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    if (recording == null) IconButton({ back = !back }) { Icon(Icons.Default.Cameraswitch, "Changer de caméra", tint = Color.White, modifier = Modifier.size(30.dp)) }
                }
            }
            if (takes.isNotEmpty() && recording == null) Text("${takes.size} prise(s) · elles iront directement dans le montage" + if (autoCut) ", blancs coupés" else "",
                color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

private fun Modifier.graphicsRotate() = this.then(Modifier.layoutRotate270())

/** Vertical slider: rotated -90°, with its layout size swapped. */
private fun Modifier.layoutRotate270(): Modifier = this
    .layout { m, c ->
        val p = m.measure(c.copy(minWidth = c.minHeight, maxWidth = c.maxHeight, minHeight = c.minWidth, maxHeight = c.maxWidth))
        layout(p.height, p.width) { p.placeWithLayer(-(p.width - p.height) / 2, (p.width - p.height) / 2) { rotationZ = -90f } }
    }

@Composable
private fun Opt(icon: ImageVector, label: String, on: Boolean, accent: Color, onClick: () -> Unit) {
    Row(Modifier.clip(CxDesign.Chip).background(if (on) accent.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.35f))
        .border(1.dp, if (on) accent.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.2f), CxDesign.Chip)
        .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = if (on) accent else Color.White)
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (on) accent else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

private fun clock(ms: Long): String { val s = ms / 1000; return "%02d:%02d".format(s / 60, s % 60) }
