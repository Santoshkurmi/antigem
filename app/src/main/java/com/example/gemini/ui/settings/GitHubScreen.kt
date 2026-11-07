package com.example.gemini.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.gemini.data.daemon.ProjectItem
import com.example.gemini.data.github.GitHubManager
import com.example.gemini.data.github.GitHubManager.Login
import com.example.gemini.theme.QuotaGreen
import com.example.gemini.ui.components.bouncyVerticalScroll

/**
 * Settings → GitHub: installs the GitHub CLI, signs in with it, makes git use that login, and lists the account's
 * repositories (clone one to open it as a project). In server mode all of it is the server's gh and git.
 */
@Composable
fun GitHubScreen(cardBg: Color, cardBorder: BorderStroke, onOpenProject: (ProjectItem) -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        GitHubManager.init(context)
        GitHubManager.refresh()
    }
    val state by GitHubManager.state.collectAsState()
    var cloneUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.signedIn) {
        if (state.signedIn && !state.reposLoaded && !state.reposLoading) GitHubManager.loadRepos()
    }

    Column(
        Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GitHubStatusCard(state, cardBg, cardBorder)

        state.message?.let { msg ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = (if (state.isError) MaterialTheme.colorScheme.error else QuotaGreen).copy(alpha = 0.1f)
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, fontSize = 12.5.sp, color = if (state.isError) MaterialTheme.colorScheme.error else QuotaGreen, modifier = Modifier.weight(1f))
                    IconButton(onClick = GitHubManager::clearMessage) { Icon(Icons.Default.Close, "Dismiss", Modifier.size(18.dp)) }
                }
            }
        }

        when {
            state.ghInstalled == null -> Hint(if (GitHubManager.remote) "Checking the server for the GitHub CLI…" else "Checking the terminal for the GitHub CLI…")
            state.ghInstalled == false || state.installing -> InstallCard(state, cardBg, cardBorder)
            !state.signedIn -> SignInCard(state, cardBg, cardBorder)
            else -> {
                AccountCard(state, cardBg, cardBorder)
                GitSetupCard(state, cardBg, cardBorder)
                ReposCard(state, cardBg, cardBorder, onClone = { cloneUrl = it })
            }
        }
    }

    cloneUrl?.let { url ->
        com.example.gemini.ui.components.GitCloneDialog(
            initialUrl = url,
            onCloned = { project ->
                cloneUrl = null
                onOpenProject(project)
            },
            onDismiss = { cloneUrl = null }
        )
    }
}

@Composable
private fun GitHubStatusCard(state: GitHubManager.State, cardBg: Color, cardBorder: BorderStroke) {
    val account = state.account
    val onSurfaceDim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (account?.avatarUrl != null) {
                AsyncImage(
                    model = account.avatarUrl, contentDescription = "${account.login}'s avatar",
                    modifier = Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                )
            } else {
                val (icon, tint) = when {
                    state.ghInstalled == false -> Icons.Outlined.Download to Amber
                    state.signedIn -> Icons.Outlined.AccountCircle to QuotaGreen
                    else -> Icons.Outlined.AccountTree to onSurfaceDim
                }
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                val title = when {
                    account != null -> account.name ?: account.login
                    state.signedIn -> "Signed in"
                    state.ghInstalled == false -> "GitHub CLI not installed"
                    state.ghInstalled == true -> "Not signed in"
                    else -> "GitHub"
                }
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = when {
                    account != null -> "@${account.login}"
                    state.signedIn -> state.accountError ?: "Loading account…"
                    state.ghInstalled == false -> if (GitHubManager.remote) "Install gh on the server to continue" else "Install it below to get started"
                    state.ghInstalled == true -> "Sign in below"
                    else -> "Checking…"
                }
                Text(subtitle, fontSize = 12.sp, color = onSurfaceDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (GitHubManager.remote) Pill("SERVER", MaterialTheme.colorScheme.primary)
                    account?.plan?.let { Pill(it.uppercase(), QuotaGreen) }
                }
            }
            if (state.refreshing) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { GitHubManager.refresh() }) { Icon(Icons.Outlined.Refresh, "Refresh") }
        }
    }
}

