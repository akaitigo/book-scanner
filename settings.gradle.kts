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

rootProject.name = "book-scanner"
include(":app")
include(":core-contracts")
include(":core-session")
include(":pdf-writer")
include(":vision")
include(":engine-production")
