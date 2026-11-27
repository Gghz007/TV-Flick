package app.tvflick

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import app.tvflick.core.update.*
import app.tvflick.plugin.adblock.RuleBasedAdBlockPlugin
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.io.encoding.Base64
import kotlin.test.*

class AdBlockRuleUpdatesTest {
    companion object {
        private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val publicKey = Base64.encode(key.public.encoded)
    }
    private fun signed(revision: Int): ByteArray {
        val payload = """{"schemaVersion":1,"revision":$revision,"blockedHosts":["ads.example.test"]}""".encodeToByteArray()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(key.private); update(RULE_SIGNATURE_CONTEXT.encodeToByteArray() + payload); sign()
        }
        return """{"schemaVersion":1,"payload":"${Base64.encode(payload)}","signature":"${Base64.encode(signature)}"}""".encodeToByteArray()
    }
    private class Store : AdBlockUpdateStore {
        var bytes: ByteArray? = null
        var failWrite = false
        override suspend fun read() = bytes
        override suspend fun write(bytes: ByteArray) {
            if (failWrite) error("disk full")
            this.bytes = bytes.copyOf()
        }
    }

    @Test fun `signed update survives restart and rejects rollback or tampering`() = runBlocking<Unit> {
        var response = signed(3)
        val client = HttpClient(MockEngine { respond(response) })
        val store = Store()
        try {
            AdBlockRuleUpdates(client, store, publicKey).use { updater ->
                val first = RuleBasedAdBlockPlugin()
                assertTrue(updater.check("https://updates.example.test/rules", first))
                assertEquals(3, first.rulesRevision)
                val restarted = RuleBasedAdBlockPlugin()
                updater.restore(restarted)
                assertEquals(3, restarted.rulesRevision)
                val cached = store.bytes!!.copyOf()
                response = signed(2)
                assertFalse(updater.check("https://updates.example.test/rules", restarted))
                val valid = signed(4).decodeToString()
                // Change one signed payload byte, keeping valid JSON/base64.
                val oldPayload = """{"schemaVersion":1,"revision":4,"blockedHosts":["ads.example.test"]}"""
                response = valid.replace(Base64.encode(oldPayload.encodeToByteArray()),
                    Base64.encode(oldPayload.replace("revision\":4", "revision\":9").encodeToByteArray())).encodeToByteArray()
                assertFalse(updater.check("https://updates.example.test/rules", restarted))
                assertEquals(3, restarted.rulesRevision)
                assertContentEquals(cached, store.bytes)
                val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                assertFailsWith<IllegalArgumentException> { verifiedAdBlockRules(signed(4), Base64.encode(other.public.encoded)) }
            }
        } finally { client.close() }
    }

    @Test fun `failed writes redirects and oversized downloads keep current rules`() = runBlocking {
        val store = Store()
        var requests = 0
        var mode = 0
        val client = HttpClient(MockEngine {
            requests++
            when (mode) {
                1 -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other.test/rules"))
                2 -> respond(ByteArray(MAX_SIGNED_RULE_BYTES + 1))
                else -> respond(signed(2))
            }
        })
        try {
            AdBlockRuleUpdates(client, store, publicKey).use { updater ->
                val plugin = RuleBasedAdBlockPlugin()
                assertFalse(updater.check("http://updates.example.test/rules", plugin))
                assertEquals(0, requests)
                store.failWrite = true
                assertFalse(updater.check("https://updates.example.test/rules", plugin))
                assertEquals(1, plugin.rulesRevision)
                assertNull(store.bytes)
                mode = 1
                assertFalse(updater.check("https://updates.example.test/rules", plugin))
                assertEquals(2, requests) // Redirect target was never requested.
                mode = 2
                assertFalse(updater.check("https://updates.example.test/rules", plugin))
                assertEquals(1, plugin.rulesRevision)
            }
        } finally { client.close() }
    }

    @Test fun `atomic store replaces complete envelope and cleans staging files`() = runBlocking {
        val directory = Files.createTempDirectory("tvflick-rules-").toFile()
        try {
            val file = directory.resolve("rules.signed.json")
            val store = AtomicAdBlockUpdateStore(file)
            assertNull(store.read())
            store.write(signed(2))
            val next = signed(3)
            store.write(next)
            assertContentEquals(next, store.read())
            assertEquals(listOf("rules.signed.json"), directory.list()!!.toList())
            assertFailsWith<IllegalArgumentException> { store.write(ByteArray(MAX_SIGNED_RULE_BYTES + 1)) }
            assertContentEquals(next, store.read())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `signed rules activate over real TLS and restore from disk`() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("tvflick-rules-tls").toFile()
        try {
            val keyStoreFile = directory.resolve("server.p12")
            val pass = "test-only-password"
            val tool = File(System.getProperty("java.home"), "bin/keytool")
            val generator = ProcessBuilder(tool.path, "-genkeypair", "-alias", "localhost", "-keyalg", "RSA",
                "-keysize", "2048", "-dname", "CN=localhost", "-ext", "SAN=ip:127.0.0.1",
                "-validity", "1", "-storetype", "PKCS12", "-keystore", keyStoreFile.path, "-storepass", pass)
                .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log")).start()
            try {
                check(generator.waitFor(20, TimeUnit.SECONDS) && generator.exitValue() == 0) { "Test TLS key generation failed" }
            } finally { if (generator.isAlive) generator.destroyForcibly() }
            val keyStore = KeyStore.getInstance("PKCS12").apply {
                keyStoreFile.inputStream().use { load(it, pass.toCharArray()) }
            }
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, pass.toCharArray())
            }
            val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("test-server", keyStore.getCertificate("localhost"))
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trustStore) }
                .trustManagers.filterIsInstance<X509TrustManager>().single()
            val tls = SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) }
            val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            try {
                server.httpsConfigurator = HttpsConfigurator(tls)
                server.createContext("/rules.signed.json") { exchange ->
                    exchange.use {
                        val response = signed(2)
                        exchange.sendResponseHeaders(200, response.size.toLong())
                        exchange.responseBody.write(response)
                    }
                }
                server.start()
                val url = "https://127.0.0.1:${server.address.port}/rules.signed.json"
                val cache = directory.resolve("rules.signed.json")
                HttpClient(CIO) { engine { https { trustManager = trust } } }.use { client ->
                    val plugin = RuleBasedAdBlockPlugin()
                    AdBlockRuleUpdates(client, AtomicAdBlockUpdateStore(cache), publicKey).use { updater ->
                        assertTrue(updater.check(url, plugin))
                        assertEquals(2, plugin.rulesRevision)
                    }
                    AdBlockRuleUpdates(client, AtomicAdBlockUpdateStore(cache), publicKey).use { updater ->
                        val restarted = RuleBasedAdBlockPlugin()
                        updater.restore(restarted)
                        assertEquals(2, restarted.rulesRevision)
                        assertFalse(updater.check(url, restarted))
                    }
                }
            } finally { server.stop(0) }
        } finally { directory.deleteRecursively() }
    }
}
