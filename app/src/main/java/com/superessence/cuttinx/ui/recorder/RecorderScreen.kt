package com.superessence.cuttinx.ui.recorder

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.util.Range
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MirrorMode
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.superessence.cuttinx.core.smart.Plus
import com.superessence.cuttinx.core.smart.Tech
import com.superessence.cuttinx.design.CxDesign
import com.superessence.cuttinx.lab.TechGate
import com.superessence.cuttinx.recording.ScreenAudio
import com.superessence.cuttinx.recording.ScreenOptions
import com.superessence.cuttinx.recording.ScreenQuality
import com.superessence.cuttinx.recording.ScreenRecState
import com.superessence.cuttinx.recording.ScreenRecordService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2

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
private enum class Mode(val label: String) { CAMERA("CAMÉRA"), SCREEN("ÉCRAN") }
private enum class Safe(val label: String) { OFF("Zones sûres"), TIKTOK("TikTok"), REELS("Reels"), SHORTS("Shorts") }
private val MAX_DURATIONS = listOf(0, 15, 30, 60, 180)

/**
 * The CUTTINX camera: films takes in up to 4K / 60 i/s (HDR when the phone can), with stabilisation,
 * flash, zoom, tap-to-focus, AE/AF lock, exposure, grid, level, safe zones, selfie ring light,
 * mirror, timer, max duration, pause and a teleprompter. The ÉCRAN mode films the phone screen
 * (games, apps, tutorials) with the phone sound and / or the microphone. "Monter" puts every take
 * straight into the edit; with "Couper les blancs" (CUTTINX Plus) the silences are removed right away.
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

    // ---- mode & shared options
    var mode by remember { mutableStateOf(Mode.CAMERA) }
    var maxDur by remember { mutableIntStateOf(0) }
    var autoCut by remember { mutableStateOf(false) }
    val takes = remember { mutableStateListOf<File>() }
    var error by remember { mutableStateOf<String?>(null) }

    // ---- camera options
    var back by remember { mutableStateOf(true) }
    var res by remember { mutableStateOf(Res.FHD) }
    var fps by remember { mutableIntStateOf(30) }
    var hdr by remember { mutableStateOf(false) }
    var stabilize by remember { mutableStateOf(true) }
    var torch by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(true) }
    var level by remember { mutableStateOf(false) }
    var safe by remember { mutableStateOf(Safe.OFF) }
    var ringLight by remember { mutableStateOf(false) }
    var mirror by remember { mutableStateOf(true) }
    var timer by remember { mutableIntStateOf(0) }
    var sound by remember { mutableStateOf(true) }
    var prompter by remember { mutableStateOf(false) }
    var prompterText by remember { mutableStateOf("Écris ici ton texte : il défile pendant que tu filmes.") }
    var prompterSpeed by remember { mutableFloatStateOf(1f) }
    var exposure by remember { mutableFloatStateOf(0f) }

    // ---- screen options
    var sQuality by remember { mutableStateOf(ScreenQuality.HD) }
    var sFps by remember { mutableIntStateOf(30) }
    var sAudio by remember { mutableStateOf(if (Build.VERSION.SDK_INT >= 29) ScreenAudio.DEVICE else ScreenAudio.MIC) }
    var sMinimize by remember { mutableStateOf(true) }
    val screen by ScreenRecState.state.collectAsStateWithLifecycle()

    // ---- camera state
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var capture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var paused by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var bytes by remember { mutableLongStateOf(0L) }
    var countdown by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var focus by remember { mutableStateOf<Offset?>(null) }
    var locked by remember { mutableStateOf(false) }
    var can60 by remember { mutableStateOf(false) }
    var canHdr by remember { mutableStateOf(false) }
    var activeFps by remember { mutableIntStateOf(30) }
    var activeHdr by remember { mutableStateOf(false) }

    LaunchedEffect(mode, camOk, back, res, stabilize, fps, hdr, mirror) {
        val provider = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ProcessCameraProvider.getInstance(ctx).get() } }.getOrNull()
        if (mode != Mode.CAMERA || !camOk) { runCatching { provider?.unbindAll() }; camera = null; capture = null; return@LaunchedEffect }
        if (provider == null) { error = "Caméra indisponible"; return@LaunchedEffect }
        val selector = if (back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        val info = runCatching { selector.filter(provider.availableCameraInfos).firstOrNull() }.getOrNull()
        can60 = info?.supportedFrameRateRanges?.any { it.upper >= 60 } == true
        canHdr = info?.let { i -> runCatching { Recorder.getVideoCapabilities(i).supportedDynamicRanges.any { it != DynamicRange.SDR } }.getOrDefault(false) } == true
        fun bind(advanced: Boolean): Boolean {
            val pb = Preview.Builder()
            if (stabilize) Camera2Interop.Extender(pb).setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            val preview = pb.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(res.quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build()
            val vb = VideoCapture.Builder(recorder).setMirrorMode(if (mirror) MirrorMode.MIRROR_MODE_ON_FRONT_ONLY else MirrorMode.MIRROR_MODE_OFF)
            val want60 = advanced && fps == 60 && can60
            val wantHdr = advanced && hdr && canHdr
            if (want60) vb.setTargetFrameRate(Range(60, 60))
            if (wantHdr) vb.setDynamicRange(DynamicRange.HLG_10_BIT)
            val vc = vb.build()
            return runCatching {
                provider.unbindAll()
                camera = provider.bindToLifecycle(owner, selector, preview, vc)
                capture = vc
                activeFps = if (want60) 60 else 30; activeHdr = wantHdr
                zoom = 1f; torch = false; exposure = 0f; locked = false; error = null
            }.isSuccess
        }
        if (!bind(true)) {
            if (bind(false)) error = if (fps == 60 || hdr) "60 i/s ou HDR non pris en charge ensemble ici : réglage standard utilisé" else null
            else error = "Cette caméra ne prend pas en charge ce réglage"
        }
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
        if (recording == null) { elapsedMs = 0; bytes = 0; return@LaunchedEffect }
        while (isActive && recording != null && !paused) { delay(100); elapsedMs += 100 }
    }

    // selfie ring light: white frame + screen at full brightness
    val activity = remember(ctx) { ctx.findActivity() }
    val ringOn = ringLight && !back && mode == Mode.CAMERA
    DisposableEffect(ringOn) {
        val w = activity?.window
        val old = w?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (ringOn && w != null) w.attributes = w.attributes.apply { screenBrightness = 1f }
        onDispose { if (w != null) w.attributes = w.attributes.apply { screenBrightness = old } }
    }
    // keep the screen awake while filming
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // spirit level (horizon)
    var roll by remember { mutableFloatStateOf(0f) }
    DisposableEffect(level) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val acc = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { roll = roll * 0.8f + Math.toDegrees(atan2(e.values[0].toDouble(), e.values[1].toDouble())).toFloat() * 0.2f }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (level && acc != null) sm.registerListener(l, acc, SensorManager.SENSOR_DELAY_UI)
        onDispose { runCatching { sm?.unregisterListener(l) } }
    }

    fun startRecording() {
        val vc = capture ?: return
        val dir = File(ctx.filesDir, "recordings").apply { mkdirs() }
        val file = File(dir, "prise_${System.currentTimeMillis()}.mp4")
        val pending = vc.output.prepareRecording(ctx, FileOutputOptions.Builder(file).build())
        val rec = (if (sound && micOk) pending.withAudioEnabled() else pending).start(ContextCompat.getMainExecutor(ctx)) { e ->
            if (e is VideoRecordEvent.Status) bytes = e.recordingStats.numBytesRecorded
            if (e is VideoRecordEvent.Finalize) {
                if (!e.hasError() || e.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE || e.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED) { if (file.length() > 0) takes += file }
                else { error = "Prise non enregistrée (code ${e.error})"; file.delete() }
            }
        }
        recording = rec; paused = false
    }
    fun stopRecording() { runCatching { recording?.stop() }; recording = null; paused = false }
    // max duration: stop by itself
    LaunchedEffect(elapsedMs, maxDur) { if (recording != null && maxDur > 0 && elapsedMs >= maxDur * 1000L) stopRecording() }

    LaunchedEffect(countdown) {
        if (countdown <= 0) return@LaunchedEffect
        delay(1000)
        if (countdown == 1) { countdown = 0; startRecording() } else countdown--
    }

    // ---- screen capture
    val screenBusy = screen.phase != ScreenRecState.Phase.IDLE
    LaunchedEffect(screen.phase) { takes += ScreenRecState.takeFinished() }
    LaunchedEffect(screen.error, screen.note) { (screen.error ?: screen.note)?.let { error = it } }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
            ScreenRecordService.start(ctx, r.resultCode, data, ScreenOptions(sQuality, sFps, if (micOk) sAudio else ScreenAudio.NONE, maxDur))
            if (sMinimize) activity?.moveTaskToBack(true)
        } else error = "Capture d'écran annulée"
    }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun startScreen() {
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (sAudio != ScreenAudio.NONE && !micOk) { perms.launch(arrayOf(Manifest.permission.RECORD_AUDIO)); error = "Autorise le micro pour capturer le son, puis relance"; return }
        runCatching { consent.launch(ScreenRecordService.consentIntent(ctx)) }.onFailure { error = "Ce téléphone ne permet pas la capture d'écran" }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (mode == Mode.CAMERA) {
            if (camOk) AndroidView({ previewView }, Modifier.fillMaxSize()
                .pointerInput(camera) {
                    detectTapGestures(
                        onTap = { o ->
                            val c = camera ?: return@detectTapGestures
                            focus = o; locked = false
                            val pt = previewView.meteringPointFactory.createPoint(o.x, o.y)
                            runCatching { c.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(pt).build()) }
                        },
                        onLongPress = { o ->
                            val c = camera ?: return@detectTapGestures
                            focus = o; locked = true
                            val pt = previewView.meteringPointFactory.createPoint(o.x, o.y)
                            runCatching { c.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(pt).disableAutoCancel().build()) }
                        },
                    )
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

            if (grid) Canvas(Modifier.fillMaxSize()) {
                val c = Color.White.copy(alpha = 0.28f)
                for (i in 1..2) {
                    drawLine(c, Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height), 1.5f)
                    drawLine(c, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), 1.5f)
                }
            }
            if (safe != Safe.OFF) SafeZones(safe)
            if (level) Canvas(Modifier.fillMaxSize()) {
                val ok = abs(roll) < 1.5f
                val col = if (ok) Color(0xFF34C759) else Color.White.copy(alpha = 0.85f)
                val c = Offset(size.width / 2f, size.height / 2f)
                drawLine(Color.White.copy(alpha = 0.35f), c - Offset(size.width * 0.32f, 0f), c + Offset(size.width * 0.32f, 0f), 2f)
                rotate(-roll, c) { drawLine(col, c - Offset(size.width * 0.22f, 0f), c + Offset(size.width * 0.22f, 0f), if (ok) 5f else 3f) }
                drawCircle(col, 6f, c)
            }
            if (ringOn) Box(Modifier.fillMaxSize().border(36.dp, Color.White.copy(alpha = 0.96f), RoundedCornerShape(28.dp)))
            focus?.let { f ->
                LaunchedEffect(f, locked) { if (!locked) { delay(900); focus = null } }
                Canvas(Modifier.fillMaxSize()) {
                    if (locked) drawRect(accent, f - Offset(50f, 50f), Size(100f, 100f), style = Stroke(3f))
                    else drawCircle(accent, 46f, f, style = Stroke(3f))
                }
                if (locked) Text("AE/AF verrouillé", Modifier.align(Alignment.Center).offset(y = (-90).dp).clip(CxDesign.Chip).background(accent).padding(horizontal = 8.dp, vertical = 3.dp),
                    color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

            // teleprompter: scrolls by itself while recording
            if (prompter) {
                val scroll = rememberScrollState()
                LaunchedEffect(recording, paused, prompterSpeed) {
                    while (isActive && recording != null && !paused) { delay(40); scroll.scrollTo((scroll.value + (2 * prompterSpeed).toInt()).coerceAtMost(scroll.maxValue)) }
                }
                Column(Modifier.align(Alignment.TopCenter).padding(top = 150.dp, start = 16.dp, end = 16.dp).fillMaxWidth().height(170.dp)
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
        } else {
            ScreenPanel(Modifier.align(Alignment.Center), screen, accent, sQuality, { sQuality = it }, sFps, { sFps = it }, sAudio, { sAudio = it }, sMinimize, { sMinimize = it })
        }

        // ---- top bar: options
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.35f)).statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.Default.Close, "Fermer", tint = Color.White) }
                Spacer(Modifier.weight(1f))
                val busyMs = if (mode == Mode.SCREEN) screen.elapsedMs else elapsedMs
                val busy = if (mode == Mode.SCREEN) screenBusy else recording != null
                val isPaused = if (mode == Mode.SCREEN) screen.phase == ScreenRecState.Phase.PAUSED else paused
                if (busy) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (isPaused) Color.Gray else Color(0xFFFF3B30)))
                    Spacer(Modifier.width(6.dp))
                    Text(clock(busyMs) + if (maxDur > 0) " / ${clock(maxDur * 1000L)}" else "", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    if (mode == Mode.CAMERA && bytes > 0) Text("  ${"%.1f".format(bytes / 1_048_576.0)} Mo", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                } else {
                    Text(if (mode == Mode.CAMERA) res.label + " · ${activeFps} i/s" + (if (activeHdr) " · HDR" else "") + (if (stabilize) " · stabilisé" else "")
                        else "Écran · ${sQuality.label} · $sFps i/s", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                }
                if (mode == Mode.CAMERA) IconButton({ torch = !torch }, enabled = camera?.cameraInfo?.hasFlashUnit() == true) {
                    Icon(if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff, "Lampe", tint = if (torch) accent else Color.White)
                } else Spacer(Modifier.size(48.dp))
            }
            AnimatedVisibility(recording == null && !screenBusy) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (mode == Mode.CAMERA) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Opt(Icons.Default.HighQuality, res.label, true, accent) { res = Res.entries[(res.ordinal + 1) % Res.entries.size] }
                            Opt(Icons.Default.Speed, if (fps == 60) "60 i/s" else "30 i/s", fps == 60, accent) {
                                if (fps == 60) fps = 30 else if (can60) fps = 60 else error = "60 i/s non disponible sur cette caméra"
                            }
                            Opt(Icons.Default.HdrOn, "HDR", hdr, accent) { if (hdr) hdr = false else if (canHdr) hdr = true else error = "Vidéo HDR non disponible sur cette caméra" }
                            Opt(Icons.Default.Vibration, "Stabilisation", stabilize, accent) { stabilize = !stabilize }
                            Opt(Icons.Default.Timer, if (timer == 0) "Retardateur" else "$timer s", timer > 0, accent) { timer = when (timer) { 0 -> 3; 3 -> 5; 5 -> 10; else -> 0 } }
                            Opt(if (sound) Icons.Default.Mic else Icons.Default.MicOff, if (sound) "Son" else "Muet", sound, accent) { sound = !sound }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Opt(Icons.Default.Grid3x3, "Grille", grid, accent) { grid = !grid }
                            Opt(Icons.Default.Straighten, "Niveau", level, accent) { level = !level }
                            Opt(Icons.Default.CropFree, safe.label, safe != Safe.OFF, accent) { safe = Safe.entries[(safe.ordinal + 1) % Safe.entries.size] }
                            Opt(Icons.Default.LightMode, "Anneau lumineux", ringLight, accent) { ringLight = !ringLight; if (ringLight && back) back = false }
                            Opt(Icons.Default.Flip, "Miroir selfie", mirror, accent) { mirror = !mirror }
                            Opt(Icons.Default.TextFields, "Prompteur", prompter, accent) { prompter = !prompter }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Opt(Icons.Default.HourglassBottom, if (maxDur == 0) "Durée libre" else if (maxDur < 60) "$maxDur s max" else "${maxDur / 60} min max", maxDur > 0, accent) {
                            maxDur = MAX_DURATIONS[(MAX_DURATIONS.indexOf(maxDur) + 1) % MAX_DURATIONS.size]
                        }
                        Opt(Icons.Default.ContentCut, "Couper les blancs" + if (Plus.active) "" else " · PLUS", autoCut, accent) {
                            if (autoCut) autoCut = false else TechGate.request(Tech.JUMP_CUT) { autoCut = true }
                        }
                    }
                }
            }
        }

        // ---- bottom: zoom, record, flip, finish, mode
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.35f)).navigationBarsPadding().padding(bottom = 10.dp, top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            error?.let {
                LaunchedEffect(it) { delay(5000); if (error == it) error = null }
                Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 6.dp, start = 16.dp, end = 16.dp))
            }
            if (mode == Mode.CAMERA) {
                if (camera != null && recording == null) Row(Modifier.padding(horizontal = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.WbSunny, "Exposition", tint = Color.White, modifier = Modifier.size(18.dp))
                    Slider(exposure, { exposure = it }, Modifier.weight(1f).padding(start = 8.dp).height(28.dp), valueRange = -1f..1f,
                        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = Color.White.copy(alpha = 0.6f), inactiveTrackColor = Color.White.copy(alpha = 0.25f)))
                }
                val zs = camera?.cameraInfo?.zoomState?.value
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (z in listOf(0.5f, 1f, 2f, 5f, 10f).filter { zs == null || it in zs.minZoomRatio..zs.maxZoomRatio }) {
                        val on = abs(zoom - z) < 0.05f
                        Text(if (z < 1f) ".5×" else "${z.toInt()}×", Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).border(1.dp, if (on) accent else Color.Transparent, CircleShape)
                            .clickable { zoom = z }.padding(horizontal = 10.dp, vertical = 6.dp), color = if (on) accent else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                val idle = recording == null && !screenBusy
                // takes / finish / pause
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (takes.isNotEmpty() && idle) Row(verticalAlignment = Alignment.CenterVertically) {
                        Button({ onDone(RecordedTakes(takes.map { Uri.fromFile(it) }, autoCut)) }, shape = CxDesign.Button,
                            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                            Text("Monter (${takes.size})", fontWeight = FontWeight.SemiBold)
                        }
                        IconButton({ takes.removeLastOrNull()?.delete() }) { Icon(Icons.AutoMirrored.Filled.Undo, "Supprimer la dernière prise", tint = Color.White) }
                    } else if (mode == Mode.CAMERA && recording != null) IconButton({ val r = recording ?: return@IconButton; if (paused) { r.resume(); paused = false } else { r.pause(); paused = true } }) {
                        Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (paused) "Reprendre" else "Pause", tint = Color.White, modifier = Modifier.size(30.dp))
                    } else if (mode == Mode.SCREEN && screenBusy) IconButton({
                        ScreenRecordService.send(ctx, if (screen.phase == ScreenRecState.Phase.PAUSED) ScreenRecordService.ACTION_RESUME else ScreenRecordService.ACTION_PAUSE)
                    }) {
                        val p = screen.phase == ScreenRecState.Phase.PAUSED
                        Icon(if (p) Icons.Default.PlayArrow else Icons.Default.Pause, if (p) "Reprendre" else "Pause", tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                }
                // record button (ring = progress toward the max duration)
                val busy = if (mode == Mode.SCREEN) screenBusy else recording != null
                val prog = if (maxDur > 0) ((if (mode == Mode.SCREEN) screen.elapsedMs else elapsedMs) / (maxDur * 1000f)).coerceIn(0f, 1f) else 0f
                Box(Modifier.size(78.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape)
                    .semantics { contentDescription = if (busy) "Arrêter la prise" else if (mode == Mode.SCREEN) "Enregistrer l'écran" else "Enregistrer" }
                    .clickable(enabled = (mode == Mode.SCREEN || camera != null) && countdown == 0) {
                        if (mode == Mode.SCREEN) { if (screenBusy) ScreenRecordService.send(ctx, ScreenRecordService.ACTION_STOP) else startScreen() }
                        else if (recording != null) stopRecording() else if (timer > 0) countdown = timer else startRecording()
                    }, contentAlignment = Alignment.Center) {
                    if (busy && maxDur > 0) Canvas(Modifier.fillMaxSize()) { drawArc(accent, -90f, 360f * prog, false, style = Stroke(10f)) }
                    if (mode == Mode.SCREEN && !busy) Box(Modifier.size(62.dp).clip(CircleShape).background(Color(0xFFFF3B30)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.ScreenshotMonitor, null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    else Box(Modifier.size(if (busy) 30.dp else 62.dp).clip(if (busy) RoundedCornerShape(6.dp) else CircleShape).background(Color(0xFFFF3B30)))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    if (mode == Mode.CAMERA && recording == null) IconButton({ back = !back }) { Icon(Icons.Default.Cameraswitch, "Changer de caméra", tint = Color.White, modifier = Modifier.size(30.dp)) }
                }
            }
            // mode switch, TikTok-style
            if (recording == null && !screenBusy) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Mode.entries.forEach { m ->
                    Text(m.label, Modifier.clip(CxDesign.Chip).clickable { mode = m; error = null }.padding(horizontal = 10.dp, vertical = 4.dp),
                        color = if (mode == m) accent else Color.White.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = if (mode == m) FontWeight.Bold else FontWeight.Medium, letterSpacing = 1.2.sp)
                }
            }
            if (takes.isNotEmpty() && recording == null && !screenBusy) Text("${takes.size} prise(s) · elles iront directement dans le montage" + if (autoCut) ", blancs coupés" else "",
                color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Options and live status of the screen capture. */
