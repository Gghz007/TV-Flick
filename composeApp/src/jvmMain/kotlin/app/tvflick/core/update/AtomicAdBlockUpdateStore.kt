package app.tvflick.core.update

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android API 26+ and Desktop: a complete old or new file, never a partially written one. */
internal class AtomicAdBlockUpdateStore(private val file: File) : AdBlockUpdateStore {
    override suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        file.inputStream().use { input ->
            val bytes = ByteArray(MAX_SIGNED_RULE_BYTES + 1)
            var size = 0
            while (size < bytes.size) {
                val count = input.read(bytes, size, bytes.size - size)
                if (count < 0) break
                size += count
            }
            require(size <= MAX_SIGNED_RULE_BYTES)
            bytes.copyOf(size)
        }
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        require(bytes.size <= MAX_SIGNED_RULE_BYTES)
        val directory = requireNotNull(file.absoluteFile.parentFile)
        check(directory.isDirectory || directory.mkdirs())
        val staging = Files.createTempFile(directory.toPath(), ".rules-", ".tmp")
        try {
            FileOutputStream(staging.toFile()).use { it.write(bytes); it.fd.sync() }
            // Deliberately no non-atomic fallback: failure retains the current file.
            Files.move(staging, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Unit
        } finally { Files.deleteIfExists(staging) }
    }
}
