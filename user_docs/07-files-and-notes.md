# 07 — Files & Notes

AntiGem comes equipped with two persistent productivity tools designed to make mobile work seamless: a full **File Explorer** and a dedicated **Notes & To-Do** pad.

---

## 📁 Part 1: The File Explorer

The **Files Screen** lets you manage, organize, and inspect files in your AntiGem workspace and phone storage without relying on external file manager apps.

### 1. Opening the File Explorer
- Tap the **Files** icon on the floating app switcher.

### 2. Navigating and Organizing Your Files
- **Navigation Controls**:
  - **Back**: Navigate up to the parent directory or exit search.
  - **Breadcrumb Navigation**: Tap any folder name in the top path strip to jump directly to that directory.
  - **Search**: Tap the search icon (`contentDescription="Search"`) to open the search bar. Tap **Close search** to exit search mode.
  - **View Layout Toggle**: Tap the layout icon (`contentDescription="List view"` or `contentDescription="Grid view"`) to toggle between structured list rows and visual grid tiles.
  - **More**: Tap the top-right overflow menu icon (`contentDescription="More"`) to access folder actions:
    - **New file**: Prompts for a new file name (e.g. `main.py`).
    - **New folder**: Creates a new subfolder in the current directory.
    - **Select all**: Selects all items in the folder for bulk operations.
    - **Show hidden files**: Toggles dotfiles (such as `.git` or `.env`).
    - **Sort & view…**: Opens the Sort & View dialog.
    - **Open this folder in IDE**: Sets the current folder as the active project workspace.
    - **Copy folder path**: Copies the folder's absolute path to the Android clipboard.
    - **Folder details**: Shows folder size, total item count, and storage usage.
    - **Refresh**: Reloads directory contents from disk.
- **Create Button**: Floating Action Button in the bottom corner (`contentDescription="Create"`) provides quick 1-tap creation of a **New file**, **New folder**, or **New project**.

### 3. File Operations, Selection & Dialogs
Long-press any item to activate **Multi-Selection Mode**:
- **Selection Action Bar**:
  - **Clear selection**: Deselects all highlighted items and exits selection mode.
  - **Select all**: Selects or deselects all visible items.
  - **Copy**: Copies selected files to the AntiGem clipboard.
  - **Cut**: Prepares selected files to be moved.
  - **Delete**: Prompts for bulk deletion.
  - **More**: Additional batch actions (**Compress to ZIP**, **Copy paths**).
- **Clipboard Bar**: When items are copied or cut, a floating bar appears showing the item count:
  - Tap **Paste** to place items into the current directory.
  - Tap **Cancel** (X) to clear the clipboard.

#### Item Action Sheet (Single Item Tap/Long-Press):
- **Open**: Opens the file with its default viewer or enters the folder.
- **Open with…**: Opens the chooser sheet titled *"Open with"* (*"Choose the editor or another app"*):
  - Choose between **Code editor**, **Markdown preview**, or external Android apps.
  - Check **Always open .$ext files this way** to remember your preference.
- **Open in code editor**: Directly opens editable text files in the IDE.
- **Copy**: Adds item to clipboard (*"Copied. Open a folder and tap Paste."*).
- **Cut**: Prepares item to move (*"Cut. Open a folder and tap Paste."*).
- **Copy to…** / **Move to…**: Opens a directory picker to choose the destination directly.
- **Compress to ZIP**: Archives selected files into a `.zip` archive.
- **Extract here** / **Extract to…**: Extracts zip archives.
- **Rename**: Prompts with a dialog to change the file or folder name.
- **Delete**: Opens the permanent deletion confirmation dialog:
  - Shows warning: *"This is permanent. Deleted files cannot be recovered."*
  - Optional checkbox: **I understand, delete permanently**.
  - Tap **Delete** to confirm, or **Cancel** to abort.
- **Details**: Displays exact file size, modification date, storage permissions, and executable toggle (*"Executable: Lets scripts and programs run (chmod +x)"*).
- **Copy path**: Copies the absolute path to your phone clipboard.

#### Specialized Explorer Dialogs:
- **Sort & view dialog**:
  - **Sort by**: Filter chips for **Name**, **Date**, **Size**, or **Type**.
  - **Order**: Segmented buttons for **Ascending** or **Descending**.
  - **Layout**: Segmented buttons for **List** or **Grid**.
  - **Forget choices**: Button to **Forget "always open with" choices ([count])** to reset file associations.
- **Conflict Dialog**: When pasting an existing file name, prompts *"What should happen to the moved/copied items with the same name?"* with options to **Replace**, **Keep both**, or **Skip**.
- **Not a text file warning**: If opening a binary or non-text file in the editor, displays buttons: **Open anyway**, **Other app**, or **Cancel**.
- **ZIP Viewer**: Allows inspecting archive contents before extraction, offering **Extract here** and **Extract to…**.

---

## 📝 Part 2: Notes & To-Do Lists

The **Notes Screen** is a lightweight, distraction-free notepad built right into the app. It survives switching screens and restarting the app, and synchronizes live with AI agents via the Notes MCP server.

### 1. Accessing Notes
- Tap the **Notes** icon on the floating app switcher.

### 2. Creating, Searching and Managing Notes
- **Top Bar Controls**:
  - **Back**: Returns to the previous view.
  - **Search**: Tap `contentDescription="Search"` to open the note search bar.
  - **Clear search**: When search text is present, tap `contentDescription="Clear search"` (X) to reset the filter.
  - **Filter Chips**: Filter displayed notes by **All**, **Notes**, or **To-dos**.
- **Creating a Note**:
  - Tap the floating **+** button (`contentDescription="New"`) to choose:
    - **Note**: A freeform scratchpad for prompts, code snippets, and general documentation.
    - **To-do list**: An interactive checklist with checkable items.
- **Pinning**: Tap the pin button (`contentDescription="Pin"` / `contentDescription="Unpin"`) to keep important notes pinned under the **PINNED** section at the top.

### 3. Note Editor & To-Do Controls
When inside a note or checklist:
- **Back**: Exits the editor and saves changes automatically.
- **Pin / Unpin**: Toggles pinned status for this note.
- **More Menu**: Tap `contentDescription="More"` to reveal note management options:
  - **Turn into note** / **Turn into to-do list**: Converts note formats while preserving all lines and checked statuses.
  - **Check all**: Marks every item on the checklist as completed.
  - **Uncheck all**: Unchecks all items.
  - **Clear completed**: Removes all checked tasks from the checklist.
  - **Delete**: Permanently removes the note, with an immediate **Undo** snackbar if tapped by accident.
- **To-Do Items**:
  - Checkboxes to toggle completion.
  - Drag handle to reorder items.
  - **Remove item**: Tap the cross icon (`contentDescription="Remove item"`) on any task row to delete that item.

---

## 🛠️ Troubleshooting Files & Notes Issues

### ❌ Symptom: The File Explorer cannot see files on my phone's SD card or Downloads folder.
- **What to check**: Check whether storage permission was granted to Termux/AntiGem.
- **How to fix**:
  1. Open the Terminal and run `termux-setup-storage`.
  2. Android will prompt you to grant storage access. Tap **Allow**.
  3. A new folder named `storage/shared` will appear in your home directory, linking directly to your phone's internal storage and Downloads folder.

### ❌ Symptom: I accidentally deleted a file in the File Explorer. Can I get it back?
- **What to check**: Check if your project is managed with Git.
- **How to fix**: If your project is using Git (see [Projects & IDE Guide](04-projects-and-ide.md)), go to the **Git** tab in the IDE or run `git checkout -- <filename>` in the Terminal to restore your deleted file to its last saved state.
