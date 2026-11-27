package app.tvflick.desktop

import app.tvflick.core.update.verifyRsaSignature
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.zip.ZipFile
import kotlin.io.path.name

internal const val DESKTOP_MODULE_SIGNATURE_CONTEXT = "TV-FLICK-DESKTOP-MODULE-V1\n"
internal const val MAX_DESKTOP_MODULE_BYTES = 32 * 1024 * 1024
private val moduleJar = Regex("(video-plugin|adblock-plugin)(?:-desktop)?-[a-zA-Z0-9.-]+\\.jar")
private val sha256 = Regex("[0-9a-f]{64}")

/** A signed update authorizes a specific transition against the exact packaged host libraries. */
internal data class DesktopModuleTransition(
    val target: String,
    val host: String,
    val previous: String,
    val replacement: String,
) {
    init {
        require(moduleJar.matches(target)) { "Unsupported Windows module" }
        require(listOf(host, previous, replacement).all(sha256::matches) && previous != replacement) {
            "Invalid module transition"
        }
    }
}

internal fun desktopModuleDigest(bytes: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

private fun jarDigest(path: Path): String {
    require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Missing or linked application JAR" }
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}

internal class DesktopModuleUpdates(private val root: Path, private val encodedPublicKey: String) {
    private val app = root.resolve("app").also {
        require(isLocalDirectory(it)) { "Packaged Windows app directory is unavailable" }
    }

    private fun isLocalDirectory(path: Path): Boolean =
        Files.isDirectory(path, NOFOLLOW_LINKS) && path.toRealPath() == root.toRealPath().resolve(path.fileName)

    fun hostFingerprint(): String = Files.list(app).use { files ->
        val hostJars = files.filter { it.name.endsWith(".jar") && !moduleJar.matches(it.name) }.sorted().toList()
        require(hostJars.isNotEmpty()) { "Packaged host has no runtime JARs" }
        val inventory = hostJars.joinToString("\n") { "${it.name}\t${jarDigest(it)}" }
        desktopModuleDigest(inventory.encodeToByteArray())
    }

    fun install(archive: Path) = switch(archive, rollback = false)
    fun rollback(archive: Path) = switch(archive, rollback = true)

    private fun switch(archive: Path, rollback: Boolean) = withDesktopModuleLock(root, shared = false) {
        require(encodedPublicKey.isNotBlank()) { "This build has no trusted module signing key" }
        require(Files.isRegularFile(archive) && Files.size(archive) <= MAX_DESKTOP_MODULE_BYTES + 64 * 1024) {
            "Invalid module archive"
        }
        val (transition, replacement) = ZipFile(archive.toFile()).use { zip ->
            val entries = zip.entries().asSequence().take(4).map { it.name }.toList()
            require(entries.size == 3 && entries.toSet() == setOf("manifest.json", "signature.bin", "module.jar")) {
                "Unexpected module archive contents"
            }
            fun entry(name: String, max: Int): ByteArray = zip.getInputStream(zip.getEntry(name)).use {
                it.readNBytes(max + 1).also { bytes -> require(bytes.size in 1..max) { "Invalid length for $name" } }
            }
            val manifest = entry("manifest.json", 4096)
            val signature = entry("signature.bin", 1024)
            require(verifyRsaSignature(DESKTOP_MODULE_SIGNATURE_CONTEXT.encodeToByteArray() + manifest,
                signature, Base64.getDecoder().decode(encodedPublicKey))) { "Module signature is invalid" }
            val json = Json.parseToJsonElement(manifest.decodeToString(throwOnInvalidSequence = true)).jsonObject
            require(json.keys == setOf("schema", "target", "host", "previous", "replacement") &&
                json["schema"] == JsonPrimitive(1)) { "Unsupported module manifest" }
            fun read(name: String) = json.getValue(name).jsonPrimitive.also { require(it.isString) }.content
            val change = DesktopModuleTransition(read("target"), read("host"), read("previous"), read("replacement"))
            change to entry("module.jar", MAX_DESKTOP_MODULE_BYTES).also {
                require(desktopModuleDigest(it) == change.replacement) { "Module JAR hash mismatch" }
            }
        }
        require(hostFingerprint() == transition.host) { "The package targets a different Windows build" }
        val target = app.resolve(transition.target)
        val active = readJar(target)
        val expected = if (rollback) transition.replacement else transition.previous
        require(desktopModuleDigest(active) == expected) { "Installed module is not the expected version" }
        val history = root.resolve(".module-rollback")
        if (!Files.exists(history, NOFOLLOW_LINKS)) Files.createDirectory(history)
        require(isLocalDirectory(history)) { "Invalid module rollback folder" }
        val saved = history.resolve("${transition.target}.${transition.previous}.jar")

        if (rollback) {
            val original = readJar(saved)
            require(desktopModuleDigest(original) == transition.previous) { "Saved module hash mismatch" }
            atomicJarWrite(target, original)
        } else {
            if (Files.exists(saved, NOFOLLOW_LINKS)) {
                require(desktopModuleDigest(readJar(saved)) == transition.previous) { "Saved module hash mismatch" }
            } else atomicJarWrite(saved, active)
            atomicJarWrite(target, replacement)
        }
    }
}

private fun readJar(path: Path): ByteArray {
    require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Module JAR is missing or linked" }
    return Files.newInputStream(path, NOFOLLOW_LINKS).use {
        it.readNBytes(MAX_DESKTOP_MODULE_BYTES + 1).also { bytes ->
            require(bytes.size in 1..MAX_DESKTOP_MODULE_BYTES) { "Module JAR is too large or empty" }
        }
    }
}

private fun atomicJarWrite(path: Path, bytes: ByteArray) {
    val staged = Files.createTempFile(path.parent, ".module-", ".tmp")
    try {
        FileOutputStream(staged.toFile()).use { output -> output.write(bytes); output.fd.sync() }
        Files.move(staged, path, ATOMIC_MOVE, REPLACE_EXISTING)
    } finally { Files.deleteIfExists(staged) }
}

internal fun <T> withDesktopModuleLock(root: Path, shared: Boolean, action: () -> T): T =
    FileChannel.open(root.resolve(".module-run.lock"), CREATE, READ, WRITE, NOFOLLOW_LINKS).use { channel ->
        val lock = channel.tryLock(0, Long.MAX_VALUE, shared)
            ?: error("Close TV Flick and any other module updater before retrying")
        lock.use { action() }
    }
