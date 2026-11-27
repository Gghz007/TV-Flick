package app.tvflick

import app.tvflick.core.backup.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class GoogleTokenRecoveryTest {
    private class Auth : GoogleAuthorization {
        override val configured = true
        val interactiveCalls = mutableListOf<Boolean>()
        val invalidated = mutableListOf<String>()
        override suspend fun token(interactive: Boolean): String {
            interactiveCalls += interactive
            return if (invalidated.isEmpty()) "expired" else "refreshed"
        }
        override suspend fun invalidateToken(token: String) { invalidated += token }
        override suspend fun signOut() {}
    }

    @Test fun `401 retries once with fresh token without opening consent again`() = runBlocking {
        val auth = Auth()
        val headers = mutableListOf<String?>()
        val store = GoogleBackupStore(auth, HttpClient(MockEngine { request ->
            headers += request.headers[HttpHeaders.Authorization]
            if (headers.size == 1) respond("", HttpStatusCode.Unauthorized)
            else respond("""{"email":"test@example.com"}""", HttpStatusCode.OK)
        }))
        try {
            assertEquals("test@example.com", store.profile(true).email)
            assertEquals(listOf(true, false), auth.interactiveCalls)
            assertEquals(listOf("expired"), auth.invalidated)
            assertEquals(listOf<String?>("Bearer expired", "Bearer refreshed"), headers)
        } finally { store.close() }
    }

    @Test fun `persistent unauthorized stops after one refresh`() = runBlocking {
        val auth = Auth()
        var requests = 0
        val store = GoogleBackupStore(auth, HttpClient(MockEngine {
            requests++; respond("", HttpStatusCode.Unauthorized)
        }))
        try {
            assertFailsWith<GoogleSignInRequired> { store.list() }
            assertEquals(2, requests)
            assertEquals(listOf(false, false), auth.interactiveCalls)
        } finally { store.close() }
    }
}
