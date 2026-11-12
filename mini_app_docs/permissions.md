# Permissions

Mini apps work like small Android apps: an app can only use what its manifest declares, the user allows or denies each declared permission, and every native module checks the decision before it does anything. This page explains every permission, when the user is asked, what changes a decision, required permissions, the typed confirmation for broad file access, the App info switches, and what a mini app can never reach whatever is granted.

## The model

1. The app declares permissions in `manifest.json` ([manifest.md](manifest.md#permissions)).
2. AntiGem turns each declaration into one or more named permissions: `network`, `storage:/sdcard/Download`, `mcp:notes_mcp`, … Entries it ignores, or that look like a mistake, are printed as warnings in the app's console ([manifest.md](manifest.md#manifest-warnings)).
3. The user grants or denies each one: on the first launch, when the app asks, or in App info.
4. A call that needs a permission that isn't granted rejects with a `DOMException` named `NotAllowedError`.

Each permission has one state:

| State | Meaning |
|---|---|
| `granted` | The user allowed it. |
| `denied` | The user said no. The app can ask again with `AntiGem.permissions.request()`. |
| `prompt` | Declared, but not decided yet (new, or its declaration was widened). |
| `undeclared` | Not in the manifest. It can't be requested. |

No permission is needed for the app's own things: its `data/` folder, `localStorage`, `sessionStorage`, `AntiGem.storage`, IndexedDB, its cookie jar, native dialogs and sheets, toasts, haptics, writing to the clipboard, sharing text with `AntiGem.device.share`, device info and status, and sending intents.

## Every permission

| Manifest | Permission name(s) | What it allows | Shown to the user as |
|---|---|---|---|
| `network` | `network` | Requests to the listed hosts (any host when there is no `hosts` list) | **Internet**: "Only: host, host" or "Connect to any website or server" (with a warning) |
| `localhost` | `localhost` | Servers on this phone: `127.0.0.1`, `localhost` and the phone's own addresses (listed ports, or any) | **Local servers** |
| `storage.folders[]` | `storage:<path>` (one per folder) | That folder, with the declared access | **Folder <path>**: "Read only", "Write (not read)" or "Read and change" |
| `storage: true` / `full` | `storage:all` | Everything in shared storage and the home folder | **All files** (warning; typed confirmation) |
| `storage.pick` | none (picking is the grant) | `AntiGem.fs.pickFolder()` | the folder picker |
| `notifications` | `notifications` | Show notifications, create channels, schedule reminders | **Notifications** |
| `background` | `background` | Background tasks while the app is closed | **Run in the background**: "Do tasks while closed, up to 48 times a day" |
| `clipboard` | `clipboard` | Read the clipboard (`AntiGem.device.paste()`) | **Clipboard** |
| `camera` | `camera` | Camera through `getUserMedia`, and the camera app for `<input type="file" capture>` | **Camera** (warning) |
| `microphone` | `microphone` | Microphone through `getUserMedia` | **Microphone** (warning) |
| `mcp` | `mcp:<server>` (one per server) | `AntiGem.mcp.list` / `call` on that server | **AntiGem tools: <server>** |

### network

```json
"network": { "hosts": ["api.example.com", "*.example.com"] }
```

- Covers everything that leaves the phone: `fetch`, `XMLHttpRequest`, `WebSocket`, `EventSource`, `navigator.sendBeacon`, `<img>` / CSS / fonts / scripts loaded from the web, the cookies API for those hosts, web URLs in the media viewer and notification images.
- Host rules: an exact name matches that host; `*.example.com` matches its subdomains but not `example.com` itself; `"*"` or no `hosts` list matches every host. An empty list matches no host (the manifest gets a warning). Any port.
- Every redirect is checked again: a redirect to a host outside the list fails. A host outside the list is not even looked up (DNS).
- The native network stack handles `http`, `https`, `ws` and `wss`. Addresses on your local network (`192.168.x.x`) are ordinary hosts: list them. The phone's own addresses are the exception: they also need `localhost` ([below](#localhost)).
- The WebView itself may only talk to the app's own origin. All other traffic goes through native code, which is where the checks happen.
- Opening a link outside the app (`AntiGem.device.openUrl`, or a tapped link that leaves the app) needs no permission when `network` is granted. Without it, AntiGem asks first: **Open link?** "<App> wants to open: <url>". Only `http(s)`, `mailto` and `tel` links open.

### localhost

```json
"localhost": { "ports": [8080, 3000] }
```

- Local means `localhost`, `*.localhost`, `127.x.x.x`, `::1`, `0.0.0.0`, and the phone's own addresses (its Wi-Fi or LAN IPs). A host name that resolves to this phone is treated as localhost too.
- The address is checked when the socket connects, for every connection: `fetch` and XHR, images and other subresources, WebSocket handshakes and every redirect.
- The phone's own IP, or another name that points to the phone, is also a host for `network`: it must be in the `hosts` list too.
- `ports` limits the ports; with no `ports` list, any port. An empty list allows no port (the manifest gets a warning).
- **AntiGem's own ports are always refused**, even with this permission: the bridge, the hub and the phone-tools (MCP) server. In the `com.termux` build these are 1234, 1235, 8765 and 8865; in the `com.antigem` build 1236, 1237, 8767 and 8867. The error is "port <n> belongs to AntiGem".

### storage

Files outside the app's own `data/` folder. Paths in the API are `data/…` (own folder, always allowed), `app:<id>/…` ([shared-data.md](shared-data.md)), `~/…` (AntiGem's home folder) or absolute (`/sdcard/…`).

**Manifest folders.** Each folder in `storage.folders` is its own permission (`storage:<path>`), so the user can allow some folders and deny others. The access comes from the manifest; the user only allows or denies it:

| Access | Allows |
|---|---|
| `read` | read, list |
| `write` | create, change, delete, move; not read |
| `readwrite` | both |

`exists` / `stat` work with either access.

**All files.** `storage:all` grants the declared access to the whole home folder and the whole of shared storage (`/sdcard`), minus the protected places listed [below](#never-reachable).

**Picked folders.** With `"pick"` in the manifest, `AntiGem.fs.pickFolder()` opens AntiGem's file explorer (**Give access to this folder**). The folder the user chooses gets the manifest's `pick` access. Picked folders are listed in App info under **Folders you picked** with **Remove**, and the app can give one back with `AntiGem.fs.forgetFolder()`. If the manifest later changes `pick`, each picked folder keeps the access it was picked with, but never more than the new `pick` allows: a change from `read` to `write` doesn't make a folder picked for reading writable. When the two have nothing in common (`read` and `write`), that folder no longer counts. Removing `pick` turns them all off. In Remote mode the folder must be on the phone, not on the PC or server.

**Typed confirmation.** These need the user to type a phrase under a warning before they turn on:

- `storage:all`;
- a manifest folder that is, or contains, the home folder or the root of shared storage (`~/`, `/sdcard`, `/storage`, `/`), or a folder that can't be resolved;
- picking such a folder.

The dialog says **Give <App> access to <scope>?**, explains that the app could read, change or delete photos, documents and downloads and send them anywhere it can reach, and asks to `Type iknowiamstupid to confirm:` (case does not matter). The button is **Give access**. In the first-launch dialog these switches start off.

**Symlinks.** Every path is resolved through symbolic links before it is checked. A link inside an allowed folder that points outside it is refused. Listings show such links as `{type: "link", blocked: true}`, and deleting a folder removes the links, not what they point to.

**Root folders.** The app's own `data/` folder, a shared app's `data/` folder, the home folder, shared storage and every granted folder can't be deleted or moved as a whole.

Errors you will see:

| Message | Cause |
|---|---|
| `Reading or writing <path> needs a "storage" folder in the manifest` | No storage declared at all |
| `<path> is not in a folder you allowed this app` | Declared, but not this folder, or denied |
| `<path>: the app may only read there` / `… only write there` | The folder's access doesn't allow this operation |
| `<path> is protected` | A place no app may reach ([below](#never-reachable)) |
| `The manifest doesn't allow picking folders (permissions.storage.pick)` | `pickFolder()` without `pick` |
| `Access to <path> was not confirmed` | The typed confirmation was cancelled |

### notifications

Needed to show a notification, create a channel and schedule a reminder. Listing, dismissing and cancelling don't need it. AntiGem's own notification switch in Android settings must be on too, otherwise calls fail with "Notifications are turned off for AntiGem in Android settings". See [notifications.md](notifications.md).

### background

Needed to register and run background tasks. The manifest must also name a `background` script. Without the permission, scheduled runs are skipped and recorded in App info. Limits and battery rules: [background.md](background.md).

### clipboard

Reading only. Writing (`AntiGem.device.copy`) is always allowed.

### camera and microphone

Used by `navigator.mediaDevices.getUserMedia`. The first time the page asks for a camera or microphone stream, AntiGem asks for the mini app permission and then, if needed, Android asks for AntiGem's own camera or microphone permission. Both must be allowed.

There is no location permission: `navigator.geolocation` is always refused.

### mcp:<server>

Each MCP server the app declares is its own permission. With it, `AntiGem.mcp.list(server)` and `AntiGem.mcp.call(server, tool, args)` reach that server's tools inside AntiGem. Servers whose name contains `terminal`, `android` or `browser` are shown with a warning ("Can control parts of your phone through <server>"), because they can run commands, tap the screen or drive the browser. See [mcp.md](mcp.md).

## When the user is asked

**First launch.** When an app opens and some declared permissions are still undecided, a dialog titled **<App> wants to** lists them, each with a switch, and says "You can change these later in App info." Switches start on, except broad file access (typed confirmation). The buttons are **Continue** and **Deny all**; when the dialog lists required permissions the second button reads **Don't allow (app can't run)** and leads to the screen that explains the app can't run without them ([below](#required-permissions)). **Continue** stays disabled while a required switch is off.

**When the app asks.** `AntiGem.permissions.request(name)` shows **Allow <App> to use this?** with **Allow** / **Don't allow**. It asks again even after a denial (there is no "don't ask again"). `request("storage")` asks for every storage folder not granted yet, each with its own switch. A granted or undeclared permission returns at once without a dialog.

`"storage"` on its own is a summary of the storage permissions: `prompt` while any declared folder (or all files) is undecided, `granted` only when every one is granted (or, with no folders declared, when a folder was picked), otherwise `denied`, and `undeclared` when there is nothing. So with one folder allowed and one denied it is `denied`: check the folder you need with `query('storage:<path>')`. `query('storage')` works in pages and in workers.

**Camera and microphone** are asked for when the page requests a stream. The camera is also asked for when the user taps a file input with `capture` ([ui.md](ui.md#camera-and-microphone)).

**Never asked:** background task pages and calls that simply fail. A call to an API whose permission is not granted rejects with `NotAllowedError` (`"notifications" permission is denied`, `… is prompt`, `… is undeclared`); it does not open a prompt. Call `request()` first, for example from a button the user tapped.

The page can follow decisions: `AntiGem.permissions.all()`, `query(name)` and the `permission` event ([api-reference.md](api-reference.md)).

## When a declaration changes

A decision is tied to the declaration it was given for. If the manifest later widens a permission, that permission goes back to `prompt`, and the user is asked again on the next launch or the next `request()`. Widening means:

- `network`: a host the old list didn't cover, or any host (`true`, no `hosts` list, `"*"`) where there was a list;
- `localhost`: a new port, or any port where there was a list;
- a folder or all files: access the old one didn't include (`read` or `write` changed to `readwrite`, or `read` and `write` swapped).

A change that only narrows or reorders keeps the decision: fewer hosts or ports, a host an old `*.` rule already covered, less access for the same folder, the same entries in another order. For the other permissions, a change that only reorders keys or list entries keeps it too; any other change asks again. Unchanged permissions keep their decision.

Because each storage folder is a separate permission, adding a folder only asks for the new folder. An update of an installed app works the same way: the install screen lists **New permissions it will ask for**, and the app asks for them when it opens.

## Required permissions

`required_permissions` in the manifest ([manifest.md](manifest.md#required_permissions)) lists permissions the app can't work without.

- In the permission dialogs a required permission says "Required: the app won't open without it".
- If one is not granted when the app opens, the page is not loaded. A native screen says **<App> can't run without**, lists what is missing, and offers **Review permissions** and **Close**.
- The check runs again whenever the app comes back to the screen. Turning a required permission off in App info first asks **<App> needs this** ("… is required: without it <App> won't open. Turn it off anyway?" **Turn off** / **Keep**); after that the app shows the blocked screen.
- Background tasks of an app with a missing required permission are skipped.

## App info

Long-press an app in Mini Apps (or, for a development app, use the developer bubble → **App info & permissions**). The **Permissions** section has one switch per permission, with the same titles and details as the prompts; required ones are marked **Required**. An app without permissions shows "This app asks for no permissions. It can only use its own storage."

- Turning on broad file access asks for the typed confirmation.
- **Folders you picked** lists picked folders with **Remove**.
- **Reset permissions** forgets every decision, every picked folder and every file shared to the app; the app asks again on its next launch. Its data stays.
- **Clear data** deletes the app's data but keeps its permissions.
- Uninstalling an installed app removes its permissions with everything else. Installing a new app over leftover data starts with no decisions.

## Files shared with an app

When another mini app sends a file through an intent, the receiving app may read exactly that file for one day, without a storage permission, like an Android content URI grant ([intents.md](intents.md)). Files shared from Android's share sheet are copied into the app's own `data/inbox/`.

## Never reachable

Whatever is granted, a mini app can never reach:

- **Other mini apps' code:** everything in `~/mini-apps/`, and installed apps' files.
- **Other mini apps' data:** everything in `~/.antigem/mini-apps/`, except its own `data/` and the `data/` folders of its [shared group](shared-data.md).
- **Hidden folders in the home folder:** `~/.ssh`, `~/.antigem`, `~/.codex`, `~/.claude` and every other folder starting with `.`.
- **Anything outside the home folder and shared storage:** AntiGem's private files, system folders, other storage volumes.
- **AntiGem's own local ports** (bridge, hub, phone-tools server), even with `localhost`.
- **Other origins from the WebView itself:** pages can't navigate away or open windows; links open outside.
