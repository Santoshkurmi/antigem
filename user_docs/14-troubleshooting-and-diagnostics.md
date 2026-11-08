# 14 — Self-Help, Troubleshooting & Diagnostics

When something in AntiGem doesn’t work as expected, you don’t need to be a developer to diagnose and fix it. This guide provides plain-language troubleshooting steps for the most common issues, along with instructions on how to use AntiGem's built-in diagnostic tools.

---

## 🚦 Quick Diagnostic Health Check: What Healthy Looks Like

Before troubleshooting, check the status indicators across the app:
- 🟢 **Top Bar Dot (Green)**: The AI daemon is running normally and connected.
- 🟡 **Top Bar Dot (Yellow)**: The agent is busy thinking or executing a multi-step task.
- 🔴 **Top Bar Dot (Red)**: The app is disconnected from the local daemon or remote server.

---

## 🔌 1. Connection & Server Issues

### ❌ Symptom: Red status dot or "Failed to connect to daemon".
- **What to check**: Check whether the background Android service is active.
- **How to fix**:
  1. Swipe down your Android notification shade: check if the persistent **"AntiGem Service"** notification is visible. If not, open Android **Settings → Apps → AntiGem → Battery** and set to **Unrestricted**.
  2. If you are running locally on your phone, open **Settings → Servers & Connectivity** and make sure **Remote Mode** is turned **OFF**.
  3. Open **Android Settings → Apps → AntiGem → Storage → "Manage space" → AntiGem Space Manager**, tap **Clear Temporary Cache**, force-close the app from your Android Recent Apps switcher, and reopen it.

### ❌ Symptom: Remote Server Mode says "Connection Refused" or "Host Unreachable".
- **What to check**: Verify Wi-Fi network and firewall settings on your host machine.
- **How to fix**:
  1. Verify that your phone and your computer are on the exact same Wi-Fi network. Phones on cellular data (5G/LTE) cannot connect to local addresses like `192.168.x.x`.
  2. Check firewall rules on your computer to ensure ports `50051` (gRPC) and `8080` (IDE Bridge) allow inbound connections.

---

## 🤖 2. AI & Generation Issues

### ❌ Symptom: The agent is stuck in an endless thinking loop or won't finish.
- **What to check**: Check whether the agent is waiting on a long command or slow network request.
- **How to fix**: Look at the bottom-right corner of the chat input bar. While an agent is generating, the Send button turns into a **Red Square (Stop button)**. Tap it to halt execution immediately. Then ask the agent: *"What step were you working on?"* to diagnose the bottleneck.

### ❌ Symptom: "Error 429: Rate Limit Exceeded" or "Quota Exhausted".
- **What to check**: Check your token usage details by opening the top bar info icon (**Chat Telemetry & Tokens**).
- **How to fix**:
  1. Open the Model Selector bottom sheet by tapping the model pill at the top of Chat.
  2. Tap the refresh icon to re-check your 5-hour and 7-day quota windows.
  3. Select a faster or lighter model from your available model list, or wait a few minutes for the rate limit window to refresh.

---

## ⌨️ 3. Terminal & Linux Issues

### ❌ Symptom: A terminal command is frozen, typing does nothing.
- **What to check**: Check if the command is waiting for confirmation or trapped in a loop.
- **How to fix**:
  1. Tap the virtual **CTRL** key above your keyboard, then press **C** (`CTRL+C`). This sends an interrupt signal to stop the stuck program.
  2. If still unresponsive, open a new terminal session tab by tapping **(+) New Session** at the top of the terminal screen, and close the stuck tab with `exit`.

### ❌ Symptom: "No space left on device" error in the terminal.
- **What to check**: Check available internal storage in your phone's Android Settings.
- **How to fix**: Ensure you have at least 1 GB of free phone storage. Go to **Android Settings → Apps → AntiGem → Storage → "Manage space" → AntiGem Space Manager** and tap **Clear Temporary Cache**.

---

## 🎙️ 4. Jarvis & Voice Assistant Issues

### ❌ Symptom: Jarvis does not hear you or the voice orb stays cyan.
- **What to check**: Check Android microphone permissions and speech recognition state.
- **How to fix**:
  1. Open Android **Settings → Apps → AntiGem → Permissions → Microphone** and set it to **Allow**.
  2. Test speech recognition on the Chat screen by tapping the small microphone icon in the text input bar. If voice dictation works there, your microphone is healthy.
  3. Wake phrase detection ("Hey Jarvis") requires the phone screen to be on unless background listening is enabled in **Settings → Jarvis → Waking Jarvis**.

### ❌ Symptom: Jarvis speaks with no sound.
- **What to check**: Check media volume and TTS settings.
- **How to fix**:
  1. Ensure your phone’s media volume slider is turned up (not Ring or Notification volume).
  2. Open **Settings → Jarvis → Voice output** and tap **Test Voice**. If you hear nothing, change the voice engine or language setting.

