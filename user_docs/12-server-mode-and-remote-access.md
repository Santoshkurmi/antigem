# 12 — Server Mode & Remote Access

AntiGem can operate in two modes: running locally on your phone (**"This phone"**), or running on an external computer or cloud instance (**"Server"** or **"SSH tunnel"**). This lets your phone act as a light, responsive mobile interface while your computer handles the heavy processing and compilation.

---

## 🎛️ 1. Where the Server Runs: The Three Modes

In **Settings → Servers & Connectivity**, the very first card is titled **"Where the server runs"**:

1. **This phone** (Local Mode):
   - *Description*: *"The bridge and the agents run on this phone."*
   - Everything runs directly inside your phone's storage. No external server, network setup, or SSH key required.
2. **Server** (Direct / Reverse Proxy Mode):
   - *Description*: *"The bridge and the agents run on your server, reached by a domain or open ports. The app manages them over SSH."*
   - Best when connecting to a remote server with public domains (e.g. `https://bridge.example.com` and `https://hub.example.com` behind nginx/Apache) or direct IP addresses.
3. **SSH tunnel** (Encrypted Tunnel Mode):
   - *Description*: *"On your server, reached through the SSH connection: no open ports, no domain, no reverse proxy."*
   - The safest and easiest way to connect to a private PC or server: the bridge and hub listen strictly on the server's `127.0.0.1`, and AntiGem forwards the ports over your encrypted SSH session.

---

## 💾 2. Saved Server Profiles

You can save and switch between multiple servers (e.g., your home desktop, work workstation, and a cloud server) using **Saved profiles**:

- **"Saved profiles (n)" Button**: Opens a dialog listing your saved configurations with badges: `LOCAL` (shown on local endpoints), `SERVER`, or `SSH TUNNEL`.
- **Loading a Profile**: Tap any saved profile in the list to load its addresses, ports, and credentials. The app displays the confirmation banner: `"Loaded profile \"<name>\". Tap Save below to apply it."`. Its connection mode is selected automatically; tap **Save Server & Security Settings** to apply.
- **"Save as profile" Button**: Opens the **Save server profile** or **Save local profile** dialog:
  - **Profile name**: Enter a friendly label (e.g., `Home Desktop Linux` or `AWS GPU Instance`).
  - **Include SSH password and key**: Checkbox (off by default for security, so someone borrowing your phone cannot export your credentials).
- **Profile Editor Actions**:
  - **Update**: Overwrites the profile with current form values.
  - **Discard Changes** (in red): Discards any unsaved host, port, user, or password edits and reloads the saved profile settings.
  - **Delete**: Permanently removes the saved profile.
- **"Reset All to Defaults" Button**: Available in local mode to restore all URLs and binary paths to fresh installation values.

---

## 🌐 3. Server Addresses & Ports

The **"Server addresses"** card configures where AntiGem sends network traffic:

- **IDE Bridge address**:
  - In local mode or SSH tunnel mode: defaults to `http://127.0.0.1:8080`.
  - In direct Server mode: enter your domain or IP (e.g. `https://bridge.example.com` or `http://203.0.113.5:1234`).
- **AGY Hub address**:
  - In local mode or SSH tunnel mode: defaults to `http://127.0.0.1:50051`.
  - In direct Server mode: enter your domain or IP (e.g. `https://hub.example.com` or `http://203.0.113.5:1235`).
- **Server Ports (in direct Server mode)**:
  - **Bridge listens on**: Real port the bridge listens on at the server (default `8080`).
  - **Hub listens on**: Real port the hub listens on at the server (default `50051`).
  - **Bridge bind address**: `127.0.0.1` behind a reverse proxy (like nginx), or `0.0.0.0` for a direct open address.
- **Live Status Lines & Testing**:
  - Displays real-time status dots (green = online, red = offline) for both **Hub** and **Bridge**.
  - **"Test Hub" Button**: Probes the AGY Hub gRPC endpoint and displays the response latency or error message.
  - **"Test Bridge" Button**: Probes the IDE Bridge HTTP endpoint and reports health.

---

## 🔑 4. SSH Access & Authentication

When using **Server** or **SSH tunnel** mode, AntiGem manages the remote server process through SSH:

- **SSH host**: The server hostname or IP address (e.g., `192.168.1.50` or `mybox.net`).
- **Port**: SSH port (default `22`).
- **User**: Your Linux login username (*"not root if you can avoid it"*).
- **Password (optional when a key is set)**: Enter your SSH account password.
- **Private key (recommended)**:
  - Text area supporting OpenSSH formatted keys (`-----BEGIN OPENSSH PRIVATE KEY-----`).
  - Tap **"Choose key file"** to pick a key from your phone storage, or **"Paste key"** to paste from the clipboard.
- **Key passphrase (if it has one)**: Passphrase used to decrypt your private key.
- **"Test SSH" Button**: Verifies SSH connectivity and displays the remote shell confirmation.
- **"Forget host key" Button**: AntiGem pins the server's SSH host key on first connection for security. If you reinstalled your server OS and the host key changed, tap this button to accept the new key.

### Advanced Server Binary Paths:
Tapping **"Show"** under **Advanced (server paths and ports)** exposes:
- **Bridge binary on the server**: Path to `agy_ide_bridge` on the server.
- **agy binary on the server**: Path to the `agy` CLI executable.
- **claude binary on the server**: Path to `claude` CLI.
- **opencode binary on the server**: Path to `opencode` CLI.
- **Bridge folder (in the server home)**: Directory where AntiGem stores server runtime files (default: `~/.antigem-server`).

