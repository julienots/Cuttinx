package com.superessence.cuttinx.core.smart

import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.timeline.TimelineEditor

/** Ready-made text designs (font, colours, outline, background and animation in one tap). */
enum class TextLook(val label: String) {
    IMPACT("Gros titre impact"), NEON("Néon"), OUTLINE("Contour épais"), BANNER("Bandeau"), SUBTITLE_PRO("Sous-titre pro"),
    TYPEWRITER("Machine à écrire"), KARAOKE("Karaoké"), FIRE("Dégradé feu"), MINIMAL("Minimal élégant"), COMIC("Bande dessinée"),
    RETRO("Rétro arcade"), HANDWRITTEN("Écriture manuscrite"), LUXURY("Luxe"), NEWS("Info flash"),
}

/** Titles, captions and text tools. Texts created here land on their own text tracks. */
object Titles {
    private const val FONT_BOLD = "asset:fonts/Montserrat_800ExtraBold.ttf"
    private const val FONT_MEDIUM = "asset:fonts/Montserrat_500Medium.ttf"

    fun style(look: TextLook, base: TextStyle = TextStyle()): TextStyle = when (look) {
        TextLook.IMPACT -> base.copy(fontFamily = "asset:fonts/Anton_400Regular.ttf", sizeSp = 80.0, uppercase = true, color = 0xFFFFFFFF, strokeWidth = 6.0, strokeColor = 0xFF000000, shadowRadius = 12.0, backgroundColor = 0, gradientEnabled = false)
        TextLook.NEON -> base.copy(fontFamily = "asset:fonts/Righteous_400Regular.ttf", sizeSp = 60.0, color = 0xFFFFFFFF, strokeWidth = 0.0, shadowRadius = 26.0, shadowDx = 0.0, shadowDy = 0.0, shadowColor = 0xFFFF2BD6, backgroundColor = 0, gradientEnabled = false)
        TextLook.OUTLINE -> base.copy(fontFamily = FONT_BOLD, sizeSp = 58.0, uppercase = true, color = 0xFFFFE14D, strokeWidth = 9.0, strokeColor = 0xFF000000, shadowRadius = 0.0, backgroundColor = 0, gradientEnabled = false)
        TextLook.BANNER -> base.copy(fontFamily = FONT_BOLD, sizeSp = 44.0, uppercase = true, color = 0xFF0B0C0F, strokeWidth = 0.0, shadowRadius = 0.0, backgroundColor = 0xFFFFD21F, backgroundPadding = 22.0, backgroundCorner = 6.0, gradientEnabled = false)
        TextLook.SUBTITLE_PRO -> base.copy(fontFamily = FONT_MEDIUM, sizeSp = 36.0, uppercase = false, bold = false, weight = 500, color = 0xFFFFFFFF, strokeWidth = 0.0, shadowRadius = 0.0, backgroundColor = 0xB0000000, backgroundPadding = 14.0, backgroundCorner = 10.0, gradientEnabled = false)
        TextLook.TYPEWRITER -> base.copy(fontFamily = "monospace", sizeSp = 42.0, uppercase = false, color = 0xFFF2F2F2, strokeWidth = 0.0, shadowRadius = 4.0, backgroundColor = 0, gradientEnabled = false)
        TextLook.KARAOKE -> base.copy(fontFamily = FONT_BOLD, sizeSp = 46.0, uppercase = true, color = 0xFFFFFFFF, strokeWidth = 7.0, strokeColor = 0xFF000000, backgroundColor = 0, gradientEnabled = false)
        TextLook.FIRE -> base.copy(fontFamily = "asset:fonts/Bangers_400Regular.ttf", sizeSp = 72.0, uppercase = true, gradientEnabled = true, gradientStart = 0xFFFFE14D, gradientEnd = 0xFFFF3B00, strokeWidth = 4.0, strokeColor = 0xFF2A0A00, shadowRadius = 10.0, backgroundColor = 0)
        TextLook.MINIMAL -> base.copy(fontFamily = FONT_MEDIUM, sizeSp = 40.0, uppercase = true, bold = false, weight = 500, letterSpacing = 0.25, color = 0xFFFFFFFF, strokeWidth = 0.0, shadowRadius = 3.0, backgroundColor = 0, gradientEnabled = false)
        TextLook.COMIC -> base.copy(fontFamily = "asset:fonts/Bangers_400Regular.ttf", sizeSp = 64.0, uppercase = true, color = 0xFF111111, strokeWidth = 0.0, backgroundColor = 0xFFFFFFFF, backgroundPadding = 18.0, backgroundCorner = 28.0, shadowRadius = 0.0, gradientEnabled = false)
        TextLook.RETRO -> base.copy(fontFamily = "asset:fonts/PressStart2P_400Regular.ttf", sizeSp = 30.0, uppercase = true, color = 0xFF39FF14, strokeWidth = 0.0, shadowRadius = 0.0, shadowDx = 4.0, shadowDy = 4.0, shadowColor = 0xFF0A3D00, backgroundColor = 0, gradientEnabled = false)
        TextLook.HANDWRITTEN -> base.copy(fontFamily = "asset:fonts/Caveat_700Bold.ttf", sizeSp = 64.0, uppercase = false, color = 0xFFFFFFFF, strokeWidth = 0.0, shadowRadius = 8.0, backgroundColor = 0, gradientEnabled = false)
        TextLook.LUXURY -> base.copy(fontFamily = "asset:fonts/PlayfairDisplay_700Bold_Italic.ttf", sizeSp = 58.0, uppercase = false, gradientEnabled = true, gradientStart = 0xFFFFF1C1, gradientEnd = 0xFFC9962B, strokeWidth = 0.0, shadowRadius = 10.0, backgroundColor = 0)
        TextLook.NEWS -> base.copy(fontFamily = "asset:fonts/Oswald_600SemiBold.ttf", sizeSp = 40.0, uppercase = true, color = 0xFFFFFFFF, strokeWidth = 0.0, shadowRadius = 0.0, backgroundColor = 0xFFE5202E, backgroundPadding = 16.0, backgroundCorner = 0.0, gradientEnabled = false)
    }

