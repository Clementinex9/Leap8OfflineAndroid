import org.gradle.api.tasks.Sync
import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val hexagonRuntime = providers.environmentVariable("HEXAGON_RUNTIME").orNull
if (!hexagonRuntime.isNullOrBlank()) {
    val runtimeRoot = file(hexagonRuntime)
    val requiredLibraries = listOf("libggml-base.so", "libggml.so", "libllama.so", "libggml-cpu.so", "libggml-rpc.so", "libggml-opencl.so", "libggml-hexagon.so", "libggml-htp-v79.so")
    val generatedRuntimeAssets = layout.buildDirectory.dir("hexagon-assets")
    android {
        namespace = "com.leap8.qairt"
        compileSdk = 35
        ndkVersion = "29.0.13113456"
        defaultConfig {
            minSdk = 35
            buildConfigField("String", "QAIRT_VERSION", "\"not-used-hexagon-b11982\"")
            consumerProguardFiles("consumer-rules.pro")
            externalNativeBuild.cmake {
                arguments += "-DHEXAGON_RUNTIME=${runtimeRoot.invariantSeparatorsPath}"
                abiFilters += "arm64-v8a"
            }
        }
        externalNativeBuild.cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.31.6" }
        sourceSets["main"].assets.srcDir(generatedRuntimeAssets)
        buildFeatures.buildConfig = true
        packaging.jniLibs.useLegacyPackaging = true
        // These ABI-incompatible cores are assets loaded only by the NPU worker, never app JNI libraries.
        packaging.jniLibs.excludes += requiredLibraries.map { "**/$it" }
        androidResources.noCompress += listOf("so", "json")
        compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
        kotlinOptions { jvmTarget = "17" }
    }
    val prepareHexagonRuntime by tasks.registering {
        inputs.dir(runtimeRoot.resolve("lib"))
        outputs.dir(generatedRuntimeAssets)
        doLast {
            require(runtimeRoot.resolve("include/llama.h").isFile) { "HEXAGON_RUNTIME must be the extracted official b11982 folder" }
            val output = generatedRuntimeAssets.get().asFile.resolve("npu-runtime/arm64-v8a")
            output.mkdirs()
            val checksums = requiredLibraries.associateWith { name ->
                val source = runtimeRoot.resolve("lib/$name")
                require(source.isFile) { "Official Hexagon runtime missing: $name" }
                val digest = MessageDigest.getInstance("SHA-256")
                source.inputStream().use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                }
                source.copyTo(output.resolve(name), overwrite = true)
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            runtimeRoot.resolve("LICENSE").copyTo(output.resolve("LICENSE"), overwrite = true)
            val manifest = mapOf("release" to "b11982", "soc_model" to "SM8750", "dsp_arch" to "v79", "file_sha256" to checksums)
            output.resolve("runtime-manifest.json").writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(manifest)))
        }
    }
    tasks.named("preBuild").configure { dependsOn(prepareHexagonRuntime) }
    dependencies { testImplementation("junit:junit:4.13.2") }
} else {
val qairtPath = providers.environmentVariable("QAIRT_PATH").orNull
    ?: error("npu-qairt is included only when QAIRT_PATH points to Qualcomm AI Runtime SDK")
val qairtNdkVersion = providers.environmentVariable("QAIRT_NDK_VERSION").orElse("29.0.13113456").get()
val qairtCmakeVersion = providers.environmentVariable("QAIRT_CMAKE_VERSION").orElse("3.31.6").get()
val generatedLibs = layout.buildDirectory.dir("qairt-libs/arm64-v8a")
val qairtVersion = providers.environmentVariable("QAIRT_VERSION").orNull
    ?: file(qairtPath).name.takeIf { it.matches(Regex("[0-9]+(\\.[0-9]+){2,3}")) }
    ?: error("Set QAIRT_VERSION to the exact SDK version when QAIRT_PATH has a custom directory name")
require(qairtVersion.matches(Regex("[0-9]+(\\.[0-9]+){2,3}"))) { "Invalid QAIRT_VERSION" }

android {
    namespace = "com.leap8.qairt"
    compileSdk = 35
    ndkVersion = qairtNdkVersion

    defaultConfig {
        minSdk = 30
        buildConfigField("String", "QAIRT_VERSION", "\"$qairtVersion\"")
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DQAIRT_PATH=$qairtPath"
                abiFilters += "arm64-v8a"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = qairtCmakeVersion
        }
    }
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("qairt-libs"))
    buildFeatures.buildConfig = true
    packaging.jniLibs.useLegacyPackaging = true
    // DSP ELF files are not ARM64 libraries. Android's strip step must not alter them.
    packaging.jniLibs.keepDebugSymbols += "**/libQnnHtpV*Skel.so"
    androidResources.noCompress += listOf("bin", "json")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

