package app.tvflick.desktop

import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class DesktopModuleUpdatesTest {
    @Test
    fun `updates only the trusted target and rolls it back without touching the other plugin`() {
        val root = Files.createTempDirectory("tvflick-module-test")
        try {
            val app = Files.createDirectory(root.resolve("app"))
            val host = Files.write(app.resolve("composeApp.jar"), byteArrayOf(1))
            val target = app.resolve("video-plugin-desktop-0.5.1-shipped.jar")
            val previous = byteArrayOf(2)
            val replacement = byteArrayOf(3)
            val other = byteArrayOf(4)
            Files.write(target, previous)
            val adBlock = Files.write(app.resolve("adblock-plugin-desktop-0.2.0.jar"), other)
            val keyGen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            val signing = keyGen.generateKeyPair()
            val wrong = keyGen.generateKeyPair()
            val updater = DesktopModuleUpdates(root, Base64.getEncoder().encodeToString(signing.public.encoded))
            val manifest = DesktopModuleTransition(target.fileName.toString(), updater.hostFingerprint(),
                desktopModuleDigest(previous), desktopModuleDigest(replacement))
            val packageFile = root.resolve("update.tvmodule")
            Files.write(packageFile, signedDesktopModule(manifest, replacement, wrong.private))
            assertFailsWith<IllegalArgumentException> { updater.install(packageFile) }
            assertContentEquals(previous, Files.readAllBytes(target))

            Files.write(packageFile, signedDesktopModule(manifest, byteArrayOf(5), signing.private))
            assertFailsWith<IllegalArgumentException> { updater.install(packageFile) }
            assertContentEquals(previous, Files.readAllBytes(target))

            Files.write(packageFile, signedDesktopModule(manifest, replacement, signing.private))
            Files.write(host, byteArrayOf(6))
            assertFailsWith<IllegalArgumentException> { updater.install(packageFile) }
            Files.write(host, byteArrayOf(1))
            assertFailsWith<OverlappingFileLockException> {
                withDesktopModuleLock(root, shared = true) { updater.install(packageFile) }
            }

            updater.install(packageFile)
            assertContentEquals(replacement, Files.readAllBytes(target))
            assertFailsWith<IllegalArgumentException> { updater.install(packageFile) }
            assertContentEquals(other, Files.readAllBytes(adBlock))
            updater.rollback(packageFile)
            assertContentEquals(previous, Files.readAllBytes(target))
            assertContentEquals(other, Files.readAllBytes(adBlock))
        } finally { root.toFile().deleteRecursively() }
    }
}
