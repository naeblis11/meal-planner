import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The owner's release tool (plan 8, docs/RELEASING.md): stages the MSI and the APK with latest.json, signs latest.json
// with the release key, and writes the key's public half into the apps. tools/release.ps1 and tools/release-key.ps1 run
// it from installDist's script; nothing else does. It uses the apps' own ReleaseManifest and ReleaseSignature, so the
// list it writes is the list they read.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":shared:core"))
    testImplementation(libs.junit)
}

application {
    mainClass = "com.naeblis11.mealplanner.release.ReleaseToolKt"
    applicationName = "releaseTool"
}

tasks.withType<Test>().configureEach {
    // ReleaseVersionsTest and PublicKeyFileTest read apps/ (the build files, gradle.properties, ReleaseKeyData.kt).
    systemProperty("appsDir", rootProject.projectDir.absolutePath)
    // Those files aren't inputs Gradle can see, so the tests always run.
    outputs.upToDateWhen { false }
}
