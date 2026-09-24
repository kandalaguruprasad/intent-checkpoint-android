// Standalone build so the platform-independent engine can be built and tested
// without the Android SDK: `../gradlew -p intent-core test` from android/.
// The Android app consumes it as a composite build (see android/settings.gradle).
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "intent-core"
