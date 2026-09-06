pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Only for the extraction library, which is published nowhere else.
        // Scoped so a typo in any other coordinate cannot silently resolve here.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.MetrolistGroup.innertubex") }
        }
    }
}

rootProject.name = "Museroom"
include(":app")
