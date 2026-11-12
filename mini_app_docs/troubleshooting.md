# Troubleshooting

Common problems with mini apps, what causes them and how to fix them: apps that don't appear or don't open, permission errors, blank pages, changes that don't show, late reminders, background tasks that don't run, quiet notifications, refused installs and the "modified" label. The last section lists where every log is.

## The app doesn't appear in Mini Apps

Mini Apps lists every folder `~/mini-apps/<folder>/` that has a `manifest.json` AntiGem can read. A folder with a `manifest.json` that isn't an app right now is listed in red on the Mini Apps screen with the reason (also on the empty screen), as `~/mini-apps/<folder> isn't an app: …` or `~/mini-apps/<folder> doesn't run: …`. Read that line first.

| Cause | Fix |
|---|---|
| `manifest.json` is not valid JSON (a trailing comma, a comment, a missing quote) | Fix the JSON. The red line shows `manifest.json: <error>`. |
| `id` is missing or breaks the rules (spaces, `/`, `:`, other characters, over 100 characters) | Letters, digits, `.`, `_`, `-`, starting with a letter or digit ([manifest.md](manifest.md#id)). |
| `shared_id` breaks the same rules | Same fix. |
| Another development folder has the same `id` and comes first alphabetically | Give each app its own id. Only the first folder is used; the red line says `its id <id> is already used by ~/mini-apps/<folder>`. |
| An installed app has the same `id` | Mini Apps shows `~/mini-apps/<folder> doesn't run: its id <id> belongs to an installed (signed) app`. Give the folder another id, or uninstall the installed app. |

These folders are skipped without a red line:

| Cause | Fix |
|---|---|
| The folder name starts with `.` | Rename it. |
| The file is not exactly `manifest.json` at the top of the folder | Move or rename it. |

Then tap the refresh icon in Mini Apps (it also rescans each time you come back to the screen). The Mini App Developer MCP's `miniapp_validate` reports the same problems. If you need more detail, the reason is also written to Android's log with the tag `MiniApps` ("Bad manifest in <folder>: …"): `logcat -s MiniApps` in AntiGem's terminal shows it. This is optional; the Mini Apps screen and the app's console come first.

## A DEV app won't open

"<App> is a development app (unsigned, from ~/mini-apps). Development apps only run with Developer mode on (Mini Apps → </>)."

Developer mode is off. Tap `</>` in the Mini Apps top bar. While it is off, development apps are dimmed, their background tasks are skipped and other apps can't send them intents.

## "<App> can't run without …"

A required permission is not granted. Tap **Review permissions** and allow it, or turn it on in App info. To make a permission optional, remove it from `required_permissions` ([permissions.md](permissions.md#required-permissions)).

## NotAllowedError

Every permission problem rejects with a `DOMException` named `NotAllowedError`. Its message says why:

| Message | Fix |
|---|---|
| `"<name>" permission is undeclared` | Add it to `permissions` in the manifest. Check the spelling: an unknown name is ignored, and the console shows a warning `manifest.json: Unknown permission "<name>" ignored …`. `"storage"` in the list form and `"mcp": true` are ignored too ([manifest.md](manifest.md#manifest-warnings)). |
| `"<name>" permission is prompt` | Not decided yet (new, or its declaration was widened). Call `AntiGem.permissions.request(name)` first. |
| `"<name>" permission is denied` | The user said no. Ask again with `request()`, or the user turns it on in App info. |
| `Network request to <host> blocked: the app has no "network" permission` | Declare `network` and get it granted. |
| `… <host> is not in the manifest's network hosts` | Add the host. `*.example.com` doesn't cover `example.com`. Images, fonts and scripts from other hosts need their hosts too (CDNs: `cdn.jsdelivr.net`, `fonts.gstatic.com`, …), and so do hosts a request redirects to. |
| `… the manifest's network hosts list is empty` | `"hosts": []` allows no host. List the hosts, or leave `hosts` out for any host. |
| `… the app has no "localhost" permission` / `localhost port <n> is not in the manifest` | Declare `localhost` with that port. The phone's own Wi-Fi address, and names that point to the phone, need it too. |
| `… the manifest's localhost ports list is empty` | `"ports": []` allows no port. List the ports, or leave `ports` out for any port. |
| `… port <n> belongs to AntiGem` | AntiGem's own ports are never reachable. Use another port. |
| `Reading or writing <path> needs a "storage" folder in the manifest` | Declare the folder in `storage.folders`, or use `data/…`. |
| `<path> is not in a folder you allowed this app` | The folder isn't declared, or the user denied it. |
| `<path>: the app may only read there` (or `write`) | Change the folder's `access`. |
| `<path> is protected` | Never reachable: hidden home folders, other apps' code and data, anything outside home and `/sdcard` ([permissions.md](permissions.md#never-reachable)). |
| `The manifest doesn't allow picking folders (permissions.storage.pick)` | Add `"pick"` to `storage`. |
| `Notifications are turned off for AntiGem in Android settings` | Turn on AntiGem's notifications in Android settings. |
| `The user did not open the link` | The app has no `network` permission, so opening a link asked first, and the user cancelled. |

Requests blocked inside the page (an `<img>` or `<script>` from a host the app may not reach) don't throw: the console shows `Blocked <url>: <reason>`. A `fetch` whose redirect goes to a host the app may not reach, or whose address turns out to be this phone without `localhost`, fails with `TypeError` ("Failed to fetch: Blocked: …").

## Blank or broken page

- **Read the console first** (developer bubble → **Console**, or `console.log` in the data folder). Most blank pages are a JavaScript error at load.
- A script, stylesheet or font from the web without `network` (and that host) is blocked. Bundle it into the app folder.
- `Not found` with a path: the file doesn't exist. Paths are relative to the app folder; `/x.js` means `<folder>/x.js`. Paths without an extension fall back to the entry page.
- ES modules: a wrong import path stops the whole module. Check the console for the failing URL.
- The page "crashed (renderer crashed); reopening": the WebView ran out of memory or crashed. Reduce memory use (huge images, endless loops).
- The splash screen always goes away after 4 seconds, even if the page never paints.

## Changes don't show

| Cause | Fix |
|---|---|
| It's an installed app | Installed apps don't hot-reload and their files can't be edited. Edit the development folder and sign a new version. |
| A development folder with the same id as an installed app | That folder doesn't run (see above). The installed app opens instead. |
| The file is in a folder starting with `.` or in `node_modules`, or ends in `~`, `.swp`, `.tmp` | These are ignored by hot reload. |
| You edited a different folder | App info shows the app's folder: `Development app (unsigned) · ~/mini-apps/<folder>`. |
| The `id` changed while the app was open | Close the app and open the new one from Mini Apps. |
| A service worker or your own cache | Mini apps don't need service workers; AntiGem serves files without caching. Remove your own caching. |

Use developer bubble → **Reload** to force a reload.

## Reminders come late

On Android 12 and newer, on-time reminders need AntiGem's **Alarms & reminders** access. Without it Android fires them within a window, and App info → Background says "Reminders may come late: AntiGem has no "Alarms & reminders" access." Tap **Fix** and allow it.

Also check:

- **Battery optimization:** App info → Background shows "Android may delay or skip background tasks while AntiGem's battery use is optimized." when it applies. Tap **Allow**. Some phone makers add their own battery savers: set AntiGem to "unrestricted" or "no restrictions" in Android's battery settings.
- `exact: false` lets Android batch a reminder on purpose.
- Reminders missed while the phone was off are shown when it starts again, if they are less than 6 hours late; older ones are skipped.
- Force-stopping AntiGem stops all reminders until AntiGem is opened again.

## A background task doesn't run

Open App info → **Background**. Each task shows its schedule and its last run (`Last: <time> · ok in 812 ms`, `· error · …` or `· skipped · <reason>`), and **Run now** runs it at once. The console log has a line for every run (`⏱ Background task "sync" (schedule) finished in …`).

| Reason shown | Fix |
|---|---|
| "background" permission is not granted | Declare it, and allow it (App info or `request('background')`). |
| The manifest has no background script | Add `"background": "background.js"` ([manifest.md](manifest.md#background)). |
| Developer mode is off (development apps don't run) | Turn Developer mode on. |
| Required permission missing: … | Grant it. |
| Daily limit of 48 runs reached | At most 48 runs per app per 24 hours. Run less often. |
| Battery saver is on | Scheduled runs are skipped in battery saver. **Run now** and notification buttons still run. |
| The app was modified after it was installed | Reinstall the app from its developer. |
| Stopped after 60 s | A run may take at most 60 seconds. Do less per run; await promises instead of timers. |

Other limits: `every` must be at least 15 minutes and Android runs these roughly on time (use `at` when the minute matters), at most 10 tasks per app, one task at a time on the whole phone. The handler's promise is the run: work after it resolves may be cut off. See [background.md](background.md).

## Notifications are silent, hidden or bundled

- **No sound or pop-up:** the notification is `ongoing` or `silent`, or uses a channel with low importance. A channel made with `importance: "low"` lands in the Silent section.
- **Changing a channel doesn't help:** Android keeps a channel's importance and sound once it is created, and the user's choices win. Use a new channel id. Users can tune each app's channels in Android settings under "Mini app: <name>".
- **Nothing shows:** AntiGem's notifications are off in Android settings, or the app already has 12 notifications showing (the limit; dismiss some first).
- **Bundled together:** only notifications with the same `group` are bundled. Without `group` each notification stands alone and is never mixed with other apps'.

See [notifications.md](notifications.md).

## Install errors

Problems found before the install screen show **Can't install** with one of these:

| Message | Meaning |
|---|---|
| This file isn't a mini app package (.agapp) | Not a ZIP with `META-INF/AGAPP.json`. Wrong file, or a download page instead of the file. |
| Not an .agapp package (not a zip) | The file is damaged or not a ZIP. |
| Not signed: the certificate is missing / the signature is missing | The package is incomplete. Sign it again. |
| Unknown signature algorithm | Not made by AntiGem or `agapp.mjs`. |
| The signature is not valid | Changed after signing, or a broken file. |
| <file> was changed after signing | That file's content doesn't match the signed hash. |
| <file> is not covered by the signature | A file was added after signing. |
| <file> is missing from the package | A signed file was removed. |
| Unsafe path in package: <name> | An absolute path, `..` or a backslash. |
| manifest.json is missing | No manifest at the root of the ZIP. |
| The manifest id doesn't match the signed id / … version doesn't match the signed version / The signed versionCode doesn't match the manifest | The manifest was edited after signing. |
| "version_code" must be a whole number (got …) | The manifest's `version_code` is a decimal, negative, text or `null`. Use a whole number or a string of digits, or leave it out ([manifest.md](manifest.md#version-and-version_code)). |
| The file is bigger than 100 MB: not a mini app package | Too big. |
| Only https:// links: … / That isn't a link / The server answered <code> / Download failed: … | **Install from a link** problems. Use a direct https download link. |
| The package changed while it was being installed | The file changed between the check and the install. Open it again. |

Refusals shown on the install screen itself (different developer, development app with the same id, shared-data group signed by someone else, invalid manifest) are explained in [signing-and-distribution.md](signing-and-distribution.md#install-and-update-rules).

## The "modified" label

An installed app labeled **modified** (dimmed, in red) has files that no longer match its developer's signature: something changed them after installing. It doesn't open; its screen offers **Uninstall**. Uninstall it and install it again from a package its developer signed. Never edit files of installed apps: edit the development folder and sign a new version.

## Where the logs are

| Log | Where |
|---|---|
| Console of an app (page, workers, background tasks, runtime messages) | `~/.antigem/mini-apps/<id>/console.log`, previous part in `console.log.1` (rotated at 1 MB) |
| The same, live, last 1000 lines | Developer bubble → **Console**, or `AntiGem.dev.console()` |
| Background runs and skips | App info → **Background**, and the console log |
| Why a folder is not listed | The red lines on the Mini Apps screen. Optional, more detail: Android's log, tag `MiniApps` (`logcat -s MiniApps`) |
| Ignored or suspicious manifest entries | The app's console (`manifest.json: …` warnings), and `miniapp_validate` |
| Errors of a running app | Android's log, tag `MiniApp/<id>` |

**Clear data** deletes the console log with the rest of the app's data.
