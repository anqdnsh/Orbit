package com.aniq.orbit.search

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.Size
import java.util.concurrent.Executors

class FileEntry(
    val id: Long,
    val name: String,
    val uri: Uri,
    val mime: String?,
    val size: Long,
    val modified: Long,     // seconds
    val folder: String,
) {
    val norm = Text.normalize(name)

    fun kind(): String = when {
        mime == null -> "File"
        mime.startsWith("image/") -> "Image"
        mime.startsWith("video/") -> "Video"
        mime.startsWith("audio/") -> "Audio"
        mime == "application/pdf" -> "PDF"
        mime.startsWith("text/") -> "Text"
        mime.contains("word") || mime.contains("document") -> "Document"
        mime.contains("sheet") || mime.contains("excel") -> "Spreadsheet"
        mime.contains("presentation") || mime.contains("powerpoint") -> "Slides"
        mime.contains("zip") || mime.contains("compressed") -> "Archive"
        else -> "File"
    }

    fun describe(ctx: Context): String {
        val when_ = DateUtils.getRelativeTimeSpanString(modified * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        return "${kind()}  ·  ${Formatter.formatShortFileSize(ctx, size)}  ·  $when_  ·  ${folder.trimEnd('/').ifEmpty { "Storage" }}"
    }
}

/**
 * Spotlight-style file search over the system's own file index (MediaStore), so there is
 * no crawling and nothing runs in the background. Needs "All files access" to see documents.
 * Queries run off the main thread, debounced, and only for the latest text you typed.
 */
object FileSearch {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    @Volatile private var lastQuery = ""
    @Volatile private var lastResults: List<FileEntry> = emptyList()

    fun allowed(): Boolean = Environment.isExternalStorageManager()

    /** Results for [q] if they're ready, else null. */
    fun cached(q: String): List<FileEntry>? = if (q == lastQuery) lastResults else null

    /** Starts a search for [q]; [done] runs on the main thread when new results arrive. */
    fun request(ctx: Context, q: String, done: () -> Unit) {
        if (q.length < 2 || !allowed() || q == lastQuery) return
        pending?.let { main.removeCallbacks(it) }
        val app = ctx.applicationContext
        val r = Runnable {
            worker.execute {
                val found = try { query(app, q) } catch (_: Exception) { emptyList() }
                main.post {
                    lastQuery = q
                    lastResults = found
                    done()
                }
            }
        }
        pending = r
        main.postDelayed(r, 90) // wait for a pause in typing
    }

    fun clear() {
        lastQuery = ""
        lastResults = emptyList()
    }

    private fun query(ctx: Context, q: String): List<FileEntry> {
        val words = q.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val like = words.joinToString(" AND ") { "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'" }
        val args = words.map { "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" }
        val sel = "$like AND ${MediaStore.MediaColumns.MIME_TYPE} IS NOT NULL"
        val base = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val bundle = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.toTypedArray())
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, 40)
        }
        val proj = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.RELATIVE_PATH,
        )
        val out = ArrayList<FileEntry>()
        ctx.contentResolver.query(base, proj, bundle, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += FileEntry(
                    id, c.getString(1) ?: continue,
                    ContentUris.withAppendedId(base, id),
                    c.getString(2), c.getLong(3), c.getLong(4), c.getString(5) ?: "",
                )
            }
        }
        val nq = Text.normalize(q)
        return out
            .map { it to Matcher.score(nq, it.norm, Text.initials(it.name)) }
            .sortedWith(compareByDescending<Pair<FileEntry, Int>> { it.second }.thenByDescending { it.first.modified })
            .map { it.first }
            .take(8)
    }
}

/** What the Space-bar preview shows: a picture (image, video frame, PDF page 1) or some text. */
class PreviewContent(val bitmap: Bitmap?, val text: String?)

object FilePreview {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun load(ctx: Context, f: FileEntry, maxW: Int, maxH: Int, done: (PreviewContent) -> Unit) {
        val cr = ctx.applicationContext.contentResolver
        worker.execute {
            val content = try { read(cr, f, maxW, maxH) } catch (_: Exception) { PreviewContent(null, null) }
            main.post { done(content) }
        }
    }

    private fun read(cr: ContentResolver, f: FileEntry, maxW: Int, maxH: Int): PreviewContent {
        val mime = f.mime ?: ""
        return when {
            mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/") ->
                PreviewContent(cr.loadThumbnail(f.uri, Size(maxW, maxH), null), null)
            mime == "application/pdf" -> cr.openFileDescriptor(f.uri, "r")?.use { fd ->
                PdfRenderer(fd).use { pdf ->
                    pdf.openPage(0).use { page ->
                        val scale = minOf(maxW.toFloat() / page.width, maxH.toFloat() / page.height)
                        val bmp = Bitmap.createBitmap(
                            (page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888,
                        )
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        PreviewContent(bmp, null)
                    }
                }
            } ?: PreviewContent(null, null)
            mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") || mime.contains("csv") ->
                cr.openInputStream(f.uri)?.use { s ->
                    val buf = ByteArray(6000)
                    val n = s.read(buf).coerceAtLeast(0)
                    PreviewContent(null, String(buf, 0, n, Charsets.UTF_8))
                } ?: PreviewContent(null, null)
            else -> PreviewContent(null, null)
        }
    }
}
