package app.tvflick.core.update

import app.tvflick.plugin.adblock.AdBlockRules
import app.tvflick.plugin.adblock.MAX_ADBLOCK_RULE_BYTES
import app.tvflick.plugin.adblock.RuleBasedAdBlockPlugin
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64

internal const val MAX_SIGNED_RULE_BYTES = 384 * 1024
internal const val RULE_SIGNATURE_CONTEXT = "TV-FLICK-ADBLOCK-RULES-V1\n"

internal interface AdBlockUpdateStore {
    suspend fun read(): ByteArray?
    /** Must atomically replace the previous complete envelope, or leave it intact. */
    suspend fun write(bytes: ByteArray)
}

internal expect fun verifyRsaSignature(payload: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean

/** Only the exact signed UTF-8 bytes are parsed; the envelope supplies no trust key or algorithm. */
internal fun verifiedAdBlockRules(envelope: ByteArray, publicKey: String): String {
    require(envelope.size <= MAX_SIGNED_RULE_BYTES && publicKey.length <= 16_384)
    val json = Json.parseToJsonElement(envelope.decodeToString(throwOnInvalidSequence = true)).jsonObject
    require(json.keys == setOf("schemaVersion", "payload", "signature"))
    require(json["schemaVersion"] == JsonPrimitive(1))
    fun string(name: String): String = json.getValue(name).jsonPrimitive.also { require(it.isString) }.content
    val payload = Base64.decode(string("payload"))
    val signature = Base64.decode(string("signature"))
    require(payload.size <= MAX_ADBLOCK_RULE_BYTES && signature.size in 256..1024)
    require(verifyRsaSignature(RULE_SIGNATURE_CONTEXT.encodeToByteArray() + payload, signature, Base64.decode(publicKey)))
    return payload.decodeToString(throwOnInvalidSequence = true).also { AdBlockRules.parse(it) }
}

/** One startup check. Network failures never remove or replace working persisted rules. */
internal class AdBlockRuleUpdates(
    client: HttpClient,
    private val store: AdBlockUpdateStore,
    private val publicKey: String,
) : AutoCloseable {
    private val http = client.config { followRedirects = false; expectSuccess = false }

    suspend fun restore(plugin: RuleBasedAdBlockPlugin) {
        val bytes = store.read() ?: return
        val text = verifiedAdBlockRules(bytes, publicKey)
        if (AdBlockRules.parse(text).revision > plugin.rulesRevision) plugin.installRules(text)
    }

    suspend fun check(url: String, plugin: RuleBasedAdBlockPlugin): Boolean {
        val address = runCatching { Url(url) }.getOrNull() ?: return false
        if (address.protocol != URLProtocol.HTTPS || address.host.isBlank() || address.user != null ||
            address.password != null || address.fragment.isNotEmpty() || publicKey.isBlank()) return false
        return try { withTimeoutOrNull(8_000) {
            val bytes = http.prepareGet(address).execute { response ->
                if (response.status != HttpStatusCode.OK || (response.contentLength() ?: 0) > MAX_SIGNED_RULE_BYTES)
                    return@execute null
                val buffer = ByteArray(MAX_SIGNED_RULE_BYTES + 1)
                var size = 0
                val channel = response.bodyAsChannel()
                while (size < buffer.size) {
                    val read = channel.readAvailable(buffer, size, buffer.size - size)
                    if (read < 0) break
                    size += read
                }
                if (size > MAX_SIGNED_RULE_BYTES) null else buffer.copyOf(size)
            } ?: return@withTimeoutOrNull false
            val text = verifiedAdBlockRules(bytes, publicKey)
            if (AdBlockRules.parse(text).revision <= plugin.rulesRevision) return@withTimeoutOrNull false
            // Persist before activation. A failed write cannot change the running rules;
            // cancellation after commit is harmless: next startup restores this envelope.
            store.write(bytes)
            plugin.installRules(text)
            true
        } ?: false } catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
    }

    override fun close() = http.close()
}