    private fun anims(look: TextLook): Pair<TextAnimation, TextAnimation> = when (look) {
        TextLook.IMPACT -> TextAnimation.POP to TextAnimation.ZOOM_OUT
        TextLook.NEON -> TextAnimation.FLICKER to TextAnimation.FADE
        TextLook.OUTLINE -> TextAnimation.BOUNCE to TextAnimation.FADE
        TextLook.BANNER -> TextAnimation.SLIDE_RIGHT to TextAnimation.SLIDE_LEFT
        TextLook.SUBTITLE_PRO -> TextAnimation.FADE to TextAnimation.FADE
        TextLook.TYPEWRITER -> TextAnimation.TYPEWRITER to TextAnimation.FADE
        TextLook.KARAOKE -> TextAnimation.KARAOKE to TextAnimation.NONE
        TextLook.FIRE -> TextAnimation.ELASTIC to TextAnimation.ZOOM_OUT
        TextLook.MINIMAL -> TextAnimation.RISE to TextAnimation.FADE
        TextLook.COMIC -> TextAnimation.POP to TextAnimation.POP
        TextLook.RETRO -> TextAnimation.GLITCH to TextAnimation.GLITCH
        TextLook.HANDWRITTEN -> TextAnimation.TYPEWRITER to TextAnimation.FADE
        TextLook.LUXURY -> TextAnimation.BLUR_IN to TextAnimation.FADE
        TextLook.NEWS -> TextAnimation.SLIDE_RIGHT to TextAnimation.FADE
    }