val validateQairtBundle by tasks.registering {
    doLast {
        val root = file("src/main/assets/models/essay")
        listOf("genie_config.json", "tokenizer.json", "metadata.json").forEach {
            require(root.resolve(it).isFile && root.resolve(it).length() > 0) { "NPU bundle is missing $it" }
        }
        val metadata = JsonSlurper().parse(root.resolve("metadata.json")) as Map<*, *>
        require(metadata["qairt_version"] == qairtVersion) { "Model qairt_version must match SDK $qairtVersion" }
        require(metadata["prompt_format"] == "chatml") { "NPU model must declare prompt_format: chatml" }
        val dsp = when (metadata["soc_model"]) {
            "SM8750" -> "79"
            "SM8650" -> "75"
            else -> error("NPU bundle must declare soc_model SM8750 or SM8650")
        }
        val htpConfigName = if (dsp == "79") "qualcomm-snapdragon-8-elite.json" else "qualcomm-snapdragon-8-gen3.json"
        val htpConfigFile = file("src/main/assets/htp_config/$htpConfigName")
        require(htpConfigFile.isFile) { "HTP v$dsp configuration is missing" }
        val htpConfig = JsonSlurper().parse(htpConfigFile) as Map<*, *>
        val htpDevices = htpConfig["devices"] as? List<*> ?: error("HTP device configuration is missing")
        val htpDevice = htpDevices.singleOrNull() as? Map<*, *> ?: error("HTP configuration must describe one device")
        require(htpDevice["dsp_arch"] == "v$dsp") { "HTP architecture does not match model target" }
        require((htpDevice["soc_model"] as? Number)?.toInt() == if (dsp == "79") 69 else 57) {
            "HTP SoC id does not match model target"
        }
        listOf("GenieCommon.h", "GenieDialog.h").forEach {
            require(file("$qairtPath/include/Genie/$it").isFile) { "QAIRT header missing: $it" }
        }
        listOf("libGenie.so", "libQnnHtp.so", "libQnnSystem.so", "libQnnHtpV${dsp}Stub.so").forEach {
            require(file("$qairtPath/lib/aarch64-android/$it").isFile) { "QAIRT ARM64 dependency missing: $it" }
        }
        require(file("$qairtPath/lib/hexagon-v$dsp/unsigned/libQnnHtpV${dsp}Skel.so").isFile) { "QAIRT DSP v$dsp skeleton missing" }
        val config = JsonSlurper().parse(root.resolve("genie_config.json")) as Map<*, *>
        val dialog = config["dialog"] as Map<*, *>
        val engine = dialog["engine"] as Map<*, *>
        require((engine["backend"] as Map<*, *>)["type"] == "QnnHtp") { "NPU config must use QnnHtp" }
        val model = engine["model"] as Map<*, *>
        val bins = (model["binary"] as Map<*, *>)["ctx-bins"] as List<*>
        require(bins.isNotEmpty()) { "No NPU context binaries declared" }
        val paths = bins.map {
            val path = (it as String).replace('\\', '/')
            require(!path.startsWith('/') && ':' !in path && path.split('/').none { part -> part.isBlank() || part == "." || part == ".." }) { "Invalid relative context binary path" }
            require(path.endsWith(".bin", ignoreCase = true)) { "Context binary must be .bin: $path" }
            require(root.resolve(path).isFile && root.resolve(path).length() > 0) { "Context binary missing: $path" }
            path
        }
        require(paths.distinct().size == paths.size) { "Duplicate context binary path" }
        val checksums = metadata["file_sha256"] as? Map<*, *> ?: error("metadata.json must provide file_sha256")
        (paths + "tokenizer.json" + "genie_config.json").forEach { path ->
            val expected = checksums[path] as? String ?: error("SHA-256 missing for $path")
            require(expected.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid SHA-256 for $path" }
            val digest = MessageDigest.getInstance("SHA-256")
            root.resolve(path).inputStream().use { stream ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual.equals(expected, ignoreCase = true)) { "SHA-256 mismatch for $path" }
        }
    }
}

val copyQairtLibraries by tasks.registering(Sync::class) {
    dependsOn(validateQairtBundle)
    from(file("$qairtPath/lib/aarch64-android")) {
        include(
            "libGenie.so", "libQnnHtp.so", "libQnnHtpPrepare.so", "libQnnSystem.so",
            "libQnnSaver.so", "libQnnCpu.so", "libQnnGpu.so", "libQnnHtpV*Stub.so",
            "libQnnHtpNetRunExtensions.so", "libQnnHtpV*Calculator.so", "libQnnHtpOptraceProfilingReader.so"
        )
    }
    from(file("$qairtPath/lib")) {
        include("hexagon-v*/unsigned/libQnnHtpV*Skel.so")
    }
    into(generatedLibs)
    eachFile { path = name }
    includeEmptyDirs = false
}

tasks.named("preBuild").configure { dependsOn(copyQairtLibraries) }

dependencies { testImplementation("junit:junit:4.13.2") }
}
