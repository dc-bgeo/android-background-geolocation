pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // The closed engine AAR ships inside this package until phase 2 puts it
        // on Maven Central.
        maven { url = uri("${rootDir}/libs") }
        google()
        mavenCentral()
    }
}
rootProject.name = "bgeo-android-sdk"
include(":sdk")
