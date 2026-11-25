// ---------------------------------------------------------------------------------------------------- version.json
// version.json has the stable release at the top level and, for testers, a beta in "beta" (version_code, version_name
// like 2.0.4-beta.1, changelog). Every release, beta or stable, takes the next version_code: the biggest one in the
// file + 1. A build makes the newest of the two (the higher version_code): the beta while it is newer, else the stable
// release (an older beta block just stays in the file and nobody gets it). Installed stable apps read only the top
// level; a beta app reads both and updates to the newer one (data/updater/AppUpdateManager.kt). The same rule names the
// APKs and the GitHub release (scripts/release_version.py) and gives the bridge its version (agy_ide_bridge/pkg/buildinfo).
// The file's shape is checked here, when the build is configured; its version_codes against its git history by the
// checkVersionJson task, before every build.
val versionFile = rootProject.file("version.json")

class ReleaseVersion(val code: Int, val name: String, val isBeta: Boolean)

fun versionJsonError(message: String): Nothing = throw GradleException("version.json: $message")

fun shown(value: Any?): String = if (value is String) "\"$value\"" else value.toString()

/** The numbers of "2.0.4", or of the "2.0.4" in "2.0.4-beta.1". */
fun versionNumbers(name: String): List<Int> = name.substringBefore('-').split('.').map { it.toInt() }

fun compareNumbers(a: List<Int>, b: List<Int>): Int =
    a.zip(b).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: a.size.compareTo(b.size)

/** Checks one release of version.json (the top level, or the "beta" block) and reads its version. */
fun readRelease(json: Map<*, *>, beta: Boolean): ReleaseVersion {
    val where = if (beta) " in \"beta\"" else ""
    val allowed = if (beta) listOf("version_code", "version_name", "changelog")
    else listOf("version_code", "version_name", "min_supported_version_code", "changelog", "is_critical", "beta")
    val unknown = json.keys.map { it.toString() }.filter { it !in allowed }
    if (unknown.isNotEmpty()) versionJsonError(
        "unknown ${unknown.joinToString { "\"$it\"" }}$where (a typo? the keys are ${allowed.joinToString()})")

    val code = json["version_code"]
    if (code !is Long || code !in 1L..2_100_000_000L) versionJsonError(
        if (code == null) "\"version_code\" is missing$where"
        else "\"version_code\"$where must be a whole number without quotes, not ${shown(code)}")

    val name = json["version_name"]
    val pattern = if (beta) Regex("""\d+\.\d+\.\d+-beta\.[1-9]\d*""") else Regex("""\d+\.\d+\.\d+""")
    if (name !is String || !pattern.matches(name)) versionJsonError(
        if (name == null) "\"version_name\" is missing$where"
        else "\"version_name\"$where must look like ${if (beta) "2.0.4-beta.1" else "2.0.4"}, not ${shown(name)}")

    val changelog = json["changelog"]
    if (changelog !is List<*> || changelog.isEmpty()) versionJsonError(
        if (changelog == null) "\"changelog\" is missing$where"
        else "\"changelog\"$where must be a list of lines, like [\"Fixed X\", \"Added Y\"]")
    changelog.forEachIndexed { i, line ->
        if (line !is String || line.isBlank()) versionJsonError("line ${i + 1} of \"changelog\"$where must be text, not ${shown(line)}")
    }

    if (!beta) {
        val min = json["min_supported_version_code"]
        if (min != null && (min !is Long || min < 0 || min > code)) versionJsonError(
            "\"min_supported_version_code\" must be a whole number from 0 to version_code ($code), not ${shown(min)}")
        val critical = json["is_critical"]
        if (critical != null && critical !is Boolean) versionJsonError("\"is_critical\" must be true or false, not ${shown(critical)}")
    }
    return ReleaseVersion(code.toInt(), name, beta)
}

val versionData: Map<*, *> = run {
    if (!versionFile.exists()) versionJsonError("not found at ${versionFile.path}")
    val parsed = try {
        StrictJson.parse(versionFile.readText())
    } catch (e: IllegalArgumentException) {
        versionJsonError(e.message ?: "not valid JSON")
    }
    parsed as? Map<*, *> ?: versionJsonError("must be one JSON object, { … }")
}
val stableRelease = readRelease(versionData, beta = false)
val betaRelease: ReleaseVersion? = when (val block = versionData["beta"]) {
    null -> null
    is Map<*, *> -> readRelease(block, beta = true)
    else -> versionJsonError("\"beta\" must be a block like { \"version_code\": …, \"version_name\": …, \"changelog\": […] }, not ${shown(block)}")
}

