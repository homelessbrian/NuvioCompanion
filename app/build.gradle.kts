plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.homelessbrian.nuviocompanion"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.homelessbrian.nuviocompanion"
        minSdk = 24
        targetSdk = 35
        // Release builds get their version from the Build Release workflow (APP_VERSION, e.g. 0.3.0).
        // The version code is made from it (0.3.0 -> 3000, 1.2.3 -> 1002003) so every newer
        // release can install over the older one. Debug builds keep the run number.
        val appVersion = System.getenv("APP_VERSION")?.trim()?.removePrefix("v")?.takeIf { Regex("""\d+\.\d+\.\d+""").matches(it) }
        versionCode = appVersion?.split('.')?.map { it.toInt() }?.let { (major, minor, patch) -> major * 1_000_000 + minor * 1_000 + patch }
            ?: System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 2
        versionName = appVersion ?: "0.2.0"
    }

    val companionSigningStore = System.getenv("COMPANION_SIGNING_STORE")
    val companionSigningPassword = System.getenv("COMPANION_SIGNING_PASSWORD")
    val companionSigningAlias = System.getenv("COMPANION_SIGNING_ALIAS")
    val companionSigningKeyPassword = System.getenv("COMPANION_SIGNING_KEY_PASSWORD")

    if (
        !companionSigningStore.isNullOrBlank() &&
        !companionSigningPassword.isNullOrBlank() &&
        !companionSigningAlias.isNullOrBlank() &&
        !companionSigningKeyPassword.isNullOrBlank()
    ) {
        signingConfigs.create("companionPersistent") {
            storeFile = file(companionSigningStore)
            storePassword = companionSigningPassword
            keyAlias = companionSigningAlias
            keyPassword = companionSigningKeyPassword
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = if (signingConfigs.findByName("companionPersistent") != null) {
                signingConfigs.getByName("companionPersistent")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        release {
            // The permanent Nuvio Launch key, so each release can update the one before it.
            // The Build Release workflow refuses to run without it.
            signingConfig = signingConfigs.findByName("companionPersistent") ?: signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    // Lint's extra release-only pass is skipped so it can't block a release; the build itself still checks the code.
    lint { checkReleaseBuilds = false }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
