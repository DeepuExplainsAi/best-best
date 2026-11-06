package app.dkord.data.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.DocumentsContract
import androidx.core.net.toUri
import java.io.File
import java.io.IOException

/**
 * Where finished files live: the app's own music folder by default (no permission needed), or a
 * folder the user picked with the system folder picker (a SAF tree uri).
 */
class DownloadStorage(private val context: Context) {
    private val resolver get() = context.contentResolver

    /** Temp area for partial files; survives process death so downloads can resume. */
    val workDir: File get() = File(context.filesDir, "downloads").apply { mkdirs() }

    fun partFile(videoId: String) = File(workDir, "$videoId.part")

    private val defaultDir: File
        get() = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "music"), "Dkord").apply { mkdirs() }

    /** Moves [file] to the final location and returns the uri to store. [file] is deleted. */
    fun publish(file: File, displayName: String, mime: String, tree: String?): String {
        if (tree != null) runCatching { return publishToTree(file, displayName, mime, tree.toUri()) }
        if (Build.VERSION.SDK_INT >= 29) runCatching { return publishToMediaStore(file, displayName, mime) }
        val target = uniqueFile(defaultDir, displayName)
        if (!file.renameTo(target)) {
            file.copyTo(target, overwrite = true)
            file.delete()
        }
        return Uri.fromFile(target).toString()
    }

    /** Public Music/Dkord folder: shows up in every file manager and music app, no permission needed. */
    private fun publishToMediaStore(file: File, name: String, mime: String): String {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, mime.substringBefore(';'))
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Dkord")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Cannot create file in Music")
        try {
            resolver.openOutputStream(uri, "w")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        file.delete()
        return uri.toString()
    }

    private fun publishToTree(file: File, name: String, mime: String, tree: Uri): String {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(resolver, parent, mime, name) ?: throw IOException("Cannot create file in the chosen folder")
        try {
            resolver.openOutputStream(doc, "w")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            throw e
        }
        file.delete()
        return doc.toString()
    }

    fun exists(uri: String): Boolean {
        val u = uri.toUri()
        return if (u.scheme == "file") u.path?.let { File(it).exists() } == true
        else if (u.authority == MediaStore.AUTHORITY) runCatching { resolver.openFileDescriptor(u, "r")?.use { true } == true }.getOrDefault(false)
        else runCatching { resolver.query(u, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true }
            .getOrDefault(false)
    }

    fun delete(uri: String) {
        val u = uri.toUri()
        runCatching {
            if (u.scheme == "file") u.path?.let { File(it).delete() }
            else if (u.authority == MediaStore.AUTHORITY) resolver.delete(u, null, null)
            else DocumentsContract.deleteDocument(resolver, u)
        }
    }

    /** Keeps access to a picked folder across restarts. */
    fun keepAccess(tree: Uri) {
        resolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    companion object {
        /** Removes characters Android file systems reject and keeps names a sane length. */
        fun safeName(artist: String, title: String, ext: String): String {
            val base = "$artist - $title".replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "").replace(Regex("\\s+"), " ").trim().take(120).trim('.', ' ', '-')
            return "${base.ifBlank { "track" }}.$ext"
        }

        fun uniqueFile(dir: File, name: String): File {
            var f = File(dir, name)
            val stem = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "")
            var i = 1
            while (f.exists()) f = File(dir, "$stem ($i).$ext".also { i++ })
            return f
        }
    }
}
