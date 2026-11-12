package com.example.gemini.data.run

import android.content.Context
import com.example.gemini.data.ide.RunConfigManager
import com.example.gemini.data.ide.RunConfiguration
import com.example.gemini.data.local.LocalEnvironmentManager
import com.example.gemini.data.preferences.AuthPreferences
import org.json.JSONObject
import java.io.File

/** A ready-made command the Run panel suggests for a folder ("Suggested"); saved to the project only when added. */
data class RunTemplate(
    val name: String,
    val command: String,
    /** Relative to the project folder; null = the project folder itself. */
    val cwd: String? = null,
    val note: String? = null
) {
    fun toConfig() = RunConfiguration(name = name, type = "terminal", command = command, cwd = cwd)
}

data class RunTemplateGroup(
    val kind: String,
    val templates: List<RunTemplate>,
    /** Android templates need the Android Development tools (adev) on this phone. */
    val needsAndroidSetup: Boolean = false
)

/**
 * Finds out what a folder is (Flutter, React Native, Gradle Android app, Node, Python, Go, Rust, make) from its marker
 * files and suggests the usual commands. On this phone Android builds go through `adev` (the Android Development
 * container); on a server or PC the tools are called directly.
 */
object RunTemplates {
    private val APP_ID = Regex("""applicationId\s*=?\s*["']([^"']+)["']""")

    suspend fun detect(context: Context, folder: String): List<RunTemplateGroup> {
        if (folder.isBlank()) return emptyList()
        val remote = AuthPreferences.remoteShell
        val adevReady = remote || File(LocalEnvironmentManager.getBinDir(context), "adev").exists()
        val adev = if (remote) "" else "adev "
        suspend fun read(rel: String) = RunConfigManager.readText("$folder/$rel")

        val groups = mutableListOf<RunTemplateGroup>()
        val pubspec = read("pubspec.yaml")
        val packageJson = read("package.json")?.let { runCatching { JSONObject(it) }.getOrNull() }
        val isReactNative = packageJson != null && listOf("dependencies", "devDependencies").any {
            packageJson.optJSONObject(it)?.has("react-native") == true
        }

        when {
            pubspec != null && Regex("""sdk:\s*flutter""").containsMatchIn(pubspec) -> {
                val appId = appIdOf(read("android/app/build.gradle.kts") ?: read("android/app/build.gradle"))
                val apk = "build/app/outputs/flutter-apk/app-debug.apk"
                groups += RunTemplateGroup(
                    "Flutter",
                    androidApp(
                        build = "${adev}flutter build apk --debug",
                        apk = apk, appId = appId, remote = remote
                    ) + listOf(
                        RunTemplate("Flutter run (hot reload)", "${adev}flutter run", note = "Needs adb connected. Press r to hot reload, R to restart, q to quit."),
                        RunTemplate("Get packages", "${adev}flutter pub get"),
                        RunTemplate("Clean", "${adev}flutter clean")
                    ),
                    needsAndroidSetup = !adevReady
                )
            }
            isReactNative -> {
                val appId = appIdOf(read("android/app/build.gradle") ?: read("android/app/build.gradle.kts"))
                groups += RunTemplateGroup(
                    "React Native",
                    listOf(
                        RunTemplate(
                            "Start Metro", "${adev}npx react-native start --port 8081",
                            note = "Debug builds load their JavaScript from Metro: keep it running in its own tab."
                        )
                    ) + androidApp(
                        build = "${adev}./gradlew assembleDebug --console=plain",
                        apk = "app/build/outputs/apk/debug/app-debug.apk", appId = appId, remote = remote, cwd = "android"
                    ) + listOf(
                        RunTemplate("Install npm packages", "${adev}npm install"),
                        RunTemplate("Clean", "${adev}./gradlew clean", cwd = "android")
                    ),
                    needsAndroidSetup = !adevReady
                )
            }
            (read("settings.gradle.kts") ?: read("settings.gradle")) != null -> {
                val appGradle = read("app/build.gradle.kts") ?: read("app/build.gradle")
                val isAndroid = appGradle != null && (appGradle.contains("com.android.application") || appGradle.contains("android {") || APP_ID.containsMatchIn(appGradle))
                if (isAndroid) {
                    groups += RunTemplateGroup(
                        "Android (Gradle)",
                        androidApp(
                            build = "${adev}./gradlew assembleDebug --console=plain",
                            apk = "app/build/outputs/apk/debug/app-debug.apk", appId = appIdOf(appGradle), remote = remote
                        ) + listOf(RunTemplate("Clean", "${adev}./gradlew clean --console=plain")),
                        needsAndroidSetup = !adevReady
                    )
                } else {
                    groups += RunTemplateGroup(
                        "Gradle",
                        listOf(
                            RunTemplate("Build", "./gradlew build --console=plain"),
                            RunTemplate("Run", "./gradlew run --console=plain"),
                            RunTemplate("Test", "./gradlew test --console=plain")
                        )
                    )
                }
            }
        }

        if (packageJson != null && !isReactNative) {
            val scripts = packageJson.optJSONObject("scripts")
            val list = mutableListOf<RunTemplate>()
            scripts?.let { s -> s.keys().forEach { key -> list += RunTemplate("npm run $key", "npm run $key", note = s.optString(key)) } }
            list += RunTemplate("Install packages", "npm install")
            groups += RunTemplateGroup("Node.js", list)
        }
        if (read("go.mod") != null) {
            groups += RunTemplateGroup(
                "Go",
                listOf(RunTemplate("Run", "go run ."), RunTemplate("Build", "go build ./..."), RunTemplate("Test", "go test ./..."))
            )
        }
        if (read("Cargo.toml") != null) {
            groups += RunTemplateGroup(
                "Rust",
                listOf(RunTemplate("Run", "cargo run"), RunTemplate("Build", "cargo build"), RunTemplate("Test", "cargo test"))
            )
        }
        val python = mutableListOf<RunTemplate>()
        if (read("manage.py") != null) python += RunTemplate("Django server", "python manage.py runserver")
        listOf("main.py", "app.py").firstOrNull { read(it) != null }?.let { python += RunTemplate("Run $it", "python $it") }
        if (read("requirements.txt") != null) python += RunTemplate("Install requirements", "pip install -r requirements.txt")
        if (python.isNotEmpty()) groups += RunTemplateGroup("Python", python)
        if (read("Makefile") != null) groups += RunTemplateGroup("make", listOf(RunTemplate("make", "make")))
        return groups
    }

