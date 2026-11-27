package app.tvflick.plugin.source

import app.tvflick.plugin.api.*
import org.jsoup.Jsoup
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal actual fun decodeSourceText(bytes: ByteArray, charsetName: String): String =
    Charset.forName(charsetName).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()

internal actual fun parseFilmixPage(html: String, url: String): FilmixPage {
    val document = Jsoup.parse(html, url)
    val article = document.selectFirst("article.fullstory[data-id]") ?: FilmixFormat.fail()
    val id = article.attr("data-id").takeIf { it.toLongOrNull()?.let { number -> number > 0 } == true } ?: FilmixFormat.fail()
    val title = article.selectFirst("h1.name")?.text()?.trim()?.takeIf(String::isNotBlank) ?: FilmixFormat.fail()
    val year = article.selectFirst("[itemprop=copyrightYear]")?.text()?.toIntOrNull() ?: FilmixFormat.fail()
    val type = when {
        "/seria/" in java.net.URI(url).path -> MatchedMediaType.TvEpisode
        "/film/" in java.net.URI(url).path -> MatchedMediaType.Movie
        else -> FilmixFormat.fail()
    }
    val alternatives = article.select("[itemprop=alternativeHeadline], .original-name, .origin-name")
        .map { it.text().trim() }.filter(String::isNotBlank)
    return FilmixPage(id, title, year, type, alternatives)
}
