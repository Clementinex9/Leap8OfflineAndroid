pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Leap8Offline"
include(":app")
include(":llama-runtime")

// The proprietary Qualcomm runtime is deliberately optional. Set QAIRT_PATH to
// include the Snapdragon/Genie JNI module; the portable app remains buildable
// and fully usable without it.
if (!System.getenv("QAIRT_PATH").isNullOrBlank() || !System.getenv("HEXAGON_RUNTIME").isNullOrBlank()) {
    include(":npu-qairt")
}
