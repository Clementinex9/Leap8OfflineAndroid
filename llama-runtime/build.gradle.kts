plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val skipNativeBuild = providers.environmentVariable("LEAP8_SKIP_NATIVE_BUILD")
    .map { it == "1" }
    .getOrElse(false)

android {
    namespace = "com.leap8.llama"
    compileSdk = 35
    if (!skipNativeBuild) ndkVersion = "29.0.13113456"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        if (!skipNativeBuild) {
            ndk { abiFilters += "arm64-v8a" }
            externalNativeBuild {
                cmake {
                    cppFlags += listOf("-std=c++17", "-O3", "-fvisibility=hidden")
                    arguments += listOf("-DANDROID_STL=c++_shared", "-DCMAKE_BUILD_TYPE=Release")
                }
            }
        }
    }

    if (!skipNativeBuild) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.31.6"
            }
        }
    }

    sourceSets["main"].jniLibs.srcDir("src/main/jniLibs")
    packaging.jniLibs.useLegacyPackaging = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
