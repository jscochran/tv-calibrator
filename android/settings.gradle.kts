// TV Calibrator for Android: an Android port of TV Calibrator by Slidebean
// (https://github.com/slidebean/tv-calibrator), CC BY-NC 4.0.
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
rootProject.name = "TVCalibratorAndroid"
include(":calibrationkit", ":app")
