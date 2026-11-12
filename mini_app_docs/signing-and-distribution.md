# Signing and distribution

A finished mini app is shipped as a signed package, an `.agapp` file, and installed like an Android APK: the first install records the developer's key, updates must be signed with the same key, an older version needs the user's OK, and every file is checked against the signature each time it is loaded. This page covers the developer key and its backup, the package format, exporting, the install and update rules, the ways to install and share packages, Developer mode and uninstalling.

## Development apps and installed apps

| | Development app | Installed app |
|---|---|---|
| Comes from | A folder in `~/mini-apps/` | A signed `.agapp` package |
| Code lives in | `~/mini-apps/<folder>/` (editable) | AntiGem's private storage (read-only, outside the home folder) |
| Signed | No | Yes, by the developer's key |
| Label in Mini Apps | `DEV · <version>` | `<version>` (or `modified`) |
| Hot reload, developer bubble | Yes | No |
| Runs | Only with Developer mode on | Always (unless modified) |
| App info | **Export signed package** | **Uninstall**, **Share app** |
| Removed by | Deleting its folder | **Uninstall** |

Data is stored the same way for both, in `~/.antigem/mini-apps/<id>/`.

There is no way to turn a development app into an installed app without signing it: the installer only accepts signed packages.

## The developer key

Packages are signed with your developer key, an EC P-256 key pair. People who install your apps see your name and the key's fingerprint, and only packages signed with the same key can update those apps.

**Create it on the phone:** Mini Apps → key icon (**Developer key**) → **Create**. Enter "Your name or team, shown to people who install your apps." (up to 80 characters) and tap **Create**. The key is stored in AntiGem's private storage, encrypted by a key kept in Android Keystore: it never sits readable on disk.

The dialog then shows the name and a short fingerprint like `AB12:CD34:EF56:7890` (the first 16 hex digits of the SHA-256 of the public key).

You can also create a key on a PC with `agapp.mjs keygen` and import it ([pc-development.md](pc-development.md)).

## Back up the key

AntiGem's private storage is wiped when AntiGem is uninstalled or its data is cleared, and a lost phone takes the key with it. **A key that is lost can't be recovered, and every app signed with it can never be updated again**, the same as a lost Android keystore. Users would have to uninstall your app (which deletes its data) and install a package signed with a new key.

So back the key up:

1. Mini Apps → key icon → **Back up now** (or **Back up again**).
2. Choose a password (at least 8 characters) and type it twice. It is typed only on the phone.
3. Tap **Back up**. AntiGem saves a password-protected `.agkey` file to the phone's public **`Download/AntiGem/`** folder, named like `antigem-key-<name>-<8 hex>.agkey`. That folder stays when AntiGem is uninstalled.
4. The share sheet opens: also keep a copy somewhere else (Drive, email to yourself, a USB stick). "Keep a second copy somewhere safe too."

Right after **Create**, AntiGem asks to back up the new key (**Back up your new key**). **Later** skips it, and until the key is backed up the Mini Apps screen shows a warning card, **Your developer key isn't backed up** ("If it's lost, the apps you signed can never be updated."), with a **Back up** button. The Developer key dialog shows "Backed up <date>" and the file's path once it is done.

Keep the password: "You need this password to restore the key (Developer key → Import) or to sign on a PC. Nobody can recover it." The file is encrypted with AES-256-GCM using a key derived from the password (PBKDF2-SHA256, 210,000 iterations). Anyone with the file **and** the password can sign updates as you, so don't share both together.

The AI model never sees the key or the password. An agent with the Mini App Developer MCP can open this backup dialog on the phone for you, but you type the password there, and the key stays inside AntiGem.

### Restore the key

On a new phone, after reinstalling AntiGem, or to use a key made on a PC: Mini Apps → key icon → **Import**, pick the `.agkey` file, type its password, **Import**. You see "Key imported". A wrong password gives "Wrong password, or not an .agkey file".

