plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val stableKeystore = rootProject.file("ci/trajectory-debug.jks")
if (!stableKeystore.exists()) {
    val encoded = rootProject.file("ci/trajectory-debug-keystore.b64")
    if (encoded.exists()) {
        stableKeystore.parentFile.mkdirs()
        stableKeystore.writeBytes(java.util.Base64.getDecoder().decode(encoded.readText().trim()))
    }
}

android {
    namespace = "com.example.trajectoryoverlay"
    compileSdk = 35

    signingConfigs {
        create("stableDebug") {
            storeFile = stableKeystore
            storePassword = "trajectory123"
            keyAlias = "trajectory"
            keyPassword = "trajectory123"
        }
    }

    defaultConfig {
        applicationId = "com.example.trajectoryoverlay"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stableDebug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stableDebug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.opencv:opencv:5.0.0")
}
