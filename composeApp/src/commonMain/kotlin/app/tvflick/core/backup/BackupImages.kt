package app.tvflick.core.backup

import androidx.compose.runtime.mutableStateMapOf
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64

private const val MAX_IMAGE = 2 * 1024 * 1024
private const val MAX_IMAGES_TOTAL = 20 * 1024 * 1024
private val restoredImages = mutableStateMapOf<String, ByteArray>()

/** Restored library artwork also works offline. Other catalog artwork still uses Coil's cache. */
fun libraryImageModel(url: String): Any = restoredImages[url] ?: restoredImages.entries.firstOrNull {
    it.key.substringAfterLast('/') == url.substringAfterLast('/') && url.startsWith("https://image.tmdb.org/t/p/")
}?.value ?: url

fun installBackupImages(images: Map<String, ByteArray>) { restoredImages.clear(); restoredImages.putAll(images) }

internal suspend fun HttpResponse.readBounded(limit: Int): ByteArray {
    require((contentLength() ?: 0) <= limit) { "File exceeds the allowed size" }
    val chunks = mutableListOf<ByteArray>()
    var size = 0
    val channel = bodyAsChannel()
    while (true) {
        val chunk = ByteArray(minOf(8192, limit - size + 1))
        val count = channel.readAvailable(chunk)
        if (count < 0) break
        if (count == 0) continue
        size += count
        require(size <= limit) { "File exceeds the allowed size" }
        chunks += chunk.copyOf(count)
    }
    return ByteArray(size).also { result ->
        var offset = 0
        chunks.forEach { it.copyInto(result, offset); offset += it.size }
    }
}

fun backupImageUrls(snapshot: ByteArray): Set<String> {
    val root = Json.parseToJsonElement(snapshot.decodeToString()).jsonObject
    val titles = root.getValue("tables").jsonObject.getValue("content").jsonArray
    return buildSet {
        titles.forEach { item ->
            val title = item.jsonObject
            listOf("poster_path" to "w342", "backdrop_path" to "w780").forEach { (key, width) ->
                val path = title.text(key)
                if (path.isNotEmpty()) add("https://image.tmdb.org/t/p/$width$path")
            }
        }
    }
}

private fun validateImage(url: String, bytes: ByteArray) {
    require(url.matches(Regex("https://image\\.tmdb\\.org/t/p/(w342|w780)/[A-Za-z0-9_-]+\\.(jpg|jpeg|png|webp)"))) { "Unsupported artwork URL" }
    require(bytes.size in 12..MAX_IMAGE) { "Artwork file exceeds the allowed size" }
    val jpg = bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
    val png = bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte)
    val webp = bytes.copyOfRange(0, 4).decodeToString() == "RIFF" && bytes.copyOfRange(8, 12).decodeToString() == "WEBP"
    require(jpg || png || webp) { "Unsupported artwork file" }
}

suspend fun collectBackupImages(snapshot: ByteArray): Map<String, ByteArray> {
    val urls = backupImageUrls(snapshot)
    require(urls.size <= 1000) { "Too many images; export without Image" }
    val result = linkedMapOf<String, ByteArray>()
    var total = 0
    // No redirects or arbitrary source URLs: this option only exports library artwork from TMDB.
    val client = HttpClient {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 15_000 }
    }
    try {
        for (url in urls) {
            require(url.matches(Regex("https://image\\.tmdb\\.org/t/p/(w342|w780)/[A-Za-z0-9_-]+\\.(jpg|jpeg|png|webp)")))
            val cached = libraryImageModel(url) as? ByteArray
            val bytes = cached ?: client.prepareGet(url).execute { response ->
                check(response.status.isSuccess()) { "Artwork download failed; retry or turn Image off" }
                response.readBounded(MAX_IMAGE)
            }
            validateImage(url, bytes)
            total += bytes.size
            require(total <= MAX_IMAGES_TOTAL) { "Artwork exceeds 20 MB; export without Image" }
            result[url] = bytes
        }
    } finally { client.close() }
    return result
}

fun withBackupImages(snapshot: ByteArray, images: Map<String, ByteArray>): ByteArray {
    val root = Json.parseToJsonElement(snapshot.decodeToString()).jsonObject
    return JsonObject(root + ("images" to encodeBackupImages(images))).toString().encodeToByteArray().also {
        require(it.size <= MAX_BACKUP_BYTES) { "Backup exceeds 32 MB; export without Image" }
    }
}

fun encodeBackupImages(images: Map<String, ByteArray>): JsonObject = buildJsonObject {
    images.forEach { (url, bytes) -> put(url, Base64.encode(bytes)) }
}

fun decodeBackupImages(root: JsonObject): Map<String, ByteArray> {
    val images = root["images"]?.jsonObject ?: return emptyMap()
    require(images.size <= 1000)
    var total = 0
    return images.mapValues { (url, value) ->
        val encoded = value.jsonPrimitive.content
        require(encoded.length <= (MAX_IMAGE + 2) / 3 * 4)
        Base64.decode(encoded).also { bytes ->
            validateImage(url, bytes)
            total += bytes.size
            require(total <= MAX_IMAGES_TOTAL)
        }
    }
}
