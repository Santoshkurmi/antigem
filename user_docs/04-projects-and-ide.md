# 04 — Projects & Code Editor (IDE)

The **IDE (Integrated Development Environment)** in AntiGem provides a mobile-first code editor, project file manager, visual Git source control client, image/SVG previewer, and app runner directly on your Android phone.

---

## 💻 1. Accessing the IDE & Toolbar Controls

You can switch to the IDE at any time:
1. Tap the **IDE** icon on the floating app switcher pill.
2. Or, when an AI agent mentions a file in chat (e.g. `src/main.py#L25`), tap that link to jump directly into the code editor at that exact line.

### Top Toolbar Controls:
- **Toggle Sidebar** (folder/menu icon): Slides out the project file manager and project switcher.
- **Undo** & **Redo**: Step backward or forward through text edits in the active editor buffer.
- **Save File** (Save icon): Saves modifications to disk. For files opened from outside a project, this triggers the **Save As...** flow.
- **Run Configurations** (Play icon + dropdown arrow): Runs the active file or opens the execution profile menu.
- **Git** (Git branch icon): Opens visual Git source control.
- **AI Chat** (sparkle icon): Opens the Chat screen with the current file context pre-attached.
- **More editor options (⋮)**:
  - **Word Wrap** (with checkbox): Toggles line wrapping on and off for long code lines.
  - **Find in File**: Opens the interactive search bar.
  - **Preview SVG Graphic** / **Edit SVG XML Source** (only appears when an SVG file is open): Toggles between vector graphic preview and raw SVG XML source code.

---

## 📁 2. The Project Sidebar (`ProjectSidebar.kt`)

Tapping **Toggle Sidebar** slides out the workspace drawer:

### Project Selector Header:
- Displays the active project name (or **"Select Project"** if none selected) and its filesystem path (or **"No project opened"**).
- Tapping the header opens the project menu:
  - **Available Projects**: Lists your registered workspaces (or displays *"No projects found in Termux"*).
  - **Remove project**: Tap the trash icon on any project entry to remove it from your workspace list (project files remain safely on disk).
  - **Browse / File Manager...**: Opens the file picker to open any directory on your device.

### Sidebar Navigation & Action Bar:
- Tabs: **Explorer** (file tree) and visual Git source control.
- **Explorer Action Bar**:
  - **New File** (+ icon): Opens the **Create File** dialog.
  - **New Folder** (+ folder icon): Opens the **Create Folder** dialog.
  - **Refresh** (🔄 icon): Reloads the file tree from disk.

### File & Folder Context Menu (⋮):
Tap the three dots next to any file or folder to access:
- **New File**: Creates a new file inside that folder.
- **New Folder**: Creates a new subfolder inside that folder.
- **Rename**: Opens the **Rename File** or **Rename Folder** dialog.
- **Share**: Opens Android's system share chooser to share the file to other apps.
- **Delete**: Opens the **Delete File?** or **Delete Folder?** confirmation dialog.

### Empty & Offline States:
- When no project is open: *"Open or create a project to view files"*.
- When a folder contains no files: *"Project is empty"*.
- When the daemon is disconnected: *"IDE Server Offline"* with a **Retry Now** button.

---

## 📝 3. Editor Tabs, Find in File & Safety Dialogs

### Open File Tabs:
- **Multiple Open Tabs**: The top tab bar lets you switch between open files.
- **Close Tab** (× icon): Closes the active tab.
- **Selected Tab Indicator**: The active tab is highlighted in bold; in dropdown menus, it shows a **Selected** checkmark.
- **Modified State**: Unsaved buffers display an asterisk (`*`) next to the file name.
- **Read-Only**: Non-editable revision or system files display a locked padlock icon (**Read-Only**).

### Find in File Bar:
Tap **More editor options (⋮) → Find in File** to open the search bar:
- Search input with placeholder: *"Find in file..."*.
- **Previous Match** (↑ arrow) & **Next Match** (↓ arrow): Cycle through occurrences.
- **Clear** (× icon): Clears the search text.
- **Close Find** (× icon): Dismisses the find bar.
- **Match Count Indicator**: Displays current match position and total matches (e.g. `2/8` or `0 matches`).
- **Case Sensitive Toggle**: Toggle case-sensitive matching (`Aa` icon).

