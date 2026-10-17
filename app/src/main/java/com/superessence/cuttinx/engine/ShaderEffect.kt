package com.superessence.cuttinx.engine

import android.content.Context
import android.opengl.GLES20
import android.util.Log
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.superessence.cuttinx.core.effects.ShaderLibrary
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Per-frame uniforms of a [ShaderEffect]. A null value from the provider means "pass through". */
class FrameUniforms(val params: FloatArray, val timeSec: Float, val progress: Float = 1f)

/**
 * Generic GPU effect: compiles one of the CUTTINX fragment shaders and evaluates its uniforms for
 * every frame (keyframes, transitions, animated effects). The same instance is used by the preview
 * and by the export, which guarantees identical results.
 */
@UnstableApi
class ShaderEffect(
    private val debugName: String,
    private val fragmentBody: String,
    private val provider: (presentationTimeUs: Long) -> FrameUniforms?,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(debugName, ShaderLibrary.wrap(fragmentBody), provider, useHdr)

    private class Program(
        private val name: String,
        fragment: String,
        private val provider: (Long) -> FrameUniforms?,
        useHdr: Boolean,
    ) : BaseGlShaderProgram(useHdr, 1) {
        /** Effect program, or null when the driver refuses the shader (the frame is then copied unchanged). */
        private val effect: Locations?
        private val copy: Locations
        private val quad: FloatBuffer
        private var width = 1
        private var height = 1
        private var reported = false

        init {
            effect = try { Locations(link(ShaderLibrary.VERTEX, fragment)) } catch (e: GlUtil.GlException) {
                Log.e(TAG, "Shader '$name' désactivé : ${e.message}"); null
            }
            copy = try { Locations(link(ShaderLibrary.VERTEX, COPY_FRAGMENT)) } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException("Shader '$name' : ${e.message}", e)
            }
            val v = floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 0f, 1f, -1f, 1f, 0f, 1f, 1f, 1f, 0f, 1f)
            quad = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            width = inputWidth; height = inputHeight
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val u = if (effect == null) null else try { provider(presentationTimeUs) } catch (t: Throwable) { null }
            // Drop errors left by earlier GL calls so that they are not blamed on this effect.
            drainGlErrors()
            if (!draw(if (u != null) effect!! else copy, inputTexId, u) && u != null) {
                // The effect failed on this driver: fall back to a plain copy rather than stopping playback.
                if (!reported) { reported = true; Log.e(TAG, "Effet '$name' ignoré (erreur GL)") }
                drainGlErrors()
                if (!draw(copy, inputTexId, null)) throw VideoFrameProcessingException("Effet '$name' : erreur OpenGL")
            }
        }

        /** Bounded: a lost context may keep reporting errors. */
        private fun drainGlErrors() { repeat(16) { if (GLES20.glGetError() == GLES20.GL_NO_ERROR) return } }

        /** Draws a full-frame quad; returns false when OpenGL reported an error. */
        private fun draw(l: Locations, inputTexId: Int, u: FrameUniforms?): Boolean {
            GLES20.glUseProgram(l.program)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
            if (l.sampler >= 0) GLES20.glUniform1i(l.sampler, 0)
            if (u != null) {
                if (l.resolution >= 0) GLES20.glUniform2f(l.resolution, width.toFloat(), height.toFloat())
                if (l.time >= 0) GLES20.glUniform1f(l.time, u.timeSec)
                if (l.progress >= 0) GLES20.glUniform1f(l.progress, u.progress)
                if (l.params >= 0 && l.paramCount > 0) GLES20.glUniform1fv(l.params, minOf(l.paramCount, u.params.size), u.params, 0)
            }
            if (l.position < 0) return false
            GLES20.glEnableVertexAttribArray(l.position)
            quad.position(0)
            GLES20.glVertexAttribPointer(l.position, 4, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(l.position)
            return GLES20.glGetError() == GLES20.GL_NO_ERROR
        }

        override fun release() {
            super.release()
            effect?.let { GLES20.glDeleteProgram(it.program) }
            GLES20.glDeleteProgram(copy.program)
        }

        /** Locations resolved once (the driver may optimise unused uniforms away: they are then -1). */
        private class Locations(val program: Int) {
            val sampler = GLES20.glGetUniformLocation(program, "uTexSampler")
            val resolution = GLES20.glGetUniformLocation(program, "uResolution")
            val time = GLES20.glGetUniformLocation(program, "uTime")
            val progress = GLES20.glGetUniformLocation(program, "uProgress")
            val params: Int
            /** Active size of the uP array (drivers shrink it to the highest index used). */
            val paramCount: Int
            val position = GLES20.glGetAttribLocation(program, "aFramePosition")
            init {
                var loc = GLES20.glGetUniformLocation(program, "uP")
                if (loc < 0) loc = GLES20.glGetUniformLocation(program, "uP[0]")
                params = loc
                paramCount = if (loc < 0) 0 else activeArraySize(program, "uP")
            }
            private fun activeArraySize(program: Int, base: String): Int {
                val count = IntArray(1)
                GLES20.glGetProgramiv(program, GLES20.GL_ACTIVE_UNIFORMS, count, 0)
                val size = IntArray(1); val type = IntArray(1)
                for (i in 0 until count[0]) {
                    val n = GLES20.glGetActiveUniform(program, i, size, 0, type, 0)
                    if (n == base || n == "$base[0]") return size[0].coerceIn(1, ShaderLibrary.MAX_PARAMS)
                }
                return ShaderLibrary.MAX_PARAMS
            }
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] != GLES20.GL_TRUE) {
                val log = GLES20.glGetShaderInfoLog(s)
                GLES20.glDeleteShader(s)
                throw GlUtil.GlException("compilation : $log")
            }
            return s
        }

        private fun link(vs: String, fs: String): Int {
            val p = GLES20.glCreateProgram()
            val v = compile(GLES20.GL_VERTEX_SHADER, vs)
            val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
            GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f)
            GLES20.glLinkProgram(p)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
            if (ok[0] != GLES20.GL_TRUE) {
                val log = GLES20.glGetProgramInfoLog(p)
                GLES20.glDeleteProgram(p)
                throw GlUtil.GlException("édition de liens : $log")
            }
            return p
        }
    }

    companion object {
        private const val TAG = "CuttinxShader"
        const val COPY_FRAGMENT = """
precision mediump float;
uniform sampler2D uTexSampler;
varying vec2 vTexSamplingCoord;
void main() { gl_FragColor = texture2D(uTexSampler, vTexSamplingCoord); }
"""
    }
}
