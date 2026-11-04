plugins { kotlin("jvm"); application }
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies { implementation(project(":core-contract")); implementation(project(":core-engine")); implementation(project(":platform-jvm")) }
application { mainClass.set("dev.wordprism.fixtures.FixturesKt") }
tasks.named<JavaExec>("run") {
    args(rootProject.file("../docs/backend/fixtures").absolutePath)
    if (providers.gradleProperty("skipReferencePackages").isPresent) args("--skip-packages")
    if (providers.gradleProperty("explanationReferencePackage").isPresent) args("--explanation-package")
    systemProperty("java.library.path", rootProject.file("memory-native/target/release").absolutePath)
}
