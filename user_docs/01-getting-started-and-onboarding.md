# 01 — Getting Started & First Launch

This guide walks you through opening AntiGem for the first time, granting system permissions, choosing your coding agents, setting up the local Linux environment, and configuring your launcher icon.

---

## 🚀 1. Welcome to AntiGem & Permissions (`AppPermissionsDialog`)

When you open AntiGem for the first time, a full-screen prompt titled **Welcome to AntiGem** appears (*"Allow a few things so AntiGem can run in the background and work with your files."*).

At the top, a progress indicator displays:
- **Permissions**: *"$allowedCount of 3 allowed"*

Below the progress bar are the three permission options:

1. **Notifications** (*"Keeps the background service running and shows progress"*):
   - **Required**. AntiGem runs background services (such as AI generation, local development servers, and shell commands). Without this permission, Android may terminate AntiGem's background process when you switch apps or lock your phone.
   - Tap **Allow**. If not yet granted, a prompt reads *"Allow notifications to continue"*. Once granted, the button switches to **Allowed** with a checkmark.
2. **Files** (*"Open projects and files on your storage"*):
   - **Optional**. Allows the AI agents, the built-in IDE, and the File Explorer to open, edit, and save project files in your device storage and Downloads directory. Tap **Allow** to grant access.
3. **Microphone** (*"Dictate messages and voice notes"*):
   - **Optional**. Enables live voice dictation and voice note recording in chat. Tap **Allow** to grant access.

- **Footnote**: *"You can change these anytime in Android settings."*
- **Action**: Tap **Continue** (enabled as soon as Notifications permission is granted).

---

## 🤖 2. Step 1: Choose Your Agents (`AgentsSetupUi`)

Next, the first setup step appears with a progress indicator reading **STEP 1 OF 2**:

- **Title**: **Choose your agents**
- **Subtitle**: *"Tick the coding agents you want to use. The app runs only the ones you pick; you can change this later in Settings → Agents."*

### Available Agent Options:
Nothing is preselected by default. You can choose any combination of the three supported agents:
- **Antigravity**: *"Google's coding agent through the agy hub: Gemini and Claude models on your Google plan."*
- **Claude Code**: *"Anthropic's Claude Code CLI on this device: your Claude subscription or Anthropic Console account."*
- **OpenCode**: *"Open-source coding agent: 200+ providers with your own keys, any OpenAI-compatible API, and free models."*

### Selection Rules & Buttons:
- If no agent is selected: The bottom button is disabled and reads **Pick at least one agent**.
- If one agent is ticked: The button reads **Continue with <agent>** (e.g. *"Continue with Antigravity"*).
- If several agents are ticked: The button reads **Continue with N agents** (e.g. *"Continue with 2 agents"*), and an informational note appears: *"With several agents on, pick the agent for each new chat on the empty chat screen."*
- When you tap continue, an **Applying agents** modal appears (*"Stopping the local server, then restarting the app…"*) with a spinner while the runtime reconfigures.

### Changing Agents Later:
You can reconfigure your active agents at any time in **Settings → Agents**:
- Select or unselect agents.
- If unchanged, the button displays **No changes**.
- If modified, the button displays **Apply and restart app**.
- Tapping it opens the confirmation dialog: **Restart to apply?** (*"The local server stops and the app restarts with the new agents, so nothing of a turned-off agent keeps running. Replies in progress stop, and a chat of a turned-off agent is closed."*).
- Tap **Restart app** to proceed (button shows a spinner and **Restarting…**), or **Cancel** to keep current settings.

---

## 📦 3. Setting Up the Local Linux Environment (`FullScreenLocalToolsInstaller`)

AntiGem checks whether the local Linux runtime is installed on your phone.

### Build-Dependent Titles & Options:
- **In the Termux build (`com.termux`)**:
  - Title: **Set up your Linux environment**
  - Subtitle: *"AntiGem runs its terminal, IDE and agents on a local Linux system. Choose how to install it."*
  - **Option 1: Full environment** (Tag: *"Recommended · ~350 MB"*): *"Verified rootfs with the tools and environment preconfigured."*
  - **Option 2: Minimal** (Tag: *"~33 MB"*): *"Clean Termux base for your device architecture: core utilities and the package manager."* (This lightweight option exists **only** in the Termux build).
  - **Option 3: From a ZIP file**: *"A bootstrap backup or custom rootfs on this phone."* (Tap **Choose file** / **Choose another file** to select an archive).
