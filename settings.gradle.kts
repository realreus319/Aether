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
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Aether"
include(":app")
include(":terminal-emulator")
include(":terminal-view")

project(":terminal-emulator").projectDir = file("third_party/termux/terminal-emulator")
project(":terminal-view").projectDir = file("third_party/termux/terminal-view")
