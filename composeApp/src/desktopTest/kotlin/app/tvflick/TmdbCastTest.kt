package app.tvflick

import app.tvflick.core.tmdb.TmdbClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class TmdbCastTest {
    @Test fun `TV requests complete aggregate credits and retains multiple roles without dropping people`() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertTrue(request.url.parameters["append_to_response"].orEmpty().contains("aggregate_credits"))
            respond("""{"id":1,"name":"Show","credits":{"cast":[{"name":"Only latest season"}]},
              "aggregate_credits":{"cast":[
                {"id":1,"name":"First","roles":[{"character":"A"},{"character":"B"},{"character":"A"}]},
                {"id":2,"name":"Second","roles":[{"character":"C"}]},
                {"id":3,"name":"Guest","profile_path":null,"roles":[]}]}}""", HttpStatusCode.OK)
        })
        try {
            val cast = TmdbClient("test", http).titleDetails("tv", 1).cast
            assertEquals(listOf("First", "Second", "Guest"), cast.map { it.name })
            assertEquals("A / B", cast.first().character)
            assertNull(cast.last().photoPath)
        } finally { http.close() }
    }
}
