package app.tvflick.core.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.ByteArrayOutputStream

actual suspend fun renderLibraryPdf(rows: List<LibraryExportRow>, font: ByteArray,
                                    images: Map<String, ByteArray>): ByteArray = withContext(Dispatchers.IO) {
    PDDocument().use { doc ->
        val face = PDType0Font.load(doc, font.inputStream())
        var stream: PDPageContentStream? = null
        var y = 0f
        fun page() {
            stream?.close()
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            stream = PDPageContentStream(doc, page)
            y = 795f
        }
        fun line(text: String, x: Float = 40f, size: Float = 10f) {
            if (y < 45) page()
            // Keep the document searchable; JSON/CSV retain code points unavailable in this font.
            val printable = buildString {
                text.codePoints().forEach { code ->
                    appendCodePoint(if (code >= 32 && face.hasGlyph(code)) code else '?'.code)
                }
            }
            stream!!.beginText(); stream!!.setFont(face, size); stream!!.newLineAtOffset(x, y)
            stream!!.showText(printable); stream!!.endText()
            y -= size + 5
        }
        fun wrapped(text: String, x: Float, maxWidth: Float) {
            var part = ""
            text.replace('\n', ' ').replace('\r', ' ').forEach { ch ->
                val candidate = part + ch
                val printable = candidate.map { if (it.code >= 32 && face.hasGlyph(it.code)) it else '?' }.joinToString("")
                if (face.getStringWidth(printable) / 1000f * 10f > maxWidth && part.isNotEmpty()) {
                    line(part, x); part = ch.toString()
                } else part = candidate
            }
            if (part.isNotEmpty()) line(part, x)
        }
        page(); line("TV Flick — Library", size = 18f); line("Titles, episodes and viewing progress")
        rows.forEach { row ->
            if (y < 130) page()
            if (row.kind != "episode") {
                y -= 10
                val top = y
                val image = images[row.posterUrl]
                val x = if (image == null) 40f else 102f
                if (image != null) stream!!.drawImage(PDImageXObject.createFromByteArray(doc, image, null), 40f, top - 75f, 50f, 75f)
                wrapped("${row.title} (${row.year}) — ${row.kind}", x, 550f - x)
                line("TMDB ${row.tmdbId} | Watched: ${row.watched} | Favorite: ${row.favorite}", x)
                line("Watch list: ${row.watchList} | Resume: ${row.positionMs.ifBlank { "0" }} ms", x)
                if (image != null) y = minOf(y, top - 85f)
            } else wrapped("S${row.season.padStart(2, '0')}E${row.episode.padStart(2, '0')}  ${row.episodeTitle} | Watched: ${row.watched} | Resume: ${row.positionMs.ifBlank { "0" }} ms", 52f, 500f)
        }
        stream?.close()
        ByteArrayOutputStream().use { out -> doc.save(out); out.toByteArray() }
    }
}