@Composable
private fun InstallCard(state: GitHubManager.State, cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    StepCard(1, "Install GitHub CLI", null, cardBg, cardBorder) {
        if (GitHubManager.remote) {
            Hint("The GitHub CLI (gh) isn't installed on your server. Install it there, then tap Refresh. For example:")
            Mono("sudo apt install gh        # Debian / Ubuntu\nsudo dnf install gh        # Fedora\nbrew install gh            # macOS / Homebrew")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openUrl(context, GitHubManager.INSTALL_DOCS_URL) }) { Text("Install guide") }
                Button(onClick = { GitHubManager.refresh() }) { Text("Refresh") }
            }
            return@StepCard
        }
        if (!state.installing) {
            Hint("gh isn't installed in the terminal yet. This installs the gh package with pkg, so you can sign in to GitHub and use your repositories with git.")
            Button(onClick = GitHubManager::installGh) {
                Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Install gh")
            }
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
            Hint(state.installLog.lastOrNull { !it.startsWith("$") } ?: "Starting…")
        }
        if (state.installLog.isNotEmpty()) LogBox(state.installLog)
        state.installError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun SignInCard(state: GitHubManager.State, cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    StepCard(1, "Sign in to GitHub", null, cardBg, cardBorder) {
        state.ghVersion?.let { Hint(it) }
        state.authProblem?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        when (val login = state.login) {
            Login.Idle, is Login.Failed -> {
                Hint("Sign in with your browser: GitHub shows a short code to confirm, then git, the terminal and your agents can use your repositories, private ones too.")
                if (login is Login.Failed) {
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)) {
                        Text(login.message, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth().padding(12.dp))
                    }
                }
                Button(onClick = GitHubManager::startBrowserLogin) {
                    Icon(Icons.Outlined.Login, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Sign in with browser")
                }
                TokenLogin()
            }
            Login.Starting -> Busy("Asking GitHub for a sign-in code…") { GitHubManager.cancelLogin() }
            is Login.WaitingForBrowser -> {
                Text("Your one-time code", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)) {
                    Text(
                        login.code, fontFamily = FontFamily.Monospace, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 4.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                Hint("1. Tap the button below: the code is copied and GitHub opens.\n2. Paste the code and approve AntiGem's access.\n3. Come back here; sign-in finishes by itself.")
                Button(onClick = {
                    clipboard.setText(AnnotatedString(login.code))
                    openUrl(context, login.url)
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.OpenInBrowser, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Copy code & open GitHub")
                }
                Busy("Waiting for you to approve in the browser…") { GitHubManager.cancelLogin() }
            }
            Login.Finishing -> Busy("Finishing sign-in and setting up git…", onCancel = null)
        }
    }
}

@Composable
private fun TokenLogin() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) {
        Text(if (open) "Hide token sign-in" else "Use a personal access token instead", fontSize = 12.5.sp)
    }
    if (!open) return
    Hint("Classic tokens need the repo and read:org scopes (workflow too, to push GitHub Actions files). Fine-grained tokens work as well.")
    TextButton(onClick = { openUrl(context, GitHubManager.TOKEN_URL) }, contentPadding = PaddingValues(0.dp)) {
        Text("Create a token on GitHub", fontSize = 12.5.sp)
    }
    OutlinedTextField(
        value = token, onValueChange = { token = it.trim() }, label = { Text("Token") }, singleLine = true,
        placeholder = { Text("ghp_… or github_pat_…") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
    Button(enabled = token.isNotEmpty(), onClick = { GitHubManager.loginWithToken(token); token = "" }) { Text("Sign in with token") }
}

@Composable
private fun Busy(text: String, onCancel: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
        if (onCancel != null) TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun AccountCard(state: GitHubManager.State, cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    val account = state.account
    var confirmLogout by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Account", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Pill("SIGNED IN", QuotaGreen)
            }
            if (account == null) {
                Hint(state.accountError ?: "Loading account details…")
            } else {
                account.bio?.let { Text(it, fontSize = 12.5.sp) }
                DetailRow("Username", account.login, mono = true)
                DetailRow("Name", account.name)
                DetailRow("Email", account.email ?: "Private")
                DetailRow("Company", account.company)
                DetailRow("Location", account.location)
                DetailRow("Website", account.blog)
                DetailRow("Repositories", "${account.publicRepos} public" + (account.privateRepos?.let { " · $it private" } ?: ""))
                DetailRow("Followers", "${account.followers} followers · ${account.following} following")
                DetailRow("Member since", account.createdAt?.take(10))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            DetailRow("Token scopes", state.scopes, mono = true)
            DetailRow("Token stored in", state.tokenSource?.let { if (it.contains('/')) it.substringAfterLast('/') else it }, mono = true)
            DetailRow("Git protocol", state.gitProtocol, mono = true)
            DetailRow("GitHub CLI", state.ghVersion?.removePrefix("gh version ")?.substringBefore(' '), mono = true)
            DetailRow("Runs on", if (GitHubManager.remote) "Server" else "This phone")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                account?.let { OutlinedButton(onClick = { openUrl(context, it.htmlUrl) }) { Text("Open profile") } }
                OutlinedButton(
                    enabled = state.busy == null,
                    onClick = { confirmLogout = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Sign out") }
            }
            state.busy?.let { Busy(it, onCancel = null) }
        }
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Sign out of GitHub?") },
            text = { Text("gh forgets this login, so git can no longer push or reach private repositories" +
                (if (GitHubManager.remote) " on the server" else " in the terminal") + " until you sign in again. Cloned projects stay where they are.") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; GitHubManager.logout() }) { Text("Sign out", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String?, mono: Boolean = false) {
    if (value.isNullOrBlank()) return
    Row(verticalAlignment = Alignment.Top) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), modifier = Modifier.width(110.dp))
        Text(value, fontSize = 12.5.sp, fontFamily = if (mono) FontFamily.Monospace else null, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun GitSetupCard(state: GitHubManager.State, cardBg: Color, cardBorder: BorderStroke) {
    val account = state.account
    var name by remember(state.gitUserName, account) { mutableStateOf(state.gitUserName ?: account?.name ?: account?.login.orEmpty()) }
    var email by remember(state.gitUserEmail, account) { mutableStateOf(state.gitUserEmail ?: account?.email ?: account?.noreplyEmail.orEmpty()) }
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Git", fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
            if (!state.gitInstalled) {
                Text("git isn't installed" + (if (GitHubManager.remote) " on the server." else ". Run \"pkg install git\" in the terminal."),
                    fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error)
                return@Column
            }
            CheckRow("git uses your GitHub login", state.gitCredentialHelper)
            if (state.gitCredentialHelper) {
                Hint("git clone, pull and push over https://github.com use this account, in the terminal, the IDE and agent commands" +
                    (if (GitHubManager.remote) " on the server." else "."))
            } else {
                Hint("Without this, git asks for a password (and fails) on private repositories and on push.")
                Button(enabled = state.busy == null, onClick = { GitHubManager.setupGit() }) { Text("Set up git") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Text("Commit identity", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Hint(if (state.gitUserName == null || state.gitUserEmail == null) "Not set yet: git won't commit until it knows who you are."
                else "New commits are signed as ${state.gitUserName} <${state.gitUserEmail}>.")
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("user.name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = email, onValueChange = { email = it.trim() }, label = { Text("user.email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth()
            )
            if (account != null && account.email == null) Hint("Your GitHub email is private; ${account.noreplyEmail} keeps it private and still links commits to you.")
            val changed = name.trim() != state.gitUserName.orEmpty() || email.trim() != state.gitUserEmail.orEmpty()
            Button(enabled = changed && name.isNotBlank() && email.contains('@') && state.busy == null,
                onClick = { GitHubManager.setGitIdentity(name, email) }) { Text("Save") }
        }
    }
}

private const val REPO_PAGE = 30

@Composable
private fun ReposCard(state: GitHubManager.State, cardBg: Color, cardBorder: BorderStroke, onClone: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var shown by remember { mutableIntStateOf(REPO_PAGE) }
    val q = query.trim()
    val filtered = remember(state.repos, q) {
        if (q.isEmpty()) state.repos
        else state.repos.filter { it.fullName.contains(q, true) || it.description?.contains(q, true) == true || it.language?.contains(q, true) == true }
    }
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Repositories" + if (state.reposLoaded) " (${state.repos.size})" else "", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (state.reposLoading) CircularProgressIndicator(Modifier.padding(8.dp).size(18.dp), strokeWidth = 2.dp)
                else IconButton(onClick = GitHubManager::loadRepos) { Icon(Icons.Outlined.Refresh, "Reload repositories", Modifier.size(20.dp)) }
            }
            state.reposError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            if (state.repos.isNotEmpty()) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it; shown = REPO_PAGE }, singleLine = true,
                    placeholder = { Text("Search repositories") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (state.reposLoaded && !state.reposLoading) {
                Hint("No repositories yet.")
            } else if (state.reposLoading) {
                Hint("Loading your repositories…")
            }
            filtered.take(shown).forEachIndexed { i, repo ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                RepoRow(repo, ownLogin = state.account?.login, onClone = { onClone(repo.cloneUrl) })
            }
            if (filtered.size > shown) {
                TextButton(onClick = { shown += REPO_PAGE }) { Text("Show more (${filtered.size - shown} left)") }
            } else if (q.isNotEmpty() && filtered.isEmpty()) {
                Hint("No repository matches \"$q\".")
            }
        }
    }
}

@Composable
private fun RepoRow(repo: GitHubManager.Repo, ownLogin: String?, onClone: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    Box {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { menu = true }.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(if (repo.isPrivate) Icons.Outlined.Lock else Icons.Outlined.Book, null, tint = dim, modifier = Modifier.size(15.dp))
                Text(
                    if (repo.owner.equals(ownLogin, true)) repo.name else repo.fullName,
                    fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                )
                if (repo.isPrivate) Pill("Private", Amber)
                if (repo.isFork) Pill("Fork", MaterialTheme.colorScheme.primary)
                if (repo.isArchived) Pill("Archived", dim)
            }
            repo.description?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f), maxLines = 2, overflow = TextOverflow.Ellipsis) }
            val meta = listOfNotNull(repo.language, if (repo.stars > 0) "★ ${repo.stars}" else null, repo.pushedAt?.take(10)?.let { "Updated $it" })
            if (meta.isNotEmpty()) Text(meta.joinToString("  ·  "), fontSize = 11.sp, color = dim)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Clone and open") }, leadingIcon = { Icon(Icons.Outlined.CloudDownload, null) },
                onClick = { menu = false; onClone() })
            DropdownMenuItem(text = { Text("Open on GitHub") }, leadingIcon = { Icon(Icons.Outlined.OpenInBrowser, null) },
                onClick = { menu = false; openUrl(context, repo.htmlUrl) })
            DropdownMenuItem(text = { Text("Copy clone URL") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                onClick = { menu = false; clipboard.setText(AnnotatedString(repo.cloneUrl)) })
        }
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
