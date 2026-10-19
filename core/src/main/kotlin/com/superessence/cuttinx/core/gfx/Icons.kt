package com.superessence.cuttinx.core.gfx

/** One icon of the CUTTINX set: SVG path data on a 24 × 24 grid (2 px round strokes unless filled). */
class IconDef(val name: String, val paths: List<String>, val filled: Set<Int> = emptySet())

/**
 * The CUTTINX icon set, drawn for the app. Single source for the Compose icons of the Android and
 * PC apps (CxIcons) and for the tutorial films (drawn through [Gfx]).
 */
object IconSet {
    private fun def(name: String, vararg paths: String, filled: Set<Int> = emptySet()) = IconDef(name, paths.toList(), filled)

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

    // ---- the three spaces
    val Editor = def("Editor", "M4 10h16v9a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z", "M4 10L5.2 5.6a1 1 0 0 1 1.2-.7l13.2 3.1-0.6 2", "M9 5.6l1.6 3.6", "M14 6.8l1.6 3.4")
    val Twitch = def("Twitch", "M3 5h18v12H3z", "M8 21h8", "M12 17v4", "M10 8.5v5l4.3-2.5z")
    val Identity = def("Identity", "M11 4l2 5.2L18.2 11 13 13 11 18.2 9 13 3.8 11 9 9.2z", "M19 3v4", "M17 5h4")
    val Help = def("Help", circle(12f, 12f, 9f), "M9.5 9.5a2.5 2.5 0 1 1 3.6 2.2c-.7.4-1.1.9-1.1 1.7v.6", "M12 17h.01")

