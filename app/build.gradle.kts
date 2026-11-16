@file:Suppress("UnstableApiUsage")

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Keep this module's output under the repository-root build/ directory.
layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("app"))

val appName = "GhostLockPad"
val appVersionName = "1.1"

val gitVersionCode = runCatching {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toInt()
}.getOrElse {
    logger.warn("git rev-list failed (${it.message}); versionCode falls back to 1")
    1
}

android {
    namespace = "com.ghostlock.pad"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ghostlock.pad"
        minSdk = 31
        targetSdk = 36
        versionCode = gitVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val properties = Properties()
    runCatching { properties.load(project.rootProject.file("local.properties").inputStream()) }
    val keystorePwd = properties.getProperty("KEYSTORE_PASS")
        ?: System.getenv("KEYSTORE_PASS")
        ?: "ghostlock-pad"
    val keyAliasName = properties.getProperty("KEY_ALIAS")
        ?: System.getenv("KEY_ALIAS")
        ?: "ghostlock-pad"
    val keyPwd = properties.getProperty("KEY_PASSWORD")
        ?: System.getenv("KEY_PASSWORD")
        ?: "ghostlock-pad"
    val keystoreFile = rootProject.file("keystore/ghostlock-pad-release.jks")
        .takeIf { it.isFile && it.length() > 0L }
    if (keystoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePwd
                keyAlias = keyAliasName
                keyPassword = keyPwd
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (keystoreFile != null) "release" else "debug")
        }
        debug {
            signingConfig = signingConfigs.getByName(if (keystoreFile != null) "release" else "debug")
        }
    }
    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    // The bundled preload payload is arm64-only; that matches the target device.
    lint {
        disable += "ChromeOsAbiSupport"
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        jniLibs {
            // Extract native libs to nativeLibraryDir so the Shizuku
            // UserService (shell uid) can read the bundled preload payload.
            useLegacyPackaging = true
        }
    }
}

base {
    archivesName.set("$appName-v$appVersionName($gitVersionCode)")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-text")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
}
