import groovy.json.JsonSlurper

val versionFile = rootProject.file("version.json")
val parsedVersion = if (versionFile.exists()) {
    @Suppress("UNCHECKED_CAST")
    JsonSlurper().parseText(versionFile.readText()) as Map<String, Any>
} else {
    mapOf("version_code" to 1, "version_name" to "1.0.0")
}

val appVersionCode = (parsedVersion["version_code"] as? Number)?.toInt() ?: 1
val appVersionName = (parsedVersion["version_name"] as? String) ?: "1.0.0"

// The IDE bridge (Go) reports the same version as the app: its build embeds a copy of version.json, refreshed here on
// every Gradle build so the two can never differ and nobody has to remember it.
val syncBridgeVersion by tasks.registering(Copy::class) {
    from(versionFile)
    into(rootProject.file("agy_ide_bridge/pkg/buildinfo"))
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(syncBridgeVersion) }

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.wire)
}

android {
    namespace = "ai.antigem"
    compileSdk = 36
    defaultConfig {
        applicationId = "ai.antigem"
        minSdk = 24
        targetSdk = 28
        versionCode = appVersionCode
        versionName = appVersionName
        // The local environment (glibc, proot, IDE bridge, bootstrap) is arm64 only, but armeabi-v7a is kept so 32-bit
        // ARM phones can install the app and use server mode (bridge on a PC). x86/x86_64 copies of dependency
        // libraries stay out of the APK
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = "123456"
            keyAlias = "antigem"
            keyPassword = "123456"
        }
    }

    // Local ports differ per app so they can all run side by side: the old com.termux build uses 1234/1235/8765,
    // release (ai.antigem) 1236/1237/8767, debug (ai.antidev, see androidComponents below) 1238/1239/8768
    buildTypes {
        debug {
            buildConfigField("int", "BRIDGE_PORT", "1238")
            buildConfigField("int", "HUB_PORT", "1239")
            buildConfigField("int", "BROWSER_MCP_PORT", "8768")
        }
        release {
            buildConfigField("int", "BRIDGE_PORT", "1236")
            buildConfigField("int", "HUB_PORT", "1237")
            buildConfigField("int", "BROWSER_MCP_PORT", "8767")
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    base {
        archivesName.set("antiGem")
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
      buildConfig = true
      shaders = false
    }

    androidResources {
        noCompress += listOf("gz", "tgz", "tar.gz", "tar", "xz")
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
        // jcodings (pulled in by the editor's TextMate highlighting through joni) ships character-set converters
        // (Big5, EUC-JP, GBK…, 1.1 MB) that regex matching never uses; its Unicode tables (tables/CR_*) stay
        excludes += "tables/Transcoder_*"
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
  implementation("androidx.work:work-runtime-ktx:2.10.0")

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
  implementation(libs.androidx.webkit)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.androidx.datastore.preferences)
  implementation("com.telemetrydeck:kotlin-sdk:6.0.1")

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

// The bridge is built into build/ and shipped xz-compressed (13 MB -> 3.8 MB in the APK); the app unpacks it into the
// terminal's bin/ (LocalServerManager). Preset 6 compresses as well as -9e here, with an 8 MB dictionary, so unpacking
// needs little memory.
val agyIdeBridgeRaw = layout.buildDirectory.file("intermediates/agyIdeBridge/agy_ide_bridge")
val buildAgyIdeBridge = tasks.register<Exec>("buildAgyIdeBridge") {
  dependsOn(syncBridgeVersion) // the bridge embeds the app's version
  val bridgeSourceDir = layout.projectDirectory.dir("../agy_ide_bridge")
  val rawFile = agyIdeBridgeRaw.get().asFile

  doFirst {
    rawFile.parentFile.mkdirs()
  }

  workingDir = bridgeSourceDir.asFile
  environment("GOOS", "android")
  environment("GOARCH", "arm64")
  environment("CGO_ENABLED", "0")
  commandLine("go", "build", "-ldflags=-s -w", "-o", rawFile.absolutePath, "main.go")
}

val compressAgyIdeBridge = tasks.register<Exec>("compressAgyIdeBridge") {
  dependsOn(buildAgyIdeBridge)
  val binDir = layout.projectDirectory.dir("src/main/assets/bin").asFile
  val rawFile = agyIdeBridgeRaw.get().asFile

  doFirst {
    binDir.mkdirs()
    // an uncompressed copy left by older builds would be packaged too
    File(binDir, "agy_ide_bridge").delete()
  }

  commandLine("sh", "-c", "xz -6 -T1 -c \"$1\" > \"$2\"", "sh", rawFile.absolutePath, File(binDir, "agy_ide_bridge.xz").absolutePath)
}

val copyScriptsToAssets = tasks.register<Copy>("copyScriptsToAssets") {
  from(layout.projectDirectory.file("../scripts/backup_bootstrap.sh")) {
    rename { "backup_bootstrap" }
  }
  from(layout.projectDirectory.file("../scripts/backup_chats.sh")) {
    rename { "backup_chats" }
  }
  from(layout.projectDirectory.file("../scripts/restore_chats.sh")) {
    rename { "restore_chats" }
  }
  from(layout.projectDirectory.file("../scripts/backup_projects.sh")) {
    rename { "backup_projects" }
  }
  from(layout.projectDirectory.file("../scripts/restore_projects.sh")) {
    rename { "restore_projects" }
  }
  from(layout.projectDirectory.file("../scripts/git_clone.sh")) {
    rename { "git_clone" }
  }
  into(layout.projectDirectory.dir("src/main/assets/bin"))
}

tasks.named("preBuild") {
  dependsOn(compressAgyIdeBridge, copyScriptsToAssets)
}




// Bundle built-in extensions. The app-owned runtime lives in src/main/assets/browser-runtime.
val bundleBrowserExtensions by tasks.registering(Sync::class) {
    from(rootProject.file("extensions")) {
        exclude("**/test/**", "**/node_modules/**", "**/.storage/**", "**/.antigem-state/**")
    }
    into(layout.buildDirectory.dir("generated/browserExtensionAssets/browser-extensions"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/browserExtensionAssets").get().asFile)
tasks.named("preBuild").configure { dependsOn(bundleBrowserExtensions) }

// Documentation is served by the developer MCP; models do not need the app source tree.
val bundleExtensionDocs by tasks.registering(Sync::class) {
    from(rootProject.file("extension_docs"))
    into(layout.buildDirectory.dir("generated/extensionDocAssets/extension-docs"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/extensionDocAssets").get().asFile)
tasks.named("preBuild").configure { dependsOn(bundleExtensionDocs) }

// The mini app guide: served by the Mini App Developer MCP (miniapp_guide) and copied to ~/mini-apps/docs on the phone.
val bundleMiniAppDocs by tasks.registering(Sync::class) {
    from(rootProject.file("mini_app_docs"))
    into(layout.buildDirectory.dir("generated/miniAppDocAssets/mini-app-docs"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/miniAppDocAssets").get().asFile)
tasks.named("preBuild").configure { dependsOn(bundleMiniAppDocs) }

/**
 * am.apk: the Java code of the Linux system's `am` command (termux-am), built from the termux-am submodule
 * (termux/TermuxAm, see termux-am-build/build.gradle.kts) so that it sends intents as ai.antigem. Termux's own one says
 * com.termux, which Android refuses for this app; TermuxRelocation puts ours in place of it. Each app variant gets the
 * same build type of it (debug app: debug am.apk, release app: release am.apk) as assets/bin/am.apk, from its own
 * generated folder so variants built together can't mix them up.
 */
abstract class TermuxAmAssetTask : DefaultTask() {
    @get:InputFiles
    abstract val apkDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val apk = apkDir.get().asFile.listFiles { f -> f.extension == "apk" }?.singleOrNull()
            ?: throw GradleException("termux-am: no single .apk in ${apkDir.get().asFile}")
        val bin = outputDir.get().asFile.resolve("bin")
        bin.deleteRecursively()
        bin.mkdirs()
        apk.copyTo(File(bin, "am.apk"), overwrite = true)
    }
}

// Changelog: the app's "What's new" page is built from the git history of version.json, one entry per version_code
// (the newest commit of each wins, dated by that commit). The version.json being built is used for its own version, so
// an uncommitted bump shows up too. Needs the full history: CI checks out with fetch-depth 0. Without git (or with a
// shallow clone) only the current version is listed.
abstract class GenerateChangelogTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val versionJson: RegularFileProperty

    @get:Internal
    abstract val repoDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        // the history changes with every commit, which no file input captures; the output is rewritten only if it changed
        outputs.upToDateWhen { false }
    }

    private fun git(vararg args: String): String? = try {
        val process = ProcessBuilder(listOf("git", "-C", repoDir.get().asFile.absolutePath) + args)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val out = process.inputStream.bufferedReader().readText()
        if (process.waitFor() == 0) out else null
    } catch (_: Exception) {
        null
    }

    private fun changesOf(raw: Any?): List<String> = when (raw) {
        is List<*> -> raw.map { it.toString() }
        is String -> raw.split('\n')
        else -> emptyList()
    }.map { it.trim().trimStart('•', '-', '*', ' ').trim() }.filter { it.isNotEmpty() }

    @Suppress("UNCHECKED_CAST")
    private fun parse(text: String): Map<String, Any?>? = try {
        groovy.json.JsonSlurper().parseText(text) as? Map<String, Any?>
    } catch (_: Exception) {
        null
    }

    private fun kotlinString(s: String) = "\"" + s
        .replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    @TaskAction
    fun generate() {
        data class Entry(val code: Int, val name: String, val date: String?, val changes: List<String>)
        val byCode = LinkedHashMap<Int, Entry>()

        // newest first: the first commit seen for a version_code is its latest edit
        val log = git("log", "--format=%H %cI", "--", "version.json")
        if (log == null) logger.warn("Changelog: git history unavailable, listing only the current version")
        else if (git("rev-parse", "--is-shallow-repository")?.trim() == "true") logger.warn("Changelog: shallow clone, older versions may be missing")
        log.orEmpty().lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val sha = line.substringBefore(' ')
            val date = line.substringAfter(' ').take(10)
            val json = git("show", "$sha:version.json")?.let(::parse) ?: return@forEach
            val code = (json["version_code"] as? Number)?.toInt() ?: return@forEach
            if (code in byCode) return@forEach
            byCode[code] = Entry(code, json["version_name"]?.toString() ?: "?", date, changesOf(json["changelog"]))
        }

        parse(versionJson.get().asFile.readText())?.let { json ->
            val code = (json["version_code"] as? Number)?.toInt() ?: return@let
            byCode[code] = Entry(code, json["version_name"]?.toString() ?: "?", byCode[code]?.date, changesOf(json["changelog"]))
        }

        val body = byCode.values.sortedByDescending { it.code }.joinToString(",\n") { e ->
            "        ChangelogEntry(${e.code}, ${kotlinString(e.name)}, ${e.date?.let(::kotlinString) ?: "null"}, listOf(" +
                e.changes.joinToString(", ") { kotlinString(it) } + "))"
        }
        val source = """
            |package ai.antigem.data.changelog
            |
            |// Generated by the generateChangelog Gradle task from the git history of version.json. Do not edit.
            |internal object GeneratedChangelog {
            |    val entries: List<ChangelogEntry> = listOf(
            |$body
            |    )
            |}
            |""".trimMargin()

        val out = outputDir.get().asFile.resolve("ai/antigem/data/changelog/GeneratedChangelog.kt")
        // Anything else in the folder is left over from an older build (e.g. the com/example/gemini one from master)
        // and would be compiled too
        outputDir.get().asFile.walkBottomUp()
            .filter { it != out && it != outputDir.get().asFile && !out.startsWith(it) }
            .forEach { it.delete() }
        out.parentFile.mkdirs()
        if (!out.exists() || out.readText() != source) out.writeText(source)
    }
}

val generateChangelog = tasks.register<GenerateChangelogTask>("generateChangelog") {
    versionJson.set(versionFile)
    repoDir.set(rootProject.layout.projectDirectory)
    outputDir.set(layout.buildDirectory.dir("generated/changelog/kotlin"))
}
androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(generateChangelog, GenerateChangelogTask::outputDir)

        // Debug builds are their own app, ai.antidev ("AntiGem Dev"), so they install next to the release one. It must
        // have the length of com.termux (10): the Termux packages are rewritten for it byte for byte.
        if (variant.buildType == "debug") variant.applicationId.set("ai.antidev")

        val amBuildType = if (variant.buildType == "release") "release" else "debug"
        val termuxAm = tasks.register<TermuxAmAssetTask>("termuxAm${variant.name.replaceFirstChar { it.uppercase() }}") {
            dependsOn(":termux-am:assemble${amBuildType.replaceFirstChar { it.uppercase() }}")
            apkDir.set(project(":termux-am").layout.buildDirectory.dir("outputs/apk/$amBuildType"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(termuxAm, TermuxAmAssetTask::outputDir)
    }
}
