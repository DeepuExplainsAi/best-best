package app.dkord.data.download

import android.content.Context
import android.net.Uri
import android.os.Environment
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
        val target = uniqueFile(defaultDir, displayName)
        if (!file.renameTo(target)) {
            file.copyTo(target, overwrite = true)
            file.delete()
        }
        return Uri.fromFile(target).toString()
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
        else runCatching { resolver.query(u, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true }
            .getOrDefault(false)
    }

    fun delete(uri: String) {
        val u = uri.toUri()
        runCatching { if (u.scheme == "file") u.path?.let { File(it).delete() } else DocumentsContract.deleteDocument(resolver, u) }
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
