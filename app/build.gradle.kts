plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shortslock.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.shortslock.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 5
        versionName = "0.5.0-phase5"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
