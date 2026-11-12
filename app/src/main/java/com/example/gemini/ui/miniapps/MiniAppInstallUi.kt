package com.example.gemini.ui.miniapps

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.gemini.data.miniapps.MiniApp
import com.example.gemini.data.miniapps.MiniAppError
import com.example.gemini.data.miniapps.MiniAppInstaller
import com.example.gemini.data.miniapps.MiniAppPermissions
import com.example.gemini.data.miniapps.MiniAppSigning
import com.example.gemini.data.miniapps.MiniApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** A package being installed: the verified package and what installing it would do (or why it can't). */
class PendingInstall(val file: File, val plan: MiniAppInstaller.Plan)

object MiniAppPackages {
    /** Mini apps are small; this only stops a wrong (huge) file from filling the cache. */
    private const val MAX_BYTES = 100L * 1024 * 1024

    private fun cache(context: Context): File = File(context.cacheDir, "miniapp-packages").also { dir ->
        dir.mkdirs()
        // packages that were opened but never installed or cancelled (the app was killed meanwhile)
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86_400_000L) it.delete() }
    }

    private fun copyCapped(input: java.io.InputStream, out: File, total: Long, onProgress: (Int) -> Unit) {
        out.outputStream().use { o ->
            val buf = ByteArray(64 * 1024)
            var done = 0L
            var last = -1
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                done += n
                if (done > MAX_BYTES) throw MiniAppError("QuotaExceededError", "The file is bigger than 100 MB: not a mini app package")
                o.write(buf, 0, n)
                if (total > 0) ((done * 100) / total).toInt().let { if (it != last) { last = it; onProgress(it) } }
            }
        }
    }

    private fun check(context: Context, file: File): PendingInstall {
        if (!MiniAppSigning.looksLikePackage(file)) throw MiniAppError("DataError", "This file isn't a mini app package (.agapp)")
        return PendingInstall(file, MiniAppInstaller.plan(context, MiniAppSigning.verify(file)))
    }

    /** Copies a picked / opened / shared .agapp into AntiGem's cache and verifies it; the plan says what installing would do. */
    suspend fun open(context: Context, uri: Uri): PendingInstall = withContext(Dispatchers.IO) {
        val file = File(cache(context), "pkg-${System.nanoTime()}.agapp")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw MiniAppError("NotReadableError", "Can't read the file")
            input.use { copyCapped(it, file, -1) {} }
            check(context, file)
        } catch (e: Throwable) { file.delete(); throw e }
    }

    /** Downloads a package from an https:// link (a release page, a server, a cloud drive's direct link) and verifies it. */
    suspend fun download(context: Context, link: String, onProgress: (Int) -> Unit): PendingInstall = withContext(Dispatchers.IO) {
        val url = link.trim().toHttpUrlOrNull() ?: throw MiniAppError("TypeError", "That isn't a link")
        if (!url.isHttps && !com.example.gemini.data.miniapps.MiniAppStore.allowed(url)) throw MiniAppError("SecurityError", "Only https:// links: a package from an http:// link could be swapped on the way")
        val file = File(cache(context), "pkg-${System.nanoTime()}.agapp")
        val call = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
            .newCall(Request.Builder().url(url).header("Accept", "${MiniAppSigning.MIME}, application/octet-stream;q=0.9, */*;q=0.5").build())
        val job = coroutineContext[kotlinx.coroutines.Job]
        val cancel = job?.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            call.execute().use { r ->
                if (!r.isSuccessful) throw MiniAppError("NetworkError", "The server answered ${r.code}")
                val body = r.body ?: throw MiniAppError("NetworkError", "Empty answer")
                if (body.contentLength() > MAX_BYTES) throw MiniAppError("QuotaExceededError", "The file is bigger than 100 MB: not a mini app package")
                body.byteStream().use { copyCapped(it, file, body.contentLength(), onProgress) }
            }
            check(context, file)
        } catch (e: Throwable) {
            file.delete()
            throw if (e is java.io.IOException && e !is java.io.FileNotFoundException) MiniAppError("NetworkError", "Download failed: ${e.message ?: e.javaClass.simpleName}") else e
        } finally { cancel?.dispose() }
    }

    /** A package from the "Get apps" catalog: downloaded, then it must be exactly the one the catalog lists. */
    suspend fun downloadFromStore(context: Context, e: com.example.gemini.data.miniapps.MiniAppStore.Entry, onProgress: (Int) -> Unit): PendingInstall {
        val p = download(context, e.packageUrl, onProgress)
        try {
            val sha = withContext(Dispatchers.IO) { p.file.inputStream().use { MiniAppSigning.sha256Hex(it) } }
            if (!sha.equals(e.sha256, ignoreCase = true)) throw MiniAppError("SecurityError", "The download doesn't match the catalog (checksum). Try again in a few minutes")
            if (!p.plan.pkg.fingerprint.equals(e.fingerprint, ignoreCase = true)) throw MiniAppError("SecurityError", "The package is signed by a different key than the catalog says")
            if (p.plan.pkg.id != e.id) throw MiniAppError("SecurityError", "The package is ${p.plan.pkg.id}, not ${e.id}")
        } catch (t: Throwable) { p.file.delete(); throw t }
        return p
    }

    /** Where signed packages go: ~/mini-apps-dist (the agent and the file explorer see it). Key backups go to Download/AntiGem. */
    fun distDir(context: Context) = File(MiniApps.home(context), "mini-apps-dist").also { it.mkdirs() }

    fun share(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri(file.name, uri)
        context.startActivity(Intent.createChooser(send, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Shares a package so the other phone's AntiGem opens it in its installer (WhatsApp, Telegram, email, Drive, Nearby Share…). */
    fun sharePackage(context: Context, file: File) = share(context, file, MiniAppSigning.MIME)
}

/** Where a package comes from: a file (opened, shared or picked) or a link. */
sealed class InstallSource {
    class FromUri(val uri: Uri) : InstallSource()
    class FromLink(val url: String) : InstallSource()
    class FromStore(val entry: com.example.gemini.data.miniapps.MiniAppStore.Entry) : InstallSource()
}

private sealed class InstallStage {
    class Loading(val text: String, val progress: Int = -1) : InstallStage()
    class Failed(val message: String) : InstallStage()
    class Ready(val p: PendingInstall) : InstallStage()
    class Done(val id: String, val name: String, val kind: MiniAppInstaller.Kind, val icon: androidx.compose.ui.graphics.ImageBitmap?) : InstallStage()
}

/**
 * The whole install, like Android's package installer: read / download → check the signature → the install screen →
 * "Installed" with Open / Done. Used by the installer screen other apps open and by Mini Apps' own install button.
 */
@Composable
fun PackageInstaller(source: InstallSource, onClose: () -> Unit) {
    val context = LocalContext.current
    var stage by remember(source) { mutableStateOf<InstallStage>(InstallStage.Loading("Checking the package…")) }
    androidx.compose.runtime.LaunchedEffect(source) {
        stage = runCatching {
            when (source) {
                is InstallSource.FromUri -> MiniAppPackages.open(context, source.uri)
                is InstallSource.FromLink -> {
                    stage = InstallStage.Loading("Downloading…")
                    MiniAppPackages.download(context, source.url) { pct -> stage = InstallStage.Loading("Downloading…", pct) }
                }
                is InstallSource.FromStore -> {
                    stage = InstallStage.Loading("Downloading ${source.entry.name}…")
                    MiniAppPackages.downloadFromStore(context, source.entry) { pct -> stage = InstallStage.Loading("Downloading ${source.entry.name}…", pct) }
                }
            }
        }.fold({ InstallStage.Ready(it) }, { InstallStage.Failed(it.message ?: it.javaClass.simpleName) })
    }
    when (val s = stage) {
        is InstallStage.Loading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Mini app package") },
            text = {
                Column {
                    Text(s.text + if (s.progress >= 0) " ${s.progress}%" else "", style = MaterialTheme.typography.bodyMedium)
                    if (s.progress >= 0) androidx.compose.material3.LinearProgressIndicator(progress = { s.progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                    else androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        )
        is InstallStage.Failed -> AlertDialog(
            onDismissRequest = onClose,
            icon = { Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Can't install") },
            text = { Text(s.message, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = onClose) { Text("OK") } },
        )
        is InstallStage.Ready -> InstallDialog(s.p) { result ->
            stage = when {
                result == null -> { onClose(); return@InstallDialog }
                result.isSuccess -> InstallStage.Done(s.p.plan.pkg.id, s.p.plan.pkg.manifest.optString("name").ifBlank { s.p.plan.pkg.id }, s.p.plan.kind, packageIcon(s.p))
                else -> InstallStage.Failed(result.exceptionOrNull()?.message ?: "Install failed")
            }
        }
        is InstallStage.Done -> AlertDialog(
            onDismissRequest = onClose,
            icon = { s.icon?.let { Image(it, null, Modifier.size(56.dp).clip(RoundedCornerShape(14.dp))) } },
            title = { Text(s.name) },
            text = { Text(if (s.kind == MiniAppInstaller.Kind.NEW) "App installed." else "App updated.", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { MiniAppActivity.launch(context, s.id); onClose() }) { Text("Open") } },
            dismissButton = { TextButton(onClick = onClose) { Text("Done") } },
        )
    }
}

private fun packageIcon(p: PendingInstall) = runCatching {
    java.util.zip.ZipFile(p.file).use { z ->
        p.plan.pkg.manifest.optString("icon").takeIf { it.isNotBlank() }?.let { z.getEntry(it.trimStart('/')) }
            ?.let { e -> z.getInputStream(e).use { android.graphics.BitmapFactory.decodeStream(it) } }
    }
}.getOrNull()?.asImageBitmap()

/** Android-style install screen: who signed it, new / update / downgrade, new permissions, and the verdict. [onResult]: null = cancelled. */
@Composable
private fun InstallDialog(p: PendingInstall, onResult: (Result<Unit>?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val plan = p.plan
    val pkg = plan.pkg
    var busy by remember { mutableStateOf(false) }
    val icon = remember(pkg.id) { packageIcon(p) }
    val name = pkg.manifest.optString("name").ifBlank { pkg.id }
    val (verb, headline) = when (plan.kind) {
        MiniAppInstaller.Kind.NEW -> "Install" to "Install $name?"
        MiniAppInstaller.Kind.UPDATE -> "Update" to "Update $name?"
        MiniAppInstaller.Kind.REINSTALL -> "Reinstall" to "Reinstall $name?"
        MiniAppInstaller.Kind.DOWNGRADE -> "Downgrade" to "Go back to an older $name?"
    }
    AlertDialog(
        onDismissRequest = { if (!busy) { p.file.delete(); onResult(null) } },
        icon = { if (icon != null) Image(icon, null, Modifier.size(56.dp).clip(RoundedCornerShape(14.dp))) },
        title = { Text(headline) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (plan.blocked == null) Icons.Outlined.Verified else Icons.Outlined.WarningAmber, null,
                        tint = if (plan.blocked == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Text("  Signed by ${pkg.developer}", style = MaterialTheme.typography.titleSmall)
                }
                Text("Key ${MiniAppSigning.shortFingerprint(pkg.fingerprint)}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val version = when (plan.kind) {
                    MiniAppInstaller.Kind.NEW -> "Version ${pkg.version}"
                    else -> "Version ${plan.previous?.version} → ${pkg.version}"
                }
                Text("$version · ${pkg.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                when {
                    plan.blocked != null -> Text(plan.blocked, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                    plan.kind == MiniAppInstaller.Kind.NEW -> Text("A new app from this developer. Only packages signed with the same key can update it.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                    plan.kind == MiniAppInstaller.Kind.DOWNGRADE -> Text("This is an older version than the one installed. Older versions can have bugs that were fixed. Its data is kept.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                    else -> Text("Same developer as the installed version. Its data and permissions are kept.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                }
                if (plan.sharesWith.isNotEmpty() && plan.blocked == null) Text(
                    "Shares its data with ${plan.sharesWith.joinToString { it.name }} (same developer): each can read and change the other's files.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                if (plan.orphanData && plan.blocked == null) Text("Data left by an earlier app with this id will be deleted.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                val parsed = remember(pkg.id) { runCatching { MiniApps.parse(File("/"), pkg.manifest) }.getOrNull() }
                val perms = if (plan.kind == MiniAppInstaller.Kind.NEW) parsed?.permissions?.keys?.toList().orEmpty() else plan.newPermissions
                if (perms.isNotEmpty() && parsed != null && plan.blocked == null) {
                    Text(if (plan.kind == MiniAppInstaller.Kind.NEW) "It will ask for" else "New permissions it will ask for",
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                    perms.forEach { n ->
                        val info = MiniAppPermissions.describe(parsed, n)
                        Text("• ${info.title}" + if (info.detail.isNotBlank()) ": ${info.detail}" else "", style = MaterialTheme.typography.bodySmall,
                            color = if (info.dangerous) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            if (plan.blocked == null) TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { MiniAppInstaller.install(context, p.file, plan) } }
                    p.file.delete()
                    onResult(r)
                }
            }) { Text(if (busy) "Installing…" else verb, color = if (plan.kind == MiniAppInstaller.Kind.DOWNGRADE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { p.file.delete(); onResult(null) }) { Text(if (plan.blocked == null) "Cancel" else "Close") } },
    )
}

/** A text prompt (developer name, passwords, a link). */
@Composable
internal fun AskText(title: String, message: String, password: Boolean, confirm: String, onResult: (String?) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onResult(null) },
        title = { Text(title) },
        text = {
            Column {
                Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
                OutlinedTextField(value, { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text))
            }
        },
        confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = { onResult(value) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = { onResult(null) }) { Text("Cancel") } },
    )
}

/** Backing up the developer key: the password twice (a typo would make the backup useless), then where it goes. */
@Composable
private fun BackupKeyDialog(afterCreate: Boolean, onResult: (String?) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val ok = pw.length >= 8 && pw == again
    AlertDialog(
        onDismissRequest = { onResult(null) },
        title = { Text(if (afterCreate) "Back up your new key" else "Back up the key") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("The key is saved, locked with this password, to Download/AntiGem on this phone: it stays there even if AntiGem is " +
                    "uninstalled. Then you can also send it to Drive or email. If the key is lost, apps signed with it can never be updated.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
                OutlinedTextField(pw, { pw = it }, singleLine = true, label = { Text("Password (8+ characters)") }, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedTextField(again, { again = it }, singleLine = true, label = { Text("Repeat the password") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = again.isNotEmpty() && again != pw)
                Text("You need this password to restore the key (Developer key → Import) or to sign on a PC. Nobody can recover it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onResult(pw) }) { Text("Back up") } },
        dismissButton = { TextButton(onClick = { onResult(null) }) { Text(if (afterCreate) "Later" else "Cancel") } },
    )
}

/**
 * The developer key: create it, back it up (password-protected .agkey in Download/AntiGem + share sheet, for a new
 * phone, after reinstalling, or agapp.mjs on a PC), import one. Losing it means the apps signed with it can never be
 * updated (as with an Android keystore). [startWith] "backup" opens straight at the backup (the developer MCP).
 */
@Composable
fun DeveloperKeyDialog(onDismiss: () -> Unit, pickKeyFile: (onPicked: (Uri?) -> Unit) -> Unit, startWith: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf(MiniAppSigning.keyInfo(context)) }
    var backup by remember { mutableStateOf(com.example.gemini.data.miniapps.MiniAppKeyBackup.info(context)) }
    // "create" | "backup" | "backup-new" | "import"
    var step by remember { mutableStateOf(if (startWith == "backup" && info != null) "backup" else null) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    val toast = { m: String -> Toast.makeText(context, m, Toast.LENGTH_LONG).show() }
    when (step) {
        "create" -> AskText("Create developer key", "Your name or team, shown to people who install your apps.", false, "Create") { name ->
            step = null
            if (name != null) scope.launch {
                withContext(Dispatchers.IO) { runCatching { MiniAppSigning.createKey(context, name) } }
                    .onSuccess { info = MiniAppSigning.keyInfo(context); backup = null; step = "backup-new" }
                    .onFailure { toast(it.message ?: "Failed") }
            }
        }
        "backup", "backup-new" -> BackupKeyDialog(afterCreate = step == "backup-new") { pw ->
            step = null
            if (pw == null) { if (backup == null) toast("Not backed up yet: Mini Apps keeps reminding you until it is"); return@BackupKeyDialog }
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { com.example.gemini.data.miniapps.MiniAppKeyBackup.save(context, pw) } }
                    .onSuccess { (shown, copy) ->
                        backup = com.example.gemini.data.miniapps.MiniAppKeyBackup.info(context)
                        toast("Saved to $shown. Keep a second copy somewhere safe too.")
                        MiniAppPackages.share(context, copy, "application/octet-stream")
                    }
                    .onFailure { toast(it.message ?: "Backup failed") }
            }
        }
        "import" -> AskText("Import a key",
            (info?.let { i -> "This replaces your current key (${i.first}). " + if (backup == null) "It is NOT backed up: back it up first, or it is lost for good. " else "" } ?: "") +
                "The password of this .agkey file:", true, "Import") { pw ->
            step = null
            val uri = importUri
            if (pw != null && uri != null) scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        MiniAppSigning.importKey(context, JSONObject(context.contentResolver.openInputStream(uri)!!.bufferedReader().readText()), pw)
                        com.example.gemini.data.miniapps.MiniAppKeyBackup.markImported(context)
                    }
                }.onSuccess { info = MiniAppSigning.keyInfo(context); backup = com.example.gemini.data.miniapps.MiniAppKeyBackup.info(context); toast("Key imported") }
                    .onFailure { toast(it.message ?: "Failed") }
            }
        }
    }
    if (step == null) AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Developer key") },
        text = {
            Column {
                val i = info
                if (i == null) Text("Packages you export are signed with your key. Anyone who installs one can only update it with a package signed by the same key.",
                    style = MaterialTheme.typography.bodyMedium)
                else {
                    Text(i.first, style = MaterialTheme.typography.titleMedium)
                    Text(MiniAppSigning.shortFingerprint(i.second), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val b = backup
                    if (b == null) Text("Not backed up. If this key is lost (AntiGem uninstalled, data cleared, phone lost), apps signed with it can never be updated.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                    else Text("Backed up ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(b.at))}\n${b.path}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            if (info == null) TextButton(onClick = { step = "create" }) { Text("Create") }
            else TextButton(onClick = { step = "backup" }) { Text(if (backup == null) "Back up now" else "Back up again") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { pickKeyFile { uri -> if (uri != null) { importUri = uri; step = "import" } } }) { Text("Import") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

/** App info → "Export signed package" for a development app. */
suspend fun exportSignedPackage(context: Context, app: MiniApp): File = withContext(Dispatchers.IO) {
    val manifest = JSONObject(File(app.folder, "manifest.json").readText())
    val out = File(MiniAppPackages.distDir(context), "${app.id}-${manifest.optString("version").ifBlank { "0.0.0" }}.agapp")
    MiniAppSigning.buildPackage(context, app.folder, out)
    // check our own output like an installer would
    MiniAppSigning.verify(out)
    out
}
