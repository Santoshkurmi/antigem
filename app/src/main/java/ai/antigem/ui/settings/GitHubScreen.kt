package ai.antigem.ui.settings

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
import ai.antigem.data.daemon.ProjectItem
import ai.antigem.data.github.GitHubManager
import ai.antigem.data.github.GitHubManager.Login
import ai.antigem.theme.QuotaGreen
import ai.antigem.ui.components.bouncyVerticalScroll
import ai.antigem.i18n.L

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
                    IconButton(onClick = GitHubManager::clearMessage) { Icon(Icons.Default.Close, L.common.dismiss, Modifier.size(18.dp)) }
                }
            }
        }

        when {
            state.ghInstalled == null -> Hint(if (GitHubManager.remote) L.settings.gitHub.checkingTheServerForTheGithub else L.settings.gitHub.checkingTheTerminalForTheGithub)
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
        ai.antigem.ui.components.GitCloneDialog(
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
                    model = account.avatarUrl, contentDescription = L.settings.gitHub.loginSAvatar(account.login),
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
                    state.signedIn -> L.common.signedIn
                    state.ghInstalled == false -> L.settings.gitHub.githubCliNotInstalled
                    state.ghInstalled == true -> L.settings.gitHub.notSignedIn
                    else -> "GitHub"
                }
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = when {
                    account != null -> "@${account.login}"
                    state.signedIn -> state.accountError ?: L.settings.gitHub.loadingAccount
                    state.ghInstalled == false -> if (GitHubManager.remote) L.settings.gitHub.installGhOnTheServerTo else L.settings.gitHub.installItBelowToGetStarted
                    state.ghInstalled == true -> L.settings.gitHub.signInBelow
                    else -> L.common.checking
                }
                Text(subtitle, fontSize = 12.sp, color = onSurfaceDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (GitHubManager.remote) Pill("SERVER", MaterialTheme.colorScheme.primary)
                    account?.plan?.let { Pill(it.uppercase(), QuotaGreen) }
                }
            }
            if (state.refreshing) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { GitHubManager.refresh() }) { Icon(Icons.Outlined.Refresh, L.common.refresh) }
        }
    }
}

