plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.armanize.wallexportfolioadvisor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.armanize.wallexportfolioadvisor"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
}