- Importing **replaces** the current key. If the current key is not backed up, the dialog warns: "It is NOT backed up: back it up first, or it is lost for good."
- An imported key counts as backed up (the file you imported is its backup).
- The same `.agkey` works with `agapp.mjs` on a PC, and a key made with `agapp.mjs keygen` imports on the phone ([pc-development.md](pc-development.md#one-key-on-the-phone-and-the-pc)).

## Exporting a signed package

For a development app: long-press it in Mini Apps → App info → **Export signed package**.

- Without a developer key: "Create a developer key first (Mini Apps → key icon)".
- AntiGem signs the folder (**Signing…**), checks its own output like an installer would, saves it as `~/mini-apps-dist/<id>-<version>.agapp` ("Signed: ~/mini-apps-dist/…") and opens the share sheet.
- Bump `version` (or `version_code`) before every release ([manifest.md](manifest.md#version-and-version_code)).

What goes into the package: every file of the folder, except names starting with `.` (at any depth), `node_modules`, a `META-INF` folder at the top of the app folder (where the signature goes; a `META-INF` folder deeper down is packaged) and symbolic links. The phone and `agapp.mjs` follow the same rules. Remove test files and large leftovers first.

The same package can be made on a PC with `agapp.mjs sign` ([pc-development.md](pc-development.md)).

## The .agapp format

An `.agapp` is a ZIP file (format 1). The phone and `agapp.mjs` write and read the same format.

```
icon.png
index.html
manifest.json
…the app's files, at the root…
META-INF/AGAPP.json       ← the signed file list
META-INF/AGAPP.CERT.json  ← the developer's public key
META-INF/AGAPP.SIG        ← the signature
```

`META-INF/AGAPP.json`, the bytes that are signed:

```json
{
  "format": 1,
  "id": "com.example.tally",
  "version": "1.0.0",
  "versionCode": 1000000,
  "files": {
    "app.js": "9f2c…sha256 hex…",
    "index.html": "…",
    "manifest.json": "…"
  },
  "signedAt": 1767225600000
}
```

`META-INF/AGAPP.CERT.json`:

```json
{
  "format": 1,
  "algorithm": "ECDSA-P256-SHA256",
  "publicKey": "<base64 SPKI DER>",
  "developer": "Your Name"
}
```

`META-INF/AGAPP.SIG`: the base64 DER ECDSA signature (SHA256withECDSA) over the exact bytes of `AGAPP.json`.

A package is accepted only when:

- the signature over `AGAPP.json` is valid for the certificate's public key;
- every file in the ZIP (outside `META-INF/`) is listed, with a matching SHA-256, and every listed file is present;
- no path is absolute, contains `..` or `.` parts, or uses backslashes;
- `manifest.json` is present, its `id` is valid and equals the signed `id`, its `version` equals the signed `version`, and its `version_code` (given, or computed from `version`) equals the signed `versionCode`;
- it is at most 100 MB.

The developer's identity is the key's fingerprint, the SHA-256 of the public key (SPKI DER). The `developer` name is only a label.

## Install and update rules

When a package is opened, AntiGem verifies it and compares it with what is installed:

| Situation | Screen | Result |
|---|---|---|
| No app with this id | **Install <name>?** "A new app from this developer. Only packages signed with the same key can update it." It lists the permissions it will ask for. | Installed with a clean start: any data left by an earlier app with this id is deleted ("Data left by an earlier app with this id will be deleted."). |
| Same key, higher `version_code` | **Update <name>?** "Same developer as the installed version. Its data and permissions are kept." | Updated. New permissions are listed and asked for when the app opens. |
| Same key, same `version_code` | **Reinstall <name>?** with the same text as an update | Files replaced, data and permissions kept. |
| Same key, lower `version_code` | **Go back to an older <name>?** "This is an older version than the one installed. Older versions can have bugs that were fixed. Its data is kept." The button **Downgrade** is red. | Downgraded after the user's OK. |
| Different key | Refused | See below. |

The screen always shows "Signed by <developer>", "Key <fingerprint>" and the version (`Version 1.0.0 → 1.1.0 · <id>` for updates). After installing: "App installed." or "App updated." with **Open** and **Done**.

Installs are refused (the reason is shown in red and only **Close** remains) when:

| Message | What to do |
|---|---|
| This package is signed by a different developer than the installed <name> (<developer>, key …). Uninstall the installed app first; its data will be deleted. | Only the original developer can update it. Uninstall first if you trust the new package. |
| A development app with the id <id> is in ~/mini-apps/<folder>. A package can't take its id (it would get that app's data): give the development app another id, or delete it, then install. | A package can't take over a development app's id and data. |
| It shares data with the apps of "<shared_id>", and <name> there is signed by <developer> (key …), not by this package's developer. Only apps signed with the same key can share data. | See [shared-data.md](shared-data.md). |
| Its manifest.json is invalid: … | The packaged manifest is broken. |

A package that fails verification never reaches the install screen: **Can't install** shows why ([troubleshooting.md](troubleshooting.md#install-errors)).

The package is verified again at the moment of installing, and every file is hashed again while it is unpacked. The new version replaces the old one in one step, and open pages of the app reload with it.

## Integrity

An installed app's files live in AntiGem's private storage, and each file is checked against the signed hash every time it is served (the result is cached per file size and modification time).

- A changed or added file is refused (`403 Modified file`). The console shows "Refused /<file>: it isn't the file <developer> signed (modified after install)".
- If `manifest.json` no longer matches, the app is marked **modified** (red label, dimmed icon) and doesn't open: "<App> was changed after it was installed: its files are no longer the ones <developer> signed. Uninstall it and install it again from its developer." with **Uninstall** and **Close**.
- A modified app's background tasks are skipped, it is not offered as an intent target, and it is cut off from its shared-data group.

## Installing like an APK

Send the `.agapp` file any way you like. On the receiving phone, AntiGem's **Mini app installer** opens over the app the file came from:

- **Tap the file** in WhatsApp, Telegram, Gmail, a file manager or a browser's downloads, and choose **Mini app installer** (Android may ask which app opens it). Opening it with **AntiGem** works too.
- **AntiGem's file explorer:** tap the file → **Install mini app** ("Check the developer's signature, then install or update").
- **Share** the file to **Mini app** (or to AntiGem). A package goes to the installer; anything else goes to a mini app that accepts it ([intents.md](intents.md)).
- **Mini Apps → install icon** (**Install or update an app (.agapp)**) → **Install from a file**, or **Install from a link**: "An https:// link to an .agapp file (a release page download, your server, a cloud drive's direct link)." Then **Download**.

Updates arrive the same way: open the newer package and the installer shows **Update**.

Links must be `https://` ("Only https:// links: a package from an http:// link could be swapped on the way"). Files over 100 MB are refused.

## Sharing an installed app

App info of an installed app → **Share app** sends the exact package it was installed from, still with the developer's signature, named `<Name>-<version>.agapp`. Apps installed before AntiGem kept packages show "The package isn't kept for apps installed before sharing existed: install it again to share it".

## Hosting packages

- Serve `.agapp` files with the MIME type `application/vnd.antigem.agapp` (or `application/octet-stream`) and a file name ending in `.agapp`, so phones open them with AntiGem's installer.
- Use https. A GitHub release asset works: users paste its download link in **Install from a link**, or download it and tap it.
- Keep each file under 100 MB.

## Developer mode

Mini Apps → `</>` (**Developer mode**). It is on by default in the beta.

- **On:** "Developer mode on: development apps run, with developer tools".
- **Off:** "Developer mode off: only installed (signed) apps run". Development apps are dimmed in the grid and open to a screen saying "<App> is a development app (unsigned, from ~/mini-apps). Development apps only run with Developer mode on (Mini Apps → </>)." Their background tasks are skipped and they are not offered as intent targets.

Developer mode is not a way to ship apps: a development app runs only on a phone where the user turned Developer mode on, only from a folder in `~/mini-apps`, and never under an installed app's id. To give an app to other people, sign it.

## Uninstalling

- **Installed apps:** App info → **Uninstall** → "The app and all its data (storage, files, reminders, background tasks, notifications) are removed." Its permissions, its kept package and its home-screen shortcut go too (the shortcut is disabled: "<App> was uninstalled").
- **Development apps:** delete the folder in `~/mini-apps`. Its data stays in `~/.antigem/mini-apps/<id>/` until you use **Clear data** first, or until a package with the same id is installed (which starts clean). A deleted sample app stays deleted.

## Where things are stored

| What | Where |
|---|---|
| Development apps | `~/mini-apps/<folder>/` |
| Installed apps' code | AntiGem's private storage (`files/miniapps/installed/<id>/`, outside the home folder) |
| Installed packages (for **Share app**) | AntiGem's private storage (`files/miniapps/packages/<id>.agapp`) |
| Every app's data | `~/.antigem/mini-apps/<id>/` |
| Exported packages | `~/mini-apps-dist/<id>-<version>.agapp` |
| Developer key | AntiGem's private settings, encrypted by Android Keystore |
| Key backups | `Download/AntiGem/antigem-key-*.agkey` (public storage) |
| PC signing tool | `~/mini-apps/tools/agapp.mjs` (updated with every AntiGem version) |
