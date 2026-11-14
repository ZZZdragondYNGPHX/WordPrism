plugins { id("com.android.library"); kotlin("android"); id("com.google.devtools.ksp"); `maven-publish` }
android {
    namespace = "dev.wordprism.android"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    publishing { singleVariant("release") { withSourcesJar() } }
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["notClass"] = "dev.wordprism.android.ProcessRestartProbeTest"
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/jniLibs"))
    sourceSets["main"].assets.srcDir(rootProject.file("third-party"))
    packaging { jniLibs { useLegacyPackaging = false } }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    api(project(":core-engine"))
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
val copyNative by tasks.registering(Copy::class) {
    val nativeRoot = rootProject.file("memory-native/target")
    from(nativeRoot.resolve("aarch64-linux-android/release/libwordprism_memory.so")) { into("arm64-v8a") }
    from(nativeRoot.resolve("x86_64-linux-android/release/libwordprism_memory.so")) { into("x86_64") }
    into(layout.buildDirectory.dir("generated/jniLibs"))
    doFirst {
        listOf("aarch64-linux-android", "x86_64-linux-android").forEach {
            check(nativeRoot.resolve("$it/release/libwordprism_memory.so").isFile) { "Build memory-native for $it first; see docs/core/running.md" }
        }
    }
}
tasks.named("preBuild") { dependsOn(copyNative) }
tasks.withType<Test>().configureEach { useJUnit() }
tasks.register("writeRuntimeDependencies") {
    val output = rootProject.layout.buildDirectory.file("runtime-dependencies.tsv")
    outputs.file(output)
    doLast {
        val runtime = configurations.getByName("releaseRuntimeClasspath")
        val modules = runtime.incoming.resolutionResult.allComponents.mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
            .distinctBy { "${it.group}:${it.module}:${it.version}" }.sortedWith(compareBy({ it.group }, { it.module }, { it.version }))
        val file = output.get().asFile
        file.parentFile.mkdirs()
        file.writeText(modules.joinToString("\n", postfix = "\n") { "${it.group}\t${it.module}\t${it.version}" })
        println("Recorded ${modules.size} resolved Android runtime modules")
    }
}
afterEvaluate {
    publishing {
        publications { create<MavenPublication>("release") { from(components["release"]) } }
        repositories { maven { name = "core"; url = uri(rootProject.layout.buildDirectory.dir("repository")) } }
    }
}
