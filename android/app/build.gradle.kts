import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.androidmirroring"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.androidmirroring"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1-spike"
    }

    buildTypes {
        release {
            // The helper is started by class name via app_process, so it must not be renamed or stripped.
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
