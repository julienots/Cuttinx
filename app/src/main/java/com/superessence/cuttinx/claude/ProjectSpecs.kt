package com.superessence.cuttinx.claude

import com.superessence.cuttinx.core.effects.EffectRegistry
import com.superessence.cuttinx.core.effects.TransitionRegistry
import com.superessence.cuttinx.core.model.AspectPreset
import com.superessence.cuttinx.core.model.AudioRole
import com.superessence.cuttinx.core.model.AudioSettings
import com.superessence.cuttinx.core.model.Clip
import com.superessence.cuttinx.core.model.ClipKind
import com.superessence.cuttinx.core.model.Easing
import com.superessence.cuttinx.core.model.EffectInstance
import com.superessence.cuttinx.core.model.MediaType
import com.superessence.cuttinx.core.model.Project
import com.superessence.cuttinx.core.model.ProjectFormat
import com.superessence.cuttinx.core.model.Prop
import com.superessence.cuttinx.core.model.SpeedPoint
import com.superessence.cuttinx.core.model.TextAnimation
import com.superessence.cuttinx.core.model.TextStyle
import com.superessence.cuttinx.core.model.TrackKind
import com.superessence.cuttinx.core.model.Transform
import com.superessence.cuttinx.core.model.TransitionInstance
import com.superessence.cuttinx.core.timeline.TimelineEditor
import com.superessence.cuttinx.core.util.Ids
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Traduit la « recette » JSON envoyée par Claude en un vrai projet CUTTINX (pistes, clips,
 * transitions, effets GPU, images clés, musique, textes), entièrement modifiable ensuite dans l'éditeur.
 * Tous les temps de la recette sont en secondes.
 */
object ProjectSpecs {
    private fun us(s: Double) = (s * 1_000_000).roundToLong()
    private fun sec(us: Long) = (us / 10_000.0).roundToInt() / 100.0

    val FONTS = mapOf(
        "system" to "sans-serif", "condensed" to "sans-serif-condensed", "black" to "sans-serif-black",
        "bebas neue" to "asset:fonts/BebasNeue_400Regular.ttf", "anton" to "asset:fonts/Anton_400Regular.ttf",
        "montserrat extrabold" to "asset:fonts/Montserrat_800ExtraBold.ttf", "montserrat" to "asset:fonts/Montserrat_500Medium.ttf",
        "oswald" to "asset:fonts/Oswald_600SemiBold.ttf", "bangers" to "asset:fonts/Bangers_400Regular.ttf",
        "righteous" to "asset:fonts/Righteous_400Regular.ttf", "fredoka" to "asset:fonts/Fredoka_600SemiBold.ttf",
        "pacifico" to "asset:fonts/Pacifico_400Regular.ttf", "lobster" to "asset:fonts/Lobster_400Regular.ttf",
        "caveat" to "asset:fonts/Caveat_700Bold.ttf", "permanent marker" to "asset:fonts/PermanentMarker_400Regular.ttf",
        "abril fatface" to "asset:fonts/AbrilFatface_400Regular.ttf", "playfair display" to "asset:fonts/PlayfairDisplay_700Bold_Italic.ttf",
        "press start 2p" to "asset:fonts/PressStart2P_400Regular.ttf",
    )

    private fun color(s: String?, def: Long): Long {
        if (s.isNullOrBlank()) return def
        val named = mapOf("white" to 0xFFFFFFFF, "black" to 0xFF000000, "yellow" to 0xFFFFD21F, "red" to 0xFFFF3B30,
            "orange" to 0xFFFF6A00, "blue" to 0xFF2F80ED, "green" to 0xFF27AE60, "pink" to 0xFFFF3366, "cyan" to 0xFF00E5FF)
        named[s.lowercase()]?.let { return it }
        val h = s.removePrefix("#")
        return when (h.length) {
            6 -> 0xFF000000 or h.toLong(16)
            8 -> h.toLong(16)
            else -> def
        }
    }

    private fun aspect(s: String) = when (s) {
        "16:9" -> AspectPreset.LANDSCAPE_16_9; "1:1" -> AspectPreset.SQUARE_1_1; "4:5" -> AspectPreset.PORTRAIT_4_5
        "21:9" -> AspectPreset.CINEMA_21_9; "4:3" -> AspectPreset.CLASSIC_4_3; else -> AspectPreset.PORTRAIT_9_16
    }

    private fun anim(s: String?, def: TextAnimation) = s?.let { v -> TextAnimation.entries.firstOrNull { it.name.equals(v, true) } } ?: def

