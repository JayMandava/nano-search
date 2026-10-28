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
        versionCode = 18
        versionName = "0.8.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["nanoAppLabel"] = "Nano Search"
        manifestPlaceholders["nanoHomeCategory"] = "android.intent.category.HOME"
        manifestPlaceholders["nanoWallpaperEnabled"] = "true"

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

    // Explicit cloud handoff: -PunsignedRelease=true emits an unsigned release and does not
    // resolve, probe or read private signing configuration. See docs/cloud-release-signing.md.
    // With the flag absent/false, preserve the normal external release key or debug-key fallback.
    val unsignedRelease = providers.gradleProperty("unsignedRelease").map(String::toBooleanStrict).getOrElse(false)
    val keystoreProps = Properties()
    if (!unsignedRelease) {
        val keystoreFile = (System.getenv("NANO_KEYSTORE_PROPERTIES") ?: "${System.getProperty("user.home")}/.nano-search-keystore/keystore.properties").let { file(it) }
        if (keystoreFile.exists()) keystoreFile.inputStream().use { stream -> keystoreProps.load(stream) }
    }
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
        debug {
            // Opt-in cloud-built physical QA: never replace the installed production package,
            // become a Home candidate, or expose a selectable QA wallpaper provider.
            if (providers.gradleProperty("clockQaBuild").map(String::toBoolean).getOrElse(false)) {
                applicationIdSuffix = ".clockqa"
                manifestPlaceholders["nanoAppLabel"] = "Nano Clock QA"
                manifestPlaceholders["nanoHomeCategory"] = "ai.nanosearch.launcher.category.QA_ONLY"
                manifestPlaceholders["nanoWallpaperEnabled"] = "false"
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (unsignedRelease) {
                null
            } else {
                signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            }
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

    // Private, local-only built-in widgets live in src/personal (git ignores it). They are compiled into the debug and minified test builds only,
    // never into release, so nothing in it can reach a published APK. The launcher finds them by name at run time (see BuiltinWidgets.kt).
    val includePersonalOverlay = providers.gradleProperty("includePersonalOverlay").map(String::toBoolean).getOrElse(true)
    if (includePersonalOverlay && file("src/personal").exists()) {
        listOf("debug", "minified").forEach { name ->
            sourceSets.getByName(name) {
                java.srcDir("src/personal/java")
                assets.srcDir("src/personal/assets")
                if (file("src/personal/res").exists()) res.srcDir("src/personal/res")
                if (file("src/personal/AndroidManifest.xml").exists()) manifest.srcFile("src/personal/AndroidManifest.xml")
            }
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
    implementation("com.squareup.okhttp3:okhttp:4.12.1")
    testImplementation("junit:junit:4.13.2")
    // Test-only native Skia review of the actual Canvas renderer; not an emulator/device.
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation(platform("androidx.compose:compose-bom:2025.09.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    implementation("org.apache.commons:commons-compress:1.28.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
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
