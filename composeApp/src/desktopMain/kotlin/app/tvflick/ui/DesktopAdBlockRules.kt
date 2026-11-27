package app.tvflick.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.tvflick.core.update.AdBlockUpdateStore
import app.tvflick.core.update.AtomicAdBlockUpdateStore

@Composable
internal actual fun rememberAdBlockUpdateStore(): AdBlockUpdateStore = remember {
    val root = System.getenv("LOCALAPPDATA")?.let(::File)
        ?: File(System.getProperty("user.home"), "AppData/Local")
    AtomicAdBlockUpdateStore(File(root, "TV Flick/modules/adblock/rules.signed.json"))
}

@Composable
internal actual fun rememberAdBlockRulesReader(): suspend () -> String? = remember {
    suspend {
        withContext(Dispatchers.IO) {
            val root = System.getenv("LOCALAPPDATA")?.let(::File)
                ?: File(System.getProperty("user.home"), "AppData/Local")
            val file = File(root, "TV Flick/modules/adblock/rules.json")
            if (!file.exists()) null else file.inputStream().use { readAdBlockRulesBytes(it::read) }
        }
    }
}
