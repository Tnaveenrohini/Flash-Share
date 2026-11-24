import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.flikk.share"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.flikk.share"
        // Wi-Fi Direct "QR tho direct connect" (WifiP2pConfig.Builder) Android 10+ lo matrame untundi
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    // Fixed key: prathi kotha build paata app meedha update ga install avthundi.
    signingConfigs {
        create("flikk") {
            storeFile = file("flikk.keystore")
            storePassword = "flikk123"
            keyAlias = "flikk"
            keyPassword = "flikk123"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("flikk") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("flikk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // QR code: chupinchadam + scan cheyyadam
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
