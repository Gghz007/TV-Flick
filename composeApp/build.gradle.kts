import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.ZipFile

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
    }
}
val tmdbApiKey = localProperties.getProperty("tmdb.apiKey").orEmpty()
fun tvFlickPlugin(name: String): Any =
    if (providers.gradleProperty("tvflick.useStagedPlugins").orNull == "true") {
        val version = providers.gradleProperty("tvflick.plugins.$name.version").get()
        "app.tvflick.plugins:$name:$version"
    } else project(":$name")

plugins {
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.sqldelight)
}

val generateReleaseConfig = tasks.register("generateReleaseConfig") {
    val appVersion = providers.gradleProperty("tvflick.versionName").get()
    val repository = providers.gradleProperty("tvflick.releaseRepository").orElse("").get()
    val rulesUrl = providers.gradleProperty("tvflick.adblockUpdateUrl").orElse("").get()
    val rulesKey = providers.gradleProperty("tvflick.adblockPublicKey").orElse("").get()
    val desktopModuleKey = providers.gradleProperty("tvflick.desktopModulePublicKey").orElse("").get()
    val output = layout.buildDirectory.dir("generated/releaseConfig/kotlin")
    inputs.property("version", appVersion)
    inputs.property("repository", repository)
    inputs.property("rulesUrl", rulesUrl)
    inputs.property("rulesKey", rulesKey)
    inputs.property("desktopModuleKey", desktopModuleKey)
    outputs.dir(output)
    doLast {
        require(appVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?")))
        require(repository.isEmpty() || repository.matches(Regex("[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+")))
        require(rulesUrl.isEmpty() == rulesKey.isEmpty()) { "Configure both AdBlock URL and public key" }
        if (rulesUrl.isNotEmpty()) {
            require(rulesUrl.matches(Regex("[A-Za-z0-9:/?&=._%+~-]+")))
            val uri = URI(rulesUrl)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null)
        }
        fun requireRsaKey(encodedKey: String, label: String) {
            require(encodedKey.length <= 16_384 && encodedKey.matches(Regex("[A-Za-z0-9+/]+={0,2}")))
            val key = runCatching {
                val encoded = Base64.getDecoder().decode(encodedKey)
                KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(encoded)) as RSAPublicKey
            }.getOrNull()
            require(key != null && key.modulus.bitLength() in 2048..8192) {
                "The $label public key must be a Base64 X.509 RSA public key (2048–8192 bits)"
            }
        }
        if (rulesKey.isNotEmpty()) requireRsaKey(rulesKey, "AdBlock")
        if (desktopModuleKey.isNotEmpty()) requireRsaKey(desktopModuleKey, "Windows module")
        output.get().file("app/tvflick/core/update/ReleaseConfig.kt").asFile.apply {
            parentFile.mkdirs()
            writeText("package app.tvflick.core.update\ninternal object ReleaseConfig {\n" +
                "    const val version = \"$appVersion\"\n    const val repository = \"$repository\"\n" +
                "    const val adblockUpdateUrl = \"$rulesUrl\"\n    const val adblockPublicKey = \"$rulesKey\"\n" +
                "    const val desktopModulePublicKey = \"$desktopModuleKey\"\n}\n")
        }
    }
}

val desktopModuleTrust = tasks.register("desktopModuleTrust") {
    val key = providers.gradleProperty("tvflick.desktopModulePublicKey").orElse("")
    val resources = layout.buildDirectory.dir("generated/desktopModuleTrust")
    dependsOn(generateReleaseConfig)
    inputs.property("key", key)
    outputs.dir(resources)
    doLast {
        resources.get().file("tvflick-windows-module-public-key.txt").asFile.apply {
            parentFile.mkdirs()
            writeText(key.get())
        }
    }
}

