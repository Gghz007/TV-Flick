import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.Duration

plugins {
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

compose.resources {
    packageOfResClass = "tv_flick.video_plugin.generated.resources"
}

// Isolated visual harness: does not access the library, sources, or user data.
tasks.register<JavaExec>("playerVisualPreview") {
    val testCompilation = kotlin.targets.getByName("desktop").compilations.getByName("test")
    dependsOn("desktopTestClasses")
    classpath = files(testCompilation.output.allOutputs, testCompilation.runtimeDependencyFiles)
    mainClass.set("app.tvflick.plugin.video.PlayerVisualPreviewKt")
    workingDir(rootProject.projectDir)
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

tasks.register<JavaExec>("probeNativePlayer") {
    group = "verification"
    description = "Explicit native MP4/HLS probe without application UI or audible playback."
    dependsOn("desktopTestClasses")
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("test")
    classpath = compilation.output.allOutputs + compilation.runtimeDependencyFiles!!
    mainClass.set("app.tvflick.plugin.video.NativePlayerProbeKt")
    timeout.set(Duration.ofMinutes(2))
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}
kotlin {
    androidLibrary {
        androidResources.enable = true
        namespace = "app.tvflick.plugin.video"
        compileSdk = libs.versions.android.compile.sdk.get().toInt()
        minSdk = libs.versions.android.min.sdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }
    sourceSets.commonMain.dependencies {
        api(project(":plugin-api"))
        implementation(libs.compose.runtime)
        implementation(libs.compose.foundation)
        implementation(libs.compose.material3)
        implementation(libs.compose.ui)
        // Player assets belong to this independently replaceable plugin module.
        implementation(compose.components.resources)
        implementation(libs.mediamp.all)
    }
    sourceSets.getByName("desktopMain").dependencies {
        implementation(compose.desktop.currentOs)
        runtimeOnly(libs.mediamp.mpv.runtime.windows)
    }
    sourceSets.getByName("androidMain").dependencies {
        implementation(libs.coil.compose)
        implementation(libs.coil.svg)
    }
    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
}
