package com.example.gemini.data.github

import android.content.Context
import android.util.Log
import com.example.gemini.data.local.LocalCommandResult
import com.example.gemini.data.local.LocalEnvironmentManager
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.RemoteServerManager
import com.jcraft.jsch.ChannelExec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

/**
 * GitHub through the GitHub CLI (`gh`) of the terminal, or of the server in server mode: installs it (with pkg),
 * signs in with the browser (device code) or a token, makes git use those credentials (`gh auth setup-git`),
 * and lists the account's repositories.
 */
object GitHubManager {
    private const val TAG = "GitHubManager"
    private const val HOST = "github.com"
    /** Extra scopes on top of gh's defaults (repo, read:org, gist): workflow lets git push .github/workflows files. */
    private const val EXTRA_SCOPES = "workflow"
    private const val LOGIN_PID_FILE = "\"\${TMPDIR:-/tmp}/antigem-gh-login.pid\""
    const val TOKEN_URL = "https://github.com/settings/tokens/new?scopes=repo,read:org,gist,workflow&description=AntiGem"
    const val INSTALL_DOCS_URL = "https://github.com/cli/cli#installation"

    data class Account(
        val login: String,
        val id: Long,
        val name: String?,
        val email: String?,
        val avatarUrl: String?,
        val htmlUrl: String,
        val bio: String?,
        val company: String?,
        val location: String?,
        val blog: String?,
        val publicRepos: Int,
        val privateRepos: Int?,
        val followers: Int,
        val following: Int,
        val plan: String?,
        val createdAt: String?
    ) {
        /** The address GitHub gives every account for commits when the real one is private. */
        val noreplyEmail: String get() = "$id+$login@users.noreply.github.com"
    }

    data class Repo(
        val fullName: String,
        val name: String,
        val owner: String,
        val description: String?,
        val isPrivate: Boolean,
        val isFork: Boolean,
        val isArchived: Boolean,
        val htmlUrl: String,
        val cloneUrl: String,
        val pushedAt: String?,
        val stars: Int,
        val language: String?
    )

    sealed interface Login {
        data object Idle : Login
        data object Starting : Login
        /** gh is waiting for [code] to be entered at [url]. */
        data class WaitingForBrowser(val code: String, val url: String) : Login
        data object Finishing : Login
        data class Failed(val message: String) : Login
    }

    data class State(
        val ghInstalled: Boolean? = null,
        val ghVersion: String? = null,
        val gitInstalled: Boolean = true,
        val refreshing: Boolean = false,
        val installing: Boolean = false,
        val installLog: List<String> = emptyList(),
        val installError: String? = null,
        /** gh has a token for github.com (works offline; [account] needs the network). */
        val signedIn: Boolean = false,
        /** Why the stored token doesn't work (expired, revoked…), from `gh auth status`. */
        val authProblem: String? = null,
        val account: Account? = null,
        val accountError: String? = null,
        val scopes: String? = null,
        val tokenSource: String? = null,
        val gitProtocol: String? = null,
        /** git asks gh for github.com credentials (`gh auth setup-git`). */
        val gitCredentialHelper: Boolean = false,
        val gitUserName: String? = null,
        val gitUserEmail: String? = null,
        val login: Login = Login.Idle,
        val repos: List<Repo> = emptyList(),
        val reposLoading: Boolean = false,
        val reposLoaded: Boolean = false,
        val reposError: String? = null,
        val busy: String? = null,
        val message: String? = null,
        val isError: Boolean = false
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private lateinit var appContext: Context
    private var installJob: Job? = null
    private var loginJob: Job? = null
    private var reposJob: Job? = null
    /** Server mode or not when [state] was last filled: switching modes makes it stale. */
    private var stateRemote: Boolean? = null

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
    }

    /** In server mode GitHub means the SERVER's gh and git: everything runs there, over the app's SSH connection. */
    val remote: Boolean get() = AuthPreferences.currentRemoteMode

    fun clearMessage() = _state.update { it.copy(message = null, isError = false) }

    private fun report(message: String, isError: Boolean = false) =
        _state.update { it.copy(message = message, isError = isError) }

    // ---------------- shell ----------------

