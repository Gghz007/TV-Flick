package app.tvflick.desktop

import java.io.File
import java.time.Instant

/** Local-only, bounded crash evidence. Never include exception messages/URLs/headers. */
internal fun installDesktopCrashLog() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
        runCatching {
            val root = System.getenv("LOCALAPPDATA")?.let(::File) ?: File(System.getProperty("user.home"), "AppData/Local")
            val directory = File(root, "TV Flick/logs").apply { mkdirs() }
            val file = File(directory, "last-crash.log")
            if (file.exists()) file.copyTo(File(directory, "previous-crash.log"), overwrite = true)
            val report = buildString {
                appendLine("TV Flick ${Instant.now()} thread=${thread.id}")
                var cause: Throwable? = failure
                repeat(4) {
                    cause?.let { error ->
                        appendLine(error.javaClass.name)
                        error.stackTrace.take(60).forEach { frame -> appendLine("  $frame") }
                        cause = error.cause?.takeUnless { it === error }
                    }
                }
            }
            file.writeText(report.take(32_000))
        }
        if (previous != null) previous.uncaughtException(thread, failure)
        else System.err.println("TV Flick stopped after ${failure.javaClass.simpleName}; see local logs.")
    }
}
