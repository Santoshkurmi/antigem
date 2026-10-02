package com.example.gemini.data.ide

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

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

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun getLaunchFile(projectPath: String): File {
        val ideDir = File(projectPath, ".ide")
        if (!ideDir.exists()) {
            ideDir.mkdirs()
        }
        return File(ideDir, "launch.json")
    }

    fun loadConfigurations(projectPath: String): List<RunConfiguration> {
        val file = File(projectPath, ".ide/launch.json")
        if (!file.exists() || !file.isFile) return emptyList()
        return try {
            val content = file.readText()
            val parsed = json.decodeFromString<LaunchConfigFile>(content)
            parsed.configurations
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing .ide/launch.json in $projectPath: ${e.message}", e)
            emptyList()
        }
    }

    fun saveConfigurations(projectPath: String, configs: List<RunConfiguration>): Boolean {
        return try {
            val file = getLaunchFile(projectPath)
            val configObj = LaunchConfigFile(version = "1.0", configurations = configs)
            val jsonStr = json.encodeToString(configObj)
            file.writeText(jsonStr)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving .ide/launch.json in $projectPath: ${e.message}", e)
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
