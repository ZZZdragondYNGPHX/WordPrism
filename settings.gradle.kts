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
            url = uri("core/build/repository")
            content { includeGroup("dev.wordprism") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "WordPrism"

include(":app", ":sdk-contract", ":sdk-adapter", ":sdk-mock")
project(":sdk-contract").projectDir = file("sdk/contract")
project(":sdk-adapter").projectDir = file("sdk/adapter")
project(":sdk-mock").projectDir = file("sdk/mock")
