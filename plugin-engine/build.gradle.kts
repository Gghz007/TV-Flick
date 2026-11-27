import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    androidLibrary {
        namespace = "app.tvflick.plugin.engine"
        compileSdk = libs.versions.android.compile.sdk.get().toInt()
        minSdk = libs.versions.android.min.sdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }
    sourceSets.commonMain.dependencies { api(project(":plugin-api")) }
    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
    sourceSets.getByName("desktopTest").dependencies { implementation(libs.coroutines.core) }
}
