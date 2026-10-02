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
        versionCode = 11
        versionName = "0.7.0"
        // Public GitHub repository whose Releases the in-app update check reads (override: -PupdateRepo=owner/name).
        // 64/32-bit ARM phones plus x86_64 emulators (the WireGuard Go library is ~3.5 MB per ABI).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        buildConfigField("String", "UPDATE_REPO", "\"${project.findProperty("updateRepo") ?: "1immortal/truenas-companion"}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
