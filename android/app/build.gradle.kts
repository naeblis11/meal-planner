import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AGP 9 compiles Kotlin itself (built-in Kotlin), so there is no separate
// org.jetbrains.kotlin.android plugin; only the Compose compiler plugin.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

// Golden cases shared with the Python app (see tests/parity_support.py).
val parityDir = rootProject.file("../tests/fixtures/parity")

// Release signing. android/keystore.properties (git-ignored, never exported) names a
// keystore kept outside the repo; docs/ANDROID.md has the command that makes both.
// Debug builds and unit tests never need it; a release package without it stops
// with this message instead of producing an unsigned APK.
val keystorePropsFile: File = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.isFile) keystorePropsFile.reader(Charsets.UTF_8).use { load(it) }
}
val releaseSigningProblem: String? = when {
    !keystorePropsFile.isFile -> "android/keystore.properties is missing."
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword").any { keystoreProps.getProperty(it).isNullOrBlank() } ->
        "android/keystore.properties needs storeFile, storePassword, keyAlias and keyPassword."
    !file(keystoreProps.getProperty("storeFile")).isFile -> "The keystore that android/keystore.properties names does not exist."
    else -> null
}

android {
    namespace = "com.naeblis11.mealplanner"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.naeblis11.mealplanner"
        minSdk = 26
        targetSdk = 35
        // versionName follows the release tag (android-v<versionName>); versionCode goes up by one every release.
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (releaseSigningProblem == null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // A development build installs beside the released app instead of clashing with its signature.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            if (releaseSigningProblem == null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("parityDir", parityDir.absolutePath)
            it.systemProperty("compatOut", layout.buildDirectory.dir("compat").get().asFile.absolutePath)
            it.systemProperty("srcDir", file("src").absolutePath)
            it.inputs.dir(parityDir)
            it.systemProperty("schemaDir", file("schemas").absolutePath)
            it.inputs.dir("schemas")
        }
    }
}

val checkReleaseSigning = tasks.register("checkReleaseSigning") {
    description = "Stops a release package early when android/keystore.properties is missing or incomplete."
    val problem = releaseSigningProblem
    doLast {
        if (problem != null) {
            throw GradleException(
                "$problem Release builds are signed with your own keystore: see docs/ANDROID.md, " +
                    "\"Building a signed release\". Debug builds need none of this.",
            )
        }
    }
}

// Only packaging a release needs the keystore: assembleDebug and testDebugUnitTest do not (AGP 9 here defines no release unit-test task).
tasks.configureEach {
    if (name == "packageRelease" || name == "bundleRelease") dependsOn(checkReleaseSigning)
}

room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.snakeyaml)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
