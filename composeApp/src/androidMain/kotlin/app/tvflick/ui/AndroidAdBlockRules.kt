package app.tvflick.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.tvflick.core.update.AdBlockUpdateStore
import app.tvflick.core.update.AtomicAdBlockUpdateStore

@Composable
internal actual fun rememberAdBlockUpdateStore(): AdBlockUpdateStore {
    val context = LocalContext.current.applicationContext
    return remember(context) { AtomicAdBlockUpdateStore(File(context.filesDir, "modules/adblock/rules.signed.json")) }
}

@Composable
internal actual fun rememberAdBlockRulesReader(): suspend () -> String? {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        suspend {
            withContext(Dispatchers.IO) {
                val file = File(context.filesDir, "modules/adblock/rules.json")
                if (!file.exists()) null else file.inputStream().use { readAdBlockRulesBytes(it::read) }
            }
        }
    }
}