kotlin {
    sourceSets.commonMain { kotlin.srcDir(generateReleaseConfig) }
    androidLibrary {
        androidResources.enable = true
        namespace = "app.tvflick.shared"
        compileSdk = libs.versions.android.compile.sdk.get().toInt()
        minSdk = libs.versions.android.min.sdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    sourceSets {
        getByName("desktopMain") { kotlin.srcDir("src/jvmMain/kotlin") }
        getByName("androidMain") { kotlin.srcDir("src/jvmMain/kotlin") }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(compose.components.resources)
            implementation(libs.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.serialization.json)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            implementation(tvFlickPlugin("plugin-api"))
            implementation(tvFlickPlugin("plugin-engine"))
            implementation(tvFlickPlugin("source-plugin"))
            implementation(tvFlickPlugin("video-plugin"))
            implementation(tvFlickPlugin("adblock-plugin"))
        }

        getByName("desktopMain").resources.srcDir(desktopModuleTrust)
        getByName("desktopMain").dependencies {
            implementation("org.apache.pdfbox:pdfbox:3.0.8")
            implementation("net.java.dev.jna:jna-platform:5.13.0")
            implementation(compose.desktop.currentOs)
            implementation(libs.ktor.client.cio)
            implementation(libs.sqldelight.jdbc)
        }

        getByName("androidMain").dependencies {
            implementation("com.google.android.gms:play-services-auth:22.0.0")
            implementation(libs.androidx.activity.compose)
            implementation(libs.coil.svg)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        getByName("desktopTest").dependencies {
            implementation(libs.sqldelight.jdbc)
            implementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
        }
    }
}

sqldelight {
    databases {
        create("TvFlickDatabase") {
            packageName.set("app.tvflick.core.database")
        }
    }
}

val desktopMediaResources = layout.buildDirectory.dir("desktop-media")
val prepareDesktopMediaRuntime = tasks.register("prepareDesktopMediaRuntime") {
    val checksum = "ae947c6f7f87d19cf3de80e448409419a9e81a18987d55ead133c55c2db29e9c"
    val archiveUrl = "https://github.com/FFmpeg-Builds/FFmpeg-Builds/releases/download/autobuild-2026-11-19-12-39/ffmpeg-n8.1.3-36-ge22a4510c9-win64-lgpl-shared-8.1.zip"
    inputs.property("sha256", checksum)
    inputs.property("url", archiveUrl)
    inputs.file(rootProject.file("docs/FFMPEG_RUNTIME.md"))
    outputs.dir(desktopMediaResources)
    doLast {
        fun digest(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    hash.update(buffer, 0, count)
                }
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
        val archive = rootProject.layout.buildDirectory.file("ffmpeg-lgpl.zip").get().asFile
        archive.parentFile.mkdirs()
        if (!archive.isFile) {
            val partial = File(archive.path + ".download")
            try {
                val connection = URI(archiveUrl).toURL().openConnection().apply {
                    connectTimeout = 15_000; readTimeout = 30_000
                }
                connection.getInputStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
                check(digest(partial) == checksum) { "FFmpeg checksum mismatch" }
                Files.move(partial.toPath(), archive.toPath())
            } finally { partial.delete() }
        }
        check(digest(archive) == checksum) { "FFmpeg checksum mismatch" }
        val destination = desktopMediaResources.get().dir("common/ffmpeg").asFile.toPath().toAbsolutePath().normalize()
        val buildRoot = layout.buildDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        check(destination.startsWith(buildRoot) && destination != buildRoot)
        destination.toFile().deleteRecursively()
        Files.createDirectories(destination)
        ZipFile(archive).use { zip ->
            zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                val relative = entry.name.substringAfter('/', "")
                if (relative.isEmpty() || relative.startsWith("include/") || relative.startsWith("lib/")) return@forEach
                if (relative.startsWith("bin/") && !relative.matches(Regex("bin/(ffmpeg\\.exe|[^/]+\\.dll)"))) return@forEach
                val output = destination.resolve(relative).normalize()
                check(output.startsWith(destination) && output != destination) { "Invalid archive entry" }
                Files.createDirectories(output.parent)
                zip.getInputStream(entry).use { input -> Files.copy(input, output) }
            }
        }
        rootProject.file("docs/FFMPEG_RUNTIME.md").copyTo(destination.resolve("TV-Flick-runtime-notice.md").toFile())
        destination.resolve("archive.sha256").toFile().writeText(checksum)
        check(destination.resolve("bin/ffmpeg.exe").toFile().isFile)
    }
}