    private fun appIdOf(gradle: String?): String? = gradle?.let { APP_ID.find(it)?.groupValues?.get(1) }

    /** Build / install / open for an Android app; "open" needs the app's package (applicationId). */
    private fun androidApp(build: String, apk: String, appId: String?, remote: Boolean, cwd: String? = null): List<RunTemplate> {
        val install = "adb install -r $apk"
        val open = appId?.let { "adb shell monkey -p $it -c android.intent.category.LAUNCHER 1 >/dev/null" }
        val installOpen = listOfNotNull(install, open).joinToString(" && ")
        val adbNote = "Needs adb connected to this phone (Settings → ADB Connect)."
        val list = mutableListOf(
            RunTemplate("Build debug APK", build, cwd = cwd),
            RunTemplate(
                if (open != null) "Build, install & open" else "Build & install",
                "$build && $installOpen", cwd = cwd, note = adbNote
            ),
            RunTemplate(if (open != null) "Install & open" else "Install", installOpen, cwd = cwd, note = adbNote)
        )
        // Without adb: Android's package installer opens and the user taps Install
        if (!remote) list += RunTemplate("Install with Android's installer", "termux-open $apk", cwd = cwd, note = "No adb needed: tap Install on the screen that opens.")
        if (appId != null) {
            list += RunTemplate("App log (logcat)", "adb logcat --pid=\$(adb shell pidof -s $appId)", cwd = cwd, note = "Start the app first. Stop ends it.")
        }
        return list
    }
}
