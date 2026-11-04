plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.wordprism"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.wordprism"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.4.0-test.7"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Match the packaged memory kernel; transitive libraries also contain unsupported 32-bit ABIs.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
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
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":sdk-contract"))
    implementation(project(":sdk-codex"))
    implementation("dev.wordprism:platform-android:0.4.0")
    // Development screen only; SdkProvider always constructs the real packaged core.
    debugImplementation(project(":sdk-mock"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.savedstate)
    implementation(libs.core.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
}

// Distribution notices use the actual release resolution, including backend transitive dependencies.
tasks.register("writeRuntimeDependencies") {
    val output = layout.buildDirectory.file("reports/runtime-dependencies.tsv")
    outputs.file(output)
    doLast {
        val modules = configurations.getByName("releaseRuntimeClasspath").incoming.resolutionResult.allComponents
            .mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
            .filter { it.group != "dev.wordprism" }.distinctBy { "${it.group}:${it.module}:${it.version}" }
            .sortedWith(compareBy({ it.group }, { it.module }, { it.version }))
        val file = output.get().asFile; file.parentFile.mkdirs()
        file.writeText(modules.joinToString("\n", postfix = "\n") { "${it.group}\t${it.module}\t${it.version}" })
        println("Recorded ${modules.size} release runtime modules")
    }
}
