package com.superessence.cuttinx.core

import com.superessence.cuttinx.core.effects.Presets
import com.superessence.cuttinx.core.model.*
import com.superessence.cuttinx.core.project.*
import com.superessence.cuttinx.core.subtitles.*
import com.superessence.cuttinx.core.timeline.TimelineEditor
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class ProjectTest {
    private fun tmp() = kotlin.io.path.createTempDirectory("cx").toFile()

    private fun sample(): Project {
        var p = T.project()
        p = TimelineEditor.appendAsset(p, T.video("a", 8.0)).first
        p = TimelineEditor.appendAsset(p, T.video("b", 8.0)).first
        val c = p.tracks[0].sortedClips()
        p = TimelineEditor.setTransition(p, c[1].id, TransitionInstance("wipe", 500_000))
        p = TimelineEditor.updateClip(p, c[0].id) { it.copy(effects = Presets.instantiate(Presets.builtIn[3])) }
        p = TimelineEditor.setKeyframe(p, c[0].id, Prop.SCALE, 0, 1.0)
        p = TimelineEditor.setKeyframe(p, c[0].id, Prop.SCALE, 2_000_000, 1.3, Easing.bezier(0.2, 0.0, 0.1, 1.0))
        val (p2, t) = TimelineEditor.addTrack(p, TrackKind.TEXT); p = p2
        p = TimelineEditor.insertClip(p, t.id, TimelineEditor.textClip("Bonjour", 0))
        return p
    }

    @Test fun saveLoadRoundTrip() {
        val store = ProjectStore(tmp())
        val p = sample()
        store.save(p)
        assertEquals(p, store.load(p.id))
        assertEquals(1, store.list().size)
    }

    @Test fun corruptedFileFallsBackToBackup() {
        val root = tmp()
        val store = ProjectStore(root)
        val p = sample()
        store.save(p)
        store.save(p.copy(name = "v2"))
        File(root, "${p.id}/project.json").writeText("{ corrupted")
        val loaded = store.load(p.id)
        assertEquals("Test", loaded.name) // previous version from backup
    }

    @Test fun crashRecovery() {
        val root = tmp()
        val store = ProjectStore(root)
        val p = sample()
        store.save(p)
        store.openSession(p.id)
        Thread.sleep(20)
        store.autosave(p.copy(name = "travail non enregistré"))
        // simulate crash: new store instance, lock still present
        val store2 = ProjectStore(root)
        val rec = store2.pendingRecoveries()
        assertEquals(1, rec.size)
        assertEquals("travail non enregistré", rec[0].autosave.name)
        store2.acceptRecovery(rec[0])
        assertEquals("travail non enregistré", store2.load(p.id).name)
        assertTrue(store2.pendingRecoveries().isEmpty())
    }

    @Test fun duplicateRenameArchive() {
        val store = ProjectStore(tmp())
        val p = sample(); store.save(p)
        val d = store.duplicate(p.id, "Copie")
        assertNotEquals(p.id, d.id)
        store.rename(d.id, "Renommé"); store.setArchived(d.id, true)
        val l = store.list().first { it.id == d.id }
        assertEquals("Renommé", l.name); assertTrue(l.archived)
    }

    @Test fun archiveRoundTrip() {
        val p = sample()
        val out = ByteArrayOutputStream()
        ProjectArchive.write(out, p, includeMedia = true) { id -> "$id.mp4" to ByteArrayInputStream(ByteArray(100) { it.toByte() }) }
        val imp = ProjectArchive.read(ByteArrayInputStream(out.toByteArray()), tmp())
        assertEquals(p.tracks, imp.project.tracks)
        assertEquals(2, imp.mediaFiles.size)
        assertEquals(100, imp.mediaFiles["a"]!!.length())
    }

    @Test fun srtAndVtt() {
        val srt = "1\n00:00:01,000 --> 00:00:02,500\nBonjour\n\n2\n00:00:03,000 --> 00:00:04,000\n<i>tout</i> le monde\n"
        val cues = SubtitleCodec.parse(srt)
        assertEquals(2, cues.size)
        assertEquals(1_000_000, cues[0].startUs); assertEquals(2_500_000, cues[0].endUs)
        assertEquals("tout le monde", cues[1].text)
        assertEquals(cues, SubtitleCodec.parse(SubtitleCodec.toSrt(cues)))
        assertEquals(cues, SubtitleCodec.parse(SubtitleCodec.toVtt(cues), "x.vtt"))
        val vtt = "WEBVTT\n\n00:01.000 --> 00:02.000 align:middle\nSalut\n"
        assertEquals(1_000_000, SubtitleCodec.parseVtt(vtt)[0].startUs)
    }

    @Test fun wordSegmentation() {
        val words = "Bonjour à tous et bienvenue dans cette nouvelle vidéo. Aujourd'hui on va parler de montage".split(" ").mapIndexed { i, w -> Word(w, i * 300_000L, i * 300_000L + 250_000) }
        val cues = SubtitleCodec.segment(words)
        assertTrue(cues.size >= 2)
        cues.forEach { c -> c.text.lines().forEach { assertTrue(it.length <= 32) }; assertTrue(c.text.lines().size <= 2) }
        for (i in 1 until cues.size) assertTrue(cues[i].startUs >= cues[i - 1].endUs)
    }

    @Test fun exportPlanner() {
        assertEquals(1080 to 1920, ExportPlanner.outputSize(ProjectFormat(), 1080))
        assertEquals(3840 to 2160, ExportPlanner.outputSize(ProjectFormat.forAspect(AspectPreset.LANDSCAPE_16_9), 2160))
        val caps = listOf(EncoderCaps(VideoCodec.H264, 1920, 1088, 60, 40_000_000, true))
        val r = ExportPlanner.resolve(ExportSettings(resolutionShortSide = 2160, videoCodec = VideoCodec.HEVC), ProjectFormat.forAspect(AspectPreset.LANDSCAPE_16_9), 10_000_000, caps)
        assertEquals(VideoCodec.H264, r.videoCodec)
        assertTrue(r.width <= 1920 && r.height <= 1088)
        assertTrue(r.warnings.size >= 2)
        val ok = ExportPlanner.resolve(ExportSettings(), ProjectFormat(), 10_000_000, caps)
        assertEquals(1080, ok.width); assertEquals(1920, ok.height); assertTrue(ok.warnings.isEmpty())
    }

    @Test fun presetsRoundTrip() {
        val txt = Presets.export(Presets.builtIn.take(3))
        val back = Presets.import(txt)
        assertEquals(3, back.size)
        assertEquals(Presets.builtIn[0].effects.map { it.effectId }, back[0].effects.map { it.effectId })
        assertThrows(Exception::class.java) { Presets.import("{\"format\":\"autre\",\"presets\":[]}") }
    }
}