    private val ghEnv = mapOf(
        "GH_NO_UPDATE_NOTIFIER" to "1",
        "GH_SPINNER_DISABLED" to "1",
        "NO_COLOR" to "1",
        "BROWSER" to "true", // the app opens the login page itself
        "DEBIAN_FRONTEND" to "noninteractive"
    )

    private suspend fun shell(
        command: String,
        timeoutSeconds: Long = 30,
        stdin: String? = null,
        onLine: ((String) -> Unit)? = null
    ): LocalCommandResult {
        if (remote) return serverShell(command, timeoutSeconds, stdin, onLine)
        // Local commands take no stdin: [stdin] goes through a private temp file instead of the command line
        var stdinFile: File? = null
        val redirect = if (stdin != null) {
            stdinFile = File(LocalEnvironmentManager.getTmpDir(appContext), "antigem-gh-${System.nanoTime()}.in").apply {
                writeText(stdin)
                setReadable(false, false); setReadable(true, true)
            }
            "'${stdinFile.absolutePath}'"
        } else "/dev/null"
        return try {
            LocalEnvironmentManager.executeCommand(
                "( $command ) < $redirect", appContext, timeoutSeconds = timeoutSeconds,
                customEnv = ghEnv, onOutputLine = onLine
            )
        } finally {
            stdinFile?.delete()
        }
    }

    /** Like RemoteServerManager.exec, but streams lines as they come (the login code is printed long before gh exits). */
    private suspend fun serverShell(
        command: String,
        timeoutSeconds: Long,
        stdin: String?,
        onLine: ((String) -> Unit)?
    ): LocalCommandResult = withContext(Dispatchers.IO) {
        val session = RemoteServerManager.controlSession()
            ?: return@withContext LocalCommandResult(255, "SSH to the server is not connected. Check Settings > Servers.", 0)
        val started = System.currentTimeMillis()
        val env = ghEnv.entries.joinToString(" ") { "${it.key}=${RemoteServerManager.q(it.value)}" }
        // a non-login shell may not have gh's usual install folders on PATH
        val script = "export $env PATH=\"\$HOME/.local/bin:\$HOME/bin:/usr/local/bin:/home/linuxbrew/.linuxbrew/bin:\$PATH\"; $command"
        val output = StringBuilder()
        val pending = StringBuilder()
        fun emit(text: String) {
            output.append(text)
            pending.append(text)
            while (true) {
                val nl = pending.indexOf("\n")
                if (nl < 0) break
                onLine?.invoke(pending.substring(0, nl).trimEnd('\r'))
                pending.delete(0, nl + 1)
            }
        }
        val channel = runCatching { session.openChannel("exec") as ChannelExec }
            .getOrElse { return@withContext LocalCommandResult(255, it.message ?: "SSH error", 0) }
        try {
            channel.setCommand("sh -c ${RemoteServerManager.q(script)} 2>&1")
            val input = channel.inputStream
            val stdinStream = channel.outputStream
            channel.connect(10_000)
            runCatching {
                if (stdin != null) stdinStream.write(stdin.toByteArray())
                stdinStream.close()
            }
            val buf = ByteArray(4096)
            val deadline = started + timeoutSeconds * 1000
            while (isActive) {
                while (input.available() > 0) {
                    val n = input.read(buf)
                    if (n < 0) break
                    emit(String(buf, 0, n, Charsets.UTF_8))
                }
                if (channel.isClosed) {
                    while (input.available() > 0) {
                        val n = input.read(buf)
                        if (n < 0) break
                        emit(String(buf, 0, n, Charsets.UTF_8))
                    }
                    break
                }
                if (System.currentTimeMillis() > deadline) {
                    if (pending.isNotEmpty()) onLine?.invoke(pending.toString())
                    return@withContext LocalCommandResult(124, (output.toString() + "\n[timed out]").trim(), System.currentTimeMillis() - started)
                }
                Thread.sleep(40)
            }
            if (pending.isNotEmpty()) onLine?.invoke(pending.toString())
            LocalCommandResult(channel.exitStatus, output.toString().trim(), System.currentTimeMillis() - started)
        } catch (e: Exception) {
            LocalCommandResult(255, e.message ?: "SSH error", System.currentTimeMillis() - started)
        } finally {
            channel.disconnect()
        }
    }

