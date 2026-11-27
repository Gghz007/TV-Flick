package app.tvflick.core.tmdb

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

private val videoId = Regex("[A-Za-z0-9_-]{11}")

internal const val TRAILER_ORIGIN = "https://app.tvflick"
internal const val TRAILER_PAGE = "$TRAILER_ORIGIN/trailer/player.html"

data class YoutubeTrailer(val id: String) {
    init { require(videoId.matches(id)) { "Invalid YouTube video ID" } }

    val watchUrl: String get() = "https://www.youtube.com/watch?v=$id"

    internal fun playerHtml(): String = """
        <!doctype html>
        <html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="referrer" content="strict-origin-when-cross-origin">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'nonce-tvflick-trailer' https://www.youtube.com https://s.ytimg.com; style-src 'unsafe-inline'; frame-src https://www.youtube.com; base-uri 'none'">
        <style>html,body,#player{width:100%;height:100%;margin:0;background:#000;border:0;display:block}</style>
        </head><body><div id="player"></div>
        <script nonce="tvflick-trailer">
        var tvflickTrailerStatus = 'loading';
        var tvflickPlayer;
        function status(value) {
          tvflickTrailerStatus = value;
          if (window.chrome && window.chrome.webview) window.chrome.webview.postMessage(value);
        }
        function onYouTubeIframeAPIReady() {
          tvflickPlayer = new YT.Player('player', {
            width: '100%', height: '100%', videoId: '$id',
            playerVars: { origin: '$TRAILER_ORIGIN', playsinline: 1, fs: 0 },
            events: {
              onReady: function () { status('ready'); },
              onError: function (event) { status('error:' + event.data); },
              onStateChange: function (event) { if (event.data === YT.PlayerState.PLAYING) status('playing'); }
            }
          });
        }
        var api = document.createElement('script');
        api.src = 'https://www.youtube.com/iframe_api';
        api.onerror = function () { status('error:load'); };
        document.head.appendChild(api);
        </script></body></html>
    """.trimIndent()
}

internal fun selectYoutubeTrailer(videos: JsonArray?): YoutubeTrailer? =
    videos.orEmpty().mapNotNull { element ->
        val entry = element as? JsonObject ?: return@mapNotNull null
        fun primitive(key: String) = entry[key] as? JsonPrimitive
        if (!primitive("site")?.contentOrNull.equals("YouTube", ignoreCase = true) ||
            primitive("type")?.contentOrNull != "Trailer") return@mapNotNull null
        val id = primitive("key")?.contentOrNull?.takeIf(videoId::matches) ?: return@mapNotNull null
        YoutubeTrailer(id) to (primitive("official")?.booleanOrNull == true)
    }.let { available -> available.firstOrNull { it.second }?.first ?: available.firstOrNull()?.first }