    private fun easing(s: String?) = when (s?.lowercase()) {
        "linear" -> Easing.Linear; "ease_in" -> Easing.EaseIn; "ease_out" -> Easing.EaseOut; "hold" -> Easing.Hold; else -> Easing.Smooth
    }

    /** Effets GPU d'une liste JSON [{id, params}], avec validation contre le registre. */
    private fun effects(arr: JSONArray?, warnings: MutableList<String>, where: String): List<EffectInstance> {
        arr ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val id = o.optString("id").ifEmpty { o.optString("effect_id") }
            val def = EffectRegistry.video().firstOrNull { it.id == id }
            if (def == null) { warnings += "$where : effet inconnu « $id » ignoré (voir list_effects)"; return@mapNotNull null }
            val params = def.defaults().toMutableMap()
            o.optJSONObject("params")?.let { p ->
                for (k in p.keys()) {
                    val pd = def.param(k)
                    if (pd == null) warnings += "$where : paramètre « $k » inconnu pour $id" else params[k] = p.getDouble(k).coerceIn(pd.min, pd.max)
                }
            }
            EffectInstance(Ids.new("fx"), id, params)
        }
    }

    fun build(spec: JSONObject, lib: ClaudeLibrary, existing: Project? = null): Pair<Project, List<String>> {
        val warnings = mutableListOf<String>()
        val now = System.currentTimeMillis()
        val f = spec.optJSONObject("format") ?: JSONObject()
        val format = ProjectFormat.forAspect(aspect(f.optString("aspect", "9:16")), f.optInt("short_side", 1080).coerceIn(480, 2160), f.optInt("fps", 30).coerceIn(24, 60))
            .copy(backgroundColor = color(f.optString("background"), 0xFF000000))
        var p = Project(existing?.id ?: Ids.new("prj"), spec.optString("name", "Edit Claude"), existing?.createdAtMs ?: now, now, format,
            export = existing?.export ?: com.superessence.cuttinx.core.model.ExportSettings())

        // ---------------- piste principale
        val (p1, main) = TimelineEditor.mainTrack(p); p = p1
        val clipsJson = spec.optJSONArray("clips") ?: throw IllegalArgumentException("clips manquant")
        if (clipsJson.length() == 0) throw IllegalArgumentException("Le projet doit contenir au moins un clip.")
        val transitions = mutableListOf<Pair<String, JSONObject>>()
        val zooms = mutableListOf<Pair<String, JSONObject>>()
        var cursor = 0L
        for (i in 0 until clipsJson.length()) {
            val cj = clipsJson.getJSONObject(i)
            val sm = lib.mustGet(cj.getString("media_id"))
            val asset = sm.asset
            if (asset.type == MediaType.AUDIO) throw IllegalArgumentException("Clip ${i + 1} : ${asset.id} est un fichier audio (utilise music).")
            if (p.asset(asset.id) == null) p = TimelineEditor.addMedia(p, asset)
            val (inUs, outUs) = if (asset.type == MediaType.IMAGE) {
                0L to us(cj.optDouble("duration", 3.0).coerceAtLeast(0.2))
            } else {
                val dur = asset.durationUs
                val s = us(max(0.0, cj.optDouble("start", 0.0))).coerceAtMost(dur)
                val e = (if (cj.has("end")) us(cj.getDouble("end")) else dur).coerceAtMost(dur)
                if (e - s < 100_000) throw IllegalArgumentException("Clip ${i + 1} : intervalle invalide (${sec(s)} → ${sec(e)} s, durée source ${sec(dur)} s).")
                s to e
            }
            val ramp = cj.optJSONArray("speed_ramp")?.let { r -> (0 until r.length()).map { k -> val o = r.getJSONObject(k); SpeedPoint(o.getDouble("position").coerceIn(0.0, 1.0), o.getDouble("speed").coerceIn(0.1, 100.0)) } }.orEmpty()
            val vol = if (cj.optBoolean("mute")) 0.0 else cj.optDouble("volume", 1.0).coerceIn(0.0, 4.0)
            val clip = Clip(
                id = Ids.new("clp"), kind = ClipKind.MEDIA, assetId = asset.id, name = asset.displayName,
                startUs = cursor, sourceInUs = inUs, sourceOutUs = outUs,
                speed = if (asset.type == MediaType.IMAGE) 1.0 else cj.optDouble("speed", 1.0).coerceIn(0.1, 100.0),
                speedCurve = if (ramp.size >= 2) ramp.sortedBy { it.position } else emptyList(),
                transform = Transform(fill = cj.optBoolean("fill", true), scaleX = cj.optDouble("scale", 1.0), scaleY = cj.optDouble("scale", 1.0),
                    posX = cj.optDouble("pos_x", 0.5), posY = cj.optDouble("pos_y", 0.5), rotation = cj.optDouble("rotation", 0.0), flipH = cj.optBoolean("mirror")),
                effects = effects(cj.optJSONArray("effects"), warnings, "Clip ${i + 1}"),
                audio = AudioSettings(volume = vol, muted = vol == 0.0, fadeInUs = us(cj.optDouble("audio_fade_in", 0.0)), fadeOutUs = us(cj.optDouble("audio_fade_out", 0.0))),
            )
            p = TimelineEditor.insertClip(p, main.id, clip)
            cursor = p.track(main.id)!!.endUs
            cj.optJSONObject("transition_in")?.let { if (i > 0) transitions += clip.id to it else warnings += "Clip 1 : une transition d'entrée nécessite un clip avant (ignorée)" }
            cj.optJSONObject("zoom")?.let { zooms += clip.id to it }
        }
        // transitions (le moteur fait chevaucher les clips et recale la suite)
        for ((id, t) in transitions) {
            val tid = t.optString("id", "fade")
            val def = TransitionRegistry.all.firstOrNull { it.id == tid }
            if (def == null) { warnings += "Transition inconnue « $tid » ignorée (voir list_effects)"; continue }
            val params = def.params.associate { it.key to it.default }.toMutableMap()
            t.optJSONObject("params")?.let { o -> for (k in o.keys()) def.params.firstOrNull { it.key == k }?.let { pd -> params[k] = o.getDouble(k).coerceIn(pd.min, pd.max) } }
            p = TimelineEditor.setTransition(p, id, TransitionInstance(tid, us(t.optDouble("duration", def.defaultDurationUs / 1e6)), params))
        }
        // zooms animés (images clés d'échelle) : punch-in, Ken Burns…
        for ((id, z) in zooms) {
            val c = p.findClip(id)?.second ?: continue
            val d = c.durationUs
            val base = c.transform.scaleX
            p = TimelineEditor.setKeyframe(p, id, Prop.SCALE, 0, base * z.optDouble("from", 1.0), easing(z.optString("easing")))
            p = TimelineEditor.setKeyframe(p, id, Prop.SCALE, (d * z.optDouble("over", 1.0).coerceIn(0.05, 1.0)).toLong(), base * z.optDouble("to", 1.15), easing(z.optString("easing")))
        }
        val videoEnd = p.durationUs

        // ---------------- musique
        spec.optJSONObject("music")?.let { mj ->
            val sm = lib.mustGet(mj.getString("media_id"))
            val asset = sm.asset
            if (!asset.hasAudio && asset.type != MediaType.AUDIO) throw IllegalArgumentException("La musique ${asset.id} n'a pas de son.")
            if (p.asset(asset.id) == null) p = TimelineEditor.addMedia(p, asset)
            val (p2, track) = TimelineEditor.ensureTrack(p, TrackKind.AUDIO, AudioRole.MUSIC); p = p2
            var at = us(mj.optDouble("at", 0.0))
            val end = if (mj.has("end_at")) us(mj.getDouble("end_at")) else videoEnd
            var srcIn = us(mj.optDouble("start", 0.0)).coerceIn(0, max(0, asset.durationUs - 100_000))
            var first = true
            while (at < end - 50_000) {
                val len = min(asset.durationUs - srcIn, end - at)
                if (len < 50_000) break
                val last = at + len >= end - 50_000
                val clip = Clip(Ids.new("aud"), ClipKind.MEDIA, asset.id, asset.displayName, startUs = at, sourceInUs = srcIn, sourceOutUs = srcIn + len,
                    audio = AudioSettings(volume = mj.optDouble("volume", 1.0).coerceIn(0.0, 4.0),
                        fadeInUs = if (first) us(mj.optDouble("fade_in", 0.0)) else 0, fadeOutUs = if (last) us(mj.optDouble("fade_out", 1.5)) else 0))
                p = TimelineEditor.insertClip(p, track.id, clip)
                at += len; srcIn = 0; first = false
                if (!mj.optBoolean("loop", true)) break
            }
        }

        // ---------------- textes
        spec.optJSONArray("texts")?.let { arr ->
            if (arr.length() == 0) return@let
            val (p2, track) = TimelineEditor.ensureTrack(p, TrackKind.TEXT); p = p2
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                val s = us(t.optDouble("start", 0.0)); val e = us(t.optDouble("end", sec(videoEnd)))
                if (e - s < 100_000) { warnings += "Texte ${i + 1} trop court, ignoré"; continue }
                val font = FONTS[t.optString("font", "bebas neue").lowercase()] ?: FONTS["bebas neue"]!!
                val style = TextStyle(
                    fontFamily = font, sizeSp = t.optDouble("size", 64.0).coerceIn(10.0, 300.0),
                    color = color(t.optString("color"), 0xFFFFFFFF), uppercase = t.optBoolean("uppercase", false),
                    strokeWidth = t.optDouble("stroke", 0.0), strokeColor = color(t.optString("stroke_color"), 0xFF000000),
                    backgroundColor = color(t.optString("background"), 0x00000000), letterSpacing = t.optDouble("letter_spacing", 0.0),
                    gradientEnabled = t.has("gradient_to"), gradientStart = color(t.optString("color"), 0xFFFFD21F), gradientEnd = color(t.optString("gradient_to"), 0xFFFF6A00),
                )
                val y = when (val pos = t.opt("position")) { is Number -> pos.toDouble(); "top" -> 0.15; "center" -> 0.5; else -> 0.8 }
                var clip = TimelineEditor.textClip(t.getString("text"), s, e - s, style)
                clip = clip.copy(transform = clip.transform.copy(posY = y.coerceIn(0.0, 1.0)),
                    text = clip.text!!.copy(animIn = anim(t.optString("anim_in"), TextAnimation.POP), animOut = anim(t.optString("anim_out"), TextAnimation.FADE)))
                p = TimelineEditor.insertClip(p, track.id, clip)
            }
        }

        // ---------------- calques d'effets (au-dessus de tout, sur un intervalle)
        spec.optJSONArray("effect_layers")?.let { arr ->
            if (arr.length() == 0) return@let
            val (p2, track) = TimelineEditor.ensureTrack(p, TrackKind.EFFECT); p = p2
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val fx = effects(JSONArray().put(JSONObject().put("id", o.optString("effect_id", o.optString("id"))).put("params", o.optJSONObject("params") ?: JSONObject())), warnings, "Calque ${i + 1}")
                if (fx.isEmpty()) continue
                val s = us(o.optDouble("start", 0.0)); val e = us(o.optDouble("end", sec(videoEnd)))
                if (e - s < 50_000) continue
                p = TimelineEditor.insertClip(p, track.id, TimelineEditor.adjustmentClip(s, e - s).copy(effects = fx))
            }
        }
        return p.copy(modifiedAtMs = now) to warnings
    }

    /** Résumé lisible d'un projet (pour que Claude puisse le relire et l'ajuster). */
    fun describe(p: Project): JSONObject = JSONObject().apply {
        put("project_id", p.id); put("name", p.name); put("duration", sec(p.durationUs))
        put("format", JSONObject().put("width", p.format.width).put("height", p.format.height).put("fps", p.format.frameRate).put("aspect", p.format.aspect.label))
        put("tracks", JSONArray(p.tracks.map { t ->
            JSONObject().put("kind", t.kind.name.lowercase()).put("name", t.name).put("clips", JSONArray(t.sortedClips().map { c ->
                JSONObject().apply {
                    put("at", sec(c.startUs)); put("duration", sec(c.durationUs))
                    c.assetId?.let { put("media_id", it); put("start", sec(c.sourceInUs)); put("end", sec(c.sourceOutUs)) }
                    if (c.speed != 1.0) put("speed", c.speed)
                    if (c.speedCurve.isNotEmpty()) put("speed_ramp", JSONArray(c.speedCurve.map { JSONObject().put("position", it.position).put("speed", it.speed) }))
                    c.text?.let { put("text", it.text) }
                    if (c.effects.isNotEmpty()) put("effects", JSONArray(c.effects.map { JSONObject().put("id", it.effectId).put("params", JSONObject(it.params as Map<*, *>)) }))
                    c.transitionIn?.let { put("transition_in", JSONObject().put("id", it.transitionId).put("duration", sec(it.durationUs))) }
                    if (c.audio.volume != 1.0 || c.audio.muted) put("volume", if (c.audio.muted) 0.0 else c.audio.volume)
                }
            }))
        }))
        put("note", "Les projets modifiés à la main dans CUTTINX peuvent contenir plus de réglages que ce résumé.")
    }
}