    /** Applies [look] to the selected texts (or every title and caption). */
    fun applyLook(p: Project, selection: List<String>, look: TextLook): Project = Kit.editAll(p, Kit.texts(p, selection)) { c ->
        val t = c.text ?: return@editAll c
        val (a, b) = anims(look)
        c.copy(text = t.copy(style = style(look, t.style), animIn = a, animOut = b, animInUs = if (a == TextAnimation.TYPEWRITER || a == TextAnimation.KARAOKE) (c.durationUs * 0.6).toLong() else t.animInUs))
    }

    fun resize(p: Project, selection: List<String>, factor: Double): Project = Kit.editAll(p, Kit.texts(p, selection)) { c ->
        val t = c.text ?: return@editAll c
        c.copy(text = t.copy(style = t.style.copy(sizeSp = (t.style.sizeSp * factor).coerceIn(8.0, 400.0))))
    }

    fun uppercase(p: Project, selection: List<String>, on: Boolean = true): Project = Kit.editAll(p, Kit.texts(p, selection)) { c ->
        val t = c.text ?: return@editAll c
        c.copy(text = t.copy(style = t.style.copy(uppercase = on)))
    }

    fun align(p: Project, selection: List<String>, a: TextAlign): Project = Kit.editAll(p, Kit.texts(p, selection)) { c ->
        val t = c.text ?: return@editAll c
        c.copy(text = t.copy(style = t.style.copy(align = a)), transform = c.transform.copy(posX = when (a) { TextAlign.LEFT -> 0.3; TextAlign.CENTER -> 0.5; TextAlign.RIGHT -> 0.7 }))
    }

    fun recolor(p: Project, selection: List<String>, color: Long): Project = Kit.editAll(p, Kit.texts(p, selection)) { c ->
        val t = c.text ?: return@editAll c
        c.copy(text = t.copy(style = t.style.copy(color = color, gradientEnabled = false)))
    }

    private fun title(text: String, startUs: Long, durUs: Long, look: TextLook, posX: Double = 0.5, posY: Double = 0.5): Clip {
        val (a, b) = anims(look)
        return TimelineEditor.textClip(text, startUs.coerceAtLeast(0), durUs, style(look)).let {
            it.copy(transform = it.transform.copy(posX = posX, posY = posY), text = it.text!!.copy(animIn = a, animOut = b, animInUs = 250_000, animOutUs = 200_000))
        }
    }

    /** "3 2 1" countdown, one second per number, starting at [atUs]. */
    fun countdown(p: Project, atUs: Long, from: Int = 3): Project = (from downTo 1).fold(p) { acc, n ->
        val c = title("$n", atUs + (from - n) * 1_000_000L, 1_000_000L, TextLook.IMPACT).let { it.copy(text = it.text!!.copy(style = it.text.style.copy(sizeSp = 180.0), animIn = TextAnimation.POP, animOut = TextAnimation.ZOOM_OUT)) }
        Kit.place(acc, TrackKind.TEXT, "Compte à rebours", c)
    }

    /** Ranking badges "#N … #1" at the start of every shot of the main track (top-N videos). */
    fun ranking(p: Project, descending: Boolean = true): Project {
        val shots = SmartTools.mainTrack(p)?.sortedClips().orEmpty().filter { it.durationUs >= 600_000 }
        if (shots.isEmpty()) return p
        return shots.withIndex().fold(p) { acc, (i, s) ->
            val n = if (descending) shots.size - i else i + 1
            Kit.place(acc, TrackKind.TEXT, "Classement", title("#$n", s.startUs, (s.durationUs).coerceAtMost(2_500_000), TextLook.OUTLINE, 0.18, 0.14).let { it.copy(name = "Classement #$n") })
        }
    }

    /** Lower third (name + role) at the playhead, bottom left, for 4 s. */
    fun lowerThird(p: Project, atUs: Long, name: String = "Prénom Nom", role: String = "Créateur de contenu"): Project {
        val c = title("$name\n$role", atUs, 4_000_000, TextLook.BANNER, 0.32, 0.8).let {
            it.copy(name = "Bandeau nom", text = it.text!!.copy(style = it.text.style.copy(align = TextAlign.LEFT, uppercase = false, sizeSp = 34.0, lineSpacing = 1.25), maxWidth = 0.6))
        }
        return Kit.place(p, TrackKind.TEXT, "Bandeaux", c)
    }

