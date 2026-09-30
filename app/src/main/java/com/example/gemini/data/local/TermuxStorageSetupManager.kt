package com.example.gemini.data.local

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.system.Os
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File

object TermuxStorageSetupManager {
    private const val TAG = "TermuxStorageSetup"

    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestStoragePermission(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } else {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch storage permission settings: ${e.message}")
        }
    }

    fun setupStorageSymlinks(context: Context): Boolean {
        return try {
            val homeDir = LocalEnvironmentManager.getHomeDir(context)
            val storageDir = File(homeDir, "storage")
            if (!storageDir.exists()) {
                storageDir.mkdirs()
            }

            // Primary shared storage
            val sharedDir = Environment.getExternalStorageDirectory()
            createSymlink(sharedDir.absolutePath, File(storageDir, "shared"))

            // Standard public directories
            createPublicSymlink(Environment.DIRECTORY_DOWNLOADS, File(storageDir, "downloads"))
            createPublicSymlink(Environment.DIRECTORY_DCIM, File(storageDir, "dcim"))
            createPublicSymlink(Environment.DIRECTORY_PICTURES, File(storageDir, "pictures"))
            createPublicSymlink(Environment.DIRECTORY_MUSIC, File(storageDir, "music"))
            createPublicSymlink(Environment.DIRECTORY_MOVIES, File(storageDir, "movies"))
            createPublicSymlink(Environment.DIRECTORY_DOCUMENTS, File(storageDir, "documents"))
            createPublicSymlink(Environment.DIRECTORY_PODCASTS, File(storageDir, "podcasts"))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                createPublicSymlink(Environment.DIRECTORY_AUDIOBOOKS, File(storageDir, "audiobooks"))
            }

            // App external files dirs
            val extDirs = context.getExternalFilesDirs(null)
            if (extDirs != null) {
                for (i in extDirs.indices) {
                    val dir = extDirs[i] ?: continue
                    createSymlink(dir.absolutePath, File(storageDir, "external-$i"))
                }
            }

            // App external media dirs
            val mediaDirs = context.externalMediaDirs
            if (mediaDirs != null) {
                for (i in mediaDirs.indices) {
                    val dir = mediaDirs[i] ?: continue
                    createSymlink(dir.absolutePath, File(storageDir, "media-$i"))
                }
            }

            Log.i(TAG, "Termux storage symlinks setup successfully at ${storageDir.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup storage symlinks: ${e.message}", e)
            false
        }
    }

    private fun createPublicSymlink(publicDirType: String, linkFile: File) {
        try {
            val target = Environment.getExternalStoragePublicDirectory(publicDirType)
            if (target != null) {
                if (!target.exists()) {
                    target.mkdirs()
                }
                createSymlink(target.absolutePath, linkFile)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error creating symlink for $publicDirType: ${e.message}")
        }
    }

    private fun createSymlink(targetPath: String, linkFile: File) {
        try {
            if (linkFile.exists() || isSymlink(linkFile)) {
                linkFile.delete()
            }
            Os.symlink(targetPath, linkFile.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "Failed creating symlink ${linkFile.absolutePath} -> $targetPath: ${e.message}")
        }
    }

    private fun isSymlink(file: File): Boolean {
        return try {
            val canon: File = if (file.parent == null) file else File(file.parentFile?.canonicalFile, file.name)
            canon.canonicalFile != canon.absoluteFile
        } catch (_: Exception) {
            false
        }
    }
}
