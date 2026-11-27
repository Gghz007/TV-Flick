package app.tvflick.plugin.source

internal actual fun resolveHlsReference(base: String, reference: String): String =
    java.net.URI(base).resolve(reference).toASCIIString()