@Composable
private fun InstallCard(state: GitHubManager.State, cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    StepCard(1, L.settings.gitHub.installGithubCli, null, cardBg, cardBorder) {
        if (GitHubManager.remote) {
            Hint(L.settings.gitHub.theGithubCliGhIsntInstalled)
            Mono("sudo apt install gh        # Debian / Ubuntu\nsudo dnf install gh        # Fedora\nbrew install gh            # macOS / Homebrew")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openUrl(context, GitHubManager.INSTALL_DOCS_URL) }) { Text(L.settings.gitHub.installGuide) }
                Button(onClick = { GitHubManager.refresh() }) { Text(L.common.refresh) }
            }
            return@StepCard
        }
        if (!state.installing) {
            Hint("gh isn't installed in the terminal yet. This installs the gh package with pkg, so you can sign in to GitHub and use your repositories with git.")
            Button(onClick = GitHubManager::installGh) {
                Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(L.settings.gitHub.installGh)
            }
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
            Hint(state.installLog.lastOrNull { !it.startsWith("$") } ?: L.common.starting)
        }
        if (state.installLog.isNotEmpty()) LogBox(state.installLog)
        state.installError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun SignInCard(state: GitHubManager.State, cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    StepCard(1, L.settings.gitHub.signInToGithub, null, cardBg, cardBorder) {
        state.ghVersion?.let { Hint(it) }
        state.authProblem?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        when (val login = state.login) {
            Login.Idle, is Login.Failed -> {
                Hint(L.settings.gitHub.signInWithYourBrowserGithub)
                if (login is Login.Failed) {
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)) {
                        Text(login.message, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth().padding(12.dp))
                    }
                }
                Button(onClick = GitHubManager::startBrowserLogin) {
                    Icon(Icons.Outlined.Login, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(L.settings.gitHub.signInWithBrowser)
                }
                TokenLogin()
            }
            Login.Starting -> Busy(L.settings.gitHub.askingGithubForASignIn) { GitHubManager.cancelLogin() }
            is Login.WaitingForBrowser -> {
                Text(L.settings.gitHub.yourOneTimeCode, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)) {
                    Text(
                        login.code, fontFamily = FontFamily.Monospace, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 4.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                Hint(L.settings.gitHub.n1TapTheButtonBelowThe)
                Button(onClick = {
                    clipboard.setText(AnnotatedString(login.code))
                    openUrl(context, login.url)
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.OpenInBrowser, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(L.settings.gitHub.copyCodeOpenGithub)
                }
                Busy(L.settings.gitHub.waitingForYouToApproveIn) { GitHubManager.cancelLogin() }
            }
            Login.Finishing -> Busy(L.settings.gitHub.finishingSignInAndSettingUp, onCancel = null)
        }
    }
}

@Composable
private fun TokenLogin() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) {
        Text(if (open) L.settings.gitHub.hideTokenSignIn else L.settings.gitHub.useAPersonalAccessTokenInstead, fontSize = 12.5.sp)
    }
    if (!open) return
    Hint(L.settings.gitHub.classicTokensNeedTheRepoAnd)
    TextButton(onClick = { openUrl(context, GitHubManager.TOKEN_URL) }, contentPadding = PaddingValues(0.dp)) {
        Text(L.settings.gitHub.createATokenOnGithub, fontSize = 12.5.sp)
    }
    OutlinedTextField(
        value = token, onValueChange = { token = it.trim() }, label = { Text(L.settings.gitHub.token) }, singleLine = true,
        placeholder = { Text("ghp_… or github_pat_…") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
    Button(enabled = token.isNotEmpty(), onClick = { GitHubManager.loginWithToken(token); token = "" }) { Text(L.settings.gitHub.signInWithToken) }
}

@Composable
private fun Busy(text: String, onCancel: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
        if (onCancel != null) TextButton(onClick = onCancel) { Text(L.common.cancel) }
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
                Text(L.common.account, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Pill("SIGNED IN", QuotaGreen)
            }
            if (account == null) {
                Hint(state.accountError ?: L.settings.gitHub.loadingAccountDetails)
            } else {
                account.bio?.let { Text(it, fontSize = 12.5.sp) }
                DetailRow(L.settings.gitHub.username, account.login, mono = true)
                DetailRow(L.common.name, account.name)
                DetailRow(L.settings.gitHub.email, account.email ?: L.common.private)
                DetailRow(L.settings.gitHub.company, account.company)
                DetailRow(L.common.location, account.location)
                DetailRow(L.settings.gitHub.website, account.blog)
                DetailRow(L.settings.gitHub.repositories, "${account.publicRepos} public" + (account.privateRepos?.let { " · $it private" } ?: ""))
                DetailRow(L.settings.gitHub.followers, L.settings.gitHub.followersFollowersFollowingFollowing(account.followers, account.following))
                DetailRow(L.settings.gitHub.memberSince, account.createdAt?.take(10))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            DetailRow(L.settings.gitHub.tokenScopes, state.scopes, mono = true)
            DetailRow(L.settings.gitHub.tokenStoredIn, state.tokenSource?.let { if (it.contains('/')) it.substringAfterLast('/') else it }, mono = true)
            DetailRow(L.settings.gitHub.gitProtocol, state.gitProtocol, mono = true)
            DetailRow(L.settings.gitHub.githubCli, state.ghVersion?.removePrefix("gh version ")?.substringBefore(' '), mono = true)
            DetailRow(L.settings.gitHub.runsOn, if (GitHubManager.remote) L.common.server else L.common.thisPhone)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                account?.let { OutlinedButton(onClick = { openUrl(context, it.htmlUrl) }) { Text(L.settings.gitHub.openProfile) } }
                OutlinedButton(
                    enabled = state.busy == null,
                    onClick = { confirmLogout = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(L.common.signOut) }
            }
            state.busy?.let { Busy(it, onCancel = null) }
        }
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(L.settings.gitHub.signOutOfGithub) },
            text = { Text(L.settings.gitHub.ghForgetsThisLoginSoGit((if (GitHubManager.remote) L.settings.gitHub.onTheServer else L.settings.gitHub.inTheTerminal))) },
            confirmButton = { TextButton(onClick = { confirmLogout = false; GitHubManager.logout() }) { Text(L.common.signOut, color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(L.common.cancel) } }
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
            Text(L.settings.gitHub.git, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
            if (!state.gitInstalled) {
                Text("git isn't installed" + (if (GitHubManager.remote) L.settings.gitHub.onTheServer2 else ". Run \"pkg install git\" in the terminal."),
                    fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error)
                return@Column
            }
            CheckRow("git uses your GitHub login", state.gitCredentialHelper)
            if (state.gitCredentialHelper) {
                Hint("git clone, pull and push over https://github.com use this account, in the terminal, the IDE and agent commands" +
                    (if (GitHubManager.remote) L.settings.gitHub.onTheServer2 else "."))
            } else {
                Hint(L.settings.gitHub.withoutThisGitAsksForA)
                Button(enabled = state.busy == null, onClick = { GitHubManager.setupGit() }) { Text(L.settings.gitHub.setUpGit) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Text(L.settings.gitHub.commitIdentity, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Hint(if (state.gitUserName == null || state.gitUserEmail == null) L.settings.gitHub.notSetYetGitWontCommit
                else L.settings.gitHub.newCommitsAreSignedAsGitusername(state.gitUserName, state.gitUserEmail))
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("user.name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = email, onValueChange = { email = it.trim() }, label = { Text("user.email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth()
            )
            if (account != null && account.email == null) Hint(L.settings.gitHub.yourGithubEmailIsPrivateNoreplyemail(account.noreplyEmail))
            val changed = name.trim() != state.gitUserName.orEmpty() || email.trim() != state.gitUserEmail.orEmpty()
            Button(enabled = changed && name.isNotBlank() && email.contains('@') && state.busy == null,
                onClick = { GitHubManager.setGitIdentity(name, email) }) { Text(L.common.save) }
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
                Text(if (state.reposLoaded) L.settings.gitHub.repositoriesCount(state.repos.size) else L.settings.gitHub.repositories, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (state.reposLoading) CircularProgressIndicator(Modifier.padding(8.dp).size(18.dp), strokeWidth = 2.dp)
                else IconButton(onClick = GitHubManager::loadRepos) { Icon(Icons.Outlined.Refresh, L.settings.gitHub.reloadRepositories, Modifier.size(20.dp)) }
            }
            state.reposError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            if (state.repos.isNotEmpty()) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it; shown = REPO_PAGE }, singleLine = true,
                    placeholder = { Text(L.settings.gitHub.searchRepositories) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (state.reposLoaded && !state.reposLoading) {
                Hint(L.settings.gitHub.noRepositoriesYet)
            } else if (state.reposLoading) {
                Hint(L.settings.gitHub.loadingYourRepositories)
            }
            filtered.take(shown).forEachIndexed { i, repo ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                RepoRow(repo, ownLogin = state.account?.login, onClone = { onClone(repo.cloneUrl) })
            }
            if (filtered.size > shown) {
                TextButton(onClick = { shown += REPO_PAGE }) { Text(L.settings.gitHub.showMoreValueLeft(filtered.size - shown)) }
            } else if (q.isNotEmpty() && filtered.isEmpty()) {
                Hint(L.settings.gitHub.noRepositoryMatchesQ(q))
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
                if (repo.isPrivate) Pill(L.common.private, Amber)
                if (repo.isFork) Pill(L.settings.gitHub.fork, MaterialTheme.colorScheme.primary)
                if (repo.isArchived) Pill(L.settings.gitHub.archived, dim)
            }
            repo.description?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f), maxLines = 2, overflow = TextOverflow.Ellipsis) }
            val meta = listOfNotNull(repo.language, if (repo.stars > 0) "★ ${repo.stars}" else null, repo.pushedAt?.take(10)?.let { L.settings.gitHub.updatedValue(it) })
            if (meta.isNotEmpty()) Text(meta.joinToString("  ·  "), fontSize = 11.sp, color = dim)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(L.settings.gitHub.cloneAndOpen) }, leadingIcon = { Icon(Icons.Outlined.CloudDownload, null) },
                onClick = { menu = false; onClone() })
            DropdownMenuItem(text = { Text(L.settings.gitHub.openOnGithub) }, leadingIcon = { Icon(Icons.Outlined.OpenInBrowser, null) },
                onClick = { menu = false; openUrl(context, repo.htmlUrl) })
            DropdownMenuItem(text = { Text(L.settings.gitHub.copyCloneUrl) }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                onClick = { menu = false; clipboard.setText(AnnotatedString(repo.cloneUrl)) })
        }
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