- **In the Standalone build**:
  - Title: **Set up the Ubuntu container**
  - Subtitle: *"AntiGem runs its terminal, IDE and agents on a local Linux system. Choose how to install it."*
  - **Option 1: Ubuntu ARM64 base** (Tag: *"Recommended"*): *"Official minimal Ubuntu rootfs with glibc, bash and dev tools."*
  - **Option 2: From a ZIP file**: *"A container backup or custom rootfs (bootstraps.zip) on this phone."* (Tap **Choose file** / **Choose another file**).

### Installation Flow & Buttons:
- Tap **Install (~350 MB)**, **Install minimal (~33 MB)**, **Install from ZIP**, or **Install Ubuntu base** (standalone).
- **Finding the latest release**: *"Checking for the latest verified archive."*
- **Ready to install**: *"Review the details and confirm."* Displays **Release**, **Architecture**, **Download size**, and package URL. Tap **Confirm & install**, or tap **Change source** to pick another option.
- **Installing**: *"This takes a few minutes. You can keep using the app meanwhile."* (Progresses through Downloading, Extracting, Configuring, and Verifying). Tap **Continue in background** to let installation proceed while you use the app.
- **Setup didn't finish**: If an error occurs, the screen shows *"You can try again, or install from a ZIP file instead."* with buttons **Try again**, **Change source**, and **Skip**.
- **You're all set**: Once complete, the screen confirms *"The shell, core tools and AGY tools are installed."* (or *"Ubuntu, bash, glibc and AGY tools are installed."* in standalone). Tap **Get started** to finish.
- **Skip Button**: Located in the top right corner. It allows bypassing local installation if you only plan to connect AntiGem to an external computer (see [Server Mode & Remote Access](12-server-mode-and-remote-access.md)). It is automatically hidden while downloading, extracting, or after success.
- **Installer Logs**: Tap **Show details** / **Hide details** to inspect live download and extraction stdout logs.

### 32-Bit Processor Notice:
If your phone has an older 32-bit processor:
- Title: **Not available on this phone**
- Description: *"Your phone has a 32-bit processor. The local Linux environment (terminal, IDE backend and agents running on the phone) needs a 64-bit one, so it can't be installed here."*
- Card: **You can still use AntiGem**:
  - *"Server mode: run the agents on a computer or server and connect to it"*
  - *"SSH: work in a terminal on another machine"*
- Action: Tap **Continue to AntiGem**.

---

## 🔘 4. Terminal App Setup (`TerminalLauncherOnboardingDialog`)

When step 2 of setup appears (or if triggered from settings), the dialog titled **Terminal App Setup** opens (*"Choose how you want to open the Linux command line on your device."*):

- **HOME SCREEN PREVIEW Card**:
  - Shows the main **AntiGem** app icon labeled *"Chat & IDE"*.
  - Beside it, the terminal app preview icon:
    - When the toggle is OFF: Shows **Hidden** in red with *"In-App only"* underneath.
    - When the toggle is ON: Shows **Termux** or **AntiTerm** with *"Terminal"* underneath.
- **Toggle: Standalone Terminal App Icon**:
  - Description: *"Places a dedicated icon in your app launcher for 1-tap terminal access"*.
  - OFF by default.
- **Icon Style Picker (`Select Icon & App Name:`)**:
  - Visible when the toggle is enabled:
    - **AntiTerm**: *"Modern unified icon matching AntiGem"* (styled to match the app).
    - **Termux**: *"Classic terminal >_ prompt icon & familiar name"* (traditional black Termux icon).
- **Actions**:
  - Tap **Save & Continue**.
  - **Footnote**: *"You can change this anytime in Settings."*
- **Settings Location**: You can change this anytime under **Settings → Terminal & Shell** via **Separate Terminal App Icon** and **Launcher Icon & Name Style**.

---

## 🧭 5. Navigating with the Floating App Switcher

After completing onboarding, you arrive at the **Chat** screen. A compact, floating dock hovers on your screen:
- **What it is**: The **Floating App Switcher** (`FloatingSwitcherWidget`), providing 1-tap navigation between all AntiGem workspaces:
  - 💬 **Chat**: Returns to your conversation with the AI.
  - 💻 **IDE**: Opens the code editor, project files, and Git tab.
  - ⌨️ **Terminal**: Opens your Linux command prompt.
  - 🌐 **Browser**: Opens the web preview browser to test websites or web apps.
  - 📝 **Notes**: Opens your quick notes and to-do lists.
  - 📁 **Files**: Opens the built-in file explorer to browse folders on your phone.