    /** End card over the last 3 s of the video (a black card is added when the video is shorter than 3 s). */
    fun endCard(p: Project, text: String = "Merci d'avoir regardé", call: String = "Abonne-toi pour la suite"): Project {
        var np = p
        if (np.durationUs < 3_000_000) {
            val (q, main) = TimelineEditor.mainTrack(np)
            np = runCatching { TimelineEditor.insertClip(q, main.id, TimelineEditor.colorClip(0xFF0B0C0F, main.endUs, 3_000_000)) }.getOrDefault(q)
        }
        val start = (np.durationUs - 3_000_000).coerceAtLeast(0)
        np = Kit.place(np, TrackKind.TEXT, "Fin", title(text, start, 3_000_000, TextLook.MINIMAL, posY = 0.45).copy(name = "Carte de fin"))
        return Kit.place(np, TrackKind.TEXT, "Fin", title(call, start + 400_000, 2_600_000, TextLook.BANNER, posY = 0.58).copy(name = "Appel à l'action"))
    }

    /** A title at every marker (its label, or "Partie N"). */
    fun chapterTitles(p: Project): Project = p.markers.sortedBy { it.timeUs }.withIndex().fold(p) { acc, (i, m) ->
        Kit.place(acc, TrackKind.TEXT, "Chapitres", title(m.label.ifBlank { "Partie ${i + 1}" }, m.timeUs, 2_200_000, TextLook.MINIMAL, posY = 0.2))
    }

    /** "@account" watermark over the whole video, bottom right, semi-transparent. */
    fun watermark(p: Project, handle: String = "@moncompte"): Project {
        val d = p.durationUs.coerceAtLeast(3_000_000)
        val c = TimelineEditor.textClip(handle, 0, d, style(TextLook.MINIMAL).copy(sizeSp = 26.0, letterSpacing = 0.08, uppercase = false)).let {
            it.copy(name = "Filigrane", transform = it.transform.copy(posX = 0.8, posY = 0.93, opacity = 0.6), text = it.text!!.copy(animIn = TextAnimation.NONE, animOut = TextAnimation.NONE))
        }
        return Kit.place(p, TrackKind.TEXT, "Filigrane", c)
    }

    /** Opening title over the first 2.5 s. */
    fun opening(p: Project, text: String = "MON TITRE", look: TextLook = TextLook.IMPACT): Project =
        Kit.place(p, TrackKind.TEXT, "Titre", title(text, 0, 2_500_000, look, posY = 0.42).copy(name = "Titre d'ouverture"))

    /** Big "AVANT" / "APRÈS" labels on the first and second half of a comparison. */
    fun beforeAfter(p: Project, firstUs: LongRange, secondUs: LongRange, vertical: Boolean): Project {
        var np = Kit.place(p, TrackKind.TEXT, "Comparaison", title("AVANT", firstUs.first, (firstUs.last - firstUs.first).coerceAtLeast(500_000), TextLook.BANNER, if (vertical) 0.5 else 0.25, if (vertical) 0.08 else 0.12))
        np = Kit.place(np, TrackKind.TEXT, "Comparaison", title("APRÈS", secondUs.first, (secondUs.last - secondUs.first).coerceAtLeast(500_000), TextLook.BANNER, if (vertical) 0.5 else 0.75, if (vertical) 0.58 else 0.12))
        return np
    }

    /** Every subtitle revealed word by word, karaoke style. */
    fun karaoke(p: Project): Project = Kit.editAll(p, Kit.selOrAll(p, emptyList()) { _, c -> c.kind == ClipKind.SUBTITLE && c.text != null }) { c ->
        c.copy(text = c.text!!.copy(animIn = TextAnimation.KARAOKE, animInUs = (c.durationUs * 0.85).toLong(), animOut = TextAnimation.NONE))
    }
}
