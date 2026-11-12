package com.superessence.cuttinx.planner

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.superessence.cuttinx.MainActivity
import com.superessence.cuttinx.R
import com.superessence.cuttinx.core.create.PlannedPost
import com.superessence.cuttinx.core.create.Planner
import com.superessence.cuttinx.core.create.PostStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The publication plan, kept in a JSON file of the app (never sent anywhere), with one reminder
 * per planned post (an ordinary alarm: it can arrive a few minutes late when the phone sleeps).
 */
object PlannerStore {
    private val _posts = MutableStateFlow<List<PlannedPost>>(emptyList())
    val posts: StateFlow<List<PlannedPost>> = _posts
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "planner.json")

    fun load(ctx: Context) {
        if (loaded) return
        val f = file(ctx)
        _posts.value = if (f.exists()) Planner.decode(runCatching { f.readText() }.getOrDefault("")) else emptyList()
        loaded = true
    }

    private fun save(ctx: Context, list: List<PlannedPost>) {
        _posts.value = list
        val f = file(ctx); val tmp = File(f.path + ".tmp")
        runCatching { tmp.writeText(Planner.encode(list)); if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) } }
    }

    fun put(ctx: Context, p: PlannedPost) {
        load(ctx); save(ctx, Planner.upsert(_posts.value, p)); Reminders.schedule(ctx, p)
    }

    fun delete(ctx: Context, id: String) {
        load(ctx); _posts.value.firstOrNull { it.id == id }?.let { Reminders.cancel(ctx, it) }
        save(ctx, _posts.value.filter { it.id != id })
    }

    /** After a reboot or an update the alarms are gone: put them back. */
    fun rescheduleAll(ctx: Context) { load(ctx); _posts.value.forEach { Reminders.schedule(ctx, it) } }
}

object Reminders {
    const val CHANNEL = "cuttinx_planner"
    private const val EXTRA_ID = "post_id"

    private fun pending(ctx: Context, p: PlannedPost, create: Boolean): PendingIntent? {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction("com.superessence.cuttinx.REMIND").putExtra(EXTRA_ID, p.id)
        val flags = PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(ctx, p.id.hashCode(), i, flags)
    }

    fun schedule(ctx: Context, p: PlannedPost) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        cancel(ctx, p)
        if (!p.remind || p.status == PostStatus.PUBLISHED || p.atMs <= System.currentTimeMillis()) return
        val pi = pending(ctx, p, true) ?: return
        // inexact alarm: no special permission needed
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.atMs, pi) }
    }

    fun cancel(ctx: Context, p: PlannedPost) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        pending(ctx, p, false)?.let { am.cancel(it); it.cancel() }
    }

    fun notify(ctx: Context, id: String) {
        PlannerStore.load(ctx)
        val p = PlannerStore.posts.value.firstOrNull { it.id == id } ?: return
        if (p.status == PostStatus.PUBLISHED) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Rappels de publication", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, id.hashCode(), Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("open", "planner"), PendingIntent.FLAG_IMMUTABLE)
        val text = when (p.status) {
            PostStatus.READY -> "C'est l'heure de publier sur ${p.network.label} !"
            PostStatus.IDEA, PostStatus.FILMING -> "Prévu maintenant sur ${p.network.label} — il reste à tourner"
            else -> "Prévu maintenant sur ${p.network.label} — termine le montage"
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(p.title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text + if (p.caption.isNotBlank()) "\n\n" + p.caption.take(200) else ""))
            .setAutoCancel(true).setContentIntent(open).build()
        runCatching { nm.notify(id.hashCode(), n) }
    }

    fun idOf(i: Intent) = i.getStringExtra(EXTRA_ID)
}

/** Shows the reminder of a post; also restores the alarms after the phone restarts. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> PlannerStore.rescheduleAll(ctx)
            else -> Reminders.idOf(intent)?.let { Reminders.notify(ctx, it) }
        }
    }
}
