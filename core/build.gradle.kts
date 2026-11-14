plugins {
    kotlin("jvm") version "2.2.21" apply false
    kotlin("android") version "2.2.21" apply false
    kotlin("plugin.serialization") version "2.2.21" apply false
    id("com.android.library") version "8.13.2" apply false
    id("com.google.devtools.ksp") version "2.2.21-2.0.5" apply false
}
allprojects {
    group = "dev.wordprism"
    version = "0.4.0"
    dependencyLocking { lockAllConfigurations() }
}
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(plugin = "maven-publish")
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        extensions.configure<PublishingExtension> {
            publications { create<MavenPublication>("library") { from(components["java"]) } }
            repositories { maven { name = "core"; url = uri(rootProject.layout.buildDirectory.dir("repository")) } }
        }
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("java.library.path", rootProject.file("memory-native/target/release").absolutePath)
        testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
}
