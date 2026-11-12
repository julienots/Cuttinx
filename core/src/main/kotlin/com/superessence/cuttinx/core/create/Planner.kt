package com.superessence.cuttinx.core.create

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Where a post goes, with the limits of that network and its usual busy hours (local time).
 * The hours are general habits of each audience, not measured on the user's own account.
 */
@Serializable
enum class Network(val label: String, val captionLimit: Int, val hashtagAdvice: IntRange, val bestHours: List<Int>, val bestDays: Set<DayOfWeek>) {
    TIKTOK("TikTok", 4000, 3..5, listOf(12, 19, 21), setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)),
    INSTAGRAM("Instagram", 2200, 3..8, listOf(11, 13, 19), setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)),
    YOUTUBE("YouTube", 5000, 2..4, listOf(14, 17, 20), setOf(DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)),
    SHORTS("YouTube Shorts", 100, 2..3, listOf(12, 18, 21), setOf(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)),
    SNAPCHAT("Snapchat", 250, 0..3, listOf(10, 17, 22), setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)),
    X("X", 280, 1..2, listOf(9, 12, 18), setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)),
    FACEBOOK("Facebook", 5000, 1..3, listOf(9, 13, 16), setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)),
    LINKEDIN("LinkedIn", 3000, 3..5, listOf(8, 12, 17), setOf(DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)),
    TWITCH("Twitch (live)", 140, 0..0, listOf(18, 20, 21), setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)),
}

@Serializable
enum class PostStatus(val label: String) { IDEA("Idée"), FILMING("À tourner"), EDITING("En montage"), READY("Prêt"), PUBLISHED("Publié") }

/** One planned publication. [atMs] = when to publish (epoch ms); [remind] = notify at that time. */
@Serializable
data class PlannedPost(
    val id: String, val title: String, val network: Network, val atMs: Long,
    val status: PostStatus = PostStatus.IDEA, val caption: String = "", val note: String = "", val remind: Boolean = true,
)

/** Week summary for the planner header. */
data class PlanStats(val upcoming: Int, val late: Int, val publishedThisWeek: Int, val plannedThisWeek: Int, val streakWeeks: Int, val next: PlannedPost?)

/** Pure logic of the publication planner (the apps store the list and show it). */
object Planner {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun encode(posts: List<PlannedPost>): String = json.encodeToString(posts.sortedBy { it.atMs })
    fun decode(text: String): List<PlannedPost> = runCatching { json.decodeFromString<List<PlannedPost>>(text) }.getOrDefault(emptyList())

    fun upsert(posts: List<PlannedPost>, p: PlannedPost): List<PlannedPost> = (posts.filter { it.id != p.id } + p).sortedBy { it.atMs }

    /**
     * Next [count] good moments to publish on [network] after [fromMs], best days first, never
     * closer than 3 h to a post already planned on that network.
     */
    fun suggestSlots(network: Network, fromMs: Long, zone: ZoneId, existing: List<PlannedPost> = emptyList(), count: Int = 4): List<Long> {
        val taken = existing.filter { it.network == network }.map { it.atMs }
        val start = Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDateTime()
        val out = ArrayList<Long>()
        val backup = ArrayList<Long>()
        var day = start.toLocalDate()
        for (d in 0 until 21) {
            for (h in network.bestHours) {
                val t = LocalDateTime.of(day, java.time.LocalTime.of(h, 0))
                if (!t.isAfter(start.plusMinutes(30))) continue
                val ms = t.atZone(zone).toInstant().toEpochMilli()
                if (taken.any { kotlin.math.abs(it - ms) < 3 * 3_600_000L }) continue
                if (day.dayOfWeek in network.bestDays) out += ms else backup += ms
                if (out.size >= count) return out
            }
            day = day.plusDays(1)
        }
        return (out + backup).sorted().take(count)
    }

    /** Monday 00:00 of the week containing [ms]. */
    fun weekStart(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** The posts of each day of the week starting on [monday] (7 lists). */
    fun week(posts: List<PlannedPost>, monday: LocalDate, zone: ZoneId): List<List<PlannedPost>> =
        List(7) { i -> val d = monday.plusDays(i.toLong()); posts.filter { Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate() == d }.sortedBy { it.atMs } }

    fun stats(posts: List<PlannedPost>, nowMs: Long, zone: ZoneId): PlanStats {
        val monday = weekStart(nowMs, zone)
        fun weekOf(p: PlannedPost) = weekStart(p.atMs, zone)
        val pending = posts.filter { it.status != PostStatus.PUBLISHED }
        // streak: consecutive weeks (ending this week or last week) with at least one published post
        val publishedWeeks = posts.filter { it.status == PostStatus.PUBLISHED }.map { weekOf(it) }.toSet()
        var w = if (monday in publishedWeeks) monday else monday.minusWeeks(1)
        var streak = 0
        while (w in publishedWeeks) { streak++; w = w.minusWeeks(1) }
        return PlanStats(
            upcoming = pending.count { it.atMs >= nowMs },
            late = pending.count { it.atMs < nowMs },
            publishedThisWeek = posts.count { it.status == PostStatus.PUBLISHED && weekOf(it) == monday },
            plannedThisWeek = posts.count { weekOf(it) == monday },
            streakWeeks = streak,
            next = pending.filter { it.atMs >= nowMs }.minByOrNull { it.atMs },
        )
    }

    /** "dans 2 h", "demain 19:00", "lun. 12/11 · 19:00", "en retard de 3 j"… */
    fun relative(ms: Long, nowMs: Long, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(ms).atZone(zone); val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val hm = "%02d:%02d".format(t.hour, t.minute)
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), t.toLocalDate())
        val mins = (ms - nowMs) / 60_000
        return when {
            mins < -60 * 24 -> "en retard de ${-mins / (60 * 24)} j"
            mins < -60 -> "en retard de ${-mins / 60} h"
            mins < 0 -> "en retard de ${-mins} min"
            mins < 60 -> "dans $mins min"
            days == 0L -> "aujourd'hui $hm"
            days == 1L -> "demain $hm"
            days in 2..6 -> dayName(t.dayOfWeek) + " $hm"
            else -> "%s %02d/%02d · %s".format(dayName(t.dayOfWeek), t.dayOfMonth, t.monthValue, hm)
        }
    }

    fun dayName(d: DayOfWeek) = listOf("lun.", "mar.", "mer.", "jeu.", "ven.", "sam.", "dim.")[d.value - 1]
}
