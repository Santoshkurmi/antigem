// Builds am.apk, the Java code of the Linux system's `am` command, from the termux-am submodule (termux/TermuxAm, left
// unchanged; its own build.gradle, written for AGP 8, isn't used). The one difference from Termux's build is the
// package am sends intents as: ai.antigem. Android refuses com.termux for this app, which broke every Termux command
// that talks to the app (termux-open, termux-wake-lock, termux-setup-storage, the Termux:API commands).
// app/build.gradle.kts copies the result to assets/bin/am.apk; TermuxRelocation puts it in place of Termux's.
// Updating to a new TermuxAm: move the submodule, then check its app/build.gradle for new dependencies or versions.
plugins {
    alias(libs.plugins.android.application)
}

private val termuxAm = rootProject.file("termux-am/app/src/main")

android {
    namespace = "com.termux.termuxam"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.termux.termuxam"
        minSdk = 21
        // as upstream: only affects tests (the apk is never installed), must stay < 28 for the hidden APIs it uses
        targetSdk = 27
        versionCode = 2
        versionName = "0.8.1"
        buildConfigField("String", "TERMUX_PACKAGE_NAME", "\"ai.antigem\"")
    }
    buildFeatures {
        buildConfig = true
    }
    sourceSets {
        named("main") {
            java.srcDir(File(termuxAm, "java"))
            manifest.srcFile(File(termuxAm, "AndroidManifest.xml"))
        }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // as termux-am/app/build.gradle
    implementation("androidx.annotation:annotation:1.7.1")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
