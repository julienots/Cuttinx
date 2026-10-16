package com.superessence.cuttinx.templates

import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import com.superessence.cuttinx.core.util.Ids
import com.superessence.cuttinx.resources.ResourceLibrary

data class ProjectTemplate(val id: String, val name: String, val description: String, val aspect: AspectPreset)

/**
 * Original intro/outro/example templates built only from CUTTINX resources. The result is a
 * normal, fully editable project.
 */
class Templates(private val res: ResourceLibrary) {
    val list = listOf(
        ProjectTemplate("intro_essence", "Intro Essence", "Logo texte animé sur fond doré, musique pop", AspectPreset.PORTRAIT_9_16),
        ProjectTemplate("intro_cinema", "Intro cinéma", "Bandes cinéma, titre élégant, ambiance épique", AspectPreset.LANDSCAPE_16_9),
        ProjectTemplate("outro_subscribe", "Conclusion « Merci »", "Remerciements + pouce + cloche animés", AspectPreset.PORTRAIT_9_16),
        ProjectTemplate("story_three", "Story en 3 temps", "Trois cartes de texte avec transitions", AspectPreset.PORTRAIT_9_16),
        ProjectTemplate("demo", "Projet d'exemple", "Démonstration : pistes, effets, transitions, images clés, sous-titres", AspectPreset.PORTRAIT_9_16),
    )

