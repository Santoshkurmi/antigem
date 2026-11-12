# Developing on a PC

The phone is the runtime: a mini app only runs inside AntiGem on Android. From a PC you can still do most of the work. You can write the code there and keep it in git, sign and check packages with the Node tool `agapp.mjs` (same format and same keys as the phone), and let an agent on the PC build and test apps on the phone through the Mini App Developer MCP. This page covers the tool, sharing one key between the phone and the PC, and the ways to get files onto the phone.

## What runs where

| Task | Phone | PC |
|---|---|---|
| Run and test an app | Yes (Mini Apps) | No |
| Write the code | Yes (any agent, the IDE) | Yes |
| Hot reload, console, screenshots | Yes | Through the Mini App Developer MCP |
| Create a developer key | Mini Apps → key icon | `agapp.mjs keygen` |
| Sign a package | App info → **Export signed package** | `agapp.mjs sign` |
| Verify a package | Automatically on install | `agapp.mjs verify` |
| Install a package | Yes | No (copy it to the phone) |

## agapp.mjs

A single file, Node 18 or newer, no dependencies. Get it from:

- the phone: `~/mini-apps/tools/agapp.mjs` (AntiGem puts it there and updates it with every AntiGem version, so copy it again after an update);
- the AntiGem source tree: `app/src/main/assets/mini-apps/tools/agapp.mjs`.

Copy it anywhere on the PC.

```bash
node agapp.mjs keygen --developer "Your Name" --out dev.agkey [--force]
node agapp.mjs sign <app-folder> --key dev.agkey [--out file.agapp]
node agapp.mjs verify <file.agapp>
node agapp.mjs info <file.agkey>
```

**Password.** Every command that opens a key reads the password from the `AGAPP_PASSWORD` environment variable, or asks on the terminal. `keygen` asks twice and needs at least 8 characters. Without a terminal and without the variable it stops with "No terminal to ask for the password: set AGAPP_PASSWORD".

### keygen

```bash
node agapp.mjs keygen --developer "Your Name" --out dev.agkey
```

```
Key for Your Name written to dev.agkey
Fingerprint AB12:CD34:EF56:7890
Keep it safe: updates of your apps must be signed with this key.
```

