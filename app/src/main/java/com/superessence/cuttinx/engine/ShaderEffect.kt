package com.superessence.cuttinx.engine

import android.content.Context
import android.opengl.GLES20
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
        private val effect: Int
        private val copy: Int
        private val quad: FloatBuffer
        private var width = 1
        private var height = 1

        init {
            try {
                effect = link(ShaderLibrary.VERTEX, fragment)
                copy = link(ShaderLibrary.VERTEX, COPY_FRAGMENT)
            } catch (e: GlUtil.GlException) {
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
            val u = try { provider(presentationTimeUs) } catch (t: Throwable) { null }
            val prog = if (u == null) copy else effect
            GLES20.glUseProgram(prog)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTexSampler"), 0)
            if (u != null) {
                GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uResolution"), width.toFloat(), height.toFloat())
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uTime"), u.timeSec)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uProgress"), u.progress)
                val loc = GLES20.glGetUniformLocation(prog, "uP")
                if (loc >= 0) GLES20.glUniform1fv(loc, ShaderLibrary.MAX_PARAMS, u.params, 0)
            }
            val pos = GLES20.glGetAttribLocation(prog, "aFramePosition")
            GLES20.glEnableVertexAttribArray(pos)
            quad.position(0)
            GLES20.glVertexAttribPointer(pos, 4, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(pos)
            try { GlUtil.checkGlError() } catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
        }

        override fun release() {
            super.release()
            GLES20.glDeleteProgram(effect)
            GLES20.glDeleteProgram(copy)
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
            if (ok[0] != GLES20.GL_TRUE) throw GlUtil.GlException("édition de liens : " + GLES20.glGetProgramInfoLog(p))
            return p
        }
    }

    companion object {
        const val COPY_FRAGMENT = """
precision mediump float;
uniform sampler2D uTexSampler;
varying vec2 vTexSamplingCoord;
void main() { gl_FragColor = texture2D(uTexSampler, vTexSamplingCoord); }
"""
    }
}
