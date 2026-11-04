plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Published contract from the backend build; no source copy or second business implementation.
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(project(":sdk-contract"))
    api("dev.wordprism:core-contract:0.4.0")
    testImplementation("dev.wordprism:core-engine:0.4.0")
    testImplementation("dev.wordprism:platform-jvm:0.4.0")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
tasks.withType<Test>().configureEach {
    systemProperty("java.library.path", rootProject.file("../backend/memory-native/target/release").absolutePath)
}