@Composable
private fun ScreenPanel(
    modifier: Modifier, st: ScreenRecState.State, accent: Color,
    quality: ScreenQuality, onQuality: (ScreenQuality) -> Unit,
    fps: Int, onFps: (Int) -> Unit,
    audio: ScreenAudio, onAudio: (ScreenAudio) -> Unit,
    minimize: Boolean, onMinimize: (Boolean) -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(CxDesign.Card).background(Color.White.copy(alpha = 0.06f)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Default.ScreenshotMonitor, null, tint = accent, modifier = Modifier.size(40.dp))
        if (st.phase != ScreenRecState.Phase.IDLE) {
            Text(if (st.phase == ScreenRecState.Phase.PAUSED) "Capture en pause" else "Ton écran est en cours d'enregistrement", color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(clock(st.elapsedMs), color = accent, fontSize = 40.sp, fontWeight = FontWeight.Black)
            Text("Pause et arrêt aussi depuis la notification, sans revenir dans CUTTINX.", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, textAlign = TextAlign.Center)
            return@Column
        }
        Text("Filmer mon écran", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("Jeux, applis, tutos, réactions : tout ce qui s'affiche est enregistré, avec le son du téléphone et / ou ta voix.",
            color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, textAlign = TextAlign.Center)
        Label("Qualité")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ScreenQuality.entries.forEach { q -> Chip(q.label, quality == q, accent) { onQuality(q) } } }
        Label("Images par seconde")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(30, 60).forEach { f -> Chip("$f i/s", fps == f, accent) { onFps(f) } } }
        Label("Son")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ScreenAudio.entries.filter { Build.VERSION.SDK_INT >= 29 || !it.device }.forEach { a -> Chip(a.label, audio == a, accent) { onAudio(a) } }
        }
        Row(Modifier.clip(CxDesign.Chip).clickable { onMinimize(!minimize) }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(minimize, onMinimize, colors = CheckboxDefaults.colors(checkedColor = accent, checkmarkColor = Color.Black))
            Text("Réduire CUTTINX au démarrage", color = Color.White, fontSize = 13.sp)
        }
        Text("Touche le bouton rouge, accepte la capture, puis va dans le jeu ou l'appli à filmer.", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}