### Three Key Editor Safety Dialogs:
1. **Unsaved Changes Dialog**:
   - Closing a tab with unsaved edits displays:
     > *"Do you want to save the changes made to '<file>' before closing?"*
   - Buttons: **Save & Close** (saves to disk, then closes) and **Don't Save** (discards unsaved buffer and closes).
2. **File Conflict Detected Dialog**:
   - If a file is modified externally on disk or edited by an AI agent while you have unsaved changes in the editor, saving displays:
     > *"The file '<file>' has been modified on disk or by AI."*
     > *"Choose whether to overwrite the disk version with your editor changes or reload the version from disk."*
   - Buttons: **Overwrite Disk (Keep Mine)** (overwrites disk with your editor content) and **Revert to Disk** (discards editor changes and reloads the disk version).
3. **Non-Text File Warning Dialog**:
   - Opening a binary or non-text file displays:
     > *"'<file>' appears to be a non-text / binary file (<mime>)."*
     > *"Opening it in the text editor may display garbled characters, cause lag, or corrupt the file if saved."*
   - Buttons: **Open Anyway** (forces text editor open) and **Cancel** (aborts opening).
4. **Save As... Flow**:
   - When editing files opened from outside a project or via Android content intents, tapping Save opens the **Save As...** file picker to choose a destination folder and file name on the daemon filesystem.

---

## 🌿 4. Git & Source Control (`GitSourceControlView.kt`)

Tap the Git branch icon in the top toolbar to open visual source control:

### Changes & Commit History Sections:
- **STAGED CHANGES**: Lists files staged for commit. Tap **Stage All**, **Unstage All**, or use individual file stage/unstage icons. Tap any file to inspect diffs.
- **CHANGES**: Lists unstaged modified and untracked files. Tap **Discard All** to revert working tree changes.
- **CHANGED FILES (N)**: Displays the list and count of files affected in a selected commit.
- **COMMIT HISTORY**: Chronological log of past commits with commit message, author name, short hash, and timestamp.

### Branch Management:
- The top header displays the active Git branch.
- Tapping the branch pill opens the **Create & Checkout Branch** dialog:
  - Input field with hint: *"Branch name (e.g. feature/login)"*.
  - Buttons: **Create** and **Cancel**.
  - Toast feedback: *"Branch <name> created & active"* or *"Failed to create branch"*.

### Stashing Changes:
- Tap Stash (📦) to open the **Stash Changes** dialog:
  - Input field with hint: *"Stash message (optional)"*.
  - Toast feedback: *"Changes stashed!"*, *"Stash applied!"*, or *"Stash failed"*.

### Commits & Syncing:
1. Type a description into the **Commit message...** field.
2. Tap Commit to open the **Confirm Commit** dialog.
3. Confirming commits your staged changes, displaying the toast *"Committed successfully!"* (or *"Commit failed"*).
4. **Git Author Setup / Git Config**:
   - If `user.name` or `user.email` are not configured, attempting to commit opens the **Git Author Setup** (or **Git Configuration**) dialog.
   - Fields: **Author Name (user.name)** and **Author Email (user.email)**.
   - Validation note: *"Author name and email cannot be empty"*.

---

## ▶️ 5. Running Your Code (`IdeExecutionRunner.kt`)

To run code directly inside the IDE:
1. Tap the Play button (**Run Configurations**) in the top toolbar.
2. The dropdown menu provides:
   - Saved custom profiles: displays the profile name, command as subtitle, and a **"Selected"** checkmark on the currently active profile.
   - **"No custom profiles configured"** (disabled row displayed when no profiles are saved).
   - **"Add Run Configuration..."**: Opens the configuration dialog (shows the toast *"Open a project folder first"* if no project is open).
   - **"Edit launch.json in Editor"**: Opens `.ide/launch.json` directly in a text editor tab (shows the toast *"Open a project folder first"* if no project is open).

