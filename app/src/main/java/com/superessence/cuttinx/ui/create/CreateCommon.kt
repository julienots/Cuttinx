package com.superessence.cuttinx.ui.create

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.cuttinx.design.CxDesign
import com.superessence.cuttinx.design.cxTag
import com.superessence.cuttinx.ui.theme.LocalCx
import java.io.File

/** Picture loading and saving shared by the photo, collage and audio studios. */
object CreateIo {
    /** Decodes a picture with its orientation applied, its long side at most [maxSide] px, in software memory (usable by OpenGL and Canvas). */
    fun loadBitmap(ctx: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val s = maxOf(info.size.width, info.size.height)
                if (s > maxSide) { val k = maxSide.toFloat() / s; d.setTargetSize(maxOf(1, (info.size.width * k).toInt()), maxOf(1, (info.size.height * k).toInt())) }
            }.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }
        } else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val raw = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
            val rot = runCatching { ctx.contentResolver.openInputStream(uri)?.use { when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90; 3 -> 180; 8 -> 270; else -> 0 } } ?: 0 }.getOrDefault(0)
            val s = maxOf(raw.width, raw.height)
            val m = Matrix().apply { if (s > maxSide) postScale(maxSide.toFloat() / s, maxSide.toFloat() / s); if (rot != 0) postRotate(rot.toFloat()) }
            if (m.isIdentity) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        }
    }.getOrNull()

    /** Saves a JPEG in Pictures/Cuttinx (visible in the gallery). */
    fun saveJpeg(ctx: Context, bmp: Bitmap, name: String, quality: Int = 95): Uri? = runCatching {
        val file = "$name.jpg"
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Cuttinx"); put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)!!
            ctx.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        } else {
            @Suppress("DEPRECATION") val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Cuttinx").apply { mkdirs() }
            val f = File(dir, file); f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            android.media.MediaScannerConnection.scanFile(ctx, arrayOf(f.path), arrayOf("image/jpeg"), null)
            Uri.fromFile(f)
        }
    }.getOrNull()

    /** Copies a WAV file into Music/Cuttinx. */
    fun saveWav(ctx: Context, src: File, name: String): Uri? = runCatching {
        val file = "$name.wav"
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, file); put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Cuttinx"); put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v)!!
            ctx.contentResolver.openOutputStream(uri)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
            ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            uri
        } else {
            @Suppress("DEPRECATION") val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Cuttinx").apply { mkdirs() }
            val f = File(dir, file); src.copyTo(f, overwrite = true)
            android.media.MediaScannerConnection.scanFile(ctx, arrayOf(f.path), arrayOf("audio/wav"), null)
            Uri.fromFile(f)
        }
    }.getOrNull()

    fun stamp(): String = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.FRANCE).format(java.util.Date())

    /** Crop of [b] with rotation [deg] (multiple of 90). */
    fun cropRotate(b: Bitmap, box: com.superessence.cuttinx.core.create.Box, deg: Int): Bitmap {
        val m = Matrix().apply { if (deg % 360 != 0) postRotate(deg.toFloat()) }
        return Bitmap.createBitmap(b, box.x, box.y, box.w.coerceAtMost(b.width - box.x), box.h.coerceAtMost(b.height - box.y), m, true)
    }

    /** Rotates first, then the box is measured on the rotated picture. */
    fun rotate(b: Bitmap, deg: Int): Bitmap = if (deg % 360 == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
}

/** Top bar of a studio: back, title with a tag, and the main action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioBar(title: String, tag: String, onBack: () -> Unit, action: String?, actionEnabled: Boolean = true, onAction: () -> Unit = {}) {
    val cx = LocalCx.current
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                Spacer(Modifier.width(8.dp))
                Text(tag, Modifier.cxTag(cx.accent), color = cx.accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        },
        navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
        actions = {
            if (action != null) Button(onAction, Modifier.padding(end = 8.dp), enabled = actionEnabled, shape = CxDesign.Button,
                colors = ButtonDefaults.buttonColors(containerColor = cx.accent, contentColor = cx.onAccent), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                Text(action, fontWeight = FontWeight.SemiBold)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

/** Empty state of a studio: what it does and the button to start. */
@Composable
fun StudioEmpty(title: String, text: String, button: String, onPick: () -> Unit) {
    val cx = LocalCx.current
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(text, color = cx.dim, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        Button(onPick, shape = CxDesign.Button, colors = ButtonDefaults.buttonColors(containerColor = cx.accent, contentColor = cx.onAccent)) { Text(button, fontWeight = FontWeight.SemiBold) }
    }
}

/** Busy veil while a studio renders or saves. */
@Composable
fun BusyVeil(text: String) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = LocalCx.current.accent)
            Spacer(Modifier.height(12.dp))
            Text(text, color = Color.White)
        }
    }
}
