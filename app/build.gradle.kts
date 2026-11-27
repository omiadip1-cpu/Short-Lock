plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// A fixed signing key lets every new APK install over the previous one. CI provides it from repo secrets.
val keystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")

android {
    namespace = "com.shortslock.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.shortslock.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 12
        versionName = "1.1.0"
    }
    signingConfigs {
        if (keystorePath != null) create("stable") {
            storeFile = file(keystorePath)
            storeType = "pkcs12"
            storePassword = System.getenv("SIGNING_PASSWORD")
            keyAlias = "shortslock"
            keyPassword = System.getenv("SIGNING_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("stable")
        }
        debug {
            signingConfigs.findByName("stable")?.let { signingConfig = it }
        }
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
