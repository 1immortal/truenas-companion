// Vendored Termux terminal-emulator + terminal-view (v0.118.0, Apache License 2.0). See NOTICE in this folder.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.view"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        // Third-party code kept as close to upstream as possible; lint findings here are upstream's.
        checkReleaseBuilds = false
        abortOnError = false
        ignoreWarnings = true
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.11.0")
}
