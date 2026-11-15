plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val skipNativeBuild = System.getenv("LEAP8_SKIP_NATIVE_BUILD") == "1"

android {
    namespace = "com.leap8.offline"
    compileSdk = 35
    if (!skipNativeBuild) {
        ndkVersion = "29.0.13113456"
    }

    defaultConfig {
        applicationId = "com.leap8.offline"
        minSdk = if (findProject(":npu-qairt") != null) 35 else 26
        targetSdk = 35
        versionCode = 12
        versionName = "0.5.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        ndk { abiFilters += "arm64-v8a" }
        if (!skipNativeBuild) {
            externalNativeBuild {
                cmake {
                    cppFlags += listOf("-std=c++20", "-O3", "-fvisibility=hidden")
                    arguments += listOf("-DANDROID_STL=c++_shared", "-DCMAKE_BUILD_TYPE=Release")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("performance") {
            initWith(getByName("release"))
            // Release-mode Compose/R8 without debugger overhead; upgrades the local debug
            // installation in place so learning history and imported models are retained.
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    if (!skipNativeBuild) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.31.6"
            }
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = true
        jniLibs.pickFirsts += "**/libc++_shared.so"
        jniLibs.keepDebugSymbols += "**/libQnnHtpV*Skel.so"
    }
    androidResources {
        noCompress += listOf("bin", "json", "tflite", "onnx", "wav", "so")
    }
}

val hostCoreLibName = when {
    System.getProperty("os.name").lowercase().contains("win") -> "leap8_core.dll"
    System.getProperty("os.name").lowercase().contains("mac") -> "libleap8_core.dylib"
    else -> "libleap8_core.so"
}
val hostCoreOutput = layout.buildDirectory.file("host-jni/$hostCoreLibName")

val buildHostCoreJni by tasks.registering {
    val sourceFile = file("src/main/cpp/leap8_core.cpp")
    val zigExe = rootProject.file("work/third-party/zig-x86_64-windows-0.15.2/zig.exe")
    val jdkHome = File(System.getProperty("java.home"))
    val outputFileProvider = hostCoreOutput
    inputs.file(sourceFile)
    outputs.file(outputFileProvider)
    doLast {
        val outFile = outputFileProvider.get().asFile
        outFile.parentFile.mkdirs()
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val jniInclude = File(jdkHome, "include")
        val jniPlatformInclude = File(
            jniInclude,
            when {
                isWindows -> "win32"
                System.getProperty("os.name").lowercase().contains("mac") -> "darwin"
                else -> "linux"
            }
        )
        val cmd = if (isWindows && zigExe.exists()) {
            listOf(
                zigExe.absolutePath,
                "c++",
                "-target",
                "x86_64-windows-gnu",
                "-shared",
                "-std=c++20",
                "-O3",
                "-I",
                jniInclude.absolutePath,
                "-I",
                jniPlatformInclude.absolutePath,
                sourceFile.absolutePath,
                "-o",
                outFile.absolutePath
            )
        } else {
            val cxx = System.getenv("CXX") ?: "clang++"
            listOf(
                cxx,
                "-shared",
                "-fPIC",
                "-std=c++20",
                "-O3",
                "-I",
                jniInclude.absolutePath,
                "-I",
                jniPlatformInclude.absolutePath,
                sourceFile.absolutePath,
                "-o",
                outFile.absolutePath
            )
        }
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("Failed to compile host leap8_core JNI ($exitCode):\n$output")
        }
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(buildHostCoreJni)
    systemProperty("leap8.core.dll.path", hostCoreOutput.get().asFile.absolutePath)
}

dependencies {
    implementation(project(":llama-runtime"))
    implementation(files("libs/sherpa-onnx-1.13.2.aar"))
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.05.01"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    if (findProject(":npu-qairt") != null) {
        implementation(project(":npu-qairt"))
    }
}