/** What this build makes: the newer of the stable release and the beta. */
val appRelease: ReleaseVersion = betaRelease?.let { beta ->
    if (beta.code == stableRelease.code) versionJsonError(
        "the beta and the stable release both have version_code ${beta.code}: every release takes its own number, the biggest one in the file + 1")
    if (beta.code < stableRelease.code) stableRelease
    else if (compareNumbers(versionNumbers(beta.name), versionNumbers(stableRelease.name)) <= 0) versionJsonError(
        "the beta ${beta.name} is newer than the stable release ${stableRelease.name} (version_code ${beta.code} > ${stableRelease.code}), " +
            "so it must be a beta of a later version than ${stableRelease.name}")
    else beta
} ?: stableRelease

val appVersionCode = appRelease.code
val appVersionName = appRelease.name
val isBetaBuild = appRelease.isBeta

// The IDE bridge (Go) reports the same version as the app: its build embeds a copy of version.json, refreshed here on
// every Gradle build so the two can never differ and nobody has to remember it (it picks the newer release the same way).
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

// The CPU the APK is built for. The normal build is arm64 (phones); `-Pabi=x86_64` makes the x86_64 one (emulators,
// Intel/AMD Chromebooks). The local environment has to match it: the bridge (GOARCH), the bundled glibc
// (src/abi/<abi>/assets) and the native libraries packaged.
val targetAbi = (findProperty("abi") as String?)?.trim()?.ifEmpty { null } ?: "arm64"
require(targetAbi == "arm64" || targetAbi == "x86_64") { "-Pabi must be arm64 or x86_64, not $targetAbi" }
val isX86 = targetAbi == "x86_64"

// The rooted Ubuntu chroot runs a Linux build of the bridge (~4 MB), so the normal arm64 release leaves it out and the
// chroot one carries it: `-Pchroot=true` (AntiGem-v<version>-chroot.apk). The x86_64 APK always has it (there is one
// x86_64 APK). Without the flag debug builds have it and release builds don't; `-Pchroot=false` leaves it out of debug
// builds too (to see the normal build's "Get the chroot build" page).
val chrootProp = (findProperty("chroot") as String?)?.trim()?.lowercase()?.ifEmpty { null }
require(chrootProp == null || chrootProp == "true" || chrootProp == "false") { "-Pchroot must be true or false, not $chrootProp" }
fun hasChroot(buildType: String): Boolean = isX86 || when (chrootProp) {
    "true" -> true
    "false" -> false
    else -> buildType == "debug"
}
// the APK's name suffix in a release: the in-app updater downloads the file of its own kind
val apkSuffix = when {
    isX86 -> "-x86_64"
    chrootProp == "true" -> "-chroot"
    else -> ""
}