    private fun q(value: String) = "'" + value.replace("'", "'\\''") + "'"

    // ---------------- status ----------------

    /** [quiet] skips the spinner, for background re-checks. */
    fun refresh(quiet: Boolean = false) = scope.launch {
        if (!quiet) _state.update { it.copy(refreshing = true) }
        try { refreshNow() } finally { _state.update { it.copy(refreshing = false) } }
    }

    /** Refreshes once, or again after switching between this phone and server mode. */
    fun refreshIfStale() {
        if (_state.value.ghInstalled == null || stateRemote != remote) refresh(quiet = true)
    }

    /** Returns what was set up by itself on the way (see [ensureGitReady]), for messages. */
    private suspend fun refreshNow(): List<String> {
        val mode = remote
        if (stateRemote != null && stateRemote != mode) {
            // another machine: drop what was known about the old one
            _state.update { State(login = it.login) }
        }
        stateRemote = mode
        val script = """
            if command -v git >/dev/null 2>&1; then echo "@@GIT 1"; else echo "@@GIT 0"; fi
            echo "@@GITNAME ${'$'}(git config --global user.name 2>/dev/null)"
            echo "@@GITEMAIL ${'$'}(git config --global user.email 2>/dev/null)"
            echo "@@HELPER ${'$'}(git config --global --get-all credential.https://github.com.helper 2>/dev/null | tr '\n' ' ')"
            command -v gh >/dev/null 2>&1 || { echo "@@NOGH"; exit 0; }
            echo "@@VER ${'$'}(gh --version 2>/dev/null | head -1)"
            if gh auth token -h $HOST >/dev/null 2>&1; then echo "@@AUTH 1"; else echo "@@AUTH 0"; fi
            echo "@@STATUS ${'$'}(gh auth status -h $HOST --json hosts 2>/dev/null | tr -d '\n')"
        """.trimIndent()
        val out = shell(script, 30).output
        fun value(tag: String): String? = out.lineSequence().firstOrNull { it == "@@$tag" || it.startsWith("@@$tag ") }
            ?.removePrefix("@@$tag")?.trim()
        if (out.lineSequence().none { it.startsWith("@@GIT") }) {
            // the shell itself didn't run (no SSH, no terminal environment…)
            _state.update { it.copy(ghInstalled = false, ghVersion = null, signedIn = false, account = null) }
            report(out.ifBlank { "Couldn't run commands" }.take(400), true)
            return emptyList()
        }
        val ghInstalled = value("NOGH") == null && value("VER") != null
        val signedIn = ghInstalled && value("AUTH") == "1"
        var scopes: String? = null
        var tokenSource: String? = null
        var gitProtocol: String? = null
        var authProblem: String? = null
        // `gh auth status --json` needs gh 2.57+; older versions just leave these empty
        runCatching {
            val entry = JSONObject(value("STATUS").orEmpty()).optJSONObject("hosts")?.optJSONArray(HOST)
                ?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
                ?.let { list -> list.firstOrNull { it.optBoolean("active") } ?: list.firstOrNull() }
            if (entry != null) {
                scopes = entry.optString("scopes").takeIf { it.isNotBlank() }
                tokenSource = entry.optString("tokenSource").takeIf { it.isNotBlank() }
                gitProtocol = entry.optString("gitProtocol").takeIf { it.isNotBlank() }
                val st = entry.optString("state")
                if (st.isNotBlank() && st != "success") authProblem = entry.optString("error").ifBlank { "The saved GitHub login no longer works ($st)." }
            }
        }
        _state.update {
            it.copy(
                gitInstalled = value("GIT") == "1",
                gitUserName = value("GITNAME")?.takeIf { v -> v.isNotBlank() },
                gitUserEmail = value("GITEMAIL")?.takeIf { v -> v.isNotBlank() },
                gitCredentialHelper = value("HELPER")?.contains("auth git-credential") == true,
                ghInstalled = ghInstalled,
                ghVersion = value("VER"),
                signedIn = signedIn,
                scopes = scopes,
                tokenSource = tokenSource,
                gitProtocol = gitProtocol,
                authProblem = authProblem,
                account = if (signedIn) it.account else null,
                repos = if (signedIn) it.repos else emptyList(),
                reposLoaded = signedIn && it.reposLoaded
            )
        }
        if (!signedIn) return emptyList()
        loadAccount()
        return ensureGitReady()
    }

