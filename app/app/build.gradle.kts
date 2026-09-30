plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ai.nanosearch.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.nanosearch.launcher"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.2"

        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                // Release even for debug variants: a debug-built llama.cpp is unusably slow.
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_STL=c++_static",
                    // Align ELF segments to 16 KB so the library loads on 16 KB-page devices (Android 15+).
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DCMAKE_C_FLAGS=-march=armv8.2-a+dotprod",
                    "-DCMAKE_CXX_FLAGS=-march=armv8.2-a+dotprod",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.material3:material3:1.5.0-alpha04")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    // Runs the image-and-text matching model (MobileCLIP) that powers "photos of a beach" style search.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
}
