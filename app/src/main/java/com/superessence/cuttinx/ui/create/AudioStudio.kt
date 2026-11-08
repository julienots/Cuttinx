package com.superessence.cuttinx.ui.create

import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.cuttinx.core.audio.AudioAnalysis
import com.superessence.cuttinx.core.audio.AudioChainFactory
import com.superessence.cuttinx.core.audio.OfflineAudio
import com.superessence.cuttinx.core.create.CreateKit
import com.superessence.cuttinx.core.model.EffectInstance
import com.superessence.cuttinx.core.smart.Plus
import com.superessence.cuttinx.core.smart.Tech
import com.superessence.cuttinx.design.CxDesign
import com.superessence.cuttinx.lab.TechGate
import com.superessence.cuttinx.media.PcmDecoder
import com.superessence.cuttinx.media.WavWriter
import com.superessence.cuttinx.ui.theme.LocalCx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.pow

/** What the audio studio does to the sound (in this order). */
data class AudioJob(
    val startS: Double, val endS: Double, val denoise: Boolean, val voice: Boolean, val normalize: Boolean,
    val fadeIn: Boolean, val fadeOut: Boolean, val bassBoost: Boolean,
)

object AudioRender {
    /** Applies [job] to a decoded interleaved buffer (never in place on the source). */
    fun process(src: FloatArray, sr: Int, ch: Int, job: AudioJob): FloatArray {
        var s = CreateKit.trim(src, sr, ch, job.startS, job.endS)
        if (job.denoise) s = OfflineAudio.denoise(s, sr, ch, 0.75)
        fun chain(vararg fx: EffectInstance) { for (e in fx) AudioChainFactory.effect(e)?.let { s = OfflineAudio.run(s, sr, ch, it) } }
        if (job.voice) chain(EffectInstance("a1", "a_highpass", mapOf("freq" to 85.0)), EffectInstance("a2", "a_deesser", mapOf("amount" to 0.45)),
            EffectInstance("a3", "a_compressor", mapOf("threshold" to -20.0, "ratio" to 3.0, "attack" to 8.0, "release" to 140.0, "makeup" to 5.0)))
        if (job.bassBoost) chain(EffectInstance("a4", "v_deep", mapOf("amount" to 0.5)))
        if (job.normalize) {
            val g = AudioAnalysis.normalizationGain(s, sr, ch, -14.0)
            val k = 10.0.pow(g / 20).toFloat()
            for (i in s.indices) s[i] *= k
        }
        chain(EffectInstance("a5", "a_limiter", mapOf("ceiling" to -1.0)))
        CreateKit.fades(s, sr, ch, if (job.fadeIn) 1.0 else 0.01, if (job.fadeOut) 1.5 else 0.01)
        return s
    }
}

/**
 * Studio Audio: open a sound (or the sound of a video), keep the part you want, then studio
 * treatments — broadcast voice, bass, loudness at -14 LUFS, studio noise removal (Plus) — and
 * save a WAV in the Music folder.
 */