android {
    namespace = "ai.antigem"
    compileSdk = 37
    compileSdkMinor = 1
    defaultConfig {
        applicationId = "ai.antigem"
        minSdk = 24
        targetSdk = 28
        versionCode = appVersionCode
        versionName = appVersionName
        // The arm64 build's local environment (glibc, proot, IDE bridge, bootstrap) is arm64 only, but armeabi-v7a is
        // kept so 32-bit ARM phones can install the app and use server mode (bridge on a PC); x86/x86_64 copies of
        // dependency libraries stay out of it. The x86_64 build has only x86_64 ones.
        ndk { abiFilters += if (isX86) listOf("x86_64") else listOf("arm64-v8a", "armeabi-v7a") }
        // "arm64" or "x86_64": picks the glibc loader and the bundled glibc at run time
        buildConfigField("String", "TARGET_ABI", "\"$targetAbi\"")
        // "", "-chroot" or "-x86_64": the release file the in-app updater downloads (AntiGem-v<version><suffix>.apk)
        buildConfigField("String", "APK_SUFFIX", "\"$apkSuffix\"")
        // a beta build (version.json's beta is newer than its stable release): its updater follows the betas too
        buildConfigField("boolean", "IS_BETA", "$isBetaBuild")
    }

    // the glibc runtime of this build's CPU (assets/glibc-min-<abi>.tar.xz)
    sourceSets.getByName("main").assets.srcDir("src/abi/$targetAbi/assets")
    // a beta release is "AntiGem Beta" on the home screen (src/beta/res); debug builds stay "AntiGem Dev"
    if (isBetaBuild) sourceSets.getByName("release").res.srcDir("src/beta/res")

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
            // the Linux bridge for the Ubuntu chroot is packed (see hasChroot)
            buildConfigField("boolean", "HAS_CHROOT", "${hasChroot("debug")}")
        }
        release {
            buildConfigField("int", "BRIDGE_PORT", "1236")
            buildConfigField("int", "HUB_PORT", "1237")
            buildConfigField("int", "BROWSER_MCP_PORT", "8767")
            buildConfigField("boolean", "HAS_CHROOT", "${hasChroot("release")}")
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
        // antiGem, antiGem-chroot or antiGem-x86_64
        archivesName.set("antiGem$apkSuffix")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Backports java.nio.file / java.time APIs (e.g. StandardOpenOption, used by commons-compress
        // ZipFile) to minSdk 24 — without it the app crashes on Android 7.0/7.1 (API 24/25), see issue #85
        isCoreLibraryDesugaringEnabled = true
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
  // Home-screen widgets (chats, quick actions, one chat, scripts): Compose-style code drawn as RemoteViews
  implementation("androidx.glance:glance-appwidget:1.2.0")
  implementation("androidx.glance:glance-material3:1.2.0")

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

  // Shizuku (Settings → ADB Connect → Shizuku): shell-user commands without adb pairing
  implementation("dev.rikka.shizuku:api:13.1.5")
  implementation("dev.rikka.shizuku:provider:13.1.5")

  // Native Android JLaTeXMath / LaTeX Renderer (Pure Android Canvas, Zero WebViews)
  implementation("io.noties.markwon:ext-latex:4.6.2")
  implementation("ru.noties:jlatexmath-android:0.2.0")

  // Pure Java/Kotlin SSH Client (Persistent Termux SSH connection pool)
  implementation("com.github.mwiede:jsch:0.2.20")
  implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

  // HTML Parser & Web Content Scraper (Free Web Search & Webpage Reader)
  implementation("org.jsoup:jsoup:1.18.3")

  // Sora Editor (Industry standard Android Code Editor)
  implementation("io.github.Rosemoe.sora-editor:editor:0.23.6")
  implementation("io.github.Rosemoe.sora-editor:language-textmate:0.23.6")
  implementation("io.github.Rosemoe.sora-editor:language-java:0.23.6")

  // Archive and Compression Utilities (Supports tar, xz, ar, deb extraction)
  implementation("org.apache.commons:commons-compress:1.26.1")
  implementation("org.tukaani:xz:1.9")
  // Core library desugaring runtime: backports the java.nio.file / java.time APIs used above
  // (commons-compress ZipFile needs StandardOpenOption, API 26+) down to minSdk 24 (issue #85)
  coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

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
  environment("GOARCH", if (isX86) "amd64" else "arm64")
  if (isX86) {
    // Go links android/amd64 only through cgo (android/arm64 links on its own): the NDK's clang does it, from
    // ANDROID_NDK_HOME (set on GitHub's runners) or the newest NDK in the SDK
    val host = if (System.getProperty("os.name").startsWith("Mac")) "darwin-x86_64" else "linux-x86_64"
    val sdkDir = androidComponents.sdkComponents.sdkDirectory.get().asFile
    val ndkRoots = listOfNotNull(System.getenv("ANDROID_NDK_HOME"), System.getenv("ANDROID_NDK_LATEST_HOME")).map(::File) +
      (sdkDir.resolve("ndk").listFiles()?.sortedByDescending { it.name } ?: emptyList())
    val clang = ndkRoots.map { File(it, "toolchains/llvm/prebuilt/$host/bin/x86_64-linux-android24-clang") }.firstOrNull { it.exists() }
      ?: throw GradleException("The x86_64 build needs the Android NDK (Go links the bridge with its clang): install one in $sdkDir/ndk or set ANDROID_NDK_HOME")
    environment("CGO_ENABLED", "1")
    environment("CC", clang.absolutePath)
  } else {
    environment("CGO_ENABLED", "0")
  }
  commandLine("go", "build", "-ldflags=-s -w", "-o", rawFile.absolutePath, "main.go")
}

// The rooted Ubuntu chroot runs a plain Linux build of the bridge: the Android one asks for Android's linker
// (/system/bin/linker64), which a chroot does not have. Static (no cgo), so it runs on any glibc or musl rootfs.
val agyIdeBridgeLinuxRaw = layout.buildDirectory.file("intermediates/agyIdeBridge/agy_ide_bridge_linux")
val buildAgyIdeBridgeLinux = tasks.register<Exec>("buildAgyIdeBridgeLinux") {
  dependsOn(syncBridgeVersion)
  val bridgeSourceDir = layout.projectDirectory.dir("../agy_ide_bridge")
  val rawFile = agyIdeBridgeLinuxRaw.get().asFile

  doFirst {
    rawFile.parentFile.mkdirs()
  }

  workingDir = bridgeSourceDir.asFile
  environment("GOOS", "linux")
  environment("GOARCH", if (isX86) "amd64" else "arm64")
  environment("CGO_ENABLED", "0")
  commandLine("go", "build", "-ldflags=-s -w", "-o", rawFile.absolutePath, "main.go")
}

// It is packed only into the builds with the Ubuntu chroot (hasChroot): from each variant's own generated folder
// (LinuxBridgeAssetTask, androidComponents below), so a build without it can't pick up a copy. Builds before that
// wrote it into src/main/assets/bin, where every build would package it: removed.
val deleteLegacyLinuxBridgeAsset = tasks.register<Delete>("deleteLegacyLinuxBridgeAsset") {
  delete(layout.projectDirectory.file("src/main/assets/bin/agy_ide_bridge_linux.xz"))
}

val compressAgyIdeBridge = tasks.register<Exec>("compressAgyIdeBridge") {
  dependsOn(buildAgyIdeBridge)
  val binDir = layout.projectDirectory.dir("src/main/assets/bin").asFile
  val rawFile = agyIdeBridgeRaw.get().asFile
  // declared, so Gradle knows the asset changed: without it mergeDebugAssets could package the bridge of an earlier
  // build (seen when switching -Pabi); unchanged bridge bytes skip the compression
  inputs.file(rawFile)
  outputs.file(File(binDir, "agy_ide_bridge.xz"))

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
  dependsOn(compressAgyIdeBridge, deleteLegacyLinuxBridgeAsset, copyScriptsToAssets)
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

/**
 * The Linux build of the bridge for the Ubuntu chroot, xz-compressed into <outputDir>/bin/agy_ide_bridge_linux.xz (the
 * app unpacks it in ChrootManager.linuxBridgeFile). Only the variants with the chroot get it (hasChroot).
 */
abstract class LinuxBridgeAssetTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val rawBridge: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun compress() {
        val bin = outputDir.get().asFile.resolve("bin")
        bin.deleteRecursively()
        bin.mkdirs()
        val out = File(bin, "agy_ide_bridge_linux.xz")
        // preset 6 with an 8 MB dictionary, like the Android bridge: little memory to unpack
        val xz = ProcessBuilder("xz", "-6", "-T1", "-c", rawBridge.get().asFile.absolutePath)
            .redirectOutput(out)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        if (xz.waitFor() != 0) throw GradleException("xz failed to compress ${rawBridge.get().asFile}")
    }
}

// Changelog: the app's "What's new" page is built from the git history of version.json, one entry per version_code
// (the newest commit of each wins, dated by that commit). The version.json being built is used for its own version, so
// an uncommitted bump shows up too. Needs the full history: CI checks out with fetch-depth 0. Without git (or with a
// shallow clone) only the current version is listed. Betas ("beta" in version.json) are listed only in a beta build,
// and only the ones newer than its stable release (the current beta round): stable users never see them.
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
        val betas = LinkedHashMap<Int, Entry>()
        fun betaOf(json: Map<String, Any?>, date: String?): Entry? {
            val beta = json["beta"] as? Map<*, *> ?: return null
            val code = (beta["version_code"] as? Number)?.toInt() ?: return null
            return Entry(code, beta["version_name"]?.toString() ?: "?", date, changesOf(beta["changelog"]))
        }

        // newest first: the first commit seen for a version_code is its latest edit
        val log = git("log", "--format=%H %cI", "--", "version.json")
        if (log == null) logger.warn("Changelog: git history unavailable, listing only the current version")
        else if (git("rev-parse", "--is-shallow-repository")?.trim() == "true") logger.warn("Changelog: shallow clone, older versions may be missing")
        log.orEmpty().lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val sha = line.substringBefore(' ')
            val date = line.substringAfter(' ').take(10)
            val json = git("show", "$sha:version.json")?.let(::parse) ?: return@forEach
            betaOf(json, date)?.let { if (it.code !in betas) betas[it.code] = it }
            val code = (json["version_code"] as? Number)?.toInt() ?: return@forEach
            if (code in byCode) return@forEach
            byCode[code] = Entry(code, json["version_name"]?.toString() ?: "?", date, changesOf(json["changelog"]))
        }

        parse(versionJson.get().asFile.readText())?.let { json ->
            val code = (json["version_code"] as? Number)?.toInt() ?: return@let
            byCode[code] = Entry(code, json["version_name"]?.toString() ?: "?", byCode[code]?.date, changesOf(json["changelog"]))
            betaOf(json, null)?.let { betas[it.code] = it.copy(date = betas[it.code]?.date) }
            // the betas after the stable release: none in a stable build, the betas of this round in a beta build
            betas.values.filter { it.code > code }.forEach { byCode[it.code] = it }
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

// App languages: every text the app shows is a key in src/main/assets/i18n/en.json (English, the source). This writes
// ai.antigem.i18n.L from it: one accessor per key (L.settings.dialog.themes), numbered in the file's order. English is
// the literal inside the accessor, so in English nothing is looked up; another language is one array read
// (ai.antigem.i18n.I18n). Keys: segments of letters and digits, starting lowercase, at least two ("area.name").
// A value is a text ("Show more ({left} left)": {left} becomes a parameter) or plural forms
// ({"one": "{count} file", "other": "{count} files"}: a function taking count first). The same en.json ships in the
// APK: the language editor, language_mcp and the loader of other languages read the keys from it.
// Adding texts: scripts/i18n/extract_strings.py (see its header).
abstract class GenerateStringsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val englishJson: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    private fun kotlinString(s: String) = "\"" + s
        .replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    private class Node(val name: String) {
        val children = LinkedHashMap<String, Node>()
        val leaves = ArrayList<String>()
    }

    @TaskAction
    fun generate() {
        val file = englishJson.get().asFile
        fun fail(message: String): Nothing = throw GradleException("${file.name}: $message")
        val root = StrictJson.parse(file.readText()) as? Map<*, *> ?: fail("must be a JSON object")
        val strings = root["strings"] as? Map<*, *> ?: fail("needs a \"strings\" object")
        val keywords = setOf("as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
            "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof",
            "val", "var", "when", "while")
        val segment = Regex("[a-z][A-Za-z0-9]*")
        val placeholder = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)\\}")
        val forms = listOf("zero", "one", "two", "few", "many", "other")

        val top = Node("L")
        val members = LinkedHashMap<String, String>() // full key -> member source
        val fingerprintSource = StringBuilder()
        strings.entries.forEachIndexed { index, (rawKey, value) ->
            val key = rawKey.toString()
            val parts = key.split('.')
            if (parts.size < 2) fail("\"$key\": a key needs an area and a name, like \"chat.send\"")
            parts.forEach { p ->
                if (!segment.matches(p)) fail("\"$key\": \"$p\" must start with a lowercase letter and have only letters and digits")
                if (p in keywords) fail("\"$key\": \"$p\" is a Kotlin keyword or reserved here, pick another name")
            }
            var node = top
            parts.dropLast(1).forEach { p ->
                if (p in node.leaves) fail("\"$key\": \"$p\" is already a text, it can't also hold texts")
                node = node.children.getOrPut(p) { Node(p) }
            }
            val leaf = parts.last()
            if (leaf in node.children) fail("\"$key\": \"$leaf\" already holds texts, it can't also be a text")
            node.leaves += leaf
            fun params(text: String) = placeholder.findAll(text).map { it.groupValues[1] }.distinct().toList().onEach {
                if (it in keywords) fail("\"$key\": the placeholder {$it} is a Kotlin keyword, pick another name")
            }
            fun call(fn: String, head: String, names: List<String>): String {
                val sig = names.joinToString(", ") { "$it: Any?" }
                val args = names.joinToString(", ") { "${kotlinString(it)}, $it" }
                return if (names.size <= 3) "fun $leaf($sig): String = I18n.$fn($head, $args)"
                else "fun $leaf($sig): String = I18n.${fn}v($head, arrayOf(${names.joinToString(", ") { kotlinString(it) }}), arrayOf(${names.joinToString(", ")}))"
            }
            members[key] = when (value) {
                is String -> {
                    fingerprintSource.append(key).append('\n')
                    val names = params(value)
                    if (names.isEmpty()) "val $leaf: String get() = I18n.s($index, ${kotlinString(value)})"
                    else call("f", "$index, ${kotlinString(value)}", names)
                }
                is Map<*, *> -> {
                    fingerprintSource.append(key).append("#\n")
                    value.keys.forEach { if (it !in forms) fail("\"$key\": plural forms are ${forms.joinToString()}, not \"$it\"") }
                    val other = value["other"] as? String ?: fail("\"$key\": plural forms need \"other\"")
                    val one = value["one"] as? String ?: other
                    val names = value.values.flatMap { params(it.toString()) }.distinct().filter { it != "count" }
                    val sig = (listOf("count: Int") + names.map { "$it: Any?" }).joinToString(", ")
                    val args = names.joinToString("") { ", ${kotlinString(it)}, $it" }
                    "fun $leaf($sig): String = I18n.p($index, count, ${kotlinString(one)}, ${kotlinString(other)}$args)"
                }
                else -> fail("\"$key\": a text or an object of plural forms, not $value")
            }
        }

        // FNV-1a over the key list: changes when a key is added, removed, moved or changes kind
        var h = -0x340d631b7bdddcdbL
        fingerprintSource.toString().forEach { c -> h = (h xor c.code.toLong()) * 0x100000001b3L }
        val fingerprint = "%016x".format(h)
        val out = StringBuilder()
        out.append("package ai.antigem.i18n\n\n")
        out.append("// Generated by the generateStrings Gradle task from src/main/assets/i18n/en.json. Do not edit.\n")
        out.append("@Suppress(\"ClassName\", \"unused\", \"FunctionName\", \"ObjectPropertyName\")\n")
        fun write(node: Node, path: String, indent: String) {
            node.leaves.forEach { out.append(indent).append(members.getValue(if (path.isEmpty()) it else "$path.$it")).append('\n') }
            node.children.values.forEach { child ->
                out.append(indent).append("object ${child.name} {\n")
                write(child, if (path.isEmpty()) child.name else "$path.${child.name}", "$indent    ")
                out.append(indent).append("}\n")
            }
        }
        out.append("object L {\n")
        out.append("    /** How many texts the app has (the size of a language's table). */\n")
        out.append("    const val COUNT: Int = ${strings.size}\n")
        out.append("    /** Changes when a key is added, removed, moved or changes between text and plural (cached tables are then rebuilt). */\n")
        out.append("    const val FINGERPRINT: String = \"$fingerprint\"\n")
        write(top, "", "    ")
        out.append("}\n")

        val target = outputDir.get().asFile.resolve("ai/antigem/i18n/L.kt")
        outputDir.get().asFile.walkBottomUp()
            .filter { it != target && it != outputDir.get().asFile && !target.startsWith(it) }
            .forEach { it.delete() }
        target.parentFile.mkdirs()
        val source = out.toString()
        if (!target.exists() || target.readText() != source) target.writeText(source)
    }
}

