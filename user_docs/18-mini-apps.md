# 18 — Mini Apps

**Mini apps** are small apps that run inside AntiGem but look and behave like real Android apps: each opens full screen, has its own card in Recents, can have its own home-screen icon, and uses native dialogs, notifications, reminders, files and storage. An agent can build one for you in a few minutes from a sentence like *"make me a habit tracker with a daily reminder"*, and you can install apps other people made from a single `.agapp` file, the way you install an APK.

Like Android apps, mini apps only get what you allow: each one asks before it uses the internet, your folders, notifications or other permissions, and you can change your answers at any time in **App info**.

> Developers: the full technical guide (manifest, permissions, API, signing) is in the `mini_app_docs` folder of the AntiGem repository. Agents with the Mini App Developer MCP get it from the MCP.

---

## 🚀 Quick start

1. **Open Mini Apps**: open the chat drawer and tap the **Mini Apps** icon (the grid of squares, next to the Themes palette). AntiGem comes with sample apps to try: Habits, Pocket Notes, Focus, Gallery, Sky, Explorer, Dev Toolbox and Showcase.
2. **Connect the Mini App Developer MCP** to your agent: in the agent's MCP settings, tap the **Mini App Developer** tile in *Quick presets* ([section 2](#-2-asking-an-agent-to-build-an-app)).
3. **Ask in chat**, for example `/mini_app a water reminder that notifies me every 2 hours`, or just *"Build me a mini app that …"*.
4. **Open the new app** in Mini Apps. Changes the agent makes show up in the open app straight away.

---

## 🧭 1. The Mini Apps screen

Every mini app is an icon in a grid. **Tap** an app to open it; **long-press** it for **App info**.

Under each icon:

| Label | Meaning |
| :--- | :--- |
| `1.0.0` | An installed app (from a signed package) and its version |
| `DEV · 1.0.0` | A development app: a folder in `~/mini-apps` that an agent (or you) is working on |
| `modified` (red) | An installed app whose files were changed after installing: it won't open |

Icons in the top bar:

