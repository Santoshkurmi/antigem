# 05 — Terminal & Linux Shell

AntiGem includes a full Linux command-line terminal directly on your Android phone. It is powered by Termux and an isolated Linux container, providing an authentic shell environment with access to standard Unix utilities, package managers, and programming languages.

---

## ⌨️ 1. Accessing the Terminal

To open the terminal at any moment:
- Tap the **Terminal (⌨️)** icon on the **Floating App Switcher**.
- Or, if you enabled the standalone launcher icon during setup, tap the **AntiTerm** or **Termux** icon from your Android app drawer or home screen (configured under **Settings → Terminal & Shell**).

---

## 🖥️ 2. Virtual Key Rows & Modifier Keys

Because mobile soft keyboards lack physical control keys, AntiGem provides an extra toolbar of dedicated terminal keys directly above your on-screen keyboard, laid out in **exactly two rows**:

### Row 1:
- **ESC**: Sends Escape (exits `vim` insert mode, closes menus, or interrupts search prompts).
- **/** (Slash): Enters a forward slash. **Repeats automatically while held down**.
- **-** (Dash / Hyphen): Enters a dash/minus symbol. **Repeats automatically while held down**.
- **HOME**: Jumps the cursor to the beginning of the current command line.
- **↑** (Up Arrow): Navigates backward through command history. **Repeats automatically while held down**.
- **END**: Jumps the cursor to the end of the current command line.
- **PGUP**: Scrolls the terminal buffer up one page. **Repeats automatically while held down**.

### Row 2:
- **↹** (Tab): Inserts a tab character or triggers shell autocompletion. **Repeats automatically while held down**.
- **CTRL**:
  - **Single tap (One-Shot)**: Arms Control for the next single keypress (e.g. tap `CTRL` then tap `C` to cancel a running program, or `CTRL` then `L` to clear the terminal).
  - **Long-press (Locked)**: Locks `CTRL` in an active state until you long-press it a second time.
- **ALT**:
  - **Single tap (One-Shot)**: Arms Alt for the next single keypress.
  - **Long-press (Locked)**: Locks `ALT` until long-pressed again.
- **←** (Left Arrow): Moves the cursor left. **Repeats automatically while held down**.
- **↓** (Down Arrow): Moves the cursor forward through command history. **Repeats automatically while held down**.
- **→** (Right Arrow): Moves the cursor right. **Repeats automatically while held down**.
- **PGDN**: Scrolls the terminal buffer down one page. **Repeats automatically while held down**.

---

## 📑 3. Managing Sessions & Tabs (`tmux`)

AntiGem manages terminal sessions using **tmux**, ensuring long-running builds, servers, or scripts do not terminate when you switch workspaces or lock your phone.

### The Draggable Floating Tab Pill:
Instead of a static tab bar, AntiGem uses a compact, floating pill anchored at the top right of the terminal screen:
- **Dragging**: You can touch and drag the pill anywhere on your screen so it never covers the text you are reading.
- **Active Session Indicator**: Displays a status dot (🟢 green for an active running session, 🔴 red for an exited session) and the active session name.
- **Header Actions**:
  - **Switch Tab** (dropdown arrow): Opens the active sessions list.
  - **Maximize** / **Minimize / Restore**: Toggles full-screen expansion.
  - **Retry**: Appears when a session has exited or failed to reconnect.
  - **Close**: Dismisses the terminal dialog and returns to your previous workspace.

### Switching & Adding Sessions:
Tap the **Switch Tab** arrow on the floating pill to open the sessions menu:
- Each open session is listed with its status dot (green or red), session title, and an individual **Close Tab** (X) icon.
- Tap any session row to switch to it instantly.
- **New Session**:
  - **Tap**: Creates a new default shell session.
  - **Long-press**: Forces the creation of a Bash shell session (shows toast: *"Created Bash session"*).

### Closing Sessions:
- Tapping the **Close Tab** (X) button on a session row opens the confirmation dialog:
  - **Title**: **Close Tab?**
  - **Message**: *"Are you sure you want to close \"<name>\"? Any process running in this session will be terminated."*
  - **Buttons**: Tap **Close Tab** to confirm, or **Cancel** to abort.
- Alternatively, typing `exit` inside the shell and pressing Enter closes that session.
- When the last remaining session is closed, the terminal automatically closes itself.
- When opening the terminal, a spinner with *"Restoring active terminal sessions..."* appears while previous sessions are re-attached.

---

## 🔍 4. Pinch Zoom & Text Sizing

- You can resize terminal text at any time by performing a **two-finger pinch gesture** directly on the terminal view.
- Pinching smoothly steps font sizes between **16 sp** and **60 sp**.
- The chosen font size is automatically saved to your app preferences and remembered across sessions.

---

## ⚙️ 5. Terminal Settings Reference (`Settings → Terminal & Shell`)

Under **Settings → Terminal & Shell**, you can configure whether the shell executes locally or remotely, manage the rootfs, and customize appearance:

### A. Terminal Execution Mode:
- **Local Mode** (*"Built-in chroot rootfs environment"*): Shell commands execute directly on your device.
- **Use Terminal via SSH** (*"Direct SSH to Termux sshd without local rootfs"*):
  - Toggle switch to route the terminal over network SSH.
  - Fields: **SSH Host**, **Port** (e.g. `8022`), **User**, and **Password**.
  - **Test SSH Connection**: Runs a test handshake and displays status (*"✓ SSH connection successful!"* or error details).
  - **Save SSH Settings**: Saves credentials and displays *"✓ SSH settings saved successfully!"*.
- **Remote Server Mode**: If Remote Server mode is turned on in **Settings → Servers & Connectivity**, the card title changes to **Terminal on the remote server** (*"Runs on your remote server over SSH"*), displaying note:
  > *"Remote server mode is on: the terminal and shell runs use the SSH login from Settings > Servers (<user>@<host>:<port>). Nothing to set here."*

### B. Embedded Linux Rootfs (Local Tools):
- **Status Indicator**:
  - **Installed and ready** (badge: **Installed ✓**)
  - Or **Not installed (>30MB download)** (badge: **Not Installed**)
- **Buttons**:
  - If installed: **Delete All & Factory Reset** (prompts with safety dialog to wipe local rootfs).
  - If not installed: **Download & Install Local Tools** (opens the full-screen installer).

### C. Standalone Launcher Icon:
- **Separate Terminal App Icon**: Toggle to show an independent icon in your Android launcher.
- **Launcher Icon & Name Style**:
  - **AntiTerm**: Modern icon matching the AntiGem aesthetic.
  - **Termux**: Classic black `>_` prompt icon and name.

---

## 🛠️ Troubleshooting Terminal Issues

### ❌ Symptom: My terminal tab disappeared or reopened with my old session.
- **Explanation**: AntiGem uses persistent **tmux** sessions. When you reopen the terminal, it connects to existing background sessions (*"Restoring active terminal sessions..."*). Your running programs, command history, and shell state are preserved.

### ❌ Symptom: I cannot type inside the terminal.
- **How to fix**: Tap anywhere on the terminal screen to focus the view and trigger your on-screen keyboard. Use the virtual key rows for terminal-specific keys like `CTRL`, `ALT`, `ESC`, and arrows.

### ❌ Symptom: The floating tab pill or Close button is missing.
- **How to fix**: The floating tab pill is draggable and may have been moved toward the edge of your screen. Look around the screen borders and drag it back toward the center or top-right.

### ❌ Symptom: SSH test fails with "Connection refused".
- **How to fix**:
  1. Verify the remote computer or Termux instance has OpenSSH server running (`sshd`).
  2. Check **SSH Host**, **Port** (e.g. `8022` for Termux or `22` for standard Linux), **User**, and **Password**.
  3. Tap **Test SSH Connection** to verify reachability.

### ❌ Symptom: Text is too small or too large to read comfortably.
- **How to fix**: Use a two-finger pinch gesture directly on the terminal screen to zoom in or zoom out between 16 and 60 font size.
