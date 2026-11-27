import org.gradle.api.JavaVersion
import java.util.Properties

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
    }
}
val tmdbApiKey = localProperties.getProperty("tmdb.apiKey").orEmpty()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.tvflick.android"
    compileSdk = libs.versions.android.compile.sdk.get().toInt()

    defaultConfig {
        applicationId = "app.tvflick"
        minSdk = libs.versions.android.min.sdk.get().toInt()
        targetSdk = libs.versions.android.compile.sdk.get().toInt()
        versionCode = 1
        versionName = providers.gradleProperty("tvflick.versionName").get()
        testInstrumentationRunner = "app.tvflick.android.PlayerWindowProbe"
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.sqldelight.android)
    if (providers.gradleProperty("tvflick.useStagedPlugins").orNull == "true") {
        val version = providers.gradleProperty("tvflick.plugins.plugin-api.version").get()
        androidTestImplementation("app.tvflick.plugins:plugin-api:$version")
    } else {
        androidTestImplementation(project(":plugin-api"))
    }
    androidTestImplementation(libs.coroutines.core)
    androidTestImplementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
}
