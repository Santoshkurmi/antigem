plugins {
    id("com.android.application") version "9.5.1"
    // not used: pins the Kotlin Gradle plugin to the AntiGem build's version (2.3.20), which is in the Gradle cache
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
}

android {
    namespace = "ai.antigem.netcaptest"
    compileSdk = 37
    compileSdkMinor = 1
    defaultConfig {
        applicationId = "ai.antigem.netcaptest"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}

// the same library versions as the AntiGem build (they are in the Gradle cache)
configurations.all {
    resolutionStrategy {
        force("com.squareup.okio:okio:3.18.2", "com.squareup.okio:okio-jvm:3.18.2")
        force("org.jetbrains.kotlin:kotlin-stdlib:2.3.20")
    }
    // empty since Kotlin 1.8 (their classes are in kotlin-stdlib)
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
}