---

## 🔀 5. Port Forwards (Server → This Phone)

The **"Port forwards (server → this phone)"** card lets you access web apps running on the server directly in your phone's browser:

- **What it does**: If you run `npm run dev` or a Python web app on the server (e.g., on port 5173), this feature forwards that port over the SSH connection to your phone's `http://127.0.0.1:5173`.
- **How to add a port forward**:
  1. **Server port**: Enter the port running on the server (e.g., `5173`).
  2. **Phone port**: The port on your phone (leave blank to match the server port, or specify a port between `1024` and `65535`).
  3. **Name (optional)**: Enter a label (e.g., `Vite dev server`).
  4. Tap **Add**.
- **Phone Port Rules**: Phone ports must be between `1024` and `65535` (ports below 1024 require root). The app prevents selecting ports reserved by the internal bridge or hub.
- **Live Forward Status**: Each forward shows a live indicator:
  - 🟢 **active**: Tunnel is open and ready.
  - 🔴 **could not open the phone port**: Port already in use on phone.
  - 🟡 **waiting for SSH** / **opening…**: Waiting for SSH handshake.

---

## 🛡️ 6. Security: Tokens & Proxy Access Headers

AntiGem provides two distinct layers of security to prevent unauthorized access to your server:

### Layer 1: Security Token (Header Obfuscation)
- **12-Character Secret Token**: The token input field is labelled `"12-Character Secret Token"`. It holds a secret key that frames every HTTP request to the bridge with an obfuscated authentication header.
- **HTTP Framed Header**: Directly beneath the token input, the screen displays:
  `HTTP Framed Header: <the header>`
- **Token Controls**:
  - **Randomize**: Generates a new cryptographically random 12-character token.
  - **Copy Header**: Copies the framed header string to your clipboard and displays the toast: `"Copied <header> to clipboard"`.

### Layer 2: Proxy Access Header (Second Layer)
*Used when running behind a reverse proxy like nginx or Apache in direct Server mode:*
- **Header name**: Custom HTTP header (e.g., `X-AntiGem-Secret`).
- **Header value (secret)**: Secret value required by the reverse proxy.
- **"Generate" Button**: Generates a random secure secret.
- **"Copy nginx rule" Button**: Copies a ready-to-paste nginx configuration snippet:
  ```nginx
  map $http_x_antigem_secret $antigem_key_ok { default 0; "your-secret" 1; }
  if ($antigem_key_ok = 0) { return 444; }
  ```
  This causes your reverse proxy to silently drop requests from strangers before they even reach the bridge or hub.

---

## 🕹️ 7. Live Server Control & Logs

When Remote Server mode is active, the **"Server control"** card appears:
- **Status Indicator**:
  - 🟢 **Bridge running on the server**
  - 🟡 **Starting…** / **Stopping…**
  - ⚪ **Bridge stopped** / **Not started**
  - 🔴 **Error message**
- **Action Buttons**:
  - **Start**: Launches the bridge on the remote server via SSH.
  - **Restart**: Gracefully restarts the remote processes.
  - **Stop**: Terminates the bridge process on the server.
  - **"Server log" Button**: Opens the **Server log** dialog displaying live console logs streaming from the server bridge. Tap **Refresh** to reload.

---

## 💾 8. Saving Changes & Reopening AntiGem

Whenever you change server settings:
1. Tap **"Save Server & Security Settings"** at the bottom of the screen.
2. If any required fields are missing or invalid, a red feedback banner explains the exact issue.
3. Once valid, a confirmation dialog titled **"Reopen AntiGem?"** will appear (*"The app has to restart to connect to the new server settings"*).
4. Tap **"Reopen Now"** to immediately reconnect with the new settings, or **"Later"** to finish editing.

---

## 🛠️ Troubleshooting Server & Remote Connection Issues

### ❌ Symptom: "In SSH tunnel mode both addresses are 127.0.0.1 with the port...".
- **What to check**: Check the addresses entered in **IDE Bridge address** and **AGY Hub address**.
- **How to fix**: In SSH tunnel mode, do not enter external domains or public IPs. Set the Bridge address to `http://127.0.0.1:8080` and the Hub address to `http://127.0.0.1:50051`. The SSH tunnel handles the encryption and network routing automatically.

### ❌ Symptom: "A remote server needs its address (not 127.0.0.1) or an SSH host".
- **What to check**: Check your connection mode.
- **How to fix**: If you selected **Server** mode (without tunnel), you cannot use `127.0.0.1` because the phone would attempt to connect to itself. Enter the real IP or domain name of your computer, or switch to **SSH tunnel** mode.

### ❌ Symptom: "The server's host key does not match the pinned key" or SSH connection fails after reinstalling server OS.
- **What to check**: AntiGem security pins your server's SSH key on first connection to protect against spoofing.
- **How to fix**: Go to **Settings → Servers & Connectivity → SSH access** and tap **"Forget host key"**. Then tap **Test SSH** to accept and pin the server's new key.

### ❌ Symptom: "That file is not a private key (expected -----BEGIN … PRIVATE KEY-----)".
- **What to check**: Verify the format of your SSH key file.
- **How to fix**: Ensure you picked a private key file (not a `.pub` public key). The file content must start with `-----BEGIN ... PRIVATE KEY-----`.

### ❌ Symptom: Port forward shows "could not open the phone port".
- **What to check**: Check the phone port number chosen in the forward card.
- **How to fix**: Ensure the phone port is between `1024` and `65535` and not already used by another running app on your phone. Change the phone port to another number (e.g. `15173`) and tap Add again.