val windowsTrailerHost = layout.buildDirectory.dir("windows-trailer")
val prepareWindowsTrailerHost = tasks.register("prepareWindowsTrailerHost") {
    val version = "1.0.4370.26"
    val expectedHash = "1c0dacac84b70109b3fd42f90c53a0ba942abc3a87590d7a4e0ccd86e4a06cf4"
    val source = rootProject.file("tools/windows-trailer/TrailerHost.cs")
    inputs.file(source)
    inputs.property("webView2Version", version)
    inputs.property("webView2Sha256", expectedHash)
    outputs.dir(windowsTrailerHost)
    doLast {
        val output = windowsTrailerHost.get().asFile.apply { mkdirs() }
        val archiveName = "microsoft.web.webview2.$version.nupkg"
        val archive = layout.buildDirectory.file("trailer-sdk/$archiveName").get().asFile
        if (!archive.isFile) {
            archive.parentFile.mkdirs()
            val partial = File(archive.path + ".download")
            try {
                URI("https://api.nuget.org/v3-flatcontainer/microsoft.web.webview2/$version/$archiveName").toURL()
                    .openConnection().apply { connectTimeout = 15_000; readTimeout = 30_000 }
                    .getInputStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
                Files.move(partial.toPath(), archive.toPath())
            } finally { partial.delete() }
        }
        val actualHash = MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
        check(actualHash == expectedHash) { "Microsoft WebView2 NuGet archive checksum mismatch" }
        val assemblies = listOf("Microsoft.Web.WebView2.Core.dll", "Microsoft.Web.WebView2.WinForms.dll")
        ZipFile(archive).use { sdk ->
            val paths = assemblies.map { "lib/net462/$it" } + listOf("runtimes/win-x64/native/WebView2Loader.dll", "LICENSE.txt")
            for (path in paths) {
                val entry = requireNotNull(sdk.getEntry(path))
                sdk.getInputStream(entry).use { input ->
                    File(output, if (path == "LICENSE.txt") "WebView2-LICENSE.txt" else path.substringAfterLast('/'))
                        .outputStream().use { input.copyTo(it) }
                }
            }
        }
        val compiler = File(System.getenv("SystemRoot"), "Microsoft.NET/Framework64/v4.0.30319/csc.exe")
        check(compiler.isFile) { "Windows .NET Framework C# compiler is unavailable" }
        val references = listOf("System.Windows.Forms.dll", "System.Drawing.dll") +
            assemblies.map { File(output, it).path }
        val process = ProcessBuilder(listOf(compiler.path, "/nologo", "/target:winexe", "/platform:x64",
            "/optimize+", "/out:${File(output, "TVFlickTrailer.exe").path}", source.path) +
            references.map { "/reference:$it" }).redirectErrorStream(true).start()
        val compilerOutput = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "Windows trailer host compilation failed:\n$compilerOutput" }
    }
}

val onWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val desktopRuntimeDirectory = layout.buildDirectory.dir("desktop-runtime-resources")
val desktopRuntimeResources = tasks.register<Sync>("desktopRuntimeResources") {
    from(prepareDesktopMediaRuntime)
    if (onWindows) from(prepareWindowsTrailerHost) { into("common/trailer") }
    into(desktopRuntimeDirectory)
}

