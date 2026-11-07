package com.example.gemini.data.files

import android.webkit.MimeTypeMap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.Locale

/** What a file is, which decides its icon, color and how it opens. */
enum class FileKind(val label: String) {
    FOLDER("Folder"),
    CODE("Code"),
    TEXT("Text"),
    MARKDOWN("Markdown"),
    DATA("Data"),
    IMAGE("Image"),
    VIDEO("Video"),
    AUDIO("Audio"),
    ARCHIVE("Archive"),
    PDF("PDF"),
    DOCUMENT("Document"),
    APK("App package"),
    FONT("Font"),
    BINARY("File");

    /** Opens fine in the code editor. */
    val isTextual: Boolean get() = this == CODE || this == TEXT || this == MARKDOWN || this == DATA
}

object FileKinds {
    private val code = setOf(
        "kt", "kts", "java", "js", "mjs", "cjs", "jsx", "ts", "tsx", "py", "rb", "go", "rs", "c", "h", "cc", "cpp", "hpp", "cs",
        "swift", "m", "mm", "php", "lua", "dart", "scala", "groovy", "gradle", "sh", "bash", "zsh", "fish", "ps1", "bat", "sql",
        "html", "htm", "css", "scss", "sass", "less", "vue", "svelte", "r", "pl", "ex", "exs", "erl", "hs", "clj", "elm", "zig",
        "nim", "v", "asm", "s", "makefile", "cmake", "dockerfile", "proto", "graphql", "gql"
    )
    private val text = setOf("txt", "log", "ini", "cfg", "conf", "env", "properties", "gitignore", "gitattributes", "editorconfig", "csv", "tsv", "rst", "tex", "srt", "vtt", "lock", "pro", "diff", "patch")
    private val data = setOf("json", "jsonl", "yaml", "yml", "toml", "xml", "plist", "svg")
    private val markdown = setOf("md", "markdown", "mdx")
    private val image = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "ico", "heic", "heif", "avif", "tif", "tiff")
    private val video = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "flv", "wmv", "ts.video", "mpg", "mpeg")
    private val audio = setOf("mp3", "wav", "m4a", "ogg", "oga", "opus", "flac", "aac", "amr", "wma", "mid", "midi")
    private val archive = setOf("zip", "tar", "gz", "tgz", "bz2", "tbz2", "xz", "7z", "rar", "jar", "aar", "whl", "zst")
    private val document = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "epub")
    private val font = setOf("ttf", "otf", "woff", "woff2")
    private val bareCodeNames = setOf("makefile", "dockerfile", "jenkinsfile", "procfile", "gemfile", "rakefile", "cmakelists.txt")
    private val bareTextNames = setOf("readme", "license", "licence", "changelog", "notice", "authors", "copying", "todo", "contributing", "version", "codeowners")

    fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun of(name: String): FileKind {
        val lower = name.lowercase(Locale.ROOT)
        if (lower in bareCodeNames) return FileKind.CODE
        if (lower in bareTextNames) return FileKind.TEXT
        // dotfiles such as .bashrc, .profile, .npmrc are configuration text
        if (lower.startsWith(".") && lower.count { it == '.' } == 1) return FileKind.TEXT
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tar.bz2") || lower.endsWith(".tar.xz")) return FileKind.ARCHIVE
        val ext = extOf(name)
        return when {
            ext.isEmpty() -> if (lower.startsWith(".")) FileKind.TEXT else FileKind.BINARY
            ext in markdown -> FileKind.MARKDOWN
            ext in code -> FileKind.CODE
            ext in data -> FileKind.DATA
            ext in text -> FileKind.TEXT
            ext in image -> FileKind.IMAGE
            ext in video -> FileKind.VIDEO
            ext in audio -> FileKind.AUDIO
            ext == "pdf" -> FileKind.PDF
            ext == "apk" || ext == "apks" || ext == "xapk" -> FileKind.APK
            ext in archive -> FileKind.ARCHIVE
            ext in document -> FileKind.DOCUMENT
            ext in font -> FileKind.FONT
            else -> FileKind.BINARY
        }
    }

    /** Archives the bridge can unpack. */
    fun isExtractable(name: String): Boolean {
        val l = name.lowercase(Locale.ROOT)
        return listOf(".zip", ".jar", ".apk", ".aar", ".whl", ".epub", ".tar", ".tar.gz", ".tgz", ".tar.bz2", ".tbz2", ".gz", ".bz2").any { l.endsWith(it) }
    }

    /** Archives whose file list can be shown without unpacking. */
    fun isBrowsableArchive(name: String): Boolean {
        val l = name.lowercase(Locale.ROOT)
        return listOf(".zip", ".jar", ".apk", ".aar", ".whl", ".epub", ".tar", ".tar.gz", ".tgz", ".tar.bz2", ".tbz2").any { l.endsWith(it) }
    }

    fun mimeOf(name: String): String {
        val ext = extOf(name)
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        return when (of(name)) {
            FileKind.CODE, FileKind.TEXT -> "text/plain"
            FileKind.MARKDOWN -> "text/markdown"
            FileKind.DATA -> if (ext == "json") "application/json" else "text/plain"
            FileKind.IMAGE -> "image/*"
            FileKind.VIDEO -> "video/*"
            FileKind.AUDIO -> "audio/*"
            FileKind.PDF -> "application/pdf"
            FileKind.APK -> "application/vnd.android.package-archive"
            FileKind.ARCHIVE -> "application/zip"
            else -> "*/*"
        }
    }

    fun icon(kind: FileKind, name: String = ""): ImageVector = when (kind) {
        FileKind.FOLDER -> Icons.Outlined.Folder
        FileKind.CODE -> when (extOf(name)) {
            "sh", "bash", "zsh", "fish", "bat", "ps1" -> Icons.Outlined.Terminal
            "html", "htm", "css", "scss", "vue", "svelte" -> Icons.Outlined.Html
            "js", "mjs", "cjs", "jsx", "ts", "tsx" -> Icons.Outlined.Javascript
            else -> Icons.Outlined.Code
        }
        FileKind.TEXT -> Icons.Outlined.Article
        FileKind.MARKDOWN -> Icons.Outlined.Description
        FileKind.DATA -> Icons.Outlined.DataObject
        FileKind.IMAGE -> Icons.Outlined.Image
        FileKind.VIDEO -> Icons.Outlined.Movie
        FileKind.AUDIO -> Icons.Outlined.MusicNote
        FileKind.ARCHIVE -> Icons.Outlined.FolderZip
        FileKind.PDF -> Icons.Outlined.PictureAsPdf
        FileKind.DOCUMENT -> Icons.Outlined.TextSnippet
        FileKind.APK -> Icons.Outlined.Android
        FileKind.FONT -> Icons.Outlined.FontDownload
        FileKind.BINARY -> Icons.AutoMirrored.Outlined.InsertDriveFile
    }

    /** Accent per kind; folders follow the theme's primary color (passed in). */
    fun color(kind: FileKind, name: String, folder: Color): Color = when (kind) {
        FileKind.FOLDER -> folder
        FileKind.CODE -> when (extOf(name)) {
            "kt", "kts" -> Color(0xFF8B5CF6)
            "java" -> Color(0xFFE76F00)
            "js", "mjs", "cjs", "jsx" -> Color(0xFFD4B21A)
            "ts", "tsx" -> Color(0xFF3178C6)
            "py" -> Color(0xFF3776AB)
            "go" -> Color(0xFF00ADD8)
            "rs" -> Color(0xFFCE6A3B)
            "html", "htm" -> Color(0xFFE34F26)
            "css", "scss" -> Color(0xFF1572B6)
            "sh", "bash", "zsh" -> Color(0xFF4CAF50)
            "c", "cpp", "cc", "h", "hpp" -> Color(0xFF5C8DBC)
            else -> Color(0xFF0EA5E9)
        }
        FileKind.TEXT -> Color(0xFF78909C)
        FileKind.MARKDOWN -> Color(0xFF64748B)
        FileKind.DATA -> Color(0xFF10B981)
        FileKind.IMAGE -> Color(0xFFEC4899)
        FileKind.VIDEO -> Color(0xFFEF4444)
        FileKind.AUDIO -> Color(0xFF06B6D4)
        FileKind.ARCHIVE -> Color(0xFFF59E0B)
        FileKind.PDF -> Color(0xFFDC2626)
        FileKind.DOCUMENT -> Color(0xFF2563EB)
        FileKind.APK -> Color(0xFF3DDC84)
        FileKind.FONT -> Color(0xFF6366F1)
        FileKind.BINARY -> Color(0xFF94A3B8)
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble() / 1024
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024; i++
        }
        return if (v >= 100) String.format(Locale.getDefault(), "%.0f %s", v, units[i]) else String.format(Locale.getDefault(), "%.1f %s", v, units[i])
    }
}