    /**
     * Signed in (also with `gh auth login` typed in the terminal): makes git use gh's login for github.com, and gives git a
     * commit identity from the GitHub profile when it has none, so clone, commit and push all just work. Returns what it did.
     */
    private suspend fun ensureGitReady(): List<String> {
        val s = _state.value
        if (!s.signedIn || !s.gitInstalled) return emptyList()
        val done = mutableListOf<String>()
        if (!s.gitCredentialHelper && shell("gh auth setup-git -h $HOST", 30).exitCode == 0) {
            _state.update { it.copy(gitCredentialHelper = true) }
            done += "git now uses your GitHub login"
        }
        val account = s.account
        if (account != null && (s.gitUserName == null || s.gitUserEmail == null)) {
            val name = s.gitUserName ?: account.name ?: account.login
            val email = s.gitUserEmail ?: account.email ?: account.noreplyEmail
            if (setGitIdentityNow(name, email)) {
                _state.update { it.copy(gitUserName = name, gitUserEmail = email) }
                done += "commits are signed as $name <$email>"
            }
        }
        return done
    }

    private suspend fun loadAccount() {
        val r = shell("gh api user", 30)
        val account = runCatching { parseAccount(JSONObject(r.output.substring(r.output.indexOf('{')))) }.getOrNull()
        if (r.exitCode != 0 || account == null) {
            _state.update { it.copy(accountError = r.output.lineSequence().lastOrNull { l -> l.isNotBlank() } ?: "Couldn't load the account (exit ${r.exitCode})") }
            return
        }
        _state.update { it.copy(account = account, accountError = null) }
    }

    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun parseAccount(o: JSONObject) = Account(
        login = o.getString("login"),
        id = o.optLong("id"),
        name = o.str("name"),
        email = o.str("email"),
        avatarUrl = o.str("avatar_url"),
        htmlUrl = o.str("html_url") ?: "https://github.com/${o.getString("login")}",
        bio = o.str("bio"),
        company = o.str("company"),
        location = o.str("location"),
        blog = o.str("blog"),
        publicRepos = o.optInt("public_repos"),
        privateRepos = if (o.has("total_private_repos") && !o.isNull("total_private_repos")) o.optInt("total_private_repos") else null,
        followers = o.optInt("followers"),
        following = o.optInt("following"),
        plan = o.optJSONObject("plan")?.str("name"),
        createdAt = o.str("created_at")
    )

    // ---------------- install ----------------

    /** Installs gh with pkg in this phone's terminal. In server mode the user installs it on the server themselves. */
    fun installGh() {
        if (installJob?.isActive == true || remote) return
        installJob = scope.launch {
            _state.update { it.copy(installing = true, installLog = emptyList(), installError = null) }
            // Stale package lists point at files the mirror no longer has (404), so refresh them first
            val command = "apt-get update && pkg install -y gh"
            appendInstallLog("$ $command")
            val result = shell(command, 900) { line -> if (line.isNotBlank()) appendInstallLog(line) }
            refreshNow()
            val ok = _state.value.ghInstalled == true
            _state.update {
                it.copy(installing = false, installError = if (ok) null else "Install failed (exit ${result.exitCode}). Check the log above, or run \"pkg install gh\" in the terminal.")
            }
            if (ok) appendInstallLog("✓ ${_state.value.ghVersion ?: "gh installed"}")
        }
    }

    private fun appendInstallLog(line: String) =
        _state.update { it.copy(installLog = (it.installLog + line).takeLast(400)) }

    // ---------------- sign in ----------------

    private val codeRegex = Regex("""one-time code:\s*([A-Z0-9]{4}-[A-Z0-9]{4})""")
    private val urlRegex = Regex("""https://\S*/login/device\S*""")

