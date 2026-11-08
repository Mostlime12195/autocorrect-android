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
rootProject.name = "autocorrect-android"
include(":engine")
// ENGINE_ONLY=1 skips the Android app (for hosts without an Android SDK, e.g. the arm64 dev VPS)
if (System.getenv("ENGINE_ONLY") == null) include(":app")
