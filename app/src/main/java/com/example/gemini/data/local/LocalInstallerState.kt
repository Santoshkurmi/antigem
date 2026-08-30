package com.example.gemini.data.local

data class DiscoveredPackageInfo(
    val url: String,
    val releaseTag: String,
    val assetName: String,
    val arch: String,
    val sizeBytes: Long,
    val sizeFormatted: String,
    val packageName: String
)

sealed class LocalInstallerState {
    object Idle : LocalInstallerState()

    data class Discovering(
        val message: String = "Searching GitHub for compatible Termux bootstrap release..."
    ) : LocalInstallerState()

    data class AwaitingConfirmation(
        val packageInfo: DiscoveredPackageInfo
    ) : LocalInstallerState()
    
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val progressFraction: Float,
        val speedText: String,
        val currentPackageName: String
    ) : LocalInstallerState()

    data class Extracting(
        val extractedFilesCount: Int,
        val totalFilesEstimate: Int,
        val progressFraction: Float,
        val currentFileName: String
    ) : LocalInstallerState()

    data class Configuring(
        val stepDescription: String,
        val progressFraction: Float
    ) : LocalInstallerState()

    data class Verifying(
        val testName: String
    ) : LocalInstallerState()

    data class Success(
        val message: String,
        val prefixPath: String,
        val totalDiskUsageFormatted: String
    ) : LocalInstallerState()

    data class Error(
        val errorMessage: String,
        val canRetry: Boolean = true
    ) : LocalInstallerState()
}