    fun build(t: ProjectTemplate, name: String = t.name): Project {
        val fmt = ProjectFormat.forAspect(t.aspect)
        var p = Project(Ids.new("prj"), name, System.currentTimeMillis(), System.currentTimeMillis(), fmt)
        fun bg(id: String, start: Long, dur: Long): Clip {
            val a = res.backgroundAsset(id, fmt.width, fmt.height)
            p = TimelineEditor.addMedia(p, a)
            return Clip(Ids.new("clp"), ClipKind.MEDIA, a.id, a.displayName, start, 0, dur, transform = Transform(fill = true))
        }
        fun sticker(id: String) = res.stickerAsset(id).also { p = TimelineEditor.addMedia(p, it) }
        fun music(id: String, dur: Long) {
            val m = res.musicAsset(id); p = TimelineEditor.addMedia(p, m)
            val (p2, tr) = TimelineEditor.addTrack(p, TrackKind.AUDIO, role = AudioRole.MUSIC); p = p2
            p = TimelineEditor.insertClip(p, tr.id, Clip(Ids.new("clp"), ClipKind.MEDIA, m.id, m.displayName, 0, 0, minOf(dur, m.durationUs), audio = AudioSettings(fadeOutUs = 1_000_000)))
        }
        fun title(text: String, start: Long, dur: Long, tpl: Int, posY: Double = 0.5, track: String? = null): Clip {
            val tt = res.titleTemplates[tpl]
            val c = TimelineEditor.textClip(text, start, dur, tt.style).let { it.copy(text = it.text!!.copy(animIn = tt.animIn, animOut = tt.animOut), transform = it.transform.copy(posY = posY)) }
            val trk = track ?: (p.tracks.firstOrNull { it.kind == TrackKind.TEXT } ?: TimelineEditor.addTrack(p, TrackKind.TEXT).also { p = it.first }.second).id
            p = TimelineEditor.insertClip(p, trk, c)
            return c
        }
        val (p0, main) = TimelineEditor.mainTrack(p); p = p0
        when (t.id) {
            "intro_essence" -> {
                p = TimelineEditor.insertClip(p, main.id, bg("bg_essence", 0, 4_000_000).copy(effects = listOf(EffectInstance(Ids.new("fx"), "light_leak", mapOf("intensity" to 0.6, "hue" to 45.0, "speed" to 0.6)))))
                title("VOTRE NOM", 300_000, 3_400_000, 1)
                title("présente", 900_000, 2_800_000, 10, 0.6)
                music("pop", 4_000_000)
            }
            "intro_cinema" -> {
                p = TimelineEditor.insertClip(p, main.id, bg("bg_night", 0, 5_000_000).copy(effects = listOf(EffectInstance(Ids.new("fx"), "letterbox", mapOf("ratio" to 2.39, "opacity" to 1.0)), EffectInstance(Ids.new("fx"), "grain", mapOf("amount" to 0.2, "size" to 1.5, "color" to 0.0))),
                    keyframes = mapOf(Prop.SCALE to KeyframeTrack(listOf(Keyframe(0, 1.0), Keyframe(5_000_000, 1.15))))))
                title("UN FILM DE", 400_000, 1_800_000, 12, 0.45)
                title("Votre Titre", 2_300_000, 2_600_000, 6)
                music("epic", 5_000_000)
            }
            "outro_subscribe" -> {
                p = TimelineEditor.insertClip(p, main.id, bg("bg_radial", 0, 5_000_000))
                title("Merci d'avoir regardé !", 200_000, 4_600_000, 8, 0.35)
                val (p2, ov) = TimelineEditor.addTrack(p, TrackKind.VIDEO); p = p2
                val like = sticker("like"); val bell = sticker("bell")
                p = TimelineEditor.insertClip(p, ov.id, Clip(Ids.new("clp"), ClipKind.MEDIA, like.id, like.displayName, 800_000, 0, 4_000_000, transform = Transform(posX = 0.35, posY = 0.62, scaleX = 0.28, scaleY = 0.28),
                    keyframes = mapOf(Prop.SCALE to KeyframeTrack(listOf(Keyframe(0, 0.0, Easing.bezier(0.34, 1.56, 0.64, 1.0)), Keyframe(500_000, 1.0))))))
                val (p3, ov2) = TimelineEditor.addTrack(p, TrackKind.VIDEO); p = p3
                p = TimelineEditor.insertClip(p, ov2.id, Clip(Ids.new("clp"), ClipKind.MEDIA, bell.id, bell.displayName, 1_200_000, 0, 3_600_000, transform = Transform(posX = 0.65, posY = 0.62, scaleX = 0.28, scaleY = 0.28),
                    keyframes = mapOf(Prop.ROTATION to KeyframeTrack(listOf(Keyframe(0, -15.0), Keyframe(300_000, 15.0), Keyframe(600_000, -10.0), Keyframe(900_000, 0.0))))))
                music("funk", 5_000_000)
            }
            "story_three" -> {
                val bgs = listOf("bg_sunset", "bg_ocean", "bg_mint")
                val texts = listOf("1. Le début", "2. L'aventure", "3. La fin")
                for (i in 0..2) p = TimelineEditor.insertClip(p, main.id, bg(bgs[i], i * 3_000_000L, 3_000_000))
                val ids = p.track(main.id)!!.sortedClips().map { it.id }
                p = TimelineEditor.setTransition(p, ids[1], TransitionInstance("slide", 500_000))
                p = TimelineEditor.setTransition(p, ids[2], TransitionInstance("iris", 600_000))
                val clips = p.track(main.id)!!.sortedClips()
                clips.forEachIndexed { i, c -> title(texts[i], c.startUs + 200_000, c.durationUs - 400_000, 0) }
                music("lofi", p.durationUs)
            }
            else -> { // demo
                p = TimelineEditor.insertClip(p, main.id, bg("bg_bokeh", 0, 4_000_000).copy(effects = listOf(EffectInstance(Ids.new("fx"), "adjust", mapOf("saturation" to 0.3)))))
                p = TimelineEditor.insertClip(p, main.id, bg("bg_peach", 4_000_000, 4_000_000))
                p = TimelineEditor.insertClip(p, main.id, bg("bg_grid", 8_000_000, 4_000_000).copy(effects = listOf(EffectInstance(Ids.new("fx"), "wave", mapOf("amplitude" to 0.01, "frequency" to 10.0, "speed" to 2.0)))))
                val ids = p.track(main.id)!!.sortedClips().map { it.id }
                p = TimelineEditor.setTransition(p, ids[1], TransitionInstance("dissolve", 700_000))
                p = TimelineEditor.setTransition(p, ids[2], TransitionInstance("glitch", 400_000))
                title("Bienvenue dans CUTTINX", 300_000, 3_000_000, 1)
                val (p2, ov) = TimelineEditor.addTrack(p, TrackKind.VIDEO); p = p2
                val star = sticker("star")
                p = TimelineEditor.insertClip(p, ov.id, Clip(Ids.new("clp"), ClipKind.MEDIA, star.id, star.displayName, 4_200_000, 0, 3_500_000, transform = Transform(scaleX = 0.3, scaleY = 0.3),
                    keyframes = mapOf(Prop.POS_X to KeyframeTrack(listOf(Keyframe(0, 0.2), Keyframe(3_500_000, 0.8))), Prop.ROTATION to KeyframeTrack(listOf(Keyframe(0, 0.0), Keyframe(3_500_000, 360.0))))))
                val (p3, st) = TimelineEditor.addTrack(p, TrackKind.SUBTITLE); p = p3
                listOf("Ceci est un sous-titre modifiable" to 4_500_000L, "Les images clés animent l'étoile" to 6_000_000L, "Exportez sans filigrane !" to 9_000_000L).forEach { (txt, s) ->
                    p = TimelineEditor.insertClip(p, st.id, TimelineEditor.textClip(txt, s, 1_400_000, res.titleTemplates[10].style, ClipKind.SUBTITLE).let { it.copy(text = it.text!!.copy(animIn = TextAnimation.NONE, animOut = TextAnimation.NONE)) })
                }
                music("pop", p.durationUs)
                val sfx = res.sfxAsset("whoosh"); p = TimelineEditor.addMedia(p, sfx)
                val (p4, sx) = TimelineEditor.addTrack(p, TrackKind.AUDIO, role = AudioRole.SFX); p = p4
                p = TimelineEditor.insertClip(p, sx.id, Clip(Ids.new("clp"), ClipKind.MEDIA, sfx.id, sfx.displayName, 7_600_000, 0, sfx.durationUs))
            }
        }
        return p
    }
}
