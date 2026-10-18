package com.superessence.cuttinx.desktop.engine

import com.superessence.cuttinx.core.model.Project
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Real-time preview of the PC editor. The sound card drives the clock while playing (so the picture
 * follows the sound), a render thread draws the frame of the current time. Seeking is instant:
 * there is no pipeline to rebuild, every frame is computed from the project.
 */
class PcPlayer(private val project: () -> Project, text: PcText) : AutoCloseable {
    /** Long side of the preview picture (lower = smoother on small PCs). */
    @Volatile var previewSide = 960
        set(v) { field = v; invalidate() }

    val position = MutableStateFlow(0L)
    val playing = MutableStateFlow(false)
    val frame = MutableStateFlow<Image?>(null)
    val error = MutableStateFlow<String?>(null)

    private val pool = DecoderPool(1280)
    private val renderer = PcRenderer(pool, text)
    private val audioPool = DecoderPool(64)
    private val audio = PcAudio(audioPool)
    private val dirty = AtomicLong(1)
    @Volatile private var alive = true
    @Volatile private var playStartUs = 0L
    @Volatile private var playStartNs = 0L
    @Volatile private var line: SourceDataLine? = null
    @Volatile private var audioFramesWritten = 0L
    private var surface: Surface? = null
    private val lock = Object()

    private val renderThread = Thread({ renderLoop() }, "cuttinx-preview").apply { isDaemon = true; start() }
    private var audioThread: Thread? = null

    /** Redraws the current frame (call after every edit). */
    fun invalidate() { dirty.incrementAndGet(); synchronized(lock) { lock.notifyAll() } }

    fun seek(us: Long) {
        val d = project().durationUs
        val t = us.coerceIn(0, max(0, d))
        if (playing.value) { pause(); position.value = t; play() } else { position.value = t; invalidate() }
    }

    fun toggle() = if (playing.value) pause() else play()

    fun play() {
        if (playing.value) return
        val d = project().durationUs
        if (d <= 0) return
        if (position.value >= d - 20_000) position.value = 0
        playStartUs = position.value
        playStartNs = System.nanoTime()
        audioFramesWritten = 0
        audio.reset()
        playing.value = true
        audioThread = Thread({ audioLoop() }, "cuttinx-audio").apply { isDaemon = true; start() }
        invalidate()
    }

    fun pause() {
        if (!playing.value) return
        position.value = clock()
        playing.value = false
        audioThread?.join(500)
        audioThread = null
        invalidate()
    }

    /** Current playback time: from the sound card when it runs, otherwise from the system clock. */
    private fun clock(): Long {
        val l = line
        val us = if (l != null && l.isOpen) playStartUs + l.longFramePosition * 1_000_000 / audio.rate
        else playStartUs + (System.nanoTime() - playStartNs) / 1000
        return us
    }

    private fun audioLoop() {
        val rate = audio.rate
        val chunk = 1024
        val fmt = AudioFormat(rate.toFloat(), 16, 2, true, false)
        val l = runCatching { AudioSystem.getSourceDataLine(fmt).also { it.open(fmt, chunk * 4 * 6); it.start() } }.getOrNull()
        line = l
        val buf = FloatArray(chunk * 2)
        val bytes = ByteArray(chunk * 4)
        try {
            while (alive && playing.value) {
                val p = project()
                val t = playStartUs + audioFramesWritten * 1_000_000 / rate
                if (t >= p.durationUs) {
                    l?.drain()
                    break
                }
                audio.mix(p, t, chunk, buf)
                if (l == null) { Thread.sleep(20); audioFramesWritten += chunk; continue }
                for (i in 0 until chunk * 2) {
                    val s = (buf[i].coerceIn(-1f, 1f) * 32767).roundToInt()
                    bytes[i * 2] = s.toByte(); bytes[i * 2 + 1] = (s shr 8).toByte()
                }
                l.write(bytes, 0, bytes.size)
                audioFramesWritten += chunk
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            error.value = "Son : ${e.message}"
        } finally {
            if (l != null) { runCatching { l.stop(); l.flush(); l.close() } }
            line = null
        }
    }

    private fun renderLoop() {
        var drawn = -1L
        while (alive) {
            try {
                val p = project()
                if (playing.value) {
                    val t = clock()
                    if (t >= p.durationUs) {
                        position.value = p.durationUs
                        playing.value = false
                        continue
                    }
                    position.value = t
                    draw(p, t)
                    val frameNs = 1_000_000_000L / max(1, p.format.frameRate)
                    Thread.sleep(max(1L, frameNs / 2_000_000))
                } else {
                    val d = dirty.get()
                    if (d != drawn) { drawn = d; draw(p, position.value) }
                    synchronized(lock) { if (dirty.get() == drawn && !playing.value) lock.wait(250) }
                    pool.trim(); audioPool.trim()
                }
            } catch (_: InterruptedException) {
                return
            } catch (e: Throwable) {
                error.value = e.message ?: e.javaClass.simpleName
                Thread.sleep(100)
            }
        }
    }

    private fun draw(p: Project, t: Long) {
        val fw = p.format.width; val fh = p.format.height
        val k = previewSide.toDouble() / max(fw, fh)
        val w = max(2, (fw * k).roundToInt()); val h = max(2, (fh * k).roundToInt())
        var s = surface
        if (s == null || s.width != w || s.height != h) { s?.close(); s = Surface.makeRasterN32Premul(w, h); surface = s }
        renderer.render(p, t, s)
        frame.value = s.makeImageSnapshot()
    }

    override fun close() {
        alive = false
        playing.value = false
        renderThread.interrupt()
        audioThread?.interrupt()
        pool.close(); audioPool.close()
    }
}
