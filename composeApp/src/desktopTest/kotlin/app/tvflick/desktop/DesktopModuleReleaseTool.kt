package app.tvflick.desktop

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.name

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "sign" -> signUpdate(args.drop(1))
        "install", "rollback" -> {
            require(args.size == 3) { "Pass the installed Windows image and the signed update package" }
            val image = Path.of(args[1]).toRealPath()
            val updater = DesktopModuleUpdates(image, installedModuleKey(image))
            val archive = Path.of(args[2])
            if (args[0] == "install") updater.install(archive) else updater.rollback(archive)
            println("Windows module ${args[0]} complete")
        }
        else -> error("Expected sign, install or rollback")
    }
}

private fun installedModuleKey(image: Path): String {
    require(Files.isRegularFile(image.resolve("TV Flick.exe"))) { "Expected a packaged Windows image" }
    val core = Files.list(image.resolve("app")).use { jars ->
        jars.filter { it.name.matches(Regex("composeApp-desktop(?:-[a-zA-Z0-9.-]+)?\\.jar")) }.toList().single()
    }
    return ZipFile(core.toFile()).use { jar ->
        val resource = requireNotNull(jar.getEntry("tvflick-windows-module-public-key.txt")) {
            "The installed Windows app does not support module updates"
        }
        jar.getInputStream(resource).use { input ->
            input.readNBytes(16_385).decodeToString(throwOnInvalidSequence = true).also {
                require(it.length in 1..16_384) { "The installed Windows app has no trusted module signing key" }
            }
        }
    }
}

private fun signUpdate(args: List<String>) {
    require(args.size == 5) { "Pass image, shipped JAR name, replacement JAR, PKCS#8 PEM key, and output archive" }
    val image = Path.of(args[0])
    val target = args[1]
    val replacementPath = Path.of(args[2])
    val keyPath = Path.of(args[3])
    val output = Path.of(args[4])
    require(Path.of(target).name == target)
    require(Files.size(keyPath) in 1..32_768)
    val pem = Files.readString(keyPath).trim()
    val begin = "-----BEGIN PRIVATE KEY-----"
    val end = "-----END PRIVATE KEY-----"
    require(pem.startsWith(begin) && pem.endsWith(end))
    val der = Base64.getMimeDecoder().decode(pem.removePrefix(begin).removeSuffix(end))
    val factory = KeyFactory.getInstance("RSA")
    val key = factory.generatePrivate(PKCS8EncodedKeySpec(der)) as RSAPrivateCrtKey
    require(key.modulus.bitLength() in 2048..8192)
    val publicKey = factory.generatePublic(RSAPublicKeySpec(key.modulus, key.publicExponent))
    require(MessageDigest.isEqual(publicKey.encoded, Base64.getDecoder().decode(installedModuleKey(image)))) {
        "This signing key does not match the key embedded in the Windows image"
    }
    val current = image.resolve("app").resolve(target)
    require(listOf(current, replacementPath).all { Files.isRegularFile(it) && Files.size(it) in 1..MAX_DESKTOP_MODULE_BYTES.toLong() })
    ZipFile(replacementPath.toFile()).use { require(it.size() > 0) { "Replacement JAR has no contents" } }
    val replacement = Files.readAllBytes(replacementPath)
    val manifest = DesktopModuleTransition(target, DesktopModuleUpdates(image, "").hostFingerprint(),
        desktopModuleDigest(Files.readAllBytes(current)), desktopModuleDigest(replacement))
    Files.newOutputStream(output, CREATE_NEW).use {
        it.write(signedDesktopModule(manifest, replacement, key))
    }
    println("Signed Windows module package: ${output.name}")
}

internal fun signedDesktopModule(
    transition: DesktopModuleTransition,
    replacementJar: ByteArray,
    key: PrivateKey,
): ByteArray {
    val manifest = buildJsonObject {
        put("schema", 1)
        put("target", transition.target)
        put("host", transition.host)
        put("previous", transition.previous)
        put("replacement", transition.replacement)
    }.toString().encodeToByteArray()
    val signature = Signature.getInstance("SHA256withRSA").run {
        initSign(key)
        update(DESKTOP_MODULE_SIGNATURE_CONTEXT.encodeToByteArray())
        update(manifest)
        sign()
    }
    return java.io.ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            mapOf("manifest.json" to manifest, "signature.bin" to signature, "module.jar" to replacementJar)
                .forEach { (name, value) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(value)
                    zip.closeEntry()
                }
        }
        bytes.toByteArray()
    }
}