- **Moving it out of the way**: Touch and drag the switcher anywhere along the edges of your screen.
- **Auto-Collapse**: After a few seconds of inactivity, the switcher smoothly shrinks into a compact bubble icon so it never obstructs your view. Tap the bubble to expand the full dock again.

---

## 🫧 6. Android System Floating Bubble (Floating Chat)

Beyond the in-app switcher dock, AntiGem includes an Android system-level **Floating Bubble**:
- **What it is**: An overlay bubble that hovers on top of *any* Android application on your device (browser, documentation viewer, chat, or social media).
- **Permission**: Requires Android's **Display over other apps** permission.
- **Interactions**:
  - **Drag to Move**: Touch and drag the bubble to reposition it along any edge of the screen.
  - **Dismiss**: Drag the bubble downward into the **(X)** dismiss target at the bottom center of the screen to close it.
  - **Open Floating Chat**: Tap the bubble to open `FloatingChatActivity`, a floating pop-up window containing live chat and terminal sessions without leaving your current app.

---

## 🔄 7. App Updates (`AppUpdateDialog`)

When a newer version of AntiGem is released:
- A prompt titled **"New update available"** appears showing the newest version tag, release date, and a scrollable **"What's new"** release changelog.
- **Actions**:
  - **Update now**: Downloads the APK directly with live progress (*"Downloading update…"*) and prompts Android Package Installer (*"Installing update…"*) to update the app in-place.
  - **GitHub**: Opens the GitHub release page in your external browser to review source diffs or download manually.
  - **Later**: Postpones the update and dismisses the dialog.

---

## 🛠️ Troubleshooting & First-Launch Issues

### ❌ Symptom: Only one setup step appeared during onboarding.
- **What to check**: Check whether one of the steps was previously completed.
- **Explanation**: The onboarding header displays **STEP 1 OF 2** only when both the agent choice and the terminal icon setup are pending. If one was already completed earlier, AntiGem shows only the remaining step as a single, unnumbered step.

### ❌ Symptom: I picked the wrong agents during first launch.
- **How to fix**:
  1. Open **Settings → Agents**.
  2. Tick or untick your desired agents (**Antigravity**, **Claude Code**, or **OpenCode**).
  3. Tap **Apply and restart app**.
  4. Confirm the prompt: **Restart to apply?** by tapping **Restart app**. AntiGem will cleanly restart the internal servers with your new agent selection.

### ❌ Symptom: The "Minimal" install option (~33 MB) is missing.
- **What to check**: Check which APK build you have installed.
- **Explanation**: The **Minimal (~33 MB)** option is exclusive to the Termux (`com.termux`) package build. If you installed the Standalone build, the installer provides the **Ubuntu ARM64 base** instead.

### ❌ Symptom: I skipped local tools setup by mistake.
- **How to fix**:
  1. Open **Settings → Terminal & Shell**.
  2. Under **Embedded Linux Rootfs**, tap **Download & Install Local Tools**.
  3. Tap it to re-open the full-screen installer and complete the setup.

### ❌ Symptom: The notification permission prompt does not appear, or background tasks stop when the screen turns off.
- **What to check**: Check Android battery optimization settings. Android may be aggressively putting AntiGem to sleep.
- **How to fix**:
  1. Open your phone’s system **Settings → Apps → AntiGem**.
  2. Tap **Battery** (or **App battery usage**).
  3. Select **Unrestricted** (or turn off *Battery Optimization*).
  4. Ensure **Notifications** are set to **Allowed**.

### ❌ Symptom: Local tools download fails or freezes at 99%.
- **What to check**: Check available phone storage and internet stability.
- **How to fix**:
  1. Check your phone storage in Android Settings to ensure you have at least 2 GB of free space.
  2. Tap **Retry** on the installer screen.
  3. If it remains stuck, restart the app, tap **Skip for now**, open **Android Settings → Apps → AntiGem → Storage → "Manage space" → AntiGem Space Manager**, tap **Clear Temporary Cache**, and then go to **Settings → Terminal & Shell → Download & Install Local Tools**.

### ❌ Symptom: The screen is completely blank on first launch.
- **What to check**: The app may still be extracting internal startup assets into its private app directory.
- **How to fix**: Wait 15 seconds. If it does not load, swipe AntiGem away from your Android Recent Apps list and open it once more.
