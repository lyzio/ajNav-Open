import java.util.Properties

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Mapbox downloads token (secret, DOWNLOADS:READ scope).
// Read from: -PMAPBOX_DOWNLOADS_TOKEN, env MAPBOX_DOWNLOADS_TOKEN (CI secret) or local.properties.
// Never commit it. If Mapbox doesn't require it, it can stay empty.
val localProps = Properties().apply {
    val f = file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val mapboxDownloadsToken: String =
    providers.gradleProperty("MAPBOX_DOWNLOADS_TOKEN").orNull
        ?: providers.environmentVariable("MAPBOX_DOWNLOADS_TOKEN").orNull
        ?: localProps.getProperty("MAPBOX_DOWNLOADS_TOKEN")
        ?: ""

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://api.mapbox.com/downloads/v2/releases/maven")
            content { includeGroupByRegex("com\\.mapbox.*") }
            if (mapboxDownloadsToken.isNotBlank()) {
                credentials {
                    username = "mapbox" // always "mapbox", not your user name
                    password = mapboxDownloadsToken
                }
                authentication { create<BasicAuthentication>("basic") }
            }
        }
    }
}

rootProject.name = "ajnav"

include(":app")
include(":core:model")
include(":core:security")
include(":feature:navigation")
