import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.kotlin.multiplatform)
}

tasks.register<JavaExec>("probeFilmix") {
    group = "verification"
    description = "Explicit live source probe; never runs with normal tests and never plays video."
    dependsOn("desktopTestClasses")
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("test")
    classpath = compilation.output.allOutputs + compilation.runtimeDependencyFiles!!
    mainClass.set("app.tvflick.plugin.source.FilmixLiveProbeKt")
}
tasks.register<JavaExec>("probeStructured") {
    group = "verification"
    description = "Explicit live structured source probe; no video playback and no stream URLs in output."
    dependsOn("desktopTestClasses")
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("test")
    classpath = compilation.output.allOutputs + compilation.runtimeDependencyFiles!!
    mainClass.set("app.tvflick.plugin.source.StructuredLiveProbeKt")
}
kotlin {
    androidLibrary {
        namespace = "app.tvflick.plugin.source"
        compileSdk = libs.versions.android.compile.sdk.get().toInt()
        minSdk = libs.versions.android.min.sdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }
    sourceSets {
        commonMain.dependencies {
            api(project(":plugin-api"))
            implementation(project(":plugin-engine"))
            implementation(libs.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.serialization.json)
        }
        getByName("desktopMain") {
            kotlin.srcDir("src/jvmMain/kotlin")
            dependencies { implementation("org.jsoup:jsoup:1.24.1"); implementation(libs.ktor.client.cio) }
        }
        getByName("androidMain") {
            kotlin.srcDir("src/jvmMain/kotlin")
            dependencies { implementation("org.jsoup:jsoup:1.24.1"); implementation(libs.ktor.client.okhttp) }
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        getByName("desktopTest").dependencies {
            implementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
        }
    }
}
