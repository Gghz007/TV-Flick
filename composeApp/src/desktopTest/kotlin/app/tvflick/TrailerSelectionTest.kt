package app.tvflick

import app.tvflick.core.tmdb.selectYoutubeTrailer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrailerSelectionTest {
    @Test
    fun `selects the official valid trailer and ignores arbitrary provider links`() {
        val videos = Json.parseToJsonElement("""
            [
              {"site":"YouTube","type":"Trailer","key":"abcABC01234","official":false},
              {"site":"Vimeo","type":"Trailer","key":"xyzXYZ98765","official":true},
              {"site":"YouTube","type":"Trailer","key":"https://example.com/trailer","official":true},
              {"site":"YouTube","type":"Trailer","key":"M7lc1UVf-VE","official":true}
            ]
        """).jsonArray
        assertEquals("M7lc1UVf-VE", selectYoutubeTrailer(videos)?.id)
        assertNull(selectYoutubeTrailer(Json.parseToJsonElement("""
            [{"site":"YouTube","type":"Trailer","key":"abc'1234567"}]
        """).jsonArray))
    }
}
