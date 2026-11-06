package com.superessence.cuttinx.claude

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.media3.common.util.UnstableApi
import com.superessence.cuttinx.MainActivity
import com.superessence.cuttinx.R
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** Garde CUTTINX connecté au relais : reçoit les demandes de Claude et renvoie les résultats. */
@UnstableApi
class ClaudeService : Service() {
    @Volatile private var running = false
    private var loop: Thread? = null
    private val pool = Executors.newFixedThreadPool(3)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ClaudeLink.prefs(this).edit().putBoolean("enabled", false).apply()
            stopSelf(); return START_NOT_STICKY
        }
        val n = notification("Connecté à Claude — en attente de demandes")
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NOTIF_ID, n)
        if (!running) {
            running = true
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Cuttinx:claude").apply { acquire(6 * 60 * 60 * 1000L) }
            loop = Thread { pollLoop() }.apply { start() }
        }
        ClaudeLink.update { it.copy(enabled = true) }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        loop?.interrupt()
        pool.shutdownNow()
        runCatching { wakeLock?.release() }
        ClaudeLink.update { it.copy(enabled = false, relay = "off") }
        super.onDestroy()
    }

    private fun pollLoop() {
        val tools = ClaudeTools(this)
        val base = ClaudeLink.RELAY_URL + "/device/" + ClaudeLink.token(this)
        var backoff = 1000L
        ClaudeLink.update { it.copy(relay = "connecting") }
        while (running) {
            try {
                val info = JSONObject().put("model", Build.MODEL).put("app", "cuttinx").toString()
                val conn = URL("$base/next?info=" + URLEncoder.encode(info, "UTF-8")).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000; conn.readTimeout = 40000
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
                conn.disconnect()
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                if (ClaudeLink.state.value.relay != "online") ClaudeLink.update { it.copy(relay = "online", message = "") }
                backoff = 1000L
                val cmd = JSONObject(body).optJSONObject("command") ?: continue
                val id = cmd.getString("id"); val tool = cmd.getString("tool")
                val args = cmd.optJSONObject("args") ?: JSONObject()
                ClaudeLink.update { it.copy(lastAction = label(tool)) }
                updateNotification("Claude ${label(tool)}…")
                pool.execute {
                    val result = tools.handle(tool, args)
                    postResult("$base/result/$id", result)
                    updateNotification("Connecté à Claude — en attente de demandes")
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (!running) break
                ClaudeLink.update { it.copy(relay = "error", message = e.message ?: "réseau indisponible") }
                try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
                backoff = minOf(backoff * 2, 30000L)
            }
        }
    }

    private fun postResult(url: String, result: JSONObject) {
        repeat(3) { attempt ->
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"; conn.doOutput = true
                conn.connectTimeout = 15000; conn.readTimeout = 30000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(result.toString().toByteArray()) }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) return
            } catch (_: Exception) {}
            Thread.sleep(1000L * (attempt + 1))
        }
    }

    private fun label(tool: String) = when (tool) {
        "view_storyboard", "view_frame" -> "regarde tes vidéos"
        "detect_scenes", "find_highlights" -> "cherche les meilleurs moments"
        "analyze_music" -> "écoute la musique"
        "analyze_audio" -> "analyse le son"
        "create_project" -> "monte le projet"
        "render_project", "get_render" -> "exporte la vidéo"
        else -> "consulte tes fichiers"
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Connexion à Claude", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ClaudeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_claude)
            .setContentTitle("CUTTINX × Claude")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Déconnecter", stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        if (running) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }

    companion object {
        private const val CHANNEL = "cuttinx_claude"
        private const val NOTIF_ID = 4242
        const val ACTION_STOP = "stop"

        fun start(ctx: Context) {
            ClaudeLink.prefs(ctx).edit().putBoolean("enabled", true).apply()
            ctx.startForegroundService(Intent(ctx, ClaudeService::class.java))
        }

        fun stop(ctx: Context) {
            ClaudeLink.prefs(ctx).edit().putBoolean("enabled", false).apply()
            ctx.stopService(Intent(ctx, ClaudeService::class.java))
            ClaudeLink.update { it.copy(enabled = false, relay = "off") }
        }
    }
}
