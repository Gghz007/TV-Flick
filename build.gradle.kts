plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.multiplatform.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.jetbrains.compose) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.sqldelight) apply false
}

// Stage developer-facing binaries locally. Each module has its own release version.
val pluginModules = listOf("plugin-api", "plugin-engine", "source-plugin", "video-plugin", "adblock-plugin")
val stagePluginArtifacts = tasks.register("stagePluginArtifacts") {
    group = "publishing"
    description = "Prepare Windows and Android plugin libraries in build/plugin-repository."
}

pluginModules.forEach { module ->
    project(":$module") {
        val moduleVersion = providers.gradleProperty("tvflick.plugins.$module.version").get()
        require(moduleVersion.matches(Regex("""\d+\.\d+\.\d+(-[\w.-]+)?""")))
        group = "app.tvflick.plugins"
        version = moduleVersion
        apply(plugin = "maven-publish")
        extensions.configure<PublishingExtension> {
            repositories.maven {
                name = "PluginStaging"
                url = uri(rootProject.layout.buildDirectory.dir("plugin-repository"))
            }
        }
        stagePluginArtifacts.configure { dependsOn(":$module:publishAllPublicationsToPluginStagingRepository") }
    }
}