### Add Run Configuration Dialog:
- Note: *"Saved into .ide/launch.json in your workspace root."*.
- **Quick Presets:**: One-tap chips for *Python*, *Node.js*, *C Program*, *C++*, *TypeScript*, *PHP*, *Bash Shell*, *Rust*, *Go*, and *Dev Server*.
- **Profile Name**: Descriptive label for the profile.
- **Command to Execute**: Command line string with variable substitution.
- **Working Directory (CWD)**: Defaults to `${workspaceFolder}`.
- **Set as default run profile**: Checkbox to make this profile the primary one-tap runner.
- **Supported Variables**: `${file}`, `${fileBasename}`, `${fileBasenameNoExtension}`, `${fileDirname}`, `${workspaceFolder}`.
- Button: **Save Profile** (or **Add Run Configuration**).

### Execution Runner Panel:
When a command runs, the interactive execution runner view opens:
- **Header Line**: Displays the profile name and status:
  - 🟢 **"Running: <command>"** with a green dot while the process is active.
  - 🔴 **"Finished / Exited"** with a red dot once execution terminates.
- **Header Action Buttons**:
  - **Restart** (🔄): Kills the running process tree and re-runs the profile fresh.
  - **Stop** (■): Sends interrupt signal (`Ctrl+C`) to halt execution.
  - **Clear**: Resets the terminal screen and clears scrollback.
  - **Close** (×): Exits the runner view and returns to the code editor.
- **Interactive Terminal**: Full native PTY terminal view with font pinch-to-zoom and keyboard input.
- **Virtual Key Bar**: Dedicated touch keys: `"ESC"`, `"TAB"`, `"CTRL"`, `"ALT"`, `"↑"`, `"↓"`, `"←"`, `"→"`. Tap CTRL or ALT for one-shot, or long-press to lock.

---

## 🔍 6. Go Daemon Logs (`DaemonLogsDialog.kt`)

When troubleshooting daemon communication or local tool startup:
- The dialog title is **"Go Daemon Status & Logs"**.
- Displays **"Live Log Output:"** with colored log traces.
- Displays **"SSH Execution Output"** when running over a remote server bridge.
- Empty state: *"No log entries yet"*.
- Actions: **Retry** button (re-polls daemon output) and **Close** button.

---

## 🔬 7. Unified Diff Viewer (`UnifiedDiffViewer.kt`)

Tapping any changed file in Git source control opens the Unified Diff Viewer:
- **Tabs**:
  - **WORKING TREE**: Displays unstaged modifications against the Git index.
  - **STAGED**: Displays staged modifications against the HEAD commit.
- **Toolbar Actions**:
  - **Open Current File (Editable)** (pencil icon): Opens the working copy directly in the editor.
  - **Open Revision File (Read-Only)** (code/lock icon): Opens the historical revision in read-only mode.
  - **Close Diff** (× icon): Closes the diff view.
- **Diff Display**: Highlights deleted lines in red and added lines in green.
- **Empty State**: Displays *"No changes detected or binary file."*.

---

## 🛠️ Troubleshooting IDE Issues

### ❌ Symptom: The editor says "File not found" or will not open.
- **What to check**: Check if the file was deleted or renamed outside of AntiGem.
- **How to fix**: Open the sidebar (**Toggle Sidebar**) and tap **Refresh** (🔄) to reload the project file tree.

### ❌ Symptom: Changes made in the editor did not save.
- **What to check**: Check if the tab displays the locked padlock icon (**Read-Only**) or if a conflict occurred.
- **How to fix**:
  - If the file is read-only, check file permissions in the terminal (`chmod +w <file>`).
  - If external edits occurred on disk or from AI, look for the **File Conflict Detected** dialog and tap **Overwrite Disk (Keep Mine)** or **Revert to Disk**.

### ❌ Symptom: Opening a file displays garbled text.
- **What to check**: The file may be a compiled binary, archive, or media asset.
- **How to fix**: When the **Non-Text File Warning** dialog appears, tap **Cancel** to avoid corrupting binary files.