val generateStrings = tasks.register<GenerateStringsTask>("generateStrings") {
    englishJson.set(layout.projectDirectory.file("src/main/assets/i18n/en.json"))
    outputDir.set(layout.buildDirectory.dir("generated/i18n/kotlin"))
}

// Checks version.json's version_codes against every version the file has had in git, so a release can't reuse a
// number or go below one (the file's shape is checked when the build is configured, at the top of this file):
//  - a new version_code must be higher than every one used before, betas included
//  - a version_code used before keeps its version_name (re-pushing the same version, e.g. to fix its changelog, is fine)
//  - a version_name already released (its tag v<name> exists, made by the release workflow) keeps its version_code:
//    its GitHub release would be overwritten. Only tags: a push whose release build failed was never released.
// Needs the full history: CI checks out with fetch-depth 0. Without git it is skipped, a shallow clone checks what
// history there is.
abstract class CheckVersionJsonTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val versionJson: RegularFileProperty

    @get:Internal
    abstract val repoDir: DirectoryProperty

    private fun git(stdin: String?, vararg args: String): ByteArray? = try {
        val process = ProcessBuilder(listOf("git", "-C", repoDir.get().asFile.absolutePath) + args)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        // written on its own thread: git answers while it reads, so both pipes have to move at once
        val writer = Thread { runCatching { process.outputStream.use { out -> stdin?.let { out.write(it.toByteArray()) } } } }
        writer.start()
        val out = process.inputStream.readBytes()
        writer.join()
        if (process.waitFor() == 0) out else null
    } catch (_: Exception) {
        null
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(text: String): Map<String, Any?>? = try {
        groovy.json.JsonSlurper().parseText(text) as? Map<String, Any?>
    } catch (_: Exception) {
        null
    }

    /** (version_code, version_name) of the top level and of the "beta" block of one version.json. */
    private fun releasesOf(json: Map<String, Any?>): List<Pair<Int, String>> =
        listOfNotNull<Map<*, *>>(json, json["beta"] as? Map<*, *>).mapNotNull { r ->
            val code = (r["version_code"] as? Number)?.toInt() ?: return@mapNotNull null
            val name = r["version_name"] as? String ?: return@mapNotNull null
            code to name
        }

    private fun fail(message: String): Nothing = throw GradleException("version.json: $message")

    @TaskAction
    fun check() {
        val current = parse(versionJson.get().asFile.readText()) ?: return // its shape was checked at configuration
        val log = git(null, "log", "--format=%H", "--", "version.json")?.toString(Charsets.UTF_8)
        if (log == null) {
            logger.warn("version.json: git history unavailable, version_codes not checked against older versions")
            return
        }
        if (git(null, "rev-parse", "--is-shallow-repository")?.toString(Charsets.UTF_8)?.trim() == "true")
            logger.warn("version.json: shallow clone, version_codes checked only against the history it has")

        // every (version_code, version_name) the file has had, read in one go: `git cat-file --batch` answers each
        // "<commit>:version.json" with "<id> blob <size>\n<content>\n" ("<name> missing\n" where it didn't exist)
        val namesOfCode = HashMap<Int, MutableSet<String>>()
        val request = log.lines().filter { it.isNotBlank() }.joinToString("") { "$it:version.json\n" }
        val out = git(request, "cat-file", "--batch") ?: ByteArray(0)
        var i = 0
        while (i < out.size) {
            var eol = i
            while (eol < out.size && out[eol] != '\n'.code.toByte()) eol++
            if (eol == out.size) break
            val header = String(out, i, eol - i, Charsets.UTF_8).split(' ')
            i = eol + 1
            if (header.size != 3 || header[1] != "blob") continue
            val size = header[2].toIntOrNull() ?: break
            if (i + size > out.size) break
            parse(String(out, i, size, Charsets.UTF_8))?.let { json ->
                releasesOf(json).forEach { (code, name) -> namesOfCode.getOrPut(code) { mutableSetOf() } += name }
            }
            i += size + 1
        }

        val mine = releasesOf(current)
        val newest = namesOfCode.keys.maxOrNull()
        val next = ((namesOfCode.keys + mine.map { it.first }).maxOrNull() ?: 0) + 1
        mine.forEach { (code, name) ->
            val what = if ("-beta" in name) "the beta $name" else name
            val used = namesOfCode[code]
            if (used != null && name !in used) fail(
                "$what has version_code $code, which ${used.first()} already used. Every release takes a new number: use $next.")
            if (used == null && newest != null && code < newest) fail(
                "$what has version_code $code, lower than $newest (${namesOfCode.getValue(newest).first()}). " +
                    "Every release takes a new, higher number: use $next.")
            val tagged = git(null, "show", "refs/tags/v$name:version.json")?.toString(Charsets.UTF_8)?.let(::parse)
            val released = tagged?.let(::releasesOf)?.firstOrNull { it.second == name }?.first
            if (released != null && released != code) fail(
                "$name was already released with version_code $released (tag v$name): this release would overwrite it. " +
                    "Give it a new version_name, or put back version_code $released if it is the same release.")
        }
    }
}