    // ---- library
    val Media = def("Media", "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    val Text = def("Text", "M5 7V4h14v3", "M12 4v16", "M9 20h6")
    val Music = def("Music", "M9 18V5l11-2v13", circle(6f, 18f, 3f), circle(17f, 16f, 3f))
    val Sfx = def("Sfx", "M12 2l1.8 4.6 4.6-1.8-1.8 4.6L21 12l-4.4 2.6 1.8 4.6-4.6-1.8L12 22l-1.8-4.6-4.6 1.8 1.8-4.6L3 12l4.4-2.6-1.8-4.6 4.6 1.8z")
    val Effects = def("Effects", "M3 21L14 10", "M14 10l2.5 2.5", "M17 3v4", "M15 5h4", "M20 10v3", "M18.5 11.5h3", "M10 3v2", "M9 4h2")
    val Transitions = def("Transitions", "M4 7h13", "M14 4l3 3-3 3", "M20 17H7", "M10 14l-3 3 3 3")
    val Animation = def("Animation", circle(14f, 12f, 5.5f), "M2 9h4", "M1 12h5", "M2 15h4")
    val Voice = def("Voice", "M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3z", "M19 11a7 7 0 0 1-14 0", "M12 18v3", "M8 21h8")
    val Pro = def("Pro", "M13 2L4 14h7l-1 8 9-12h-7z")
    val Image = def("Image", "M3 5h18v14H3z", "M3 16l5-5 4 4 3-3 6 6", circle(15.5f, 9f, 1.5f))
    val Video = def("Video", "M3 4h18v16H3z", "M7 4v16", "M17 4v16", "M3 8h4", "M3 12h4", "M3 16h4", "M17 8h4", "M17 12h4", "M17 16h4")
    val Upload = def("Upload", "M12 16V4", "M7 9l5-5 5 5", "M4 20h16")
    val Download = def("Download", "M12 4v12", "M7 11l5 5 5-5", "M4 20h16")

    // ---- transport
    val Play = def("Play", "M7 4.5v15a1 1 0 0 0 1.5.9l12-7.5a1 1 0 0 0 0-1.8l-12-7.5A1 1 0 0 0 7 4.5z", filled = setOf(0))
    val Pause = def("Pause", "M6 4h4v16H6z", "M14 4h4v16h-4z", filled = setOf(0, 1))
    val SkipStart = def("SkipStart", "M6 5v14", "M19 5L9 12l10 7z")
    val SkipEnd = def("SkipEnd", "M18 5v14", "M5 5l10 7-10 7z")
    val Prev = def("Prev", "M15 6l-6 6 6 6")
    val Next = def("Next", "M9 6l6 6-6 6")
    val Replay = def("Replay", "M3 12a9 9 0 1 0 3-6.7", "M3 4v5h5")

    // ---- editing
    val Split = def("Split", circle(6f, 6f, 3f), circle(6f, 18f, 3f), "M8.2 8.2L20 18", "M8.2 15.8L20 6")
    val Delete = def("Delete", "M4 7h16", "M10 11v6", "M14 11v6", "M6 7l1 13h10l1-13", "M9 7V4h6v3")
    val RippleDelete = def("RippleDelete", "M13 7h8", "M14.5 7l.8 12h3.4l.8-12", "M3 12h7", "M6 9l-3 3 3 3")
    val Duplicate = def("Duplicate", "M8 8h12v12H8z", "M16 8V4H4v12h4")
    val Marker = def("Marker", "M5 21V4", "M5 4h12l-2 4 2 4H5")
    val Undo = def("Undo", "M9 14L4 9l5-5", "M4 9h10.5a5.5 5.5 0 0 1 0 11H11")
    val Redo = def("Redo", "M15 14l5-5-5-5", "M20 9H9.5a5.5 5.5 0 0 0 0 11H13")
    val KeyframeAdd = def("KeyframeAdd", "M13 7l6 6-6 6-6-6z", "M5 2v6", "M2 5h6")
    val KeyframeRemove = def("KeyframeRemove", "M13 7l6 6-6 6-6-6z", "M2 5h6", "M13 10l3 3-3 3-3-3z", filled = setOf(2))
    val Mirror = def("Mirror", "M12 3v18", "M9 7L3 18h6z", "M15 7l6 11h-6z")
    val Add = def("Add", "M12 5v14", "M5 12h14")
    val Close = def("Close", "M6 6l12 12", "M18 6L6 18")
    val Check = def("Check", "M5 12.5l4.5 4.5L19 7")
    val Warning = def("Warning", "M12 3.5L22 20H2z", "M12 10v4", "M12 17.2h.01")
    val Back = def("Back", "M19 12H5", "M11 6l-6 6 6 6")
    val ChevronDown = def("ChevronDown", "M6 9l6 6 6-6")
    val More = def("More", "M12 5h.01", "M12 12h.01", "M12 19h.01")
    val Search = def("Search", circle(11f, 11f, 7f), "M21 21l-5-5")
    val Relink = def("Relink", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1", "M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1")

    // ---- tracks
    val Lock = def("Lock", "M5 11h14v10H5z", "M8 11V7a4 4 0 0 1 8 0v4")
    val Unlock = def("Unlock", "M5 11h14v10H5z", "M8 11V7a4 4 0 0 1 7.6-1.8")
    val Eye = def("Eye", "M2 12c2.5-4.5 6-7 10-7s7.5 2.5 10 7c-2.5 4.5-6 7-10 7S4.5 16.5 2 12z", circle(12f, 12f, 3f))
    val EyeOff = def("EyeOff", "M2 12c2.5-4.5 6-7 10-7s7.5 2.5 10 7c-2.5 4.5-6 7-10 7S4.5 16.5 2 12z", "M3 3l18 18")
    val Volume = def("Volume", "M11 5L6 9H3v6h3l5 4z", "M15.5 8.5a5 5 0 0 1 0 7", "M18.5 5.5a9 9 0 0 1 0 13")
    val VolumeOff = def("VolumeOff", "M11 5L6 9H3v6h3l5 4z", "M16 9l5 6", "M21 9l-5 6")

    // ---- pro tools & audio
    val ZoomSlow = def("ZoomSlow", circle(11f, 11f, 7f), "M21 21l-5-5", "M11 8v6", "M8 11h6")
    val Progress = def("Progress", "M3 10h18v4H3z", "M3 10h10v4H3z", filled = setOf(1))
    val Captions = def("Captions", "M3 5h18v14H3z", "M7 12h3", "M13 12h4", "M7 15h6", "M15 15h2")
    val Hook = def("Hook", "M4 6h16", "M4 12h10", "M4 18h6", "M17 14l3 3-3 3")
    val Watermark = def("Watermark", circle(12f, 12f, 9f), "M15 9.5a4 4 0 1 0 0 5")
    val NoiseOff = def("NoiseOff", "M3 12h2", "M7 8v8", "M11 5v14", "M15 9v6", "M19 11v2", "M3 3l18 18")
    val Studio = def("Studio", "M4 15v-3a8 8 0 0 1 16 0v3", "M4 15h3v5H4z", "M17 15h3v5h-3z")
    val Background = def("Background", "M3 3h18v18H3z", "M3 15l6-6 12 12", filled = setOf())
    val Folder get() = Media

    // ---- identity
    val Idea = def("Idea", "M9 18h6", "M10 21h4", "M12 3a6 6 0 0 0-3.5 10.9c.8.6 1.5 1.6 1.5 2.6V17h4v-.5c0-1 .7-2 1.5-2.6A6 6 0 0 0 12 3z")
    val Do = def("Do", circle(12f, 12f, 9f), "M8 12.5l3 3 5-6")
    val Dont = def("Dont", circle(12f, 12f, 9f), "M5.6 5.6l12.8 12.8")
    val Calendar = def("Calendar", "M4 6h16v15H4z", "M4 10h16", "M8 3v4", "M16 3v4")
    val Star = def("Star", "M12 3l2.8 5.7 6.2.9-4.5 4.4 1 6.2L12 17.3l-5.5 2.9 1-6.2L3 9.6l6.2-.9z")
    val Rocket = def("Rocket", "M5 15c-1.5 1.3-2 4-2 6 2 0 4.7-.5 6-2", "M9 15l-3-3c1.5-4 5-9 13-9 0 8-5 11.5-9 13z", circle(14.5f, 9.5f, 1.5f))

    /** All icons by name. */
    val all: Map<String, IconDef> by lazy { listOf(Editor, Twitch, Identity, Help, Media, Text, Music, Sfx, Effects, Transitions, Animation, Voice, Pro, Image, Video, Upload, Download, Play, Pause, SkipStart, SkipEnd, Prev, Next, Replay, Split, Delete, RippleDelete, Duplicate, Marker, Undo, Redo, KeyframeAdd, KeyframeRemove, Mirror, Add, Close, Check, Warning, Back, ChevronDown, More, Search, Relink, Lock, Unlock, Eye, EyeOff, Volume, VolumeOff, ZoomSlow, Progress, Captions, Hook, Watermark, NoiseOff, Studio, Background, Idea, Do, Dont, Calendar, Star, Rocket).associateBy { it.name } }
}

/** Draws [icon] in a [size]-pixel square at ([x], [y]). */
fun Gfx.icon(icon: IconDef, x: Float, y: Float, size: Float, color: Int) {
    save(); translate(x, y); scale(size / 24f, size / 24f)
    icon.paths.forEachIndexed { i, d -> path(SvgPath.parse(d), if (i in icon.filled) Paint(color) else Paint(color, stroke = true, strokeWidth = 2f)) }
    restore()
}