    /**
     * Signs in with the device flow: gh prints a one-time code and waits (up to 15 minutes) until it is entered on
     * github.com/login/device, then saves the token. Without a terminal gh skips its "Press Enter" prompt.
     */
    fun startBrowserLogin() {
        if (loginJob?.isActive == true) return
        _state.update { it.copy(login = Login.Starting, message = null) }
        loginJob = scope.launch {
            var code: String? = null
            var url = "https://github.com/login/device"
            val lines = mutableListOf<String>()
            // the pid file lets Cancel stop gh, which would otherwise keep polling GitHub until the code expires
            val command = "sh -c 'echo \$\$ > \"\$0\"; exec timeout 960 gh auth login -h $HOST -p https --web -s $EXTRA_SCOPES' $LOGIN_PID_FILE"
            val r = shell(command, 1000) { line ->
                lines += line
                codeRegex.find(line)?.let { code = it.groupValues[1] }
                urlRegex.find(line)?.let { url = it.value }
                val c = code
                if (c != null && _state.value.login !is Login.WaitingForBrowser) _state.update { it.copy(login = Login.WaitingForBrowser(c, url)) }
                if (line.contains("Authentication complete") || line.contains("Logged in as")) _state.update { it.copy(login = Login.Finishing) }
            }
            shell("rm -f $LOGIN_PID_FILE", 10)
            if (_state.value.login is Login.Idle) return@launch // cancelled
            if (r.exitCode != 0) {
                val reason = lines.lastOrNull { it.isNotBlank() && !it.contains("clipboard") && !it.contains("one-time code") }
                    ?: "gh exited with code ${r.exitCode}"
                _state.update { it.copy(login = Login.Failed(if (r.exitCode == 124) "The code expired. Try again." else reason)) }
                return@launch
            }
            finishLogin()
        }
    }

    fun cancelLogin() {
        _state.update { it.copy(login = Login.Idle) }
        scope.launch { shell("kill \$(cat $LOGIN_PID_FILE 2>/dev/null) 2>/dev/null; rm -f $LOGIN_PID_FILE", 10) }
    }

    /** Signs in with a personal access token (classic tokens need the repo and read:org scopes). */
    fun loginWithToken(token: String) {
        val value = token.trim()
        if (value.isEmpty() || loginJob?.isActive == true) return
        _state.update { it.copy(login = Login.Finishing, message = null) }
        loginJob = scope.launch {
            // the token goes in on stdin, never on a command line
            val r = shell("gh auth login -h $HOST -p https --with-token", 60, stdin = value + "\n")
            if (r.exitCode != 0) {
                // gh puts the reason first ("error validating token: HTTP 401: Bad credentials") and a hint after it
                val lines = r.output.lineSequence().filter { it.isNotBlank() }.toList()
                val reason = lines.firstOrNull { it.contains("error", true) } ?: lines.firstOrNull() ?: "gh exited with code ${r.exitCode}"
                _state.update { it.copy(login = Login.Failed(reason)) }
                return@launch
            }
            finishLogin()
        }
    }

    /** After a sign-in: git gets gh's credentials, and a commit identity from the profile when it has none yet. */
    private suspend fun finishLogin() {
        _state.update { it.copy(login = Login.Finishing) }
        val setup = shell("gh auth setup-git -h $HOST", 30)
        val notes = refreshNow().filter { !it.startsWith("git now uses") }
        val account = _state.value.account
        _state.update { it.copy(login = Login.Idle) }
        if (setup.exitCode != 0) report("Signed in, but git setup failed: ${setup.output.take(300)}", true)
        else report("Signed in as ${account?.login ?: "GitHub user"}. git now uses this account" + notes.joinToString("") { "; $it" } + ".")
        loadRepos()
    }

    fun dismissLoginError() = _state.update { if (it.login is Login.Failed) it.copy(login = Login.Idle) else it }

    fun logout() {
        val login = _state.value.account?.login
        scope.launch {
            _state.update { it.copy(busy = "Signing out…") }
            val r = shell("gh auth logout -h $HOST" + (login?.let { " -u ${q(it)}" } ?: ""), 30)
            _state.update { it.copy(busy = null, account = null, repos = emptyList(), reposLoaded = false) }
            refreshNow()
            if (_state.value.signedIn) report(r.output.take(300).ifBlank { "Sign out failed" }, true)
            else report("Signed out of GitHub.")
        }
    }

    // ---------------- git ----------------