---

## 🔄 5. App Updates & Update Failures

### ❌ Symptom: An update is available, but the download fails or the installer errors.
- **What to check**: Check internet connection and Android "Install unknown apps" permission.
- **How to fix**:
  1. In the **App Update Dialog**, check the download progress bar.
  2. If the package installer fails to launch, grant AntiGem permission to install APKs in Android **Settings → Apps → AntiGem → Install unknown apps**.
  3. Alternatively, tap the browser link inside the update dialog to download the latest APK directly from GitHub Releases.

---

## ⚙️ 6. Settings & Visibility Issues

### ❌ Symptom: A settings page I read about is missing.
- **What to check**: Check your installed app build type and active operating mode:
  - **GitHub**: Only appears in the `com.termux` package build (`LocalEnvironmentManager.isTermuxPackage`). In a standalone build, the GitHub settings page is not shown at all.
  - **Termux Support (Termux:API & X11 Desktop)**: Only appears in the `com.termux` package build. If the build is not the Shared UID build, the row shows *"Standalone"* with a warning, and external Termux:API is disabled.
  - **Backups & Restore**: Only appears when local Linux tools are installed AND you are in local mode. In remote/server mode, Backups is hidden because backups apply to the phone's local environment.
  - **Utilities**: Does not exist as a page in the UI because its former tools (GitHub, ADB Connect, and Android Automation) now each have their own prominent top-level rows.
  - **Terminal Row**: Shows *"SSH"* when SSH mode or remote server mode is on, and *"Local"* otherwise.
  - **Diagnostics Row**: Shows *"HUD on"* while the floating diagnostics HUD is enabled.

---

## 📊 7. Using AntiGem's Built-in Diagnostics Tools

AntiGem provides dedicated diagnostic instrumentation under **Settings → Diagnostics & Performance** (*"Live network connections, active streams & thread HUD"*):

### 1. Floating Diagnostics HUD
- **How to turn on**: Open **Settings → Diagnostics & Performance** and toggle **Floating Diagnostics HUD** to ON (displays badge: **HUD on**).
- **What you will see**: A draggable floating pill overlay showing real-time health metrics. Tap the floating pill to expand the detailed panel, which displays:
  - **Thread Groups: ...**: Live summary of active worker thread groups across the app.
  - **AgyGrpc Hub:**: Live streams, active RPCs, queue depth, and connection pool state (`Run=... | Q=... | Pool=... (... idle)`).
  - **IDE Bridge:**: Live HTTP bridge calls, active queue, and connection pool state (`Run=... | Q=... | Pool=... (... idle)`).
  - **Blocked Socket Reads:**: Thread traces currently waiting on blocking network socket reads.
  - Tap **Hide HUD** at the bottom-right of the expanded panel to dismiss the overlay.

### 2. Network Inspector (Live Logs)
- **Title**: **Network Inspector (Live Logs)** (*"Inspect IDE Bridge HTTP & AGY Daemon gRPC calls in real-time"*).
- **Zero-Overhead Toggle**: Toggling the inspector prompts a **Restart Required** confirmation dialog (*"Enabling Network Inspector requires restarting the app so OkHttp can attach live routing interceptors. Restart now?"* or *"Disabling Network Inspector requires restarting the app to restore 100% native network performance with zero debug interceptors. Restart now?"*) with buttons **Restart App** and **Cancel**.
- **Floating Network Bubble**: When enabled, toggle **Floating Network Bubble** (*"Show on-screen draggable pill with live call count & streams"*) to show an on-screen pill with live call counts.
- **How to use**: Tap **Open Network Inspector (N calls recorded)**.
- **Filters & Search**:
  - Filter by category chips: **All**, **gRPC Daemon**, **IDE Bridge**, **Streams**, and **Errors**.
  - Search field with hint: *"Filter by endpoint, route, or status..."*.
  - View full request method, URL path, latency duration, status codes, and payloads.
  - Tap **Clear logs** to reset the captured log buffer.
  - Empty states: *"No network calls captured yet"* (when nothing has been logged) or *"No matching calls found"* (when search filters match nothing).

### 3. Browser Diagnostic Logs
- While using the built-in browser, tap the bottom telemetry indicator to view live logs:
  - Tap **Show Diagnostic Logs (N)** to expand the recent console log entries and error traces.
  - Tap **Hide Diagnostic Logs** to collapse them.

### 4. How to Report a Bug
If you encounter an issue that cannot be resolved:
1. Open **Settings → Diagnostics & Performance**.
2. Review the **Current Telemetry Snapshot** and **Network Inspector (Live Logs)** for failed calls (red status badges).
3. In Chat, tap the server icon to view **Local Server Logs** (or **Server Logs (SSH)** when in remote mode).
4. Note the error message and failed status codes, and open an issue on the AntiGem GitHub repository.
