package com.superessence.cuttinx.desktop.engine

import com.superessence.cuttinx.core.effects.EffectRegistry
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader

/**
 * Runs the effect shaders of [EffectRegistry] on the PC. The effect bodies are written in GLSL ES
 * for the Android GPU pipeline; they are wrapped in an SkSL runtime effect with the same helper
 * functions, so every effect and transition looks the same on both platforms.
 */
object Sksl {
    private const val HEADER = """
uniform shader image;
uniform float2 uResolution;
uniform float uTime;
uniform float uProgress;
uniform float uP[16];
const float PI = 3.14159265;
vec4 tex(vec2 uv) {
  uv = clamp(uv, 0.0, 1.0);
  return unpremul(image.eval(vec2(uv.x, 1.0 - uv.y) * uResolution));
}
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
float noise(vec2 p) {
  vec2 i = floor(p); vec2 f = fract(p);
  float a = hash(i); float b = hash(i + vec2(1.0, 0.0));
  float c = hash(i + vec2(0.0, 1.0)); float d = hash(i + vec2(1.0, 1.0));
  vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
}
vec3 rgb2hsv(vec3 c) {
  vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
  vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
  vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
  float d = q.x - min(q.w, q.y);
  float e = 1.0e-10;
  return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}
vec3 hsv2rgb(vec3 c) {
  vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
  vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
  return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}
vec3 hueColor(float deg) { return hsv2rgb(vec3(fract(deg / 360.0), 1.0, 1.0)); }
vec2 rotate2(vec2 v, float a) { float s = sin(a); float c = cos(a); return vec2(c * v.x - s * v.y, s * v.x + c * v.y); }
"""

    private const val MAIN = """
half4 main(float2 fc) {
  vec4 c = clamp(fx(vec2(fc.x / uResolution.x, 1.0 - fc.y / uResolution.y)), 0.0, 1.0);
  return half4(c.rgb * c.a, c.a);
}
"""

    /** GLSL ES 1.00 → SkSL: only a few spellings differ. */
    fun translate(body: String): String {
        var b = body.trimIndent()
        b = b.replace(Regex("\\b(highp|mediump|lowp)\\s+"), "")
        b = b.replace(Regex("texture2D\\s*\\(\\s*uTexSampler\\s*,"), "tex(")
        b = b.replace("gl_FragCoord.xy", "(vec2(uv.x, 1.0 - uv.y) * uResolution)")
        return HEADER + "\nvec4 fx(vec2 uv) {\n" + b + "\n}\n" + MAIN
    }

    private val cache = HashMap<String, RuntimeEffect?>()
    private val errors = HashMap<String, String>()

    /** Compiled effect, or null when it cannot run on this machine (then the layer is drawn unchanged). */
    @Synchronized fun effect(id: String): RuntimeEffect? = cache.getOrPut(id) {
        val def = EffectRegistry[id] ?: return@getOrPut null
        if (def.glsl.isBlank()) return@getOrPut null
        runCatching { RuntimeEffect.makeForShader(translate(def.glsl)) }.onFailure { errors[id] = it.message ?: "?" }.getOrNull()
    }

    @Synchronized fun error(id: String): String? { effect(id); return errors[id] }

    /** Shader that applies effect [id] to [image] (drawn at 0,0 with the image size). */
    fun apply(id: String, image: Image, params: FloatArray, timeSec: Float, progress: Float): Shader? {
        val e = effect(id) ?: return null
        val src = image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, Matrix33.IDENTITY)
        val b = RuntimeShaderBuilder(e)
        b.uniform("uResolution", image.width.toFloat(), image.height.toFloat())
        b.uniform("uTime", timeSec)
        b.uniform("uProgress", progress)
        b.uniform("uP", params)
        b.child("image", src)
        return b.makeShader(Matrix33.IDENTITY)
    }
}
