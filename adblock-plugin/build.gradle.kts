import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    androidLibrary {
        namespace = "app.tvflick.plugin.adblock"
        compileSdk = libs.versions.android.compile.sdk.get().toInt()
        minSdk = libs.versions.android.min.sdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }
    sourceSets.commonMain.dependencies {
        api(project(":plugin-api"))
        implementation(libs.serialization.json)
        implementation("io.ktor:ktor-http:${libs.versions.ktor.get()}")
    }
    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
}