Creates a new EC P-256 key, encrypted with your password, readable only by you (file mode 600). It refuses to overwrite an existing file unless you pass `--force`. Only make a new key if you don't have one yet: use the phone's backup otherwise ([below](#one-key-on-the-phone-and-the-pc)).

### sign

```bash
node agapp.mjs sign ./tally --key dev.agkey
```

```
Signed com.example.tally 1.0.0 (code 1000000, 5 files) as Your Name AB12:CD34:EF56:7890
→ com.example.tally-1.0.0.agapp
```

- Reads `manifest.json` (it must be valid JSON with a valid `id`), packs every file and writes `<id>-<version>.agapp` in the current folder, or `--out`.
- Skips names starting with `.`, `node_modules`, a top-level `META-INF` (a nested one is packaged) and symbolic links (it prints `skipped symlink: …`). The phone skips the same files.
- `version_code` may be a whole number or a string of digits; anything else stops with `"version_code" must be a whole number (got …)`. Without it, the code comes from `version`, by the same rule as on the phone ([manifest.md](manifest.md#version-and-version_code)).

### verify

```bash
node agapp.mjs verify com.example.tally-1.0.0.agapp
```

```
OK  com.example.tally 1.0.0 (code 1000000)
    5 files, signed 2026-11-12T06:15:00.000Z
    developer Your Name  AB12:CD34:EF56:7890
```

Checks the same things as the phone's installer (signature, every file's hash, no missing or extra files, safe paths, manifest id, version and `version_code` equal to the signed ones), plus a few more: no unexpected files in `META-INF/`, a P-256 key, ZIP checksums and no duplicate entries. Run it before you publish.

### info

```bash
node agapp.mjs info dev.agkey
```

Prints the developer name, the short fingerprint and the full SHA-256 of the public key. Use it to check that a key file is the key your apps are signed with: the short fingerprint must equal the one in the phone's **Developer key** dialog and on the install screen.

## One key on the phone and the PC

The phone's key backup and `agapp.mjs` use the same `.agkey` file format, so one key can sign on both. Packages signed on either update apps installed from the other.

**Phone → PC:**

1. On the phone: Mini Apps → key icon → **Back up now**. The file goes to `Download/AntiGem/antigem-key-<name>-<8 hex>.agkey` and the share sheet opens.
2. Send it to the PC (USB, Drive, email to yourself).
3. `node agapp.mjs info antigem-key-….agkey` and compare the fingerprint.
4. Sign with `--key antigem-key-….agkey` and the backup's password.

**PC → phone:**

1. `node agapp.mjs keygen --developer "Your Name" --out dev.agkey`.
2. Copy `dev.agkey` to the phone.
3. Mini Apps → key icon → **Import**, pick the file, type its password. This replaces the phone's current key ([signing-and-distribution.md](signing-and-distribution.md#restore-the-key)).

Keep `.agkey` files out of git (add `*.agkey` to `.gitignore`). In CI, pass `AGAPP_PASSWORD` as a secret, never in the command line or logs.

## Building on the phone from a PC agent

An agent on the PC can't run mini apps, but it can drive the phone through the **Mini App Developer MCP**: its tools write files into the phone's `~/mini-apps`, open the app, reload it, read the console, run JavaScript in the page, take screenshots, validate, sign and install. The development loop is in [getting-started.md](getting-started.md#the-dev-loop-with-the-mini-app-developer-mcp) and the tools in [developer-mcp.md](developer-mcp.md).

**With AntiGem's Remote / Server mode.** When the agents run on your server or PC (**Settings → Servers & Connectivity**, **Server** or **SSH tunnel**), their own file tools write on that machine, not on the phone. Over the SSH connection AntiGem forwards the server's `127.0.0.1:8765` (`127.0.0.1:8767` in the `com.antigem` build) back to the phone's tool server, so the phone's MCP presets work for agents on the server too. Add the **Mini App Developer** preset in that agent's MCP settings and ask for the app as usual; every file it writes through the MCP lands on the phone, and you see the app hot-reload there.

**With another client on the PC** (an agent AntiGem didn't start): connect the phone over USB, run `adb forward tcp:8765 tcp:8765`, and add an HTTP MCP server with the same URL and `X-Device-Id` header that the Mini App Developer preset fills in ([developer-mcp.md](developer-mcp.md#adding-it); open the preset in any agent's MCP settings on the phone to see the exact values). AntiGem must be running on the phone.

## Getting files onto the phone

| What | How |
|---|---|
| A development app, while you work on it | Through the Mini App Developer MCP: files are written straight into `~/mini-apps/<folder>/` and the app reloads. |
| A development folder made on the PC | `adb push my-app /sdcard/Download/my-app`, then in AntiGem's terminal `cp -r /sdcard/Download/my-app ~/mini-apps/` (AntiGem needs its storage permission). `adb` can't write into `~/mini-apps` directly: it is inside AntiGem's private home. |
| A signed package | `adb push my-app-1.0.0.agapp /sdcard/Download/`, then tap it in a file manager, or Mini Apps → install icon → **Install from a file**. Or send it through any chat or email app, or host it and use **Install from a link**. |

Remember the id rule: a package can't take the id of a development folder that exists on the phone ("A development app with the id … is in ~/mini-apps/…"). To test the installed copy on the phone you develop on, give the development folder another id or remove it first.

## A typical workflow

1. Keep the app's folder in git on the PC.
2. Develop and test on the phone through the MCP (or push the folder), with hot reload.
3. Bump `version`, then `node agapp.mjs sign ./my-app --key dev.agkey` and `node agapp.mjs verify my-app-….agapp`.
4. Publish the `.agapp` (for example as a GitHub release asset) and install or update it from a link ([signing-and-distribution.md](signing-and-distribution.md#hosting-packages)).