@Composable private fun Label(t: String) = Text(t.uppercase(), color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)

@Composable
private fun Chip(label: String, on: Boolean, accent: Color, onClick: () -> Unit) {
    Text(label, Modifier.clip(CxDesign.Chip).background(if (on) accent.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.35f))
        .border(1.dp, if (on) accent.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.2f), CxDesign.Chip)
        .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp), color = if (on) accent else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
}

/** Areas hidden by the interface of each short-video app (buttons, caption, header). */
@Composable
private fun SafeZones(s: Safe) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val shade = Color(0xFFFF3B30).copy(alpha = 0.16f)
        val line = Color(0xFFFF3B30).copy(alpha = 0.6f)
        val (top, bottom, right) = when (s) {
            Safe.TIKTOK -> Triple(0.10f, 0.22f, 0.16f)
            Safe.REELS -> Triple(0.11f, 0.24f, 0.15f)
            Safe.SHORTS -> Triple(0.09f, 0.20f, 0.14f)
            Safe.OFF -> return@Canvas
        }
        drawRect(shade, Offset.Zero, Size(w, h * top))
        drawRect(shade, Offset(0f, h * (1 - bottom)), Size(w, h * bottom))
        drawRect(shade, Offset(w * (1 - right), h * 0.35f), Size(w * right, h * (0.65f - bottom)))
        drawRect(line, Offset(w * 0.04f, h * top), Size(w * (0.92f - right), h * (1 - top - bottom)), style = Stroke(2f))
    }
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun clock(ms: Long): String { val s = ms / 1000; return "%02d:%02d".format(s / 60, s % 60) }
