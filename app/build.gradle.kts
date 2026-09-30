plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.wire)
}

android {
    namespace = "com.example.gemini"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.antigem"
        minSdk = 24
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = "geminiapp123"
            keyAlias = "release"
            keyPassword = "geminiapp123"
        }
    }

    flavorDimensions += "mode"
    productFlavors {
        create("standard") {
            dimension = "mode"
            applicationId = "com.antigem"
        }
        create("termux") {
            dimension = "mode"
            applicationId = "com.termux"
        }
    }

    buildTypes {
        debug {
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
        disable += "ExpiredTargetSdkVersion"
    }

    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    androidResources {
        noCompress += listOf("gz", "tgz", "tar.gz", "tar")
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
        excludes += "*.xsd"
        excludes += "**/*.xsd"
        excludes += "META-INF/INDEX.LIST"
        excludes += "META-INF/io.netty.versions.properties"
        excludes += "META-INF/DEPENDENCIES"
        excludes += "META-INF/LICENSE*"
        excludes += "META-INF/NOTICE*"
      }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Networking and Serialization
  implementation(libs.okhttp)
  implementation(libs.okhttp.sse)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.androidx.datastore.preferences)

  // Image Loading (Coil for Markdown Images and IDE Asset Viewer)
  implementation("io.coil-kt:coil-compose:2.7.0")
  implementation("io.coil-kt:coil-svg:2.7.0")

  // Native Android JLaTeXMath / LaTeX Renderer (Pure Android Canvas, Zero WebViews)
  implementation("io.noties.markwon:ext-latex:4.6.2")
  implementation("ru.noties:jlatexmath-android:0.2.0")

  // Pure Java/Kotlin SSH Client (Persistent Termux SSH connection pool)
  implementation("com.github.mwiede:jsch:0.2.20")

  // HTML Parser & Web Content Scraper (Free Web Search & Webpage Reader)
  implementation("org.jsoup:jsoup:1.18.3")

  // Sora Editor (Industry standard Android Code Editor)
  implementation("io.github.Rosemoe.sora-editor:editor:0.23.6")
  implementation("io.github.Rosemoe.sora-editor:language-textmate:0.23.6")
  implementation("io.github.Rosemoe.sora-editor:language-java:0.23.6")

  // Archive and Compression Utilities (Supports tar, xz, ar, deb extraction)
  implementation("org.apache.commons:commons-compress:1.26.1")
  implementation("org.tukaani:xz:1.9")

  // Termux Terminal Emulator with Native PTY Support (Terminal View is compiled from source)
  implementation("com.github.termux.termux-app:terminal-emulator:v0.118.1")
  implementation("com.google.guava:listenablefuture:1.0")

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Wire Protobuf & gRPC runtime
  implementation(libs.wire.runtime)
  implementation(libs.wire.grpc.client)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}

configurations.all {
  resolutionStrategy {
    force("com.squareup.okhttp3:okhttp:4.12.0")
    force("com.squareup.okhttp3:okhttp-android:4.12.0")
  }
}

wire {
  sourcePath {
    srcDir("src/main/proto")
  }
  kotlin {
    rpcRole = "client"
    rpcCallStyle = "suspending"
  }
}

val buildAgyIdeBridge = tasks.register<Exec>("buildAgyIdeBridge") {
  val binDir = layout.projectDirectory.dir("src/main/assets/bin")
  val bridgeSourceDir = layout.projectDirectory.dir("../agy_ide_bridge")

  doFirst {
    binDir.asFile.mkdirs()
  }

  workingDir = bridgeSourceDir.asFile
  environment("GOOS", "android")
  environment("GOARCH", "arm64")
  environment("CGO_ENABLED", "0")
  commandLine("go", "build", "-ldflags=-s -w", "-o", binDir.file("agy_ide_bridge").asFile.absolutePath, "main.go")
}

tasks.named("preBuild") {
  dependsOn(buildAgyIdeBridge)
}


