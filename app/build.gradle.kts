import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vornex.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vornex.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Одна и та же подпись у всех сборок: новая версия ставится поверх старой, данные не теряются.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("vornex-debug.jks")
            storePassword = "vornex123"
            keyAlias = "vornex"
            keyPassword = "vornex123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
