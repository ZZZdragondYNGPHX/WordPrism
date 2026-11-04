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
        maven {
            name = "wordprismLocal"
            url = uri("../backend/build/repository")
            content { includeGroup("dev.wordprism") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "WordPrism"

// UI view ports only. Authoritative business types are published by backend/core-contract.
include(":sdk-contract")
// Retained for isolated UI development and tests; the installed application uses the local core.
include(":sdk-mock")
// Android app (Claude): Compose UI consuming only :sdk-contract types.
// Adapter from the UI port onto published dev.wordprism.contract.WordPrism artifacts.
include(":sdk-codex")
include(":app")