val checkVersionJson = tasks.register<CheckVersionJsonTask>("checkVersionJson") {
    versionJson.set(versionFile)
    repoDir.set(rootProject.layout.projectDirectory)
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(checkVersionJson) }

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(generateChangelog, GenerateChangelogTask::outputDir)
        variant.sources.kotlin?.addGeneratedSourceDirectory(generateStrings, GenerateStringsTask::outputDir)

        // Debug builds are their own app, ai.antidev ("AntiGem Dev"), so they install next to the release one. It must
        // have the length of com.termux (10): the Termux packages are rewritten for it byte for byte.
        // TEMPORARY: x86_64 debug builds stay ai.antigem while the x86_64 rootfs is made on the emulator (its backup
        // must have ai.antigem paths). Remove `&& !isX86` once that rootfs is hosted.
        if (variant.buildType == "debug" && !isX86) variant.applicationId.set("ai.antidev")

        val amBuildType = if (variant.buildType == "release") "release" else "debug"
        val termuxAm = tasks.register<TermuxAmAssetTask>("termuxAm${variant.name.replaceFirstChar { it.uppercase() }}") {
            dependsOn(":termux-am:assemble${amBuildType.replaceFirstChar { it.uppercase() }}")
            apkDir.set(project(":termux-am").layout.buildDirectory.dir("outputs/apk/$amBuildType"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(termuxAm, TermuxAmAssetTask::outputDir)

        // the Ubuntu chroot's Linux bridge: only in the builds with the chroot (the -chroot and x86_64 APKs, debug)
        if (hasChroot(variant.buildType ?: "release")) {
            val linuxBridge = tasks.register<LinuxBridgeAssetTask>("linuxBridgeAsset${variant.name.replaceFirstChar { it.uppercase() }}") {
                dependsOn(buildAgyIdeBridgeLinux)
                rawBridge.set(agyIdeBridgeLinuxRaw)
            }
            variant.sources.assets?.addGeneratedSourceDirectory(linuxBridge, LinuxBridgeAssetTask::outputDir)
        }
    }
}

// A strict JSON reader for version.json: unlike a lenient parser it stops at what the phones' org.json or CI's Python
// would refuse or read differently (a key written twice, a comma before } or ], comments, single quotes), and says
// where. Objects are Map<String, Any?>, arrays List<Any?>, whole numbers Long, other numbers Double.
object StrictJson {
    fun parse(text: String): Any? = Reader(text).run {
        space()
        val result = value()
        space()
        if (i < s.length) fail("unexpected text after the end")
        result
    }

    private class Reader(val s: String) {
        var i = 0

        fun fail(message: String): Nothing {
            val before = s.substring(0, minOf(i, s.length))
            val line = before.count { it == '\n' } + 1
            val column = before.length - before.lastIndexOf('\n')
            throw IllegalArgumentException("line $line, column $column: $message")
        }

        fun space() {
            while (i < s.length && s[i] in " \t\r\n") i++
        }

        fun value(): Any? {
            if (i >= s.length) fail("unexpected end of the file")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c.isDigit()) number() else fail("unexpected '$c'")
            }
        }

        fun word(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) fail("unexpected '${s[i]}'")
            i += word.length
            return value
        }

        fun obj(): Map<String, Any?> {
            i++ // {
            val map = LinkedHashMap<String, Any?>()
            space()
            if (i < s.length && s[i] == '}') { i++; return map }
            while (true) {
                space()
                if (i >= s.length) fail("missing '}'")
                if (s[i] == '}') fail("comma before '}'")
                if (s[i] != '"') fail("expected a key in double quotes")
                val at = i
                val key = string()
                if (key in map) { i = at; fail("\"$key\" is written twice") }
                space()
                if (i >= s.length || s[i] != ':') fail("expected ':' after \"$key\"")
                i++
                space()
                map[key] = value()
                space()
                if (i >= s.length) fail("missing '}'")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return map }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        fun array(): List<Any?> {
            i++ // [
            val list = ArrayList<Any?>()
            space()
            if (i < s.length && s[i] == ']') { i++; return list }
            while (true) {
                space()
                if (i < s.length && s[i] == ']') fail("comma before ']'")
                list += value()
                space()
                if (i >= s.length) fail("missing ']'")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return list }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        fun string(): String {
            i++ // "
            val out = StringBuilder()
            while (true) {
                if (i >= s.length) fail("missing closing '\"'")
                val c = s[i]
                when {
                    c == '"' -> { i++; return out.toString() }
                    c == '\\' -> {
                        if (i + 1 >= s.length) fail("missing closing '\"'")
                        when (val e = s[i + 1]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                val hex = s.substring(i + 2, minOf(i + 6, s.length))
                                if (hex.length != 4 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) fail("bad \\u escape")
                                out.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                        i += 2
                    }
                    c < ' ' -> fail("a line break or control character inside a string (write \\n)")
                    else -> { out.append(c); i++ }
                }
            }
        }

        fun number(): Any {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val text = s.substring(start, i)
            if (!Regex("""-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?""").matches(text)) { i = start; fail("bad number '$text'") }
            return text.toLongOrNull() ?: text.toDouble()
        }
    }
}
