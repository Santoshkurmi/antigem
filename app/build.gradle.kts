plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.gemini"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.gemini"
        minSdk = 24
        targetSdk = 36
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

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
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
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

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

  // Image Loading (Coil for Markdown Images)
  implementation("io.coil-kt:coil-compose:2.7.0")

  // Native Android JLaTeXMath / LaTeX Renderer (Pure Android Canvas, Zero WebViews)
  implementation("io.noties.markwon:ext-latex:4.6.2")
  implementation("ru.noties:jlatexmath-android:0.2.0")

  // Pure Java/Kotlin SSH Client (Persistent Termux SSH connection pool)
  implementation("com.github.mwiede:jsch:0.2.20")

  // HTML Parser & Web Content Scraper (Free Web Search & Webpage Reader)
  implementation("org.jsoup:jsoup:1.18.3")

  // Symja Computer Algebra System (CAS) - Pure Symbolic & Numeric Math Engine
  implementation("org.matheclipse:matheclipse-core:3.2.0")

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}

