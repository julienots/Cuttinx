package com.superessence.cuttinx.core.effects

import kotlinx.serialization.Serializable

@Serializable
enum class EffectCategory(val label: String) {
    TREND("Tendances"), ADJUST("Réglages"), COLOR("Couleur"), BLUR("Flou & netteté"), STYLIZE("Stylisé"),
    RETRO("Rétro"), CINEMA("Cinéma"), LIGHT("Lumière"), DISTORT("Distorsion"), MOTION("Mouvement & vitesse"),
    PARTICLES("Particules"), OVERLAY("Superposition"), AUDIO("Audio"), VOICE("Voix"), INTERNAL("Interne"),
}

@Serializable
enum class EffectKind { VIDEO, AUDIO }

@Serializable
enum class ParamType { SLIDER, HUE, TOGGLE, CHOICE }

/** A tunable effect parameter. Video params are mapped to `uP[index]` in the shader, in declaration order. */
@Serializable
data class ParamDef(
    val key: String,
    val label: String,
    val min: Double,
    val max: Double,
    val default: Double,
    val type: ParamType = ParamType.SLIDER,
    val choices: List<String> = emptyList(),
    val unit: String = "",
)

/**
 * Definition of an effect. For video effects [glsl] contains the body of
 * `vec4 fx(vec2 uv)`; the engine wraps it with [ShaderLibrary.wrap].
 */
@Serializable
data class EffectDefinition(
    val id: String,
    val name: String,
    val category: EffectCategory,
    val kind: EffectKind,
    val params: List<ParamDef>,
    val glsl: String = "",
    /** Effects that animate on their own (grain, glitch...) need the time uniform every frame. */
    val animated: Boolean = false,
    val description: String = "",
) {
    fun defaults(): Map<String, Double> = params.associate { it.key to it.default }
    fun param(key: String) = params.firstOrNull { it.key == key }
}

/** Shared GLSL (GLSL ES 1.00) used by every video effect shader. */
object ShaderLibrary {
    const val MAX_PARAMS = 16

    const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""

    const val HEADER = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform vec2 uResolution;
uniform float uTime;
uniform float uProgress;
uniform float uP[16];
varying vec2 vTexSamplingCoord;
const float PI = 3.14159265;
vec4 tex(vec2 uv) { return texture2D(uTexSampler, clamp(uv, 0.0, 1.0)); }
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

    /** Builds a full fragment shader from an effect body. */
    fun wrap(body: String): String = HEADER + "\nvec4 fx(vec2 uv) {\n" + body.trimIndent() + "\n}\n" +
        "void main() { gl_FragColor = fx(vTexSamplingCoord); }\n"
}