tasks.withType<JavaExec>().configureEach {
    systemProperty("tvflick.development.root", rootProject.projectDir.absolutePath)
    if (onWindows && name == "run") {
        dependsOn(prepareWindowsTrailerHost)
        systemProperty("tvflick.trailer.host", windowsTrailerHost.get().file("TVFlickTrailer.exe").asFile.absolutePath)
    }
}

tasks.register<JavaExec>("verifyGoogleLive") {
    group = "verification"
    description = "Explicit interactive Google login and read-only Drive check (not an automated test)."
    val testCompilation = kotlin.targets.getByName("desktop").compilations.getByName("test")
    dependsOn(testCompilation.compileAllTaskName)
    classpath(testCompilation.output.allOutputs, testCompilation.runtimeDependencyFiles)
    mainClass.set("app.tvflick.GoogleLiveVerificationKt")
    args(rootProject.layout.buildDirectory.file("google-verification/authorization-url.txt").get().asFile.absolutePath)
    if (providers.gradleProperty("googleLiveRoundtrip").orNull == "true") args("--roundtrip")
    providers.gradleProperty("googleLiveAndroidBackup").orNull?.let { args("--android-backup=$it") }
}

tasks.register<JavaExec>("verifyWindowsTrailer") {
    group = "verification"
    description = "Check the actual WebView2 and YouTube embed with the official sample video."
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("test")
    dependsOn(compilation.compileAllTaskName, prepareWindowsTrailerHost)
    classpath(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("app.tvflick.desktop.WindowsTrailerProbeKt")
    args(windowsTrailerHost.get().file("TVFlickTrailer.exe").asFile.path)
}

tasks.register<JavaExec>("signDesktopModule") {
    group = "publishing"
    description = "Authorize a specific Windows module update for a packaged application."
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("test")
    dependsOn(compilation.compileAllTaskName)
    classpath(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("app.tvflick.desktop.DesktopModuleReleaseToolKt")
    doFirst {
        args(listOf("sign") + listOf("desktopModuleImage", "desktopModuleTarget", "desktopModuleReplacement",
            "desktopModulePrivateKeyFile", "desktopModuleOutput").map { providers.gradleProperty(it).get() })
    }
}

tasks.register<JavaExec>("applyDesktopModule") {
    group = "publishing"
    description = "Install or roll back a signed Windows module using the installed app's trust key."
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("test")
    dependsOn(compilation.compileAllTaskName)
    classpath(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("app.tvflick.desktop.DesktopModuleReleaseToolKt")
    doFirst {
        args(listOf("desktopModuleAction", "desktopModuleImage", "desktopModulePackage")
            .map { providers.gradleProperty(it).get() })
    }
}

compose.desktop {
    application {
        mainClass = "app.tvflick.desktop.MainKt"
        // Package from the explicit build JDK rather than the Gradle daemon's
        // runtime. A Java 25 daemon produced a stripped image without java.exe.
        javaHome = System.getenv("JAVA_HOME") ?: System.getProperty("java.home")
        // The Android client already receives the same application API key via
        // BuildConfig; Desktop keeps the two catalog clients equivalent.
        jvmArgs("-Dtmdb.apiKey=$tmdbApiKey")

        nativeDistributions {
            appResourcesRootDir.set(desktopRuntimeResources.map { desktopRuntimeDirectory.get() })
            packageName = "TV Flick"
            // The application uses JDBC/SQLite and HTTP at runtime. Compose can
            // otherwise infer an image that is too small and the Windows
            // launcher only reports the unhelpful “Failed to launch JVM”. Keep
            // the complete Java runtime in development distributions so every
            // supported desktop feature has its required JDK module.
            includeAllModules = true
            windows {
                // This is the full-resolution TV Track mark supplied in Lunacy.
                // Windows application launchers require an .ico file rather than
                // the SVG used by Compose resources.
                iconFile.set(project.file("src/desktopMain/resources/TV-Flick.ico"))
            }
        }
    }
}
