import java.util.Properties

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
        versionCode = 4
        versionName = "0.3.1"

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

    // Release signing: a keystore.properties file (storeFile, storePassword, keyAlias, keyPassword) kept outside the repository, found through
    // the NANO_KEYSTORE_PROPERTIES environment variable or ~/.nano-search-keystore/keystore.properties. Without one, release builds are signed
    // with the debug key so that anyone can still build them.
    val keystoreProps = Properties()
    val keystoreFile = (System.getenv("NANO_KEYSTORE_PROPERTIES") ?: "${System.getProperty("user.home")}/.nano-search-keystore/keystore.properties").let { file(it) }
    if (keystoreFile.exists()) keystoreFile.inputStream().use { stream -> keystoreProps.load(stream) }
    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        // The release build (shrunk, optimised) signed with the debug key, so it can be installed over a debug install to test the shrinking.
        create("minified") {
            initWith(getByName("release"))
            isDebuggable = true // so adb run-as can read its files during testing; the shrinking is the same
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    lint { checkReleaseBuilds = false }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// The optional GPU build of the language model layer (Vulkan). It is a second, separate native library so that the GPU driver is never
// loaded unless the user turns the GPU on. AGP builds one CMake project per module, so this one is built here and packaged as a jniLib.
val gpuLibs = layout.buildDirectory.dir("gpuLibs")
val buildGpuLib = tasks.register<Exec>("buildGpuLib") {
    val src = file("src/main/cpp_gpu")
    val work = layout.buildDirectory.dir("gpu").get().asFile
    val out = gpuLibs.get().asFile.resolve("arm64-v8a")
    val ndk = android.ndkDirectory
    val props = Properties()
    val localProps = rootProject.file("local.properties")
    if (localProps.exists()) localProps.inputStream().use { stream -> props.load(stream) }
    val cmakeDir: String? = props.getProperty("cmake.dir")
    val cmake = if (cmakeDir != null) "$cmakeDir/bin/cmake" else "cmake"
    inputs.dir(src)
    inputs.file(file("src/main/cpp/nanollm.cpp"))
    outputs.dir(out)
    doFirst { work.mkdirs(); out.mkdirs() }
    commandLine(
        "sh", "-c",
        "'$cmake' -S '$src' -B '$work' -DCMAKE_TOOLCHAIN_FILE='$ndk/build/cmake/android.toolchain.cmake' -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-33 " +
            "-DCMAKE_BUILD_TYPE=Release -DANDROID_STL=c++_static -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON " +
            "-DCMAKE_C_FLAGS=-march=armv8.2-a+dotprod -DCMAKE_CXX_FLAGS=-march=armv8.2-a+dotprod > '$work/configure.log' 2>&1 && " +
            "'$cmake' --build '$work' --target nanollm_gpu -j 8 > '$work/build.log' 2>&1 && cp '$work/libnanollm_gpu.so' '$out/'",
    )
}
android.sourceSets.getByName("main").jniLibs.srcDir(gpuLibs)
tasks.named("preBuild") { dependsOn(buildGpuLib) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.material3:material3:1.5.0-alpha04")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    // Swipeable home pages (plain Views, so the launcher's existing screens keep working).
    implementation("androidx.viewpager:viewpager:1.0.0")
    // Runs the image-and-text matching model (MobileCLIP) that powers "photos of a beach" style search.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
}
