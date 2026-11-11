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

rootProject.name = "PilchardPlayer"
include(":app")
// "Pilchard Live TV": a tiny second app that is only a home screen tile opening Pilchard's live TV.
include(":livetv")
