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
        maven { url = uri("https://jfrog.anythinktech.com/artifactory/overseas_sdk") }
    }
}

rootProject.name = "AppTimeLimiter"
include(":app")
include(":xposed-stubs")
