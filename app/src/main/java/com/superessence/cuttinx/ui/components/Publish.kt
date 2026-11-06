package com.superessence.cuttinx.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.superessence.cuttinx.design.CxDesign
import com.superessence.cuttinx.ui.theme.LocalCx

/** The apps a finished video can be sent to in one tap (the first installed package of each wins). */
object Integrations {
    class Target(val name: String, val packages: List<String>, val hint: String)

    val targets = listOf(
        Target("TikTok", listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"), "Ouvre TikTok avec la vidéo prête à publier"),
        Target("Instagram", listOf("com.instagram.android"), "Reels, publication ou story"),
        Target("YouTube", listOf("com.google.android.youtube"), "Shorts ou vidéo"),
        Target("Snapchat", listOf("com.snapchat.android"), "Story ou Spotlight"),
        Target("WhatsApp", listOf("com.whatsapp", "com.whatsapp.w4b"), "Discussion ou statut"),
        Target("Facebook", listOf("com.facebook.katana"), "Reels ou publication"),
        Target("X", listOf("com.twitter.android"), "Publication"),
        Target("Telegram", listOf("org.telegram.messenger"), "Discussion ou chaîne"),
        Target("Discord", listOf("com.discord"), "Salon ou message"),
    )

    private fun sendIntent(uri: Uri?) = Intent(Intent.ACTION_SEND).setType("video/mp4").apply {
        if (uri != null) { putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** Installed targets with the package that will receive the video. */
    fun installed(ctx: Context): List<Pair<Target, String>> {
        val pkgs = runCatching { ctx.packageManager.queryIntentActivities(sendIntent(null), 0).map { it.activityInfo.packageName }.toSet() }.getOrDefault(emptySet())
        return targets.mapNotNull { t -> t.packages.firstOrNull { it in pkgs }?.let { t to it } }
    }

    fun icon(ctx: Context, pkg: String): ImageBitmap? = runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()

    fun send(ctx: Context, uri: Uri, pkg: String?) {
        val i = sendIntent(uri).apply { if (pkg != null) setPackage(pkg) }
        runCatching { ctx.startActivity(if (pkg == null) Intent.createChooser(i, "Publier la vidéo") else i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { ctx.startActivity(Intent.createChooser(sendIntent(uri), "Publier la vidéo")) } }
    }
}

/** "Publier sur" : one button per installed app (with its real icon), then "Autre…". */
@Composable
fun PublishRow(uri: Uri, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val cx = LocalCx.current
    val apps = remember { Integrations.installed(ctx).map { (t, p) -> Triple(t, p, Integrations.icon(ctx, p)) } }
    Column(modifier) {
        Text(if (apps.isEmpty()) "Publier" else "Publier directement sur", style = MaterialTheme.typography.labelLarge, color = cx.dim)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((t, pkg, icon) in apps) PublishButton(t.name, { Integrations.send(ctx, uri, pkg) }) {
                if (icon != null) Image(icon, null, Modifier.size(40.dp).clip(CxDesign.Button)) else Text(t.name.take(1), fontSize = 18.sp)
            }
            PublishButton("Autre…", { Integrations.send(ctx, uri, null) }) { Icon(Icons.Default.MoreHoriz, null, Modifier.size(26.dp)) }
        }
    }
}

@Composable
private fun PublishButton(label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val cx = LocalCx.current
    Column(Modifier.width(58.dp).clip(CxDesign.Button).clickable(onClick = onClick).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp).clip(CxDesign.Button).border(1.dp, cx.line, CxDesign.Button), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 10.5.sp, maxLines = 1, textAlign = TextAlign.Center)
    }
}
