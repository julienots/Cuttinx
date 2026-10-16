package com.superessence.cuttinx.core.analysis

import com.superessence.cuttinx.core.model.MediaAsset
import com.superessence.cuttinx.core.model.MediaType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class MediaGroup(val name: String, val assetIds: List<String>)

/** Automatic organisation of the media library. */
object MediaOrganizer {
    fun byDay(assets: List<MediaAsset>, zone: ZoneId = ZoneId.systemDefault()): List<MediaGroup> {
        val fmt = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)
        return assets.filter { it.dateAddedMs > 0 }.groupBy { Instant.ofEpochMilli(it.dateAddedMs).atZone(zone).toLocalDate() }
            .toSortedMap(compareByDescending { it }).map { (d, l) -> MediaGroup(d.format(fmt).replaceFirstChar { it.uppercase() }, l.map { it.id }) }
    }

    fun byType(assets: List<MediaAsset>): List<MediaGroup> = assets.groupBy { it.type }.map { (t, l) ->
        MediaGroup(when (t) { MediaType.VIDEO -> "Vidéos"; MediaType.IMAGE -> "Images"; MediaType.AUDIO -> "Audio"; MediaType.SUBTITLE -> "Sous-titres"; MediaType.FONT -> "Polices"; MediaType.STICKER -> "Autocollants" }, l.map { it.id })
    }

    fun byOrientation(assets: List<MediaAsset>): List<MediaGroup> = assets.filter { it.isVisual && it.width > 0 }.groupBy {
        val r = it.displayWidth.toDouble() / it.displayHeight
        when { r > 1.1 -> "Horizontales"; r < 0.9 -> "Verticales"; else -> "Carrées" }
    }.map { (k, l) -> MediaGroup(k, l.map { it.id }) }

    fun byDuration(assets: List<MediaAsset>): List<MediaGroup> = assets.filter { it.type == MediaType.VIDEO || it.type == MediaType.AUDIO }.groupBy {
        when { it.durationUs < 10_000_000 -> "Courts (< 10 s)"; it.durationUs < 60_000_000 -> "Moyens (10 s – 1 min)"; it.durationUs < 600_000_000 -> "Longs (1 – 10 min)"; else -> "Très longs (> 10 min)" }
    }.map { (k, l) -> MediaGroup(k, l.map { it.id }) }

    /** Likely duplicates: same type, size and duration (±50 ms). */
    fun duplicates(assets: List<MediaAsset>): List<MediaGroup> =
        assets.groupBy { Triple(it.type, it.sizeBytes, it.durationUs / 50_000) }.values.filter { it.size > 1 && it.first().sizeBytes > 0 }
            .map { MediaGroup("Doublons : ${it.first().displayName}", it.map { a -> a.id }) }

    /** Suggests a folder name for every asset (type + month). */
    fun suggestFolders(assets: List<MediaAsset>, zone: ZoneId = ZoneId.systemDefault()): Map<String, String> {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM", Locale.FRENCH)
        return assets.associate { a ->
            val type = byType(listOf(a)).first().name
            val date = if (a.dateAddedMs > 0) Instant.ofEpochMilli(a.dateAddedMs).atZone(zone).format(fmt) else "Sans date"
            a.id to "$type/$date"
        }
    }

    enum class SortKey { NAME, DATE, DURATION, TYPE, SIZE }

    fun sort(assets: List<MediaAsset>, key: SortKey, descending: Boolean = false): List<MediaAsset> {
        val s = when (key) {
            SortKey.NAME -> assets.sortedBy { it.displayName.lowercase() }
            SortKey.DATE -> assets.sortedBy { it.dateAddedMs }
            SortKey.DURATION -> assets.sortedBy { it.durationUs }
            SortKey.TYPE -> assets.sortedBy { it.type.ordinal }
            SortKey.SIZE -> assets.sortedBy { it.sizeBytes }
        }
        return if (descending) s.reversed() else s
    }

    fun search(assets: List<MediaAsset>, query: String): List<MediaAsset> {
        if (query.isBlank()) return assets
        val q = TextTools.normalize(query)
        return assets.filter { a -> TextTools.normalize(a.displayName).contains(q) || a.tags.any { TextTools.normalize(it).contains(q) } || TextTools.normalize(a.folder).contains(q) }
    }
}
