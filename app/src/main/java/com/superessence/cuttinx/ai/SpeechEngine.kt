package com.superessence.cuttinx.ai

import android.content.Context
import android.net.Uri
import com.superessence.cuttinx.core.subtitles.Word
import com.superessence.cuttinx.media.PcmDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** A downloadable speech model (Vosk, Apache 2.0 licensed small models). */
data class SpeechModelInfo(val id: String, val language: String, val url: String, val sizeMb: Int, val license: String = "Apache 2.0")

/**
 * Offline speech recognition with Vosk. Models are NOT bundled: the user downloads the language
 * they need once (internet required for the download only), then everything runs on the phone.
 */
class SpeechEngine(private val context: Context) {
    val models = listOf(
        SpeechModelInfo("vosk-model-small-fr-0.22", "Français", "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip", 41),
        SpeechModelInfo("vosk-model-small-en-us-0.15", "English (US)", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", 40),
        SpeechModelInfo("vosk-model-small-es-0.42", "Español", "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip", 39),
        SpeechModelInfo("vosk-model-small-de-0.15", "Deutsch", "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip", 45),
        SpeechModelInfo("vosk-model-small-it-0.22", "Italiano", "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip", 48),
        SpeechModelInfo("vosk-model-small-pt-0.3", "Português", "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip", 31),
    )
    private val root = File(context.filesDir, "speech").also { it.mkdirs() }

    fun dir(m: SpeechModelInfo) = File(root, m.id)
    fun isInstalled(m: SpeechModelInfo) = File(dir(m), "am").exists() || File(dir(m), "conf").exists()
    fun installed() = models.filter(::isInstalled)
    fun delete(m: SpeechModelInfo) = dir(m).deleteRecursively()

    /** Downloads and unzips a model. [progress] receives 0..1. */
    fun download(m: SpeechModelInfo, progress: (Float) -> Unit, cancelled: () -> Boolean) {
        val tmp = File(root, m.id + ".zip.part")
        val conn = URL(m.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 30_000
        try {
            if (conn.responseCode !in 200..299) error("Serveur indisponible (HTTP ${conn.responseCode})")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (m.sizeMb * 1_048_576L)
            conn.inputStream.use { inp -> tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var done = 0L
                while (true) {
                    if (cancelled()) error("Téléchargement annulé")
                    val n = inp.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n; progress((done.toFloat() / total).coerceAtMost(0.99f))
                }
            } }
            val target = dir(m); target.deleteRecursively(); target.mkdirs()
            ZipInputStream(tmp.inputStream().buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.name.contains("..")) continue
                    // strip the top-level folder of the archive
                    val rel = e.name.substringAfter('/', "")
                    if (rel.isEmpty()) continue
                    val f = File(target, rel)
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zip.copyTo(it) } }
                }
            }
            progress(1f)
        } finally { conn.disconnect(); tmp.delete() }
    }

    /**
     * Transcribes the audio of [uri] between [startUs] and [endUs] (source time).
     * Returns timed words; times are absolute source times.
     */
    fun transcribe(m: SpeechModelInfo, uri: Uri, startUs: Long, endUs: Long, progress: (Float) -> Unit, cancelled: () -> Boolean): List<Word> {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        val pcm = PcmDecoder.decode(context, uri, startUs, endUs, downmix = true, cancelled = cancelled)
        val rate = 16000
        val res = resample(pcm.samples, pcm.sampleRate, rate)
        val words = ArrayList<Word>()
        Model(dir(m).absolutePath).use { model ->
            Recognizer(model, rate.toFloat()).use { rec ->
                rec.setWords(true)
                val chunk = 4000
                val buf = ShortArray(chunk)
                var i = 0
                while (i < res.size) {
                    if (cancelled()) break
                    val n = minOf(chunk, res.size - i)
                    for (k in 0 until n) buf[k] = (res[i + k].coerceIn(-1f, 1f) * 32767).toInt().toShort()
                    if (rec.acceptWaveForm(buf, n)) parse(rec.result, startUs, words)
                    i += n
                    progress(i.toFloat() / res.size)
                }
                parse(rec.finalResult, startUs, words)
            }
        }
        return words
    }

    private val json = Json { ignoreUnknownKeys = true }
    private fun parse(s: String, offsetUs: Long, out: MutableList<Word>) {
        val o = runCatching { json.parseToJsonElement(s).jsonObject }.getOrNull() ?: return
        val arr = (o["result"] ?: return).jsonArray
        for (w in arr) {
            val wo = w as? JsonObject ?: continue
            val text = wo["word"]?.jsonPrimitive?.content ?: continue
            val st = wo["start"]?.jsonPrimitive?.doubleOrNull ?: continue
            val en = wo["end"]?.jsonPrimitive?.doubleOrNull ?: st
            val conf = wo["conf"]?.jsonPrimitive?.doubleOrNull ?: 1.0
            out += Word(text, offsetUs + (st * 1e6).toLong(), offsetUs + (en * 1e6).toLong(), conf)
        }
    }

    companion object {
        /** Linear resampler with a simple box low-pass when downsampling. */
        fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
            if (from == to || x.isEmpty()) return x
            val ratio = from.toDouble() / to
            val n = (x.size / ratio).toInt()
            val win = maxOf(1, ratio.toInt())
            return FloatArray(n) { i ->
                val pos = i * ratio
                val i0 = pos.toInt()
                if (win > 1) { var s = 0f; var c = 0; for (k in i0 until minOf(x.size, i0 + win)) { s += x[k]; c++ }; if (c > 0) s / c else 0f }
                else { val f = (pos - i0).toFloat(); val a = x[i0]; val b = x[minOf(x.size - 1, i0 + 1)]; a + (b - a) * f }
            }
        }
    }
}
