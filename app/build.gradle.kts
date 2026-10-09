import java.util.Properties
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    namespace = "app.truenascompanion"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.truenascompanion"
        minSdk = 26
        targetSdk = 37
        versionCode = 26
        versionName = "1.7.1"
        // Public GitHub repository whose Releases the in-app update check reads (override: -PupdateRepo=owner/name).
        // 64/32-bit ARM phones plus x86_64 emulators (the WireGuard Go library is ~3.5 MB per ABI).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        buildConfigField("String", "RELEASE_SIGNER_SHA256", "\"92fb06a9b958c4a08f0db43e1e4b9b2c6ee0a9cdf4a91ffd2421a11277f5e608\"")
        // Debug signer (Android default debug.keystore) — still accepted so 0.x debug builds can update among themselves.
        buildConfigField("String", "DEBUG_SIGNER_SHA256", "\"b613e16e8922015ac9cb2ddb0e3cddcb262f40a2c721b840ec7c976638c6db3c\"")
        buildConfigField("String", "UPDATE_REPO", "\"${project.findProperty("updateRepo") ?: "1immortal/truenas-companion"}\"")
    }


    // Release signing: reads /home/box/secure/keystore.properties (or -PkeystoreProperties=…) when present.
    // Never commit the keystore or passwords. Debug builds keep the standard Android debug key.
    val keystorePropsFile = (findProperty("keystoreProperties") as String?)
        ?.let { file(it) }
        ?: file("/home/box/secure/keystore.properties")
    val keystoreProps: Properties? = if (keystorePropsFile.isFile) {
        Properties().also { props ->
            keystorePropsFile.inputStream().use { props.load(it) }
        }
    } else null

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile")!!)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps != null) signingConfig = signingConfigs.getByName("release")
            buildConfigField("boolean", "PREVIEW_CHANNEL", "false")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // 1.7.1 (security H-1): the published "Debug (preview builds)" APK is not debuggable, so other tools on a
            // USB-connected computer can't attach to it or read its private data (run-as). Same package and signing key
            // as before, so existing installs update in place. Its update channel comes from PREVIEW_CHANNEL.
            isDebuggable = false
            buildConfigField("boolean", "PREVIEW_CHANNEL", "true")
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

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // wireguard-android ships helpers for its root/kernel backend; only the userspace GoBackend (libwg-go) is used.
        jniLibs.excludes += listOf("**/libwg.so", "**/libwg-quick.so")
    }
}

dependencies {
    implementation(project(":terminal"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.reorderable)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.svg)
    // Built-in WireGuard tunnel (userspace GoBackend + Android VpnService) and QR import (CameraX + ZXing, no Play services)
    implementation(libs.wireguard.tunnel)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // Screenshot tests (Robolectric + Roborazzi); run with ./gradlew testDebugUnitTest -Pscreenshots
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    // Emulator end-to-end test of the WireGuard tunnel (app/src/androidTest, needs a WireGuard peer; see docs/TECHNICAL.md)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}

// Screenshot tests need Robolectric's android-all jars (large download), so they only run on request.
tasks.withType<Test>().configureEach {
    if (!project.hasProperty("screenshots")) {
        exclude("**/screenshots/**")
    } else {
        systemProperty("roborazzi.test.record", "true")
        systemProperty("screenshot.dir", rootProject.layout.projectDirectory.dir("screenshots").asFile.absolutePath)
        maxHeapSize = "2g"
    }
}
