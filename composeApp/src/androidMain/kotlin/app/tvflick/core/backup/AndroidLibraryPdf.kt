package app.tvflick.core.backup

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

actual suspend fun renderLibraryPdf(rows: List<LibraryExportRow>, font: ByteArray,
                                    images: Map<String, ByteArray>): ByteArray = withContext(Dispatchers.IO) {
    val doc = PdfDocument()
    try {
        var page: PdfDocument.Page? = null
        var number = 0
        var y = 0f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        fun next() {
            page?.let(doc::finishPage)
            page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, ++number).create())
            y = 45f
        }
        fun line(text: String, x: Float = 40f) {
            if (y > 795) next()
            page!!.canvas.drawText(text, x, y, paint)
            y += paint.textSize + 5
        }
        fun wrapped(text: String, x: Float) {
            var part = ""
            text.replace('\n', ' ').replace('\r', ' ').forEach { ch ->
                if (paint.measureText(part + ch) > 550f - x && part.isNotEmpty()) { line(part, x); part = "$ch" }
                else part += ch
            }
            if (part.isNotEmpty()) line(part, x)
        }
        next(); paint.textSize = 18f; line("TV Flick — Library"); paint.textSize = 10f
        line("Titles, episodes and viewing progress")
        rows.forEach { row ->
            if (y > 710) next()
            if (row.kind != "episode") {
                y += 10
                val top = y
                val bytes = images[row.posterUrl]
                val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                val x = if (bitmap == null) 40f else 102f
                if (bitmap != null) {
                    page!!.canvas.drawBitmap(bitmap, null, RectF(40f, top - 10f, 90f, top + 65f), paint)
                    bitmap.recycle()
                }
                wrapped("${row.title} (${row.year}) — ${row.kind}", x)
                line("TMDB ${row.tmdbId} | Watched: ${row.watched} | Favorite: ${row.favorite}", x)
                line("Watch list: ${row.watchList} | Resume: ${row.positionMs.ifBlank { "0" }} ms", x)
                if (bytes != null) y = maxOf(y, top + 80f)
            } else wrapped("S${row.season.padStart(2, '0')}E${row.episode.padStart(2, '0')}  ${row.episodeTitle} | Watched: ${row.watched} | Resume: ${row.positionMs.ifBlank { "0" }} ms", 52f)
        }
        page?.let(doc::finishPage)
        ByteArrayOutputStream().use { out -> doc.writeTo(out); out.toByteArray() }
    } finally { doc.close() }
}
