package com.superessence.cuttinx.core

import com.superessence.cuttinx.core.create.CaptionTone
import com.superessence.cuttinx.core.create.Captions
import com.superessence.cuttinx.core.create.GifEncoder
import com.superessence.cuttinx.core.create.GifKit
import com.superessence.cuttinx.core.create.GifMode
import com.superessence.cuttinx.core.create.Network
import com.superessence.cuttinx.core.create.PlannedPost
import com.superessence.cuttinx.core.create.Planner
import com.superessence.cuttinx.core.create.PostStatus
import com.superessence.cuttinx.core.create.Quantizer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CreateKit31Test {
    private fun gradient(w: Int, h: Int, shift: Int) = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        val r = (x * 255 / (w - 1) + shift) and 255; val g = y * 255 / (h - 1); val b = ((x + y) * 3 + shift * 2) and 255
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun decode(bytes: ByteArray): List<java.awt.image.BufferedImage> {
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        reader.input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
        val n = reader.getNumImages(true)
        return List(n) { reader.read(it) }
    }

    @Test fun gifIsReadableFrameByFrame() {
        val w = 97; val h = 61 // odd sizes on purpose
        val bos = ByteArrayOutputStream()
        val enc = GifEncoder(bos, w, h, dither = false)
        val frames = List(4) { gradient(w, h, it * 40) }
        frames.forEach { enc.addFrame(it, 5) }
        enc.finish()
        val bytes = bos.toByteArray()
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        assertEquals(0x3B, bytes.last().toInt())
        val imgs = decode(bytes)
        assertEquals(4, imgs.size)
        for ((k, img) in imgs.withIndex()) {
            assertEquals(w, img.width); assertEquals(h, img.height)
            // colours close to the source (median cut on a smooth gradient)
            var err = 0L
            for (y in 0 until h step 7) for (x in 0 until w step 5) {
                val a = frames[k][y * w + x]; val b = img.getRGB(x, y)
                err += abs((a shr 16 and 255) - (b shr 16 and 255)) + abs((a shr 8 and 255) - (b shr 8 and 255)) + abs((a and 255) - (b and 255))
            }
            val samples = ((h + 6) / 7) * ((w + 4) / 5)
            assertTrue(err / samples < 30, "écart moyen trop grand : ${err / samples}")
        }
    }

    @Test fun largeNoisyFrameExercisesDictionaryReset() {
        val w = 320; val h = 240
        val rnd = java.util.Random(4)
        val px = IntArray(w * h) { (0xFF shl 24) or rnd.nextInt(0xFFFFFF) }
        val bos = ByteArrayOutputStream()
        GifEncoder(bos, w, h, dither = true).apply { addFrame(px, 4); addFrame(gradient(w, h, 0), 4); finish() }
        val imgs = decode(bos.toByteArray())
        assertEquals(2, imgs.size)
        // the gradient frame decodes right after a frame that filled the LZW table many times
        val g = gradient(w, h, 0)
        val p = imgs[1].getRGB(w / 2, h / 2); val s = g[(h / 2) * w + w / 2]
        assertTrue(abs((p shr 8 and 255) - (s shr 8 and 255)) < 40)
    }

    @Test fun flatAndTinyImages() {
        for ((w, h) in listOf(1 to 1, 2 to 3, 16 to 16)) {
            val bos = ByteArrayOutputStream()
            GifEncoder(bos, w, h).apply { addFrame(IntArray(w * h) { 0xFF336699.toInt() }, 10); finish() }
            val img = decode(bos.toByteArray()).single()
            val c = img.getRGB(0, 0)
            assertTrue(abs((c shr 16 and 255) - 0x33) < 10 && abs((c and 255) - 0x99) < 10)
        }
    }

    @Test fun paletteNeverExceeds256() {
        val px = IntArray(50_000) { (0xFF shl 24) or (it * 2654435761L).toInt() and 0xFFFFFF }
        val pal = Quantizer.medianCut(px, px.size, 256)
        assertTrue(pal.size in 200..256)
        assertEquals(2, Quantizer.medianCut(IntArray(10) { if (it < 5) 0xFF000000.toInt() else -1 }, 10, 256).size)
    }

    @Test fun gifTimingAndModes() {
        assertEquals(listOf(0, 1, 2, 3, 2, 1), GifKit.order(4, GifMode.BOOMERANG))
        assertEquals(listOf(2, 1, 0), GifKit.order(3, GifMode.REVERSE))
        assertEquals(listOf(0), GifKit.order(1, GifMode.BOOMERANG))
        assertEquals(30, GifKit.sampleTimes(1.0, 3.0, 15).size)
        assertEquals(7, GifKit.delayCs(15, 1.0))
        assertEquals(2, GifKit.delayCs(50, 4.0))
        assertEquals(480 to 270, GifKit.outputSize(1920, 1080, 480))
        assertEquals(200 to 100, GifKit.outputSize(200, 100, 480)) // never upscaled
    }

    private val zone = ZoneId.of("Europe/Paris")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test fun plannerSuggestsFutureSlotsOnGoodDays() {
        val now = ms(2026, 11, 12, 15) // Thursday 15:00
        val slots = Planner.suggestSlots(Network.TIKTOK, now, zone, count = 4)
        assertEquals(4, slots.size)
        assertTrue(slots.all { it > now })
        assertEquals(slots.sorted(), slots)
        assertTrue(slots.all { Instant.ofEpochMilli(it).atZone(zone).dayOfWeek in Network.TIKTOK.bestDays })
        assertEquals(ms(2026, 11, 12, 19), slots.first())
        // a planned post blocks its slot
        val busy = listOf(PlannedPost("a", "x", Network.TIKTOK, ms(2026, 11, 12, 19)))
        assertTrue(Planner.suggestSlots(Network.TIKTOK, now, zone, busy).none { abs(it - ms(2026, 11, 12, 19)) < 3 * 3_600_000L })
    }

    @Test fun plannerStatsAndStreak() {
        val now = ms(2026, 11, 12, 15)
        val posts = listOf(
            PlannedPost("1", "a", Network.INSTAGRAM, ms(2026, 11, 10, 12), PostStatus.PUBLISHED),
            PlannedPost("2", "b", Network.INSTAGRAM, ms(2026, 11, 4, 12), PostStatus.PUBLISHED),
            PlannedPost("3", "c", Network.INSTAGRAM, ms(2026, 10, 28, 12), PostStatus.PUBLISHED),
            PlannedPost("4", "d", Network.TIKTOK, ms(2026, 11, 11, 19), PostStatus.READY),
            PlannedPost("5", "e", Network.TIKTOK, ms(2026, 11, 14, 19), PostStatus.EDITING),
            PlannedPost("6", "f", Network.YOUTUBE, ms(2026, 11, 20, 17)),
        )
        val s = Planner.stats(posts, now, zone)
        assertEquals(3, s.streakWeeks)
        assertEquals(1, s.late)
        assertEquals(2, s.upcoming)
        assertEquals(1, s.publishedThisWeek)
        assertEquals(3, s.plannedThisWeek)
        assertEquals("5", s.next?.id)
        val week = Planner.week(posts, Planner.weekStart(now, zone), zone)
        assertEquals(DayOfWeek.MONDAY, Planner.weekStart(now, zone).dayOfWeek)
        assertEquals(1, week[1].size) // Tuesday 10
        assertEquals(1, week[5].size) // Saturday 14
        assertEquals(posts.sortedBy { it.atMs }, Planner.decode(Planner.encode(posts)))
        assertEquals(emptyList(), Planner.decode("pas du json"))
        assertEquals("demain 19:00", Planner.relative(ms(2026, 11, 13, 19), now, zone))
        assertEquals("en retard de 20 h", Planner.relative(ms(2026, 11, 11, 19), now, zone))
    }

    @Test fun captionsRespectNetworkLimits() {
        for (n in Network.entries) for (t in CaptionTone.entries) {
            val list = Captions.generate("Ma routine du matin pour être productif toute la journée", n, t, seed = 3)
            assertEquals(3, list.size)
            list.forEach { c -> assertTrue(c.text.length <= n.captionLimit, "${n.label} ${c.text.length}") }
            assertTrue(list.map { it.hook }.distinct().size > 1)
        }
        val tags = Captions.hashtags("Recette crêpes faciles", Network.TIKTOK)
        assertTrue("#crepes" in tags && "#pourtoi" in tags, tags.toString())
        assertTrue(tags.size <= Network.TIKTOK.hashtagAdvice.last)
        assertTrue(Captions.hashtags("x", Network.TWITCH).isEmpty())
        assertEquals(listOf("routine", "matin", "productif"), Captions.keywords("Ma routine du matin, très productif !"))
    }

    @Test fun captionChecks() {
        assertTrue(Captions.check("a".repeat(300), Network.X).any { "Trop long" in it })
        assertTrue(Captions.check("Super vidéo #a #b #c #d #e #f #g", Network.TIKTOK).any { "hashtags" in it })
        assertTrue(Captions.check("Bonjour", Network.INSTAGRAM).any { "appel" in it })
        assertEquals(emptyList(), Captions.check("Tu en penses quoi ? #recette", Network.INSTAGRAM))
    }
}
