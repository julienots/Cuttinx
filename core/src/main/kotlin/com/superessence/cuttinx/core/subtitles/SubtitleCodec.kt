package com.superessence.cuttinx.core.subtitles

import com.superessence.cuttinx.core.model.ClipKind
import com.superessence.cuttinx.core.model.Project
import com.superessence.cuttinx.core.model.TrackKind

data class Cue(val startUs: Long, val endUs: Long, val text: String)

/** A recognised word with timing (from the speech engine). */
data class Word(val text: String, val startUs: Long, val endUs: Long, val confidence: Double = 1.0)

object SubtitleCodec {
    private val srtTime = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})")
    private val vttTime = Regex("(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})\\.(\\d{1,3})")

    private fun parseTime(m: MatchResult, hasHours: Boolean = true): Long {
        val g = m.groupValues
        val h = g[1].ifEmpty { "0" }.toLong(); val mi = g[2].toLong(); val s = g[3].toLong()
        val ms = g[4].padEnd(3, '0').take(3).toLong()
        return ((h * 3600 + mi * 60 + s) * 1000 + ms) * 1000
    }

    fun parseSrt(text: String): List<Cue> {
        val cues = ArrayList<Cue>()
        val blocks = text.replace("\r\n", "\n").replace("﻿", "").split(Regex("\n\\s*\n"))
        for (b in blocks) {
            val lines = b.trim().lines()
            val ti = lines.indexOfFirst { it.contains("-->") }
            if (ti < 0) continue
            val parts = lines[ti].split("-->")
            val s = srtTime.find(parts[0]) ?: continue
            val e = srtTime.find(parts.getOrElse(1) { "" }) ?: continue
            val body = lines.drop(ti + 1).joinToString("\n").replace(Regex("<[^>]+>"), "").trim()
            if (body.isNotEmpty()) cues += Cue(parseTime(s), parseTime(e), body)
        }
        return cues.sortedBy { it.startUs }
    }

    fun parseVtt(text: String): List<Cue> {
        val cues = ArrayList<Cue>()
        val blocks = text.replace("\r\n", "\n").replace("﻿", "").split(Regex("\n\\s*\n"))
        for (b in blocks) {
            val lines = b.trim().lines()
            val ti = lines.indexOfFirst { it.contains("-->") }
            if (ti < 0) continue
            val parts = lines[ti].split("-->")
            val s = vttTime.find(parts[0]) ?: continue
            val e = vttTime.find(parts.getOrElse(1) { "" }) ?: continue
            val body = lines.drop(ti + 1).joinToString("\n").replace(Regex("<[^>]+>"), "").trim()
            if (body.isNotEmpty()) cues += Cue(parseTime(s), parseTime(e), body)
        }
        return cues.sortedBy { it.startUs }
    }

    fun parse(text: String, fileName: String = ""): List<Cue> =
        if (fileName.endsWith(".vtt", true) || text.trimStart('﻿').startsWith("WEBVTT")) parseVtt(text) else parseSrt(text)

    private fun fmt(us: Long, sep: Char): String {
        val ms = us / 1000
        return "%02d:%02d:%02d%c%03d".format(ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, sep, ms % 1000)
    }

    fun toSrt(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { i, c ->
            append(i + 1).append('\n')
            append(fmt(c.startUs, ',')).append(" --> ").append(fmt(c.endUs, ',')).append('\n')
            append(c.text).append("\n\n")
        }
    }

    fun toVtt(cues: List<Cue>): String = buildString {
        append("WEBVTT\n\n")
        cues.forEach { c -> append(fmt(c.startUs, '.')).append(" --> ").append(fmt(c.endUs, '.')).append('\n').append(c.text).append("\n\n") }
    }

    /** Cues from the subtitle tracks of a project. */
    fun fromProject(p: Project): List<Cue> = p.tracks.filter { it.kind == TrackKind.SUBTITLE }.flatMap { t ->
        t.clips.filter { it.kind == ClipKind.SUBTITLE || it.text != null }.map { Cue(it.startUs, it.endUs, it.text?.text ?: "") }
    }.sortedBy { it.startUs }

    /**
     * Groups recognised words into readable cues: at most [maxChars] per line, [maxLines] lines,
     * [maxDurationUs] per cue, breaking on pauses longer than [pauseUs] and on punctuation.
     */
    fun segment(words: List<Word>, maxChars: Int = 32, maxLines: Int = 2, maxDurationUs: Long = 4_500_000, pauseUs: Long = 600_000, minDurationUs: Long = 700_000): List<Cue> {
        val cues = ArrayList<Cue>()
        var cur = ArrayList<Word>()
        fun flush() {
            if (cur.isEmpty()) return
            val lines = wrap(cur.joinToString(" ") { it.text }, maxChars)
            cues += Cue(cur.first().startUs, maxOf(cur.last().endUs, cur.first().startUs + minDurationUs), lines.joinToString("\n"))
            cur = ArrayList()
        }
        for (w in words) {
            if (cur.isNotEmpty()) {
                val gap = w.startUs - cur.last().endUs
                val tentative = (cur + w).joinToString(" ") { it.text }
                val tooLong = wrap(tentative, maxChars).size > maxLines
                val tooSlow = w.endUs - cur.first().startUs > maxDurationUs
                val punct = cur.last().text.endsWith(".") || cur.last().text.endsWith("?") || cur.last().text.endsWith("!")
                if (gap > pauseUs || tooLong || tooSlow || (punct && cur.size >= 3)) flush()
            }
            cur.add(w)
        }
        flush()
        // avoid overlaps produced by minDuration
        for (i in 0 until cues.size - 1) if (cues[i].endUs > cues[i + 1].startUs) cues[i] = cues[i].copy(endUs = cues[i + 1].startUs)
        return cues
    }

    fun wrap(text: String, maxChars: Int): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val lines = ArrayList<String>()
        var line = StringBuilder()
        for (w in words) {
            if (line.isNotEmpty() && line.length + 1 + w.length > maxChars) { lines += line.toString(); line = StringBuilder() }
            if (line.isNotEmpty()) line.append(' ')
            line.append(w)
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines
    }

    /** Shifts all cues (sync correction). */
    fun shift(cues: List<Cue>, deltaUs: Long) = cues.map { it.copy(startUs = (it.startUs + deltaUs).coerceAtLeast(0), endUs = (it.endUs + deltaUs).coerceAtLeast(0)) }.filter { it.endUs > it.startUs }

    /** Linear re-timing (fixes drift between two reference points). */
    fun stretch(cues: List<Cue>, factor: Double) = cues.map { it.copy(startUs = (it.startUs * factor).toLong(), endUs = (it.endUs * factor).toLong()) }
}