    /** Makes git use gh's GitHub login for https remotes (clone, pull, push). */
    fun setupGit() = scope.launch {
        _state.update { it.copy(busy = "Setting up git…") }
        val r = shell("gh auth setup-git -h $HOST", 30)
        _state.update { it.copy(busy = null) }
        refreshNow()
        if (r.exitCode == 0) report("git now uses your GitHub login.") else report(r.output.take(300).ifBlank { "gh auth setup-git failed" }, true)
    }

    fun setGitIdentity(name: String, email: String) = scope.launch {
        _state.update { it.copy(busy = "Saving git identity…") }
        val ok = setGitIdentityNow(name.trim(), email.trim())
        _state.update { it.copy(busy = null) }
        refreshNow()
        if (ok) report("Saved. New commits use ${name.trim()} <${email.trim()}>.") else report("Couldn't save the git identity.", true)
    }

    private suspend fun setGitIdentityNow(name: String, email: String): Boolean {
        if (name.isBlank() || email.isBlank()) return false
        return shell("git config --global user.name ${q(name)} && git config --global user.email ${q(email)}", 15).exitCode == 0
    }

    // ---------------- repositories ----------------

    /** Every repository the account owns, collaborates on or can see through its organizations, newest push first. */
    fun loadRepos() {
        if (reposJob?.isActive == true) return
        reposJob = scope.launch {
            _state.update { it.copy(reposLoading = true, reposError = null) }
            val jq = ".[] | {full_name, name, owner: .owner.login, description, private, fork, archived, html_url, clone_url, pushed_at, stargazers_count, language}"
            val r = shell("gh api --paginate ${q("user/repos?per_page=100&sort=pushed&affiliation=owner,collaborator,organization_member")} --jq ${q(jq)}", 120)
            if (r.exitCode != 0) {
                _state.update { it.copy(reposLoading = false, reposError = r.output.lineSequence().lastOrNull { l -> l.isNotBlank() } ?: "Couldn't load repositories (exit ${r.exitCode})") }
                return@launch
            }
            val repos = runCatching { parseRepos(r.output) }.getOrElse {
                Log.w(TAG, "Bad repo list: ${it.message}")
                _state.update { s -> s.copy(reposLoading = false, reposError = "Couldn't read the repository list") }
                return@launch
            }
            _state.update { it.copy(repos = repos, reposLoading = false, reposLoaded = true) }
        }
    }

    // ---------------- for Jarvis (waits for the result) ----------------

    /** Fresh status, with git set up by itself when signed in. */
    suspend fun statusNow(context: Context): State {
        init(context)
        refreshNow()
        return _state.value
    }

    /** The repository list, loading it when it isn't yet (or [reload]). */
    suspend fun reposNow(reload: Boolean = false): Result<List<Repo>> {
        if (reload || !_state.value.reposLoaded) {
            reposJob?.join()
            if (reload || !_state.value.reposLoaded) { loadRepos(); reposJob?.join() }
        }
        val s = _state.value
        return if (s.reposLoaded) Result.success(s.repos) else Result.failure(IllegalStateException(s.reposError ?: "Couldn't load repositories"))
    }

    /**
     * Clones [url] into the home folder (in server mode: the server's, through the bridge), the same way the clone dialog
     * does. Returns the new folder's path, or the end of the log as the error.
     */
    suspend fun cloneNow(context: Context, url: String, folder: String?): Result<String> {
        val log = mutableListOf<String>()
        var path: String? = null
        val sink: (String) -> Unit = { line ->
            if (line.startsWith("ANTIGEM_CLONED_PATH=")) path = line.removePrefix("ANTIGEM_CLONED_PATH=").trim()
            else if (line.isNotBlank() && !line.contains('%')) log += line
        }
        val exit = if (remote) {
            com.example.gemini.data.remote.AgyBridgeService.instance.cloneRepository(url, folder.orEmpty(), onLine = sink)
        } else {
            LocalEnvironmentManager.executeScriptLive(context, "git_clone", listOfNotNull(url, folder?.takeIf { it.isNotBlank() }), sink).exitCode
        }
        val p = path
        return if (exit == 0 && p != null) Result.success(p)
        else Result.failure(IllegalStateException(log.filter { !it.startsWith("=") }.takeLast(4).joinToString("\n").ifBlank { "git clone failed (exit $exit)" }))
    }

