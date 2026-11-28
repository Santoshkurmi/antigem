# 06 · Android app side

## New Kotlin files (`app/src/main/java/ai/antigem/data/netcap/`)

| File | Job |
|---|---|
| `NetcapManager.kt` | unpacks `netcap.xz` into `$PREFIX/bin`, starts, stops and restarts the engine (like `LocalServerManager`), owns the control socket, exposes state |
| `NetcapVpnService.kt` | the `VpnService`: builds the tunnel for the chosen apps, hands it to the engine, notification with Stop, `onRevoke` |
| `NetcapControl.kt` | control-socket messages (protobuf frames), sending the tunnel handle, answering owner lookups |
| `NetcapCert.kt` | stores the CA key encrypted with the Keystore; export; root install, remove and status |
| `NetcapDeviceFacts.kt` | real device values for the privacy report (Android ID, advertising ID when available, model, installed packages); sent only to the engine |
| `NetcapMcpProxy.kt` | forwards `/network` from `AndroidLocalBridgeServer` to the engine |
| `NetcapSettings.kt` | settings: agent permissions, limits, show secrets to AI, never-decrypt list |

UI goes in `ui/netcap/`: a small page in Phases 2–3, the full screen in Phase 7.

## VPN service

- `VpnService.prepare()` shows the user's consent dialog once.
- Builder: `addAllowedApplication(pkg)` for each chosen app; a private IPv4 address and an IPv6 `fd00::/64` address;
  routes `0.0.0.0/0` and `::/0`; the DNS server is an address inside the tunnel that the engine answers; `setMtu`,
  `setBlocking(false)`, `setMetered(false)` (so apps behave as usual), underlying networks follow the default.
- `establish()` gives a `ParcelFileDescriptor`. It goes to the engine over a `LocalSocket`
  (`setFileDescriptorsForSend`), then the app closes its own copy.
- Foreground notification: "Capturing com.myapp · 312 requests" with a Stop button.
- `onRevoke()` (the user turned the VPN off, or another VPN started): stop capture; agents see it in `net_status`.
- Only one VPN can run at a time: warn when another one is on.

## Certificate

- The engine creates the CA on first start and sends the private key to the app over the control socket. The app
  encrypts it with an Android Keystore key (`ca.key.enc`) and gives it back to the engine, in memory, on each start.
- **Root install** (DeviceAccess in root mode):
  - Android 7–13: mount a writable copy (tmpfs) over `/system/etc/security/cacerts` with the CA added as
    `<hash>.0`, with the right owner, mode and SELinux label.
  - Android 14 and newer: the certificate store moved into `/apex/com.android.conscrypt/cacerts`, and the mount also
    has to be applied inside zygote's and the running apps' mount namespaces (the way HTTP Toolkit does it). The
    target app is restarted afterwards.
  - Not permanent: it's gone after a reboot and re-applied when a capture starts (status is checked first).
  - Remove: unmount.
- **Without root**: export the certificate so the user can install it as a user certificate. That works for the
  user's own debug builds that allow user certificates:

  ```xml
  <network-security-config>
    <debug-overrides>
      <trust-anchors><certificates src="user" /></trust-anchors>
    </debug-overrides>
  </network-security-config>
  ```

- **Status**: look for our hash in the system store, plus a self-test connection through the engine.

## Which app made a connection

- One captured app: already known.
- Several apps: the engine asks the app, which calls `ConnectivityManager.getConnectionOwnerUid()` (Android 10+,
  allowed for the active VPN app) and maps the UID to a package name with `PackageManager`. Results are cached.
- Android 7–9: the engine reads `/proc/net/tcp*` and `/proc/net/udp*` itself (still allowed there).

## MCP route and presets

- `AndroidLocalBridgeServer`: `path == "/network"` → `NetcapMcpProxy`, after the same `X-Device-Id` check as the
  others. It starts the engine if needed, forwards the request and streams the answer back.
- Presets: one `network_mcp` line in each of `ClaudeSettingsScreen.kt`, `CodexMcpPage.kt`, `OpenCodeMcpPage.kt` and
  `SettingsDialog.kt` (AGY), the same way as `/android`.
