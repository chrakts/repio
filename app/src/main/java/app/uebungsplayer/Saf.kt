package app.uebungsplayer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/** Hilfsfunktionen für das Storage Access Framework (ein vom Nutzer gewählter Ordnerbaum). */
object Saf {

    data class Entry(val docId: String, val name: String, val mime: String) {
        val isDir: Boolean get() = mime == Document.MIME_TYPE_DIR
    }

    private val audioExt = setOf(
        "mp3", "mp2", "flac", "ogg", "oga", "opus", "wav", "m4a", "aac", "wma", "mka", "mp4", "3gp", "amr", "aif", "aiff"
    )

    fun isAudio(e: Entry): Boolean =
        e.mime.startsWith("audio/") || e.mime == "application/ogg" ||
            e.name.substringAfterLast('.', "").lowercase() in audioExt

    fun docUri(tree: Uri, docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    fun list(ctx: Context, tree: Uri, parentId: String): List<Entry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val out = mutableListOf<Entry>()
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) out.add(Entry(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: ""))
        }
        return out
    }

    fun readText(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
    } catch (e: Exception) {
        null
    }

    /** Schreibt (oder ersetzt) die Datei [fileName] im Ordner [parentId]. */
    fun writeText(ctx: Context, tree: Uri, parentId: String, fileName: String, text: String): Boolean {
        val existing = list(ctx, tree, parentId).firstOrNull { it.name == fileName }
        val uri = if (existing != null) {
            docUri(tree, existing.docId)
        } else {
            DocumentsContract.createDocument(
                ctx.contentResolver, docUri(tree, parentId), "application/octet-stream", fileName
            ) ?: return false
        }
        val os = ctx.contentResolver.openOutputStream(uri, "wt") ?: return false
        os.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return true
    }
}
