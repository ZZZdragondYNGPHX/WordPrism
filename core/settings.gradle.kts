pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "wordprism-core"
include(":core-contract", ":core-engine", ":memory-core", ":platform-jvm", ":contract-fixtures")
if (providers.gradleProperty("android").orNull == "true") {
    include(":platform-android")
}
