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
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 2
        versionName = "0.2.0"
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
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    buildFeatures { compose = true }
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
