package com.superessence.cuttinx.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import android.util.Log
import android.util.LruCache
import com.superessence.cuttinx.core.effects.EffectRegistry
import com.superessence.cuttinx.core.effects.ShaderLibrary
import com.superessence.cuttinx.core.model.Clip
import com.superessence.cuttinx.core.model.EffectInstance
import com.superessence.cuttinx.core.model.MediaType
import com.superessence.cuttinx.core.model.Project
import com.superessence.cuttinx.core.render.FrameEvaluator
import kotlinx.coroutines.asCoroutineDispatcher
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors

/**
 * Small pictures of the filters and effects, drawn with the very same GPU shaders as the preview
 * and the export, on a frame of the user's own clip (or on a built-in sample picture). Runs on
 * its own thread with its own off-screen OpenGL context; results are cached.
 */
object FilterPreviews {
    private const val TAG = "CuttinxPreviews"
    /** Long side of a preview picture, in pixels. */
    const val SIZE = 200

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "cuttinx-previews").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    /** All the GL work happens here (the EGL context is current on this thread only). */
    val dispatcher = executor.asCoroutineDispatcher()

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
    private val sources = LruCache<String, Bitmap>(6)

    // ---------------------------------------------------------------- sources

    /** A picture to try the filters on: the selected clip (at the playhead when it is inside it), else a sample. */
    fun sourceKey(p: Project, clip: Clip?, playheadUs: Long): String {
        val c = clip ?: return "sample"
        val a = p.asset(c.assetId) ?: return "sample"
        if (a.type != MediaType.VIDEO && a.type != MediaType.IMAGE) return "sample"
        val t = sourceTimeUs(c, playheadUs)
        return "${a.uri}@${t / 500_000}"
    }

    private fun sourceTimeUs(c: Clip, playheadUs: Long): Long {
        val rel = if (playheadUs in c.startUs until c.endUs) playheadUs - c.startUs else c.durationUs / 2
        return (c.sourceInUs + rel * c.speed).toLong().coerceIn(c.sourceInUs, maxOf(c.sourceInUs, c.sourceOutUs - 1))
    }

    /** Must run on [dispatcher]. */
    fun source(ctx: Context, p: Project, clip: Clip?, playheadUs: Long): Bitmap {
        val key = sourceKey(p, clip, playheadUs)
        sources.get(key)?.let { return it }
        val bmp = runCatching { load(ctx, p, clip!!, playheadUs) }.getOrNull() ?: sample()
        val fitted = fit(bmp)
        sources.put(key, fitted)
        return fitted
    }

    private fun load(ctx: Context, p: Project, c: Clip, playheadUs: Long): Bitmap? {
        val a = p.asset(c.assetId) ?: return null
        val uri = Uri.parse(a.uri)
        return if (a.type == MediaType.IMAGE) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= SIZE * 2) sample *= 2
            val raw = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
            if (a.rotationDegrees != 0) Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(a.rotationDegrees.toFloat()) }, true) else raw
        } else {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(ctx, uri)
                val t = sourceTimeUs(c, playheadUs)
                if (Build.VERSION.SDK_INT >= 27) mmr.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SIZE * 2, SIZE * 2)
                else mmr.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally { runCatching { mmr.release() } }
        }
    }

    private fun fit(b: Bitmap): Bitmap {
        val s = SIZE.toFloat() / maxOf(b.width, b.height)
        val w = maxOf(2, (b.width * s).toInt()); val h = maxOf(2, (b.height * s).toInt())
        return Bitmap.createScaledBitmap(if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false), w, h, true)
    }

    /** An original sample picture (sky, sun, hills, a figure, a warm skin tone) that shows what every filter does. */
    private fun sample(): Bitmap {
        val w = SIZE; val h = SIZE * 5 / 4
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.62f, intArrayOf(0xFF2B5FA8.toInt(), 0xFF7FA8D8.toInt(), 0xFFF2B27A.toInt()), null, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
        p.color = 0xFFFFE6A0.toInt(); cv.drawCircle(w * 0.72f, h * 0.42f, w * 0.11f, p)
        p.color = 0xFF3E6B4A.toInt()
        cv.drawPath(Path().apply { moveTo(0f, h * 0.62f); quadTo(w * 0.3f, h * 0.45f, w * 0.6f, h * 0.6f); quadTo(w * 0.85f, h * 0.7f, w.toFloat(), h * 0.58f); lineTo(w.toFloat(), h.toFloat()); lineTo(0f, h.toFloat()); close() }, p)
        p.color = 0xFF2A4A33.toInt()
        cv.drawPath(Path().apply { moveTo(0f, h * 0.78f); quadTo(w * 0.5f, h * 0.66f, w.toFloat(), h * 0.8f); lineTo(w.toFloat(), h.toFloat()); lineTo(0f, h.toFloat()); close() }, p)
        // a figure: warm face, red top, dark hair
        p.color = 0xFFB8323A.toInt(); cv.drawRoundRect(w * 0.18f, h * 0.68f, w * 0.42f, h * 1.02f, w * 0.06f, w * 0.06f, p)
        p.color = 0xFFE0A884.toInt(); cv.drawCircle(w * 0.3f, h * 0.6f, w * 0.085f, p)
        p.color = 0xFF2B1D16.toInt(); cv.drawArc(w * 0.21f, h * 0.505f, w * 0.39f, h * 0.645f, 180f, 180f, true, p)
        p.color = 0xFFF4F1EA.toInt(); cv.drawRect(w * 0.62f, h * 0.84f, w * 0.9f, h * 0.9f, p)
        return b
    }

    // ---------------------------------------------------------------- rendering

    /** Cached picture, or null. */
    fun cached(sourceKey: String, id: String): Bitmap? = cache.get("$sourceKey|$id")

    /** Draws [effects] on [src] (must run on [dispatcher]). Null when the GPU is not available. */
    fun render(src: Bitmap, sourceKey: String, id: String, effects: List<EffectInstance>): Bitmap? {
        val k = "$sourceKey|$id"
        cache.get(k)?.let { return it }
        val out = runCatching { gl.draw(src, effects) }.onFailure { Log.w(TAG, "aperçu $id : ${it.message}") }.getOrNull() ?: return null
        cache.put(k, out)
        return out
    }

    private val gl by lazy { Gl() }

    private class Gl {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: EGLContext
        private val surface: EGLSurface
        private val programs = HashMap<String, Int>()
        private val quad: FloatBuffer
        private var srcTex = 0
        private val tex = IntArray(2)
        private val fbo = IntArray(2)
        private var w = 0
        private var h = 0
        private var srcRef: Bitmap? = null

        init {
            val v = IntArray(2)
            check(EGL14.eglInitialize(display, v, 0, v, 1)) { "EGL indisponible" }
            val attribs = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
            val configs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "aucune configuration EGL" }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "contexte EGL refusé" }
            val q = floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 0f, 1f, -1f, 1f, 0f, 1f, 1f, 1f, 0f, 1f)
            quad = ByteBuffer.allocateDirect(q.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(q); position(0) }
        }

        private fun newTexture(): Int {
            val t = IntArray(1); GLES20.glGenTextures(1, t, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return t[0]
        }

        /** (Re)creates the targets for a source size and uploads the source, bottom row first like the video frames. */
        private fun prepare(src: Bitmap) {
            if (src === srcRef) return
            if (src.width != w || src.height != h) {
                if (w > 0) { GLES20.glDeleteFramebuffers(2, fbo, 0); GLES20.glDeleteTextures(2, tex, 0) }
                w = src.width; h = src.height
                for (i in 0..1) {
                    tex[i] = newTexture()
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                    val f = IntArray(1); GLES20.glGenFramebuffers(1, f, 0); fbo[i] = f[0]
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
                    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[i], 0)
                }
            }
            if (srcTex == 0) srcTex = newTexture()
            val flipped = Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postScale(1f, -1f) }, false)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, flipped, 0)
            srcRef = src
        }

        private fun program(effectId: String): Int {
            programs[effectId]?.let { return it }
            val def = EffectRegistry[effectId] ?: return 0
            val p = runCatching { link(ShaderLibrary.VERTEX, ShaderLibrary.wrap(def.glsl)) }.getOrDefault(0)
            programs[effectId] = p
            return p
        }

        fun draw(src: Bitmap, effects: List<EffectInstance>): Bitmap {
            prepare(src)
            // shaders size their blur radii etc. in pixels of a 1080p frame: keep that look on a small picture
            val k = 1080f / minOf(w, h)
            var input = srcTex
            var target = 0
            for (fx in effects.filter { EffectRegistry[it.effectId]?.glsl?.isNotBlank() == true }) {
                val prog = program(fx.effectId)
                if (prog == 0) continue
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[target])
                GLES20.glViewport(0, 0, w, h)
                GLES20.glUseProgram(prog)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, input)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTexSampler"), 0)
                GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uResolution"), w * k, h * k)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uTime"), 1.3f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uProgress"), 1f)
                val params = FrameEvaluator.shaderParams(fx.effectId, fx.params)
                var loc = GLES20.glGetUniformLocation(prog, "uP")
                if (loc < 0) loc = GLES20.glGetUniformLocation(prog, "uP[0]")
                if (loc >= 0 && params.isNotEmpty()) GLES20.glUniform1fv(loc, minOf(params.size, ShaderLibrary.MAX_PARAMS), params, 0)
                val pos = GLES20.glGetAttribLocation(prog, "aFramePosition")
                if (pos < 0) continue
                GLES20.glEnableVertexAttribArray(pos)
                quad.position(0)
                GLES20.glVertexAttribPointer(pos, 4, GLES20.GL_FLOAT, false, 0, quad)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glDisableVertexAttribArray(pos)
                input = tex[target]
                target = 1 - target
            }
            if (input == srcTex) return src                      // nothing drawable: the plain picture
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[1 - target])
            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            buf.position(0); raw.copyPixelsFromBuffer(buf)
            // read back bottom row first: flip to get the picture upright
            return Bitmap.createBitmap(raw, 0, 0, w, h, Matrix().apply { postScale(1f, -1f) }, false)
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] != GLES20.GL_TRUE) { val log = GLES20.glGetShaderInfoLog(s); GLES20.glDeleteShader(s); error(log) }
            return s
        }

        private fun link(vs: String, fs: String): Int {
            val p = GLES20.glCreateProgram()
            val v = compile(GLES20.GL_VERTEX_SHADER, vs); val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
            GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f); GLES20.glLinkProgram(p)
            GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
            val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] != GLES20.GL_TRUE) { GLES20.glDeleteProgram(p); error("édition de liens") }
            return p
        }
    }
}
