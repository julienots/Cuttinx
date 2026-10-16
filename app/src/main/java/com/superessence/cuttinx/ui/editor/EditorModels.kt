package com.superessence.cuttinx.ui.editor

enum class Workspace(val label: String) {
    QUICK("Montage rapide"), STUDIO("Studio pro"), AUDIO("Audio"), COLOR("Colorimétrie"),
    SUBTITLES("Sous-titres"), AI("CUTTINX AI LAB"), COMPOSITING("Compositing"), EXPORT("Exportation"),
}

enum class Panel(val title: String) {
    MEDIA("Médias"), TEXT("Texte"), TEXT_STYLE("Style du texte"), TEXT_ANIM("Animation du texte"), AUDIO("Audio"), SPEED("Vitesse"),
    TRANSFORM("Transformer"), CROP("Recadrer"), EFFECTS("Effets"), FILTERS("Filtres"), ADJUST("Ajustements"),
    TRANSITION("Transition"), KEYFRAMES("Images clés"), MASK("Masque"), CHROMA("Incrustation"), BLEND("Opacité & fusion"),
    STICKERS("Autocollants"), BACKGROUND("Arrière-plan"), FORMAT("Format du projet"), VOICE("Voix off"), MUSIC("Musique & sons"),
    SUBTITLES("Sous-titres"), AI("CUTTINX AI LAB"), EXPORT("Exporter"), HISTORY("Historique"), TRACKS("Pistes"),
    MARKERS("Marqueurs"), PRESETS("Préréglages"), MIXER("Mixage"), TEMPLATES("Titres prêts"), ADJUST_LAYER("Calque d'effets"),
}

/** A long running background job (AI analysis, audio processing...). */
data class TaskState(val title: String, val progress: Float? = null, val detail: String? = null)
