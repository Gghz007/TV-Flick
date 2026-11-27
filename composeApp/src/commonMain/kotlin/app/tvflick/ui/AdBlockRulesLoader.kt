package app.tvflick.ui

import androidx.compose.runtime.*
import app.tvflick.plugin.adblock.MAX_ADBLOCK_RULE_BYTES
import app.tvflick.plugin.adblock.RuleBasedAdBlockPlugin
import kotlinx.coroutines.CancellationException
import app.tvflick.core.update.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal expect fun rememberAdBlockRulesReader(): suspend () -> String?

@Composable
internal expect fun rememberAdBlockUpdateStore(): AdBlockUpdateStore

@Composable
internal fun LoadAdBlockRules(plugin: RuleBasedAdBlockPlugin, onFailure: () -> Unit) {
    val read = rememberAdBlockRulesReader()
    val store = rememberAdBlockUpdateStore()
    val failure by rememberUpdatedState(onFailure)
    LaunchedEffect(plugin, read) {
        try { read()?.let(plugin::installRules) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failure() } // Built-in rules remain active.
        if (ReleaseConfig.adblockUpdateUrl.isBlank() || ReleaseConfig.adblockPublicKey.isBlank()) return@LaunchedEffect
        val client = HttpClient()
        try {
            AdBlockRuleUpdates(client, store, ReleaseConfig.adblockPublicKey).use { updater ->
                try { withContext(Dispatchers.Default) { updater.restore(plugin) } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { failure(); return@use }
                delay(5_000)
                withContext(Dispatchers.Default) { updater.check(ReleaseConfig.adblockUpdateUrl, plugin) }
            }
        } finally { client.close() }
    }
}

/** The limit also applies if a local file grows while being read. */
internal fun readAdBlockRulesBytes(read: (ByteArray, Int, Int) -> Int): String {
    val bytes = ByteArray(MAX_ADBLOCK_RULE_BYTES + 1)
    var size = 0
    while (size < bytes.size) {
        val count = read(bytes, size, bytes.size - size)
        if (count < 0) break
        require(count > 0)
        size += count
    }
    require(size <= MAX_ADBLOCK_RULE_BYTES)
    return bytes.copyOf(size).decodeToString(throwOnInvalidSequence = true)
}
