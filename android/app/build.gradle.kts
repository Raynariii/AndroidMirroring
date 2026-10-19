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

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            // The helper is started by class name via app_process, so it must not be renamed or stripped.
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    packaging {
        resources {
            // Duplicated across the BouncyCastle jars.
            excludes += listOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES")
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
    // TLS 1.3 with exported keying material, needed by Wireless debugging pairing.
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    // Self-signed certificate for this app's adb identity.
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
    // SPAKE2 for pairing (prefab, linked into libandroidmirroring_native).
    implementation("io.github.vvb2060.ndk:boringssl:4.1")

    testImplementation("junit:junit:4.13.2")
}
