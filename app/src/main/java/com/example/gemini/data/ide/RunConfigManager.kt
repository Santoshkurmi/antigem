package com.example.gemini.data.ide

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Serializable
data class RunConfiguration(
    val name: String,
    val type: String = "terminal", // "terminal" or "webview"
    val command: String? = null,
    val file: String? = null,
    val cwd: String? = null,
    val env: Map<String, String>? = null,
    val args: List<String>? = null,
    val isDefault: Boolean = false
)

@Serializable
data class LaunchConfigFile(
    val version: String = "1.0",
    val configurations: List<RunConfiguration> = emptyList()
)

object RunConfigManager {
    private const val TAG = "RunConfigManager"

    /** The project's run commands, shared by the IDE's Run button, the Run panel and the agents. */
    const val PROJECT_FILE = ".antigem/run.json"
    /** Where the IDE kept them before; still read when a project has no [PROJECT_FILE] yet. */
    const val LEGACY_FILE = ".ide/launch.json"
    /** Commands for any folder (Run panel → Global), in the Termux home. */
    const val GLOBAL_FILE = ".antigem/global-run.json"

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        // run.json is edited by people and agents: leave unset fields out instead of writing "file": null
        explicitNulls = false
    }

    fun parseConfigurations(text: String): List<RunConfiguration> =
        json.decodeFromString<LaunchConfigFile>(text).configurations

    fun encodeConfigurations(configs: List<RunConfiguration>): String =
        json.encodeToString(LaunchConfigFile(version = "1.0", configurations = configs))

    fun getLaunchFile(projectPath: String): File {
        val file = File(projectPath, PROJECT_FILE)
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
        return file
    }

    fun loadConfigurations(projectPath: String): List<RunConfiguration> {
        val file = listOf(File(projectPath, PROJECT_FILE), File(projectPath, LEGACY_FILE)).firstOrNull { it.isFile }
            ?: return emptyList()
        return try {
            parseConfigurations(file.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing ${file.path}: ${e.message}", e)
            emptyList()
        }
    }

    fun saveConfigurations(projectPath: String, configs: List<RunConfiguration>): Boolean {
        return try {
            getLaunchFile(projectPath).writeText(encodeConfigurations(configs))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving $PROJECT_FILE in $projectPath: ${e.message}", e)
            false
        }
    }

    fun addConfiguration(projectPath: String, config: RunConfiguration): Boolean {
        val current = loadConfigurations(projectPath).toMutableList()
        current.removeAll { it.name.equals(config.name, ignoreCase = true) }
        current.add(config)
        return saveConfigurations(projectPath, current)
    }

    fun getOrCreateLaunchJson(projectPath: String, activeFilePath: String? = null): File {
        val file = getLaunchFile(projectPath)
        if (!file.exists()) {
            // A project that still has the IDE's old file: carry its commands over instead of starting from a sample
            val legacy = File(projectPath, LEGACY_FILE)
            if (legacy.isFile && runCatching { file.writeText(legacy.readText()) }.isSuccess) return file
            val sampleConfigs = mutableListOf<RunConfiguration>()
            if (activeFilePath != null) {
                val defaultRunner = getDefaultRunnerForFile(activeFilePath, projectPath)
                if (defaultRunner != null) {
                    sampleConfigs.add(defaultRunner)
                }
            }
            if (sampleConfigs.isEmpty()) {
                sampleConfigs.add(
                    RunConfiguration(
                        name = "Run Active File",
                        type = "terminal",
                        command = "python3 \"\${file}\"",
                        cwd = "\${fileDirname}"
                    )
                )
            }
            saveConfigurations(projectPath, sampleConfigs)
        }
        return file
    }

    // ---- Run panel: the files may live on the server (server mode / a PC), read through the bridge there ----

    private val filesAreRemote: Boolean get() = com.example.gemini.data.preferences.AuthPreferences.currentRemoteMode

    /** A text file's content, or null when it doesn't exist (or can't be read). */
    suspend fun readText(path: String): String? = withContext(Dispatchers.IO) {
        if (filesAreRemote) {
            com.example.gemini.data.daemon.IdeApiClient.readFile(path)
        } else {
            runCatching { File(path).takeIf { it.isFile }?.readText() }.getOrNull()
        }
    }

    suspend fun writeText(path: String, text: String): Boolean = withContext(Dispatchers.IO) {
        if (filesAreRemote) {
            val parent = path.substringBeforeLast('/', "")
            if (parent.isNotEmpty()) com.example.gemini.data.daemon.IdeApiClient.createFileOrDir(parent, isDir = true)
            com.example.gemini.data.daemon.IdeApiClient.saveFileDetailed(path, text, force = true) is
                com.example.gemini.data.daemon.FileSaveResult.Success
        } else {
            runCatching { File(path).apply { parentFile?.mkdirs() }.writeText(text) }.isSuccess
        }
    }

    /** The project's commands for the Run panel ([PROJECT_FILE], else the IDE's old file). Null = the file is broken. */
    suspend fun loadProject(projectPath: String): List<RunConfiguration>? {
        val text = readText("$projectPath/$PROJECT_FILE") ?: readText("$projectPath/$LEGACY_FILE") ?: return emptyList()
        return runCatching { parseConfigurations(text) }.getOrNull()
    }

    suspend fun saveProject(projectPath: String, configs: List<RunConfiguration>): Boolean =
        writeText("$projectPath/$PROJECT_FILE", encodeConfigurations(configs))

    private fun globalFile(context: android.content.Context) =
        File(com.example.gemini.data.local.LocalEnvironmentManager.getHomeDir(context), GLOBAL_FILE)

    suspend fun loadGlobal(context: android.content.Context): List<RunConfiguration> = withContext(Dispatchers.IO) {
        val file = globalFile(context)
        if (!file.isFile) emptyList() else runCatching { parseConfigurations(file.readText()) }.getOrDefault(emptyList())
    }

    suspend fun saveGlobal(context: android.content.Context, configs: List<RunConfiguration>): Boolean = withContext(Dispatchers.IO) {
        runCatching { globalFile(context).apply { parentFile?.mkdirs() }.writeText(encodeConfigurations(configs)) }.isSuccess
    }

    fun resolveVariables(template: String, activeFilePath: String?, projectPath: String?): String {
        var resolved = template
        val activeFile = activeFilePath?.let { File(it) }

        val filePath = activeFile?.absolutePath ?: ""
        val fileBasename = activeFile?.name ?: ""
        val fileBasenameNoExt = activeFile?.nameWithoutExtension ?: ""
        val fileDirname = activeFile?.parentFile?.absolutePath ?: projectPath ?: ""
        val fileExt = activeFile?.extension?.let { if (it.isNotBlank()) ".$it" else "" } ?: ""
        val workspaceFolder = projectPath ?: fileDirname

        resolved = resolved.replace("\${file}", filePath)
        resolved = resolved.replace("\${fileBasename}", fileBasename)
        resolved = resolved.replace("\${fileBasenameNoExtension}", fileBasenameNoExt)
        resolved = resolved.replace("\${fileDirname}", fileDirname)
        resolved = resolved.replace("\${fileExt}", fileExt)
        resolved = resolved.replace("\${workspaceFolder}", workspaceFolder)

        return resolved
    }

    fun resolveWorkingDir(cwdTemplate: String?, activeFilePath: String?, projectPath: String?): String? {
        if (cwdTemplate.isNullOrBlank()) {
            return activeFilePath?.let { File(it).parentFile?.absolutePath } ?: projectPath
        }
        return resolveVariables(cwdTemplate, activeFilePath, projectPath)
    }

    private val SUPPORTED_EXTENSIONS = setOf(
        "c", "cpp", "cc", "cxx", "c++",
        "py", "pyw",
        "js", "mjs", "cjs",
        "ts", "mts", "cts",
        "php",
        "sh", "bash", "zsh",
        "rs",
        "go",
        "java",
        "kts",
        "rb",
        "lua",
        "r"
    )

    fun hasDefaultRunnerForFile(filePath: String?): Boolean {
        if (filePath.isNullOrBlank()) return false
        val ext = File(filePath).extension.lowercase()
        return ext in SUPPORTED_EXTENSIONS
    }

    fun getDefaultRunnerForFile(filePath: String, projectPath: String?): RunConfiguration? {
        val file = File(filePath)
        val ext = file.extension.lowercase()

        return when (ext) {
            "c" -> RunConfiguration(
                name = "Compile & Run C (${file.name})",
                type = "terminal",
                command = "mkdir -p \"\${TMPDIR:-\$PREFIX/tmp}\" && gcc -Wall -O2 \"\${file}\" -o \"\${TMPDIR:-\$PREFIX/tmp}/\${fileBasenameNoExtension}\" && \"\${TMPDIR:-\$PREFIX/tmp}/\${fileBasenameNoExtension}\"",
                cwd = "\${fileDirname}"
            )
            "cpp", "cc", "cxx", "c++" -> RunConfiguration(
                name = "Compile & Run C++ (${file.name})",
                type = "terminal",
                command = "mkdir -p \"\${TMPDIR:-\$PREFIX/tmp}\" && g++ -Wall -O2 \"\${file}\" -o \"\${TMPDIR:-\$PREFIX/tmp}/\${fileBasenameNoExtension}\" && \"\${TMPDIR:-\$PREFIX/tmp}/\${fileBasenameNoExtension}\"",
                cwd = "\${fileDirname}"
            )
            "py", "pyw" -> RunConfiguration(
                name = "Run Python (${file.name})",
                type = "terminal",
                command = "python3 \"\${file}\"",
                cwd = "\${fileDirname}"
            )
            "js", "mjs", "cjs" -> RunConfiguration(
                name = "Run Node.js (${file.name})",
                type = "terminal",
                command = "node \"\${file}\"",
                cwd = "\${fileDirname}"
            )
            "ts", "mts", "cts" -> RunConfiguration(
                name = "Run TypeScript (${file.name})",
                type = "terminal",
                command = "npx tsx \"\${file}\"",
                cwd = "\${fileDirname}"
            )
            "php" -> RunConfiguration(
                name = "Run PHP CLI (${file.name})",
                type = "terminal",
                command = "php \"\${file}\"",
                cwd = "\${fileDirname}"
            )
            "sh", "bash", "zsh" -> RunConfiguration(
                name = "Execute Shell Script (${file.name})",
                type = "terminal",
                command = "bash \"\${file}\"",
                cwd = "\${fileDirname}"
            )
            "rs" -> RunConfiguration(
                name = "Compile & Run Rust (${file.name})",
                type = "terminal",
                command = "mkdir -p \"\${TMPDIR:-\$PREFIX/tmp}\" && rustc \"\${file}\" -o \"\${TMPDIR:-\$PREFIX/tmp}/\${fileBasenameNoExtension}\" && \"\${TMPDIR:-\$PREFIX/tmp}/\${fileBasenameNoExtension}\"",
                cwd = "\${fileDirname}"
            )
            "go" -> RunConfiguration(
                name = "Run Go (${file.name})",
                type = "terminal",
                command = "go run \"\${file}\"",
                cwd = "\${fileDirname}"
            )
            "java" -> RunConfiguration(
                name = "Compile & Run Java (${file.name})",
                type = "terminal",
                command = "mkdir -p \"\${TMPDIR:-\$PREFIX/tmp}\" && javac -d \"\${TMPDIR:-\$PREFIX/tmp}\" \"\${file}\" && java -cp \"\${TMPDIR:-\$PREFIX/tmp}\" \"\${fileBasenameNoExtension}\"",
                cwd = "\${fileDirname}"
            )
            "kts" -> RunConfiguration(
                name = "Run Kotlin Script (${file.name})",
                type = "terminal",
                command = "kotlinc -script \"\${file}\"",
                cwd = "\${fileDirname}"
            )
            "rb" -> RunConfiguration(
                name = "Run Ruby (${file.name})",
                type = "terminal",
                command = "ruby \"\${file}\"",
                cwd = "\${fileDirname}"
            )
            "lua" -> RunConfiguration(
                name = "Run Lua (${file.name})",
                type = "terminal",
                command = "lua \"\${file}\"",
                cwd = "\${fileDirname}"
            )
            "r" -> RunConfiguration(
                name = "Run R Script (${file.name})",
                type = "terminal",
                command = "Rscript \"\${file}\"",
                cwd = "\${fileDirname}"
            )
            else -> null
        }
    }
}