| Icon | What it does |
| :--- | :--- |
| `</>` **Developer mode** | Turns development apps on or off ([section 8](#-8-developer-mode)) |
| Install icon | **Install from a file** or **Install from a link** ([section 6](#-6-installing-and-updating-apps)) |
| Key icon **Developer key** | Your key for signing apps you share ([section 9](#-9-your-developer-key-and-its-backup)) |
| Refresh | Looks for new or changed apps (this also happens every time you come back to the screen) |

If you have a developer key that isn't backed up, a red card says **Your developer key isn't backed up** with a **Back up** button.

With no apps at all the screen says *"No mini apps yet"* and suggests asking an agent to build one.

A folder in `~/mini-apps` that isn't a working app (for example its `manifest.json` has a mistake, or two folders use the same id) is listed in red on this screen with the reason, also when there are no apps yet.

---

## 🤖 2. Asking an agent to build an app

Any agent can write a mini app: it is just a folder of web files in `~/mini-apps/<name>/`. The **Mini App Developer** MCP makes this much better: the agent can read the mini app guide, create the app, open it on your phone, read its errors, look at screenshots, fix problems, and sign and install it, all by itself.

**Add the preset once** in the MCP settings of the agent you use. It is a tile in *Quick presets*:

| Agent | Where |
| :--- | :--- |
| Antigravity | Settings → Antigravity → MCP Servers → **Mini App Developer** |
| Claude Code | Settings → Claude Code → MCP → **Mini App Developer** |
| Codex | Settings → Codex → MCP → **Mini App Developer** |
| OpenCode | Settings → OpenCode → MCP servers → **Mini App Developer** |

Then start a new chat (or reload the agent's tools) so the agent sees it.

**Use the `/mini_app` slash command**: type `/mini_app` followed by what the app should do. It tells the agent to build a mini app with the Mini App Developer MCP. Like the other built-in commands, it is listed in **Settings → Slash Commands**, where you can change it.

Good requests say what the app is for and what it needs:

- *"/mini_app a shopping list I can share with WhatsApp"*
- *"/mini_app a gallery for my Camera folder, read only"*
- *"/mini_app a weather app for my city with a rain alert in the morning"*

The agent should ask only for the permissions the app really needs. You will see them when the app first opens.

---

## 📱 3. Running apps

- **Open** an app from the grid, from Recents, or from its home-screen icon. An app that is already running comes back exactly where you left it.
- **Back** on the app's first screen puts it in the background, like a normal app, so it reopens instantly.
- **Recents:** each mini app has its own card, with its name, icon and color.
- **Home-screen icon:** App info → **Add to home screen**. If your launcher can't, you see *"This launcher can't add shortcuts"*.
- **Development apps** show `DEV` on their start screen and a small round **developer bubble** (`⋯`) you can drag anywhere. It opens a menu: **Reload**, **Console** (the app's messages and errors), **DevTools (elements, network, storage)**, **App info & permissions**, **Add to home screen** and **Close app**. You mostly need it when something looks wrong: the **Console** shows what failed.

---

## ℹ️ 4. App info

Long-press an app in Mini Apps (or developer bubble → **App info & permissions**).

| Part | What you see and can do |
| :--- | :--- |
| Header | Name, version and id; *"Installed · signed by <developer> · <key>"* or *"Development app (unsigned) · ~/mini-apps/<folder>"* |
| **Permissions** | One switch per permission, e.g. **Internet** (*"Only: api.example.com"*), **Folder /sdcard/Download** (*"Read only"*), **Notifications**. Required ones are marked **Required**. An app without permissions says *"This app asks for no permissions. It can only use its own storage."* |
| **Folders you picked** | Folders you gave the app from the folder picker, each with **Remove** |
| **Background** | Scheduled reminders, background tasks with their last run and **Run now**, how many of the 48 daily background runs were used, and warnings with a **Fix** / **Allow** button when Android may delay them |
| **Storage** | Where the app's code and data are; which apps it shares data with |
| **Clear data** | Deletes the app's storage, databases, files, logs, reminders and background tasks. The app stays. |
| **Reset permissions** | Forgets all your permission answers; the app asks again next time |
| **Add to home screen** | A launcher icon for the app |
| **Uninstall** | Installed apps only: removes the app and all its data |
| **Share app** | Installed apps only: sends the original signed package |
| **Export signed package** | Development apps only: signs the app with your developer key so you can share it |

Turning off a required permission asks first (*"<App> needs this"*, **Turn off** / **Keep**): without it the app won't open.

---

## 🔐 5. Permission prompts

- **First launch:** a dialog titled **<App> wants to** lists what the app asks for, each with a switch. Turn off what you don't want and tap **Continue**, or **Deny all**. *"You can change these later in App info."*
- **Later:** an app can ask again for one permission: **Allow <App> to use this?** with **Allow** / **Don't allow**.
- **Camera and microphone:** asked the first time the app uses them, for example when it opens the camera to take a photo; Android may then ask for AntiGem's own camera or microphone permission.
- **Required permissions:** some apps can't work without a permission. In the permission dialog, the button to refuse then reads **Don't allow (app can't run)**. If you don't allow it, the app shows **<App> can't run without** with **Review permissions** and **Close**.
- **All your files, or your whole home or storage folder:** these switches start off and need you to **type a confirmation phrase** under a red warning before they turn on. Only do this for an app you trust completely; a narrower folder is almost always enough.
- **When an app is updated** and asks for more (another website, another folder, more access), you are asked again for just that. If it asks for less, your answers stay.

A mini app can never reach other apps' data, AntiGem's own settings and keys, or hidden folders like `~/.ssh`, whatever you allow.

---

## 📦 6. Installing and updating apps

Apps from other people come as one **`.agapp`** file. Get it any way you like and open it:

- **Tap the file** in WhatsApp, Telegram, Gmail, Files or your browser's downloads, and pick **Mini app installer** if Android asks.
- In AntiGem's **Files**, tap the file → **Install mini app**.
- **Share** the file to **Mini app**.
- In Mini Apps: install icon → **Install from a file**, or **Install from a link** and paste an `https://` link to the `.agapp` (for example from a GitHub release).

The installer shows who signed the app (**Signed by <developer>** and a key code), the version, and the permissions it will ask for. Tap **Install**, then **Open** or **Done**.

**Updates** work the same way: open the newer file and the installer says **Update**. Your data and permission choices are kept. An older version shows **Downgrade** in red and needs your OK.

The installer refuses a package when:

- it is signed by a **different developer** than the installed app (only the original developer can update it; you would have to uninstall first, which deletes its data);
- a **development app with the same id** is in `~/mini-apps` (rename or delete that folder first);
- its files were **changed after signing**, or it isn't a valid package.

An installed app is checked every time it loads: if its files are changed afterwards, it is labeled **modified** and won't open until you reinstall it from its developer.

---

## 📤 7. Sharing apps

- **An app you installed:** App info → **Share app**. It sends the exact package you installed, still signed by its developer.
- **An app you (or your agent) made:** App info → **Export signed package**. AntiGem signs it with your developer key, saves it in `~/mini-apps-dist/`, and opens the share sheet. You need a developer key first ([section 9](#-9-your-developer-key-and-its-backup)).

Before sharing a new version, make sure its version number was raised (ask the agent to bump it).

---

## 🛠️ 8. Developer mode

The `</>` icon in Mini Apps turns **Developer mode** on or off. It is on by default.

- **On:** development apps (the `DEV` ones in `~/mini-apps`) run, with the developer bubble.
- **Off:** only installed, signed apps run. Development apps are dimmed and say they need Developer mode.

If you only use apps from other people, you can turn it off.

---

## 🔑 9. Your developer key and its backup

To share apps you made, AntiGem signs them with **your developer key**. People who install your app can then only update it with a package signed by the same key, so nobody else can replace your app.

**Create the key:** Mini Apps → key icon → **Create**, and enter your name or team (shown to people who install your apps).

**Back it up right away.** The key lives inside AntiGem. If you uninstall AntiGem, clear its data or lose the phone without a backup, **your apps can never be updated again**. AntiGem asks you to back up right after creating the key (**Back up your new key**):

1. Choose a password (8 characters or more) and type it twice.
2. Tap **Back up**. AntiGem saves a locked `.agkey` file in your phone's **Download/AntiGem** folder. That folder stays even if AntiGem is uninstalled.
3. The share sheet opens: also save a copy in Google Drive or email it to yourself.

Until the key is backed up, Mini Apps shows **Your developer key isn't backed up**. Tap **Back up** there, or use the key icon → **Back up now**.

The password is typed only on your phone. The AI agent never sees your key or your password, even when it asks AntiGem to show you the backup screen. Nobody can recover a forgotten password, so keep it somewhere safe.

**Restore the key** on a new phone or after reinstalling AntiGem: Mini Apps → key icon → **Import**, pick the `.agkey` file and type its password. Importing replaces the key on this phone. The same file also lets a developer sign apps on a PC.

---

## 🗑️ 10. Removing apps

- **Installed apps:** App info → **Uninstall**. The app and all its data (storage, files, reminders, background tasks, notifications) are removed, and its home-screen icon stops working.
- **Development apps:** delete their folder in `~/mini-apps` (or ask the agent to). Use **Clear data** first if you also want their data gone. A sample app you delete doesn't come back.

---

## 🛠️ Troubleshooting

| Problem | Fix |
| :--- | :--- |
| A new app doesn't show in Mini Apps | Tap Refresh. If its folder is listed in red, that line says what is wrong (usually its `manifest.json`): tell the agent, or ask it to check the app (the Mini App Developer MCP can validate it). |
| A `DEV` app says it needs Developer mode | Tap `</>` in Mini Apps to turn Developer mode on. |
| *"<App> can't run without …"* | Tap **Review permissions** and allow what it needs, or turn it on in App info. |
| A feature doesn't work and the app says it isn't allowed | Turn the permission on in App info, or tap **Reset permissions** to be asked again. |
| Reminders come late | App info → Background → **Fix** to give AntiGem *Alarms & reminders* access, and **Allow** to stop battery optimization. |
| Background updates don't happen | Battery saver skips them; check App info → Background for the last run and its reason. |
| The app shows **modified** | Its files were changed after installing. Uninstall it and install it again from the developer. |
| *"This package is signed by a different developer…"* | Only the original developer can update the app. Uninstall the old one first if you trust the new one (its data is deleted). |
| *"A development app with the id … is in ~/mini-apps/…"* | Rename or delete that development folder, then install. |
| Lost the developer key | Restore it from your `.agkey` backup (Import). Without a backup, apps you shared can't be updated. |
| Something looks broken in an app you're building | Developer bubble → **Console** shows the errors; tell the agent what it says. |
