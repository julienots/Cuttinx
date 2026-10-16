package com.superessence.cuttinx.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.superessence.cuttinx.core.model.MediaAsset
import com.superessence.cuttinx.core.model.MediaType
import java.io.File

data class ImportResult(val assets: List<MediaAsset>, val errors: List<String>)

/**
 * Imports user media: keeps a persistable read permission on the original (never modified), or
 * copies the file into app storage when the source does not allow persistent access.
 */
class MediaImporter(private val context: Context) {
    private val copies = File(context.filesDir, "media").also { it.mkdirs() }

    fun import(uris: List<Uri>, forced: MediaType? = null, folder: String = ""): ImportResult {
        val ok = ArrayList<MediaAsset>(); val errs = ArrayList<String>()
        for (u in uris) {
            try {
                val usable = persistOrCopy(u)
                ok += MediaProbe.probe(context, usable, forced).copy(folder = folder)
            } catch (e: Exception) {
                errs += e.message ?: "Import impossible"
            }
        }
        return ImportResult(ok, errs)
    }

    private fun persistOrCopy(u: Uri): Uri {
        if (u.scheme == "file") return u
        val persisted = runCatching { context.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); true }.getOrDefault(false)
        if (persisted) return u
        // Photo picker / temporary grants: verify we can still read it later, otherwise copy.
        val alreadyPersisted = context.contentResolver.persistedUriPermissions.any { it.uri == u && it.isReadPermission }
        if (alreadyPersisted) return u
        return copyLocal(u)
    }

    /** Copies a file into app storage (the original stays untouched). */
    fun copyLocal(u: Uri, subdir: String = ""): Uri {
        val (name, _) = MediaProbe.displayName(context.contentResolver, u)
        val d = if (subdir.isEmpty()) copies else File(copies, subdir).also { it.mkdirs() }
        val dest = File(d, System.currentTimeMillis().toString(36) + "_" + name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        context.contentResolver.openInputStream(u)!!.use { inp -> dest.outputStream().use { inp.copyTo(it) } }
        return Uri.fromFile(dest)
    }

    /** Checks every asset and flags the ones that can no longer be opened. */
    fun refreshMissing(assets: List<MediaAsset>): List<MediaAsset> = assets.map { a -> a.copy(missing = !MediaProbe.isReachable(context, a.uri)) }
}
