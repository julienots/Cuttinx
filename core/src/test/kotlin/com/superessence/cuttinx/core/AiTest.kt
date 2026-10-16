package com.superessence.cuttinx.core

import com.superessence.cuttinx.core.analysis.*
import com.superessence.cuttinx.core.autoedit.*
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class AiTest {
    private val sr = 48000

    @Test fun silenceDetection() {
        val s = T.concat(T.sine(300.0, 1.0, amp = 0.3), T.noise(1.2, 0.001), T.sine(300.0, 1.0, amp = 0.3))
        val r = SilenceDetector.detect(s, sr, 1)
        assertEquals(1, r.size)
        assertEquals(1_120_000.0, r[0].startUs.toDouble(), 60_000.0)
        assertEquals(2_080_000.0, r[0].endUs.toDouble(), 60_000.0)
        val keep = SilenceDetector.keepRanges(r, 3_200_000)
        assertEquals(2, keep.size)
    }

    @Test fun removeSilencesOnTimeline() {
        var p = TimelineEditor.appendAsset(T.project(), T.video("a", 10.0)).first
        p = TimelineEditor.appendAsset(p, T.video("b", 5.0)).first
        val c = p.tracks[0].sortedClips()[0]
        p = AiActions.removeSourceRanges(p, c.id, listOf(TimeRange(2_000_000, 4_000_000), TimeRange(7_000_000, 8_000_000)))
        val m = p.tracks[0].sortedClips()
        assertEquals(4, m.size)
        assertEquals(12_000_000, p.durationUs)
        assertEquals(listOf(0L to 2_000_000L, 4_000_000L to 7_000_000L, 8_000_000L to 10_000_000L), m.take(3).map { it.sourceInUs to it.sourceOutUs })
        for (i in 1 until m.size) assertEquals(m[i - 1].endUs, m[i].startUs)
    }

    private fun clickTrack(bpm: Double, seconds: Double): FloatArray {
        val s = FloatArray((seconds * sr).toInt())
        val period = 60.0 / bpm * sr
        var t = 0.25 * sr
        while (t < s.size) {
            val i0 = t.toInt()
            for (k in 0 until 600) if (i0 + k < s.size) s[i0 + k] += (kotlin.math.sin(k * 0.3) * kotlin.math.exp(-k / 120.0) * 0.8).toFloat()
            t += period
        }
        return s
    }

    @Test fun beatDetectionOnClickTrack() {
        for (bpm in listOf(96.0, 120.0, 140.0)) {
            val r = BeatDetector.detect(clickTrack(bpm, 12.0), sr, 1)
            assertEquals("bpm $bpm", bpm, r.bpm, bpm * 0.03)
            val spacing = r.beatsUs.zipWithNext { a, b -> b - a }.drop(2).dropLast(2)
            val expected = 60_000_000 / bpm
            assertTrue(spacing.all { abs(it - expected) < expected * 0.1 })
            assertTrue(r.onsetsUs.size >= 12 * bpm / 60 * 0.8)
        }
    }

    private fun solid(t: Long, color: Int, w: Int = 32, h: Int = 18) = Frame(t, w, h, IntArray(w * h) { color })

    @Test fun sceneDetection() {
        val frames = (0 until 40).map { i ->
            val base = if (i < 20) 0xFF2050A0.toInt() else 0xFFC08020.toInt()
            // small noise so frames aren't identical
            Frame(i * 100_000L, 32, 18, IntArray(32 * 18) { k -> base + ((k * 7 + i) % 5) })
        }
        val cuts = SceneDetector.detect(frames)
        assertEquals(listOf(2_000_000L), cuts)
    }

    @Test fun highlightsPreferAction() {
        val stats = (0 until 100).map { i -> FrameStats(i * 100_000L, 0.5, 0.01, 0.3, if (i in 60..80) 0.4 else 0.01, 0.0) }
        val h = HighlightDetector.detect(stats, segmentUs = 2_000_000, count = 1)
        assertEquals(1, h.size)
        assertTrue(h[0].startUs in 5_000_000..7_000_000)
    }

    @Test fun trackingFollowsMovingSquare() {
        val w = 64; val h = 36
        val frames = (0 until 20).map { k ->
            val x0 = 5 + k * 2
            Frame(k * 40_000L, w, h, IntArray(w * h) { i -> val x = i % w; val y = i / w; if (x in x0 until x0 + 8 && y in 12 until 20) 0xFFFFFFFF.toInt() else 0xFF101010.toInt() })
        }
        val path = SubjectAnalysis.track(frames, SubjectAnalysis.Box((5 + 4) / 64.0, 16 / 36.0, 12 / 64.0, 12 / 36.0))
        val last = path.last().second
        assertEquals((5 + 38 + 4) / 64.0, last.cx, 3.0 / 64)
        assertEquals(16 / 36.0, last.cy, 2.0 / 36)
    }

    @Test fun subjectDetectionAndReframe() {
        val w = 64; val h = 36
        val f = Frame(0, w, h, IntArray(w * h) { i -> val x = i % w; val y = i / w; if (x in 44 until 54 && y in 10 until 26) 0xFFE0A070.toInt() else 0xFF303030.toInt() })
        val b = SubjectAnalysis.detectSubject(f)
        assertTrue("subject x=${b.cx}", b.cx > 0.6)
        val keys = SubjectAnalysis.autoReframe(listOf(f), 1920, 1080, 1080, 1920)
        // subject on the right -> image must be moved left (posX < 0.5)
        assertTrue(keys[0].posX < 0.5)
    }

    @Test fun backgroundRemovalOnUniformBackground() {
        val w = 40; val h = 40
        val img = IntArray(w * h) { i -> val x = i % w; val y = i / w; if (x in 12 until 28 && y in 12 until 28) 0xFFD02020.toInt() else 0xFF20C040.toInt() }
        val a = SubjectAnalysis.removeBackground(img, w, h, feather = 0)
        assertEquals(0f, a[0]); assertEquals(1f, a[20 * w + 20])
        assertEquals(256, a.count { it > 0.5f })
    }

    @Test fun titlesAndSummary() {
        val titles = TextTools.generateTitles("Notre voyage en Islande : glaciers, cascades et aurores boréales pendant 7 jours")
        assertTrue(titles.size >= 5)
        assertTrue(titles.any { it.contains("Islande", ignoreCase = true) || it.contains("Glacier", ignoreCase = true) })
        val s = TextTools.summarize(TextTools.splitSentences("Le chat dort. Le chat mange du poisson. Il pleut dehors. Le chat joue avec le poisson rouge. Fin.").map { TextTools.Sentence(it) }, 2)
        assertEquals(2, s.size)
        assertTrue(s.all { it.text.contains("chat") })
    }

    @Test fun autoEditProducesEditableBeatSyncedProject() {
        val sources = (0 until 3).map { k ->
            val a = T.video("v$k", 20.0)
            SourceInfo(a, (0 until 200).map { i -> FrameStats(i * 100_000L, 0.5, 0.01 + (i % 7) * 0.001, 0.3, ((i + k * 30) % 50) / 100.0, 0.0) })
        }
        val beats = BeatResult(120.0, (0 until 100).map { it * 500_000L }, emptyList(), emptyList(), 0.9)
        val req = AutoEditRequest(sources, AutoStyle.DYNAMIC, 15_000_000, ProjectFormat(), music = T.audio("m", 60.0), beats = beats, title = "Été 2026")
        val prop = AutoEditPlanner.propose(req)
        val p = prop.project
        val main = p.tracks.first { it.kind == TrackKind.VIDEO }.sortedClips()
        assertTrue(main.size >= 3)
        assertEquals(15.0, p.tracks.first().endUs / 1e6, 4.0)
        // every cut lands on a beat grid point (except transitions overlap)
        main.drop(1).forEach { c ->
            assertTrue("cut at ${c.startUs}", beats.beatsUs.any { abs(it - c.startUs) < 2_000 })
        }
        assertTrue(p.tracks.any { it.kind == TrackKind.AUDIO && it.clips.isNotEmpty() })
        assertTrue(p.tracks.any { it.kind == TrackKind.TEXT && it.clips.isNotEmpty() })
        assertTrue(prop.decisions.any { it.id == "transitions" })
        // rejecting a decision removes it
        val noFx = AutoEditPlanner.propose(req, rejected = setOf("look", "title", "transitions")).project
        assertTrue(noFx.allClips.all { it.effects.isEmpty() && it.transitionIn == null })
        assertTrue(noFx.tracks.none { it.kind == TrackKind.TEXT })
    }

    @Test fun syncCutsToBeats() {
        var p = T.project()
        repeat(3) { p = TimelineEditor.appendAsset(p, T.video("v$it", 3.1)).first }
        val beats = (0 until 30).map { it * 500_000L }
        p = AiActions.syncCutsToBeats(p, p.tracks[0].id, beats)
        p.tracks[0].sortedClips().dropLast(1).forEach { assertEquals(0L, it.endUs % 500_000) }
    }

    @Test fun suggestions() {
        var p = T.project()
        assertEquals(SuggestionKind.EMPTY, Suggestions.analyze(p).single().kind)
        repeat(3) { p = TimelineEditor.appendAsset(p, T.video("v$it", 20.0)).first }
        val kinds = Suggestions.analyze(p).map { it.kind }.toSet()
        assertTrue(SuggestionKind.LONG_CLIP in kinds)
        assertTrue(SuggestionKind.NO_TRANSITIONS in kinds)
        assertTrue(SuggestionKind.ASPECT_MISMATCH in kinds)
        assertTrue(SuggestionKind.NO_TITLE in kinds)
    }

    @Test fun mediaOrganizer() {
        val list = listOf(T.video("a", 5.0).copy(dateAddedMs = 1_700_000_000_000, sizeBytes = 10), T.video("b", 5.0).copy(dateAddedMs = 1_700_000_000_000, sizeBytes = 10), T.image("c").copy(dateAddedMs = 1_600_000_000_000))
        assertEquals(1, MediaOrganizer.duplicates(list).size)
        assertEquals(2, MediaOrganizer.byType(list).size)
        assertEquals(listOf("c.png", "a.mp4", "b.mp4"), MediaOrganizer.sort(list, MediaOrganizer.SortKey.DATE).map { it.displayName }.let { listOf(it[0]) + it.drop(1).sorted() })
        assertEquals(1, MediaOrganizer.search(list, "B.MP4").size)
    }
}