@Composable
fun AudioStudio(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cx = LocalCx.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var name by remember { mutableStateOf("") }
    var pcm by remember { mutableStateOf<com.superessence.cuttinx.media.Pcm?>(null) }
    var wave by remember { mutableStateOf(FloatArray(0)) }
    var range by remember { mutableStateOf(0f..1f) }
    var denoise by remember { mutableStateOf(false) }
    var voice by remember { mutableStateOf(false) }
    var normalize by remember { mutableStateOf(true) }
    var fadeIn by remember { mutableStateOf(true) }
    var fadeOut by remember { mutableStateOf(true) }
    var bass by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(Unit) { onDispose { runCatching { player?.release() } } }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null) scope.launch {
            busy = "Lecture du son…"
            val p = withContext(Dispatchers.IO) { runCatching { PcmDecoder.decode(ctx, u, downmix = false, maxSeconds = 600.0) }.getOrNull() }
            if (p == null || p.samples.isEmpty()) snack.showSnackbar("Ce fichier n'a pas de son lisible")
            else { pcm = p; wave = CreateKit.waveform(p.samples, p.channels, 160); range = 0f..1f; name = u.lastPathSegment?.substringAfterLast('/') ?: "son" }
            busy = null
        }
    }
    LaunchedEffect(Unit) { if (pcm == null) pick.launch(arrayOf("audio/*", "video/*")) }

    val durS = pcm?.let { it.samples.size.toDouble() / it.channels / it.sampleRate } ?: 0.0
    fun job() = AudioJob(range.start * durS, range.endInclusive * durS, denoise, voice, normalize, fadeIn, fadeOut, bass)
    suspend fun render(): File? {
        val p = pcm ?: return null
        return withContext(Dispatchers.Default) {
            val out = AudioRender.process(p.samples, p.sampleRate, p.channels, job())
            File(ctx.cacheDir, "cuttinx_audio.wav").also { WavWriter.write(it, out, p.sampleRate, p.channels) }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snack) },
        topBar = { StudioBar("Studio Audio", "NOUVEAU", onBack, if (pcm != null) "Exporter" else null, busy == null) {
            scope.launch {
                busy = "Traitement et enregistrement…"
                val f = render()
                val uri = f?.let { CreateIo.saveWav(ctx, it, "CUTTINX_son_${CreateIo.stamp()}") }
                busy = null
                snack.showSnackbar(if (uri != null) "Son enregistré dans Musique › Cuttinx" else "Enregistrement impossible")
            }
        } }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (pcm == null) StudioEmpty("Monte ton son", "Podcast, voix off, musique ou le son d'une vidéo : coupe, nettoie, mets au bon volume.", "Choisir un son ou une vidéo") {
                pick.launch(arrayOf("audio/*", "video/*"))
            } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
                Text("%.1f s · %d Hz · %s".format(durS, pcm!!.sampleRate, if (pcm!!.channels > 1) "stéréo" else "mono"), color = cx.dim, fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))
                // waveform with the kept part highlighted
                Canvas(Modifier.fillMaxWidth().height(120.dp).clip(CxDesign.Card).background(cx.panel)) {
                    val n = wave.size; if (n == 0) return@Canvas
                    val bw = size.width / n
                    for (i in 0 until n) {
                        val x = i * bw + bw / 2
                        val inside = i.toFloat() / n in range
                        val hgt = (wave[i] * size.height * 0.9f).coerceAtLeast(2f)
                        drawLine(if (inside) cx.accent else Color.White.copy(alpha = 0.25f), Offset(x, size.height / 2 - hgt / 2), Offset(x, size.height / 2 + hgt / 2), bw * 0.6f)
                    }
                }
                RangeSlider(range, { range = it.start..maxOf(it.endInclusive, it.start + 0.01f) }, Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(thumbColor = cx.accent, activeTrackColor = cx.accent, inactiveTrackColor = cx.line))
                Text("Garde de %.1f s à %.1f s (%.1f s)".format(range.start * durS, range.endInclusive * durS, (range.endInclusive - range.start) * durS), color = cx.dim, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth().clip(CxDesign.Card).background(cx.panel)) {
                    Opt("Volume aux normes des réseaux", "Mesuré et réglé à -14 LUFS, sans saturer", normalize) { normalize = it }
                    Opt("Voix radio", "Coupe les graves parasites, adoucit les « s », compresse", voice) { voice = it }
                    Opt("Basses renforcées", "Plus de rondeur sur la voix ou la musique", bass) { bass = it }
                    Opt("Fondu au début", "1 s", fadeIn) { fadeIn = it }
                    Opt("Fondu à la fin", "1,5 s", fadeOut) { fadeOut = it }
                    Opt("Débruitage studio" + if (Plus.active) "" else " · PLUS", "Retire le souffle, le vent et le bruit de fond", denoise) { v ->
                        if (!v) denoise = false else TechGate.request(Tech.STUDIO_SOUND) { denoise = true }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton({
                        scope.launch {
                            busy = "Préparation de l'écoute…"
                            val f = render(); busy = null
                            if (f != null) runCatching { player?.release(); player = MediaPlayer().apply { setDataSource(f.path); prepare(); start() } }
                        }
                    }, shape = CxDesign.Button) { Text("Écouter le résultat") }
                    OutlinedButton({ runCatching { player?.stop() } }, shape = CxDesign.Button) { Text("Stop") }
                    TextButton({ pick.launch(arrayOf("audio/*", "video/*")) }) { Text("Autre son") }
                }
            }
            busy?.let { BusyVeil(it) }
        }
    }
}

@Composable
private fun Opt(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val cx = LocalCx.current
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            Text(detail, fontSize = 12.sp, color = cx.dim)
        }
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedThumbColor = cx.onAccent, checkedTrackColor = cx.accent))
    }
}
