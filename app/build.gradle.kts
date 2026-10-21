plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.imayx.host"
    compileSdk = 35

    // Native bridge: CI compiles libnode_bridge.so from src/main/cpp via CMake
    // (pass -PbuildNative). Local/on-device builds use the prebuilt .so from
    // src/main/jniLibs and skip CMake entirely, because the SDK cmake binary
    // shipped in on-device IDEs is frequently broken/unexecutable.
    val buildNative = project.hasProperty("buildNative")

    defaultConfig {
        applicationId = "com.imayx.host"
        minSdk = 24
        targetSdk = 35
        versionCode = 4
        versionName = "3.1"

        ndk {
            // Embedded Node (libnode.so) ships arm64 only.
            abiFilters += "arm64-v8a"
        }
        if (buildNative) {
            externalNativeBuild {
                cmake {
                    arguments += "-DANDROID_STL=c++_shared"
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    if (buildNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
    ndkVersion = "26.3.11579264"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        noCompress += "zip" // keep assets/ayx-backend.zip STORED, not deflated (mirrors the embedded-node reference)
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Native executables (e.g. cloudflared bundled by CI as libcloudflared.so) must be
        // extracted to nativeLibraryDir so they can be exec()'d on Android 10+.
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.security:security-crypto:1.0.0") // EncryptedSharedPreferences for the embedded backend's SESSION_SECRET

    debugImplementation("androidx.compose.ui:ui-tooling")
}
