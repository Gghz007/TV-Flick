pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (providers.gradleProperty("tvflick.useStagedPlugins").orNull == "true") {
            exclusiveContent {
                forRepository {
                    maven { url = uri(rootDir.resolve("build/plugin-repository")) }
                }
                filter { includeGroup("app.tvflick.plugins") }
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "TV-Flick"
include(":composeApp")
include(":androidApp")
include(":plugin-api")
include(":plugin-engine")
include(":video-plugin")
include(":adblock-plugin")
include(":source-plugin")
