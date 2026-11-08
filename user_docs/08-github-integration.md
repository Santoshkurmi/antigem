# 08 — GitHub Integration

GitHub is the world's most popular platform for hosting and sharing code. AntiGem integrates directly with the official GitHub CLI (`gh`), allowing you to authenticate, clone your repositories into local or server workspaces, configure Git credentials and commit signatures, and launch new repositories.

---

## 🧭 Overview & Environment Detection

> ℹ️ **Build Requirement**: The GitHub settings page appears only in the `com.termux` package build (`LocalEnvironmentManager.isTermuxPackage`). In standalone builds, the GitHub page is hidden from settings.

When you navigate to **Settings → GitHub**, AntiGem checks whether the GitHub CLI is available:
- **Checking**: You will see *"Checking the terminal for the GitHub CLI…"* (or *"Checking the server for the GitHub CLI…"* when connected in Server mode).
- **Environment Badge**: An indicator pill displays **Runs on: This phone** or **Runs on: Server**, ensuring you know whether commands and credentials live locally in Termux or on your remote server.
- **CLI Not Installed**: If `gh` is missing, an **Install GitHub CLI** card appears. On your phone, tap **Install gh** to install the package automatically via `pkg`. On a remote server, commands are suggested (`sudo apt install gh`, `sudo dnf install gh`, or `brew install gh`) alongside an **Install guide** button and a **Refresh** button.

---

## 🔑 1. Signing In to GitHub

Once `gh` is detected (showing its version, e.g. **gh version** `2.x.x`), the **Sign in to GitHub** step card offers two authentication options:

### Method A: Browser Sign-In (Recommended)
1. Tap **Sign in with browser**.
2. AntiGem contacts GitHub and displays **Your one-time code** — a large, bold monospace code.
3. Tap **Copy code & open GitHub**. The one-time code is immediately copied to your clipboard and your browser opens to `github.com/login/device`.
4. In your browser, paste the one-time code and approve access for AntiGem.
5. Return to AntiGem: the prompt changes from *"Waiting for you to approve in the browser…"* to *"Finishing sign-in and setting up git…"*, and automatically loads your profile.

### Method B: Personal Access Token Sign-In
1. Below the browser button, tap **Use a personal access token instead** (tap **Hide token sign-in** to collapse it).
2. If you do not have a token yet, tap **Create a token on GitHub**. Classic tokens require the `repo` and `read:org` scopes (plus `workflow` to manage GitHub Actions); fine-grained tokens work as well.
3. Enter or paste your token into the **Token** field (starts with `ghp_…` or `github_pat_…`).
4. Tap **Sign in with token**.

---

## 👤 2. Account Details & Sign Out

Once signed in, the **Account** card displays your authenticated profile with a green **SIGNED IN** badge:
- **Profile information**: Displays your **Username**, **Name**, **Email**, **Company**, **Location**, **Website**, **Repositories** (e.g. `12 public · 5 private`), **Followers**, and **Member since** date.
- **Security & System Metadata**:
  - **Token scopes**: The OAuth or Personal Access Token scopes granted (e.g. `repo, read:org, workflow`).
  - **Token stored in**: The location of the authentication token on disk (e.g. `hosts.yml`).
  - **Git protocol**: Shows the active transfer protocol (`https`).
  - **GitHub CLI**: Shows the installed `gh` CLI version number.
  - **Runs on**: Shows **This phone** or **Server**.
- **Open profile**: Opens your public GitHub profile in your web browser.
- **Sign out**: Tap **Sign out** to open the **Sign out of GitHub?** confirmation dialog (*"gh forgets this login, so git can no longer push or reach private repositories on the server/in the terminal until you sign in again. Cloned projects stay where they are."*). Confirming signs you out cleanly.

---

## ⚙️ 3. Configuring Git & Commit Identity

The **Git** card ensures that local Git commands recognize your identity:

1. **Git Credential Helper**:
   - The card shows **git uses your GitHub login** with a checkmark if active.
   - If not active, a warning explains: *"Without this, git asks for a password (and fails) on private repositories and on push."*
   - Tap **Set up git** to run `gh auth setup-git`, configuring Git to use your token across the terminal, IDE, and autonomous agents.
2. **Commit identity**:
   - Displays your commit author credentials.
   - Enter your name into **user.name** and your email into **user.email**. If your primary email is private, GitHub's `@users.noreply.github.com` address is recommended.
   - Tap **Save** to write your identity to Git config.

---

## 📦 4. Browsing, Cloning & Managing Repositories

The **Repositories** card lists your repositories:
- **Reloading & Search**: Tap the refresh icon (**Reload repositories**) to fetch recent changes. Type into the **Search repositories** bar to filter your projects by name, language, or description.
- **Repository Badges**: Every repository lists its privacy status (**Private** or public), whether it is a **Fork**, or if it is **Archived**, along with its programming language, star count (`★`), and last updated date.
- **Repository Actions**: Tap any repository in the list to reveal its action menu:
  - **Clone and open**: Downloads the repository to your workspace storage and opens it directly in the AntiGem IDE as an active project.
  - **Open on GitHub**: Opens the repository's GitHub web page in your external browser.
  - **Copy clone URL**: Copies the HTTPS Git URL to your clipboard for use in scripts or terminal commands.
- **Paging**: Tap **Show more** at the bottom of the list to load additional repositories in batches of 30.

---

## 🚀 5. Creating New Repositories

AntiGem does not have a manual "New repository" form button on the GitHub settings screen. Instead, repository creation is streamlined through the assistant or the command line:

1. **Via Jarvis Voice & Tool Assistant**:
   - Tell Jarvis: *"Create a new private GitHub repository called my-cool-project"* or *"Put this project on GitHub"*.
   - Jarvis invokes the built-in `jarvis_github_create_repo` tool, which initializes the repository on GitHub, links it to your current project, and pushes your files automatically.
2. **Via Terminal**:
   - Open the **Terminal** screen and run:
     ```bash
     gh repo create my-new-repo --public --source=. --remote=origin --push
     ```
   - Because `gh` is already signed in and configured, the command runs without requiring any password prompts.

---

## 🛠️ Troubleshooting GitHub Issues

### ❌ Symptom: The card says "GitHub CLI not installed" or "Install gh" fails.
- **What to check**: Check internet connectivity and whether package repositories are up to date.
- **How to fix**:
  1. Open the **Terminal** screen.
  2. Run `pkg update && pkg install gh git`.
  3. Return to **Settings → GitHub** and tap the **Refresh** button on the top status card.

### ❌ Symptom: Cloning or pushing fails with "Permission denied" or "Authentication required".
- **What to check**: Check whether the Git credential helper is enabled.
- **How to fix**:
  1. Go to **Settings → GitHub** and look at the **Git** card.
  2. Ensure the row **git uses your GitHub login** is checked. If not, tap **Set up git**.
  3. If issues persist, verify that your token has the `repo` scope under **Token scopes**. If not, sign out and sign in again via **Sign in with browser**.

### ❌ Symptom: Git refuses to commit with "Author identity unknown".
- **What to check**: Check if **user.name** and **user.email** are configured.
- **How to fix**:
  1. Open **Settings → GitHub** and scroll to the **Commit identity** section.
  2. Fill in your name under **user.name** and a valid email under **user.email**.
  3. Tap **Save**.