- Works together with `android_automation_mcp` (launch, tap, screenshot, `wait_for`) to drive the app under test.

## Screens

- **v1 (Phases 2–3)**: Settings → Network Interceptor, with start/stop, app picker, certificate (install, remove,
  export, status), agent options ("Allow agents to start capture", "Show secrets to AI"), limits, never-decrypt list,
  sessions (list, delete, export).
- **Later**: its own page, `AppViewMode.NETWORK` plus `"network"` in `PageOverview`, next to Terminal and Files
  (08-gui.md).
- Every text goes through `L.*` (`assets/i18n/en.json`).

## Manifest and permissions

- A new `<service>` with `android:permission="android.permission.BIND_VPN_SERVICE"`, an intent filter for
  `android.net.VpnService`, and `foregroundServiceType="specialUse"` with a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`
  property, like `TermuxService`.
- No new `<uses-permission>` lines: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `QUERY_ALL_PACKAGES` and `WAKE_LOCK` are already there.
  `targetSdk` is 28.

## Build and packaging

**As built (2026-11-28): downloaded on first use, not in the release APKs.** Most users never use the Network
Interceptor, so the engine (~7.4 MB xz, ~29 MB unpacked) isn't packed into the normal, `-chroot` or x86_64 APK.
(Merging it into `agy_ide_bridge` as a subcommand was measured first: −3.6 MB APK, −12 MB unpacked; the download
saves all of it for everyone who doesn't use the feature, so the merge was dropped.)

- **Version:** `netcap/release.json` = `{"version": "1.0.0"}` (committed). Bump it when netcap changes.
- **URL pattern:** `https://github.com/Santoshkurmi/antigem/releases/download/netcap-v<version>/netcap-<version>-android-<arch>.xz`,
  `arch` = `arm64` (phone APKs) or `amd64` (x86_64 APK). Its own release tag, not an app release (`--latest=false`).
- **Releases are automatic:** the app's release workflow runs `scripts/netcap_release/netcap_release.sh ci` before
  building the APKs. When `netcap-v<version>` doesn't exist it builds both files (`-trimpath -buildvcs=false`) and
  publishes them; when it exists it downloads them (never rebuilt, so older apps keep the files they pin) and builds
  arm64 again: a different file means netcap (code, dependencies or Go version) changed without a version bump, and
  the release stops with "bump version". Then it writes the files' SHA-256 and size into the workspace's
  `release.json` (`"assets": {"arm64": {…}, "amd64": {…}}`, not committed), which Gradle puts into
  `BuildConfig.NETCAP_VERSION / NETCAP_URL / NETCAP_SHA256 / NETCAP_SIZE` for the APK's CPU. A build that downloads
  the engine fails (`checkNetcapPin`) without that hash; locally `netcap_release.sh pin` fetches the published files'
  hashes, `build` + `pin --local` uses your own build.
- **App:** `NetcapManager.ensureBinary` downloads to `bin/netcap.xz.part`, refuses it unless the SHA-256 matches (the
  engine holds the CA and sees decrypted traffic), unpacks to `bin/netcap` (on the terminal's PATH, so `netcap hook`
  works there) and remembers `<version>/<sha256>` (prefs `engine_installed`); a different pin after an app update
  downloads again. It happens on the first use: an agent's `network_mcp` call (waits up to 45 s, then says "still
  downloading"; the download goes on), Start capture (before the VPN starts), or the Download button on Settings →
  Network Interceptor, which shows the size and progress. Opening the page alone downloads nothing.
- **Debug builds** pack a fresh build of `../netcap` as `bin/netcap.xz` (a per-variant generated asset,
  `NetcapAssetTask`); the app uses it when present. `-Pnetcap=download` leaves it out (to test the download; the
  debug-only pref `dev_engine_url` in `netcap_prefs` points the download at a local file server), `-Pnetcap=bundle`
  packs it into a release build. The engine always runs on the Android side, not inside the chroot.
- The command line lives in `netcap/cli` (`Run(args)`); `netcap/cmd/netcap` is the thin PC/Android binary.

## Troubleshoot page

The debug report gets the engine's status, version and last errors, never traffic content.