    /**
     * Creates a repository on GitHub. With [sourceDir] the folder becomes its local copy: it is made a git repository if it
     * isn't one, gets the new repository as remote [remoteName], and its commits are pushed when [push] (a folder without
     * commits is linked but not pushed). Returns a short report with the repository's URL.
     */
    suspend fun createRepoNow(
        name: String,
        private: Boolean,
        description: String?,
        sourceDir: String?,
        push: Boolean,
        remoteName: String = "origin"
    ): Result<String> {
        val notes = mutableListOf<String>()
        val args = StringBuilder("gh repo create ${q(name)} ${if (private) "--private" else "--public"}")
        description?.takeIf { it.isNotBlank() }?.let { args.append(" --description ${q(it)}") }
        var willPush = false
        if (sourceDir != null) {
            val dir = q(sourceDir)
            val check = shell(
                "cd $dir || exit 3; if ! git rev-parse --git-dir >/dev/null 2>&1; then git init -b main >/dev/null && echo @@INIT; fi; " +
                    "git remote get-url ${q(remoteName)} 2>/dev/null | sed 's/^/@@REMOTE /'; " +
                    "git rev-parse --verify -q HEAD >/dev/null && echo @@COMMITS; true", 20
            )
            if (check.exitCode == 3) return Result.failure(IllegalArgumentException("The folder $sourceDir doesn't exist."))
            val lines = check.output.lines()
            lines.firstOrNull { it.startsWith("@@REMOTE ") }?.let {
                return Result.failure(IllegalStateException("$sourceDir already has a remote named $remoteName (${it.removePrefix("@@REMOTE ").trim()}). Pick another remote name, or remove it first."))
            }
            if ("@@INIT" in lines) notes += "made $sourceDir a git repository (branch main)"
            willPush = push && "@@COMMITS" in lines
            if (push && !willPush) notes += "nothing pushed yet: the folder has no commits (commit, then git push -u $remoteName HEAD)"
            args.append(" --source $dir --remote ${q(remoteName)}")
            if (willPush) args.append(" --push")
        }
        val r = shell(args.toString(), 180)
        if (r.exitCode != 0) {
            val lines = r.output.lines().filter { it.isNotBlank() }
            return Result.failure(IllegalStateException(lines.firstOrNull { it.contains("error", true) || it.contains("already exists") } ?: lines.lastOrNull() ?: "gh repo create failed (exit ${r.exitCode})"))
        }
        val url = Regex("""https://github\.com/\S+""").find(r.output)?.value?.removeSuffix(".git")
        if (sourceDir != null) notes += "linked as remote $remoteName" + if (willPush) " and pushed" else ""
        loadRepos() // show it in the list
        return Result.success(
            "Created ${if (private) "private" else "public"} repository ${url ?: name}." +
                (if (notes.isNotEmpty()) " " + notes.joinToString("; ").replaceFirstChar { it.uppercase() } + "." else "")
        )
    }

    /** `--jq` prints one JSON object per repository; tolerate pretty-printed ones too. */
    private fun parseRepos(text: String): List<Repo> {
        val start = text.indexOf('{').takeIf { it >= 0 } ?: return emptyList()
        val tokener = JSONTokener(text.substring(start))
        val list = mutableListOf<Repo>()
        while (tokener.more()) {
            when (val v = tokener.nextValue()) {
                is JSONObject -> list += parseRepo(v)
                is JSONArray -> for (i in 0 until v.length()) v.optJSONObject(i)?.let { list += parseRepo(it) }
            }
        }
        return list.distinctBy { it.fullName }
    }

    private fun parseRepo(o: JSONObject): Repo {
        val fullName = o.getString("full_name")
        return Repo(
            fullName = fullName,
            name = o.str("name") ?: fullName.substringAfter('/'),
            owner = o.str("owner") ?: fullName.substringBefore('/'),
            description = o.str("description"),
            isPrivate = o.optBoolean("private"),
            isFork = o.optBoolean("fork"),
            isArchived = o.optBoolean("archived"),
            htmlUrl = o.str("html_url") ?: "https://github.com/$fullName",
            cloneUrl = o.str("clone_url") ?: "https://github.com/$fullName.git",
            pushedAt = o.str("pushed_at"),
            stars = o.optInt("stargazers_count"),
            language = o.str("language")
        )
    }
}
