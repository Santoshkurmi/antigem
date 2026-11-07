# Running AntiGem on a server (Server mode)

In Server mode the IDE bridge, the Antigravity hub (`agy`) and Claude Code run on a server (a VPS, a home server, any
Linux box) instead of on the phone. The phone only shows the UI: chats, the editor, the terminal. The agents, your
projects, your files and the shell all live on the server.

This guide takes you from an empty Linux server to a working app. Follow the steps in order.

> **Security warning: read [section 15](#15-security-read-this-then-verify-it-yourself) before you put anything real on
> a server.** Nothing here is guaranteed to be secure. This software is young and has not been independently audited,
> and a server on the internet is attacked all day. **Assume your server can be hacked**, keep nothing on it that you
> cannot afford to lose or leak, and **test every protection yourself** (section 15.5): a setting that you typed in but
> never checked may do nothing at all.

## Two ways to connect: which one to use

The app always logs in to the server over SSH (to start and stop the bridge, for the terminal and for the phone
tools). What differs is how the app talks to the **bridge** and the **hub**. In **Settings > Servers**, under *Where the
server runs*, pick one:

* **SSH tunnel** (recommended, try this first): the bridge and the hub listen only on the server's `127.0.0.1`. The app
  forwards the same ports on the phone's `127.0.0.1` to them through the SSH connection. You type
  `http://127.0.0.1:1234` and `http://127.0.0.1:1235` as the addresses (the port is the one the service listens on at
  the server).
* **Server** (domain or open ports): the app reaches the bridge and the hub over the network, either through a reverse
  proxy with TLS on two sub-domains (`https://bridge.example.com`, `https://hub.example.com`) or straight on two open
  ports (`http://203.0.113.5:1234`, `:1235`).

```
SSH tunnel mode (recommended)
phone (AntiGem app)                                   server
 └─ SSH (port 22)  ─────────────────────────────────►  sshd
      · phone 127.0.0.1:1234 ──(tunnel)──► bridge   (REST + WebSocket)  on the server's 127.0.0.1:1234
      · phone 127.0.0.1:1235 ──(tunnel)──► agy hub  (gRPC over HTTP/2)   on the server's 127.0.0.1:1235
      · your own port forwards (a dev server, ...): phone 127.0.0.1:5173 ──► server :5173
      · starts / stops the bridge (in a tmux session), terminal
      · reverse tunnels: the server's agents reach the phone's MCP tools (browser, Android, notes)
      · adb: the server's adb reaches the phone's adb (optional)

Server mode (domain or open ports)
phone (AntiGem app)                              server
 ├─ https://bridge.example.com  ──────────────►  bridge   (REST + WebSocket)   :1234
 ├─ https://hub.example.com     ──────────────►  agy hub  (gRPC over HTTP/2)    :1235
 └─ SSH (port 22)               ──────────────►  sshd (start / stop, terminal, reverse tunnels, adb, as above)
```

### SSH tunnel: pros and cons

**Pros**

* **Safer: the smallest attack surface.** Only SSH is reachable from the internet. The bridge and `agy` are never
  exposed, so a bug in them, a leaked token or a wrong proxy rule cannot be used by a stranger. To get in, an attacker
  first has to get through SSH.
* **Nothing to set up** besides SSH: no domain, no DNS, no certificates to renew, no nginx / Caddy / Apache, no firewall
  rules for 1234 / 1235, no proxy access header. Sections 6 to 8b do not apply.
* **Always encrypted** by SSH, even though the addresses say `http://`. The token is never sent in clear text.
* **Works for servers you cannot expose**: a home server behind a router, a machine on a private network. All it needs is SSH.
* **Port forwards come with it:** any port of the server (a dev server started with `npm run dev`, an admin page) can be
  opened on the phone at `http://127.0.0.1:<port>` (*Port forwards* in Settings > Servers). These also work in Server
  mode.

**Cons**

* **Everything rides on one SSH connection.** When it drops (switching between Wi-Fi and mobile data, a tunnel or a
  train, Android putting the phone to sleep), chats, files, the editor and the terminal all pause together until the
  app reconnects. The app reconnects by itself, after 3 seconds and then up to every 30 seconds, but a long answer that
  was streaming at that moment may need to be reopened.
* **Nothing works until SSH is connected.** Startup takes a moment longer, and *Test Hub* / *Test Bridge* only work after
  you saved the settings and SSH is up.
* **Slower for big transfers.** Large attachments, media, downloads and plugin ZIPs go through the app's SSH library,
  which is slower than a direct HTTP/2 connection. Chat streaming and the terminal are not noticeably affected.
* **Only this app on this phone can use it.** A desktop browser or a second device cannot reach the bridge unless you
  open your own `ssh -L` tunnel there.
* **The ports must be free on the phone.** The tunnel opens the bridge and hub ports on the phone's `127.0.0.1`. If
  something else on the phone already uses them (for example the local bridge of the other AntiGem variant on the same
  ports), the tunnel cannot open: use other ports in the addresses.
* **The SSH key is the master key.** Whoever has it has the bridge too (in Server mode they have a shell anyway, so this
  is no worse, but protect the key).

### Server mode (domain or open ports): pros and cons

**Pros**

* **Independent of SSH:** chats, files and the editor go straight to the server, so they keep working when the SSH
  connection drops for a moment. Only the terminal, start / stop and the phone tools wait for SSH.
* **Full speed:** a direct HTTP/2 (and TLS) connection for every request, better for big files and media.
* **Reachable from more than one device** and from other clients, and can sit behind a CDN or your existing reverse proxy.

**Cons**

* **The bridge and `agy` are on the internet.** Any bug in them, a leaked token, or a proxy or firewall mistake is open
  to everyone who finds the server, and servers on the internet are scanned all day.
* **Much more to set up and to keep right:** a domain, DNS, certificates (and their renewal), the proxy configuration
  (`underscores_in_headers on;` for nginx, WebSocket and gRPC settings), firewall rules, and ideally the proxy access
  header. Every one of these needs testing (section 15.5).
* **Plain `http://` with an IP leaks the token** to anyone on the network path. That is only acceptable on a private
  network or a VPN (Tailscale, WireGuard).

**In short:** start with the **SSH tunnel**. Move to Server mode only if you need full speed, chats that survive SSH
drops, or access from other devices, and you are ready to secure and test a public service.

**What the app does for you:** connects over SSH, starts the bridge in tmux with the right flags, stops and restarts
it, shows its log, and opens the tunnels.
**What you do:** put the bridge binary, `agy` and `claude` on the server, and (only in Server mode with a domain) set up
the reverse proxy. The app never uploads anything to the server.

Contents

1. [Requirements](#1-requirements)
2. [Prepare the server](#2-prepare-the-server)
3. [Get the bridge binary on the server](#3-get-the-bridge-binary-on-the-server)
4. [Install agy and Claude Code](#4-install-agy-and-claude-code)
5. [SSH access](#5-ssh-access)
6. [Choose how the phone reaches the server](#6-choose-how-the-phone-reaches-the-server) (SSH tunnel users: only 6.1)
7. [Domain mode: nginx / Caddy / Apache](#7-domain-mode-reverse-proxy)
8. [IP mode: open two ports](#8-ip-mode-open-two-ports), and [8b. Second layer: secret header, which ports to open](#8b-second-layer-a-secret-header-checked-by-the-proxy-recommended)
9. [Set up the app](#9-set-up-the-app)
10. [First start and sign-in](#10-first-start-and-sign-in)
11. [What runs where](#11-what-runs-where)
12. [adb from the server to the phone](#12-adb-from-the-server-to-the-phone)
13. [Updating](#13-updating)
14. [Debugging](#14-debugging)
15. [Security: read this, then verify it yourself](#15-security-read-this-then-verify-it-yourself)
16. [Running the bridge by hand](#16-running-the-bridge-by-hand)

---

## 1. Requirements

**Server**

* Linux, x86_64 (most VPS) or aarch64 (ARM VPS, Raspberry Pi). Check with `uname -m`.
* `sshd`, `tmux` (strongly recommended: the bridge keeps running when the SSH connection drops) and `git`.
* A **normal user** for AntiGem, not root. The bridge can read and write everything that user can.
* About 1 GB of RAM free for the agents. Node.js 18 or newer only if you use browser plugins.

**Reaching it from the phone: pick one**

| | Address in the app | What you set up |
|---|---|---|
| **SSH tunnel** (recommended) | `http://127.0.0.1:1234`, `http://127.0.0.1:1235` | nothing beyond SSH |
| **Server mode: domain + TLS** | `https://bridge.example.com`, `https://hub.example.com` | two DNS names, certificates, nginx / Caddy / Apache |
| **Server mode: IP and ports** | `http://203.0.113.5:1234`, `http://203.0.113.5:1235` | open two ports in the firewall |

See [Two ways to connect](#two-ways-to-connect-which-one-to-use) for the pros and cons.

> **In Server mode, use TLS on the internet** (the SSH tunnel is encrypted by SSH). The security token is the *name* of an HTTP header, so over plain `http://` anyone on
> the path can read it, and with it read and write files and run commands on your server. Plain `http://` is fine on a
> private network or a VPN (Tailscale, WireGuard).

**Phone:** the AntiGem app. It needs internet access to the server while you use it.

---

## 2. Prepare the server

Debian / Ubuntu (use your package manager on other distros):

```bash
sudo apt update
sudo apt install -y openssh-server tmux git curl
# only for a domain: reverse proxy + certificates
sudo apt install -y nginx certbot
# only for browser plugins (Node.js 18+):
sudo apt install -y nodejs
```

Create the user (skip if you already have one) and make sure it can log in over SSH:

```bash
sudo adduser antigem
```

Everything below is done **as that user** (`su - antigem` or SSH in as `antigem`).

---

## 3. Get the bridge binary on the server

The bridge (`agy_ide_bridge`) is a single static Go program. The app does **not** contain it and does **not** upload
it: you build it once and copy it to the server.

### 3.1 Build it (on your PC)

You need Go. Use the version in `agy_ide_bridge/go.mod` (the `go` line) or newer: <https://go.dev/dl/>.

```bash
git clone <this repository>
cd antiGem/agy_ide_bridge

# 64-bit Intel/AMD server (x86_64), the usual VPS:
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o agy_ide_bridge_linux_amd64 .

# ARM server (aarch64):
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags "-s -w" -o agy_ide_bridge_linux_arm64 .
```

`CGO_ENABLED=0` makes a fully static binary: it runs on any Linux of that CPU, no libraries needed. You can also build
directly on the server (`go build -o agy_ide_bridge .`) if Go is installed there.

> Do not use the Android/Termux build of the bridge on a server. It is a different binary.

**Version.** The bridge reports the **same version as the app**: it is built from the `version.json` of the source tree
(the app's Gradle build copies it into `agy_ide_bridge/pkg/buildinfo/` on every build, so nobody has to set it). Build
the bridge from the **same source version as your app**. On every launch the app reads the bridge's version; if it
differs, a dialog asks you to update the bridge, and **Settings > Servers** always shows the version of the connected
bridge ("matches the app" in green).

### 3.2 Copy it to the default location

The app looks for the bridge at `~/.antigem/agy_ide_bridge` by default (`~` is the SSH user's home).

```bash
# from your PC
ssh antigem@your.server 'mkdir -p ~/.antigem'
scp agy_ide_bridge_linux_amd64 antigem@your.server:~/.antigem/agy_ide_bridge
ssh antigem@your.server 'chmod +x ~/.antigem/agy_ide_bridge'
```

`chmod +x` is required: a file copied with `scp` is often not executable.

### 3.3 Check it

```bash
~/.antigem/agy_ide_bridge --help
```

You should see the list of flags. If you see `cannot execute binary file` you copied the wrong CPU build (compare
`uname -m`: `x86_64` → amd64, `aarch64` → arm64). "Permission denied" means you forgot `chmod +x`.

You can keep the binary somewhere else; then set the path under *Advanced* in the app (step 9).

---

## 4. Install agy and Claude Code

Install the agents **as the same user**, on the server. You only need the ones you use.

### Antigravity (`agy`)

Use Google's official installer, as the same user, on the server:

```bash
curl -fsSL https://antigravity.google/cli/install.sh | bash
```

It installs `agy` to `~/.local/bin/agy` (no sudo, no root; `--dir <folder>` after `bash -s --` picks another folder), which
is the app's default path. If `agy` already exists there, the script prints a notice and changes nothing. It supports
Linux x86_64 / arm64 (glibc and musl).

**Or install it from the app.** Once the bridge is running (step 10), open **Settings > Agents**. The **Antigravity CLI
(agy)** card shows where `agy` is and its version (`agy --version`). If it is not found, tap **Install agy**: the bridge
runs the same installer on the server, the card shows its output, and the hub starts as soon as it is done. The
bridge starts fine without `agy` for this reason.

Check: `~/.local/bin/agy --version`.

> **The `agy` file must be writable by the AntiGem user.** When the bridge starts the hub it writes the security token
> into the `agy` binary (a small in-place patch). Keep it in your own home (`~/.local/bin`), not in `/usr/bin` as
> root, or the hub will refuse to start with "Failed to patch AGY binary". If the installed `agy` is a small wrapper script,
> the bridge patches the large `agy.va39` file next to it.

If your `agy` is somewhere else, find it with `command -v agy` and put that path in the app (step 9). If the path in the
app does not exist, the bridge looks in `~/.local/bin`, `~/.gemini/bin`, `~/.antigravity/bin` and `PATH` by itself.

**Updating agy:** `agy` checks for updates itself when you run it in a terminal on the server; the app does not update it.

### Claude Code (`claude`)

Install the official CLI:

```bash
curl -fsSL https://claude.ai/install.sh | bash
```

This puts it at `~/.local/bin/claude`, which is the app's default. Check it:

```bash
~/.local/bin/claude --version
```

Sign in to Claude from the app later (a code you paste, see step 10). If you installed Claude with npm or somewhere
else, find it with `command -v claude` and put that path in the app.

### Quick check of everything

```bash
ls -l ~/.antigem/agy_ide_bridge ~/.local/bin/agy ~/.local/bin/claude
```

The bridge must exist and have an `x` in the permissions. `agy` and `claude` can also be installed later from the app
(Settings > Agents, Settings > Claude Code), but chats need them.

---

## 5. SSH access

The app logs in over SSH to start and stop the bridge. A **private key** is better than a password.

On your PC (or on the phone with any SSH app):

```bash
ssh-keygen -t ed25519 -f ~/.ssh/antigem_server -C "antigem-phone"
ssh-copy-id -i ~/.ssh/antigem_server.pub antigem@your.server
ssh -i ~/.ssh/antigem_server antigem@your.server        # must log in without a password
```

Then get the **private** key (`~/.ssh/antigem_server`, the file without `.pub`) to the phone: copy its text and use
*Paste key* in the app, or save the file on the phone and use *Choose key file*. Keep it private; delete it from
shared places afterwards.

Notes:

* Keys that start with `-----BEGIN OPENSSH PRIVATE KEY-----` or `-----BEGIN RSA PRIVATE KEY-----` both work.
  A key with a passphrase needs the passphrase typed into the app's passphrase box.
* Password login works if `PasswordAuthentication yes` is set in `/etc/ssh/sshd_config`.
* A non-standard SSH port is fine; type it in the Port box.
* The app pins the server's SSH host key the first time it connects. If you reinstall the server, tap
  **Forget host key** in the app.

---

## 6. Choose how the phone reaches the server

The bridge and the hub are **two separate services on two ports**. The app wants two addresses.

### 6.1 SSH tunnel mode (recommended)

Pick **SSH tunnel** in the app (step 9) and you are done with this part: skip sections 7, 8 and 8b.

* The addresses are `http://127.0.0.1:1234` (bridge) and `http://127.0.0.1:1235` (hub). The app fills them in when you
  pick the mode. The **port is the one the service listens on at the server**; the app opens the same port on the phone.
  Change both numbers only if those ports are already taken on the server or on the phone.
* The app starts the bridge and the hub with `--bind 127.0.0.1`, so they are not reachable from outside the server.
  *Ports on the server* and the proxy access header are hidden in this mode; they are not used.
* Open **only SSH** in the firewall (`sudo ufw allow 22/tcp`, or your SSH port). Do not open 1234 / 1235.
* The tunnels show under *Server control > SSH tunnels* (`phone 127.0.0.1:1234 → server :1234 (bridge)`, ...).

The rest of section 6 is about **Server mode**.

### 6.2 Server mode: domain or IP

| Service | Default port | Used for |
|---|---|---|
| Bridge | **1234** | REST + WebSocket: files, git, chats relay, Claude, plugins |
| Hub (`agy`) | **1235** | gRPC over HTTP/2: Antigravity chats |

The AntiGem build you installed decides the default ports: the `com.termux` build uses 1234 / 1235, the `com.antigem`
build uses **1236 / 1237**. The app shows the numbers under the address boxes and as placeholders in *Ports on the
server*. Use those numbers wherever this guide says 1234 / 1235.

How the app starts the services depends on the address you type:

| Address you type | The app treats it as | Bridge listens on |
|---|---|---|
| `https://bridge.example.com` | reverse proxy | `127.0.0.1:1234` (only the proxy can reach it) |
| `http://bridge.example.com:8081` (a host name on another port) | reverse proxy | `127.0.0.1:1234` |
| `http://203.0.113.5:1234` (an IP with a port) | direct | `0.0.0.0:1234` (every interface) |

Behind a proxy the port in the address belongs to the proxy, never to the bridge. You can override the ports and the
bind address in the app (*Ports on the server*) if you need something different.

---

## 7. Domain mode: reverse proxy

**DNS:** create two A (or AAAA) records pointing at the server: `bridge.example.com` and `hub.example.com`.

**Certificates** (Let's Encrypt):

```bash
sudo systemctl stop nginx 2>/dev/null          # certbot --standalone needs port 80 free
sudo certbot certonly --standalone -d bridge.example.com -d hub.example.com
sudo systemctl start nginx 2>/dev/null
```

Open ports 80 and 443 in the firewall (`sudo ufw allow 80,443/tcp`). Do **not** open 1234 / 1235: only the proxy needs
them, on the loopback.

> **nginx drops headers with an underscore by default.** The security header looks like `x-ag<token>_9qx`, so you
> must set `underscores_in_headers on;` or every request is answered `401`.

### nginx

Save as `/etc/nginx/conf.d/antigem.conf`, then `sudo nginx -t && sudo systemctl reload nginx`.

```nginx
map $http_upgrade $connection_upgrade { default upgrade; '' close; }

# --- bridge: REST + WebSocket -----------------------------------------------------------
server {
    listen 443 ssl http2;
    server_name bridge.example.com;
    ssl_certificate     /etc/letsencrypt/live/bridge.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/bridge.example.com/privkey.pem;

    underscores_in_headers on;          # REQUIRED
    client_max_body_size   512m;        # attachments / plugin ZIPs arrive as base64 in JSON

    location / {
        proxy_pass http://127.0.0.1:1234;
        proxy_http_version 1.1;
        proxy_set_header Upgrade    $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_set_header Host       $host;
        proxy_set_header X-Forwarded-For   $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 1h;
        proxy_send_timeout 1h;
        proxy_buffering off;
        proxy_request_buffering off;
    }
}

# --- hub: gRPC (HTTP/2) ----------------------------------------------------------------
server {
    listen 443 ssl http2;
    server_name hub.example.com;
    ssl_certificate     /etc/letsencrypt/live/hub.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/hub.example.com/privkey.pem;

    underscores_in_headers on;          # REQUIRED
    client_max_body_size   0;

    location / {
        grpc_pass grpc://127.0.0.1:1235;
        grpc_read_timeout 1h;
        grpc_send_timeout 1h;
    }
}
```

* `underscores_in_headers` is read from the *default* server of an address:port. If you already have other sites on
  443, put it in the `http { }` block instead.
* On nginx 1.25.1 or newer `listen 443 ssl http2;` still works but prints a warning; the new form is `listen 443 ssl;`
  plus `http2 on;` inside the `server` block. gRPC needs HTTP/2 on the hub server either way.
* If the certificate files are in a different folder, certbot printed their paths.

### Caddy (simplest: certificates are automatic)

```caddyfile
bridge.example.com {
    reverse_proxy 127.0.0.1:1234
}
hub.example.com {
    reverse_proxy h2c://127.0.0.1:1235
}
```

### Apache

Modules: `ssl proxy proxy_http proxy_wstunnel proxy_http2 http2 rewrite headers`
(`sudo a2enmod ssl proxy proxy_http proxy_wstunnel proxy_http2 http2 rewrite headers`).

```apache
<VirtualHost *:443>
    ServerName bridge.example.com
    SSLEngine on
    SSLCertificateFile    /etc/letsencrypt/live/bridge.example.com/fullchain.pem
    SSLCertificateKeyFile /etc/letsencrypt/live/bridge.example.com/privkey.pem

    ProxyPreserveHost On
    RequestHeader set X-Forwarded-Proto "https"
    LimitRequestBody 0

    RewriteEngine On
    RewriteCond %{HTTP:Upgrade} =websocket [NC]
    RewriteRule /(.*) ws://127.0.0.1:1234/$1 [P,L]

    ProxyPass        / http://127.0.0.1:1234/ timeout=3600
    ProxyPassReverse / http://127.0.0.1:1234/
</VirtualHost>

<VirtualHost *:443>
    ServerName hub.example.com
    Protocols h2 http/1.1
    SSLEngine on
    SSLCertificateFile    /etc/letsencrypt/live/hub.example.com/fullchain.pem
    SSLCertificateKeyFile /etc/letsencrypt/live/hub.example.com/privkey.pem

    ProxyPass        / h2c://127.0.0.1:1235/ flushpackets=on timeout=3600
    ProxyPassReverse / h2c://127.0.0.1:1235/
</VirtualHost>
```

gRPC needs HTTP/2 trailers end to end. If **Test Hub** is green but chats do not stream, your Apache build does not
forward trailers: use nginx or Caddy for the hub (the bridge works fine behind Apache).

Do not put the two services under one host with paths (`/hub`): the gRPC client needs the hub at the root of its
address. Use two sub-domains.

---

## 8. IP mode: open two ports

With `http://IP:1234` and `http://IP:1235` the bridge listens on every interface on the port you typed. Open the two
ports in the firewall (`sudo ufw allow 1234/tcp`, `sudo ufw allow 1235/tcp`) or in the provider's panel. Restrict
them to your own IP if it never changes. Remember the token travels in clear text (see the warning at the top).

`agy --hub` decides on which interface the hub listens. After the first start check with
`ss -ltnp | grep 1235`. If it is only on `127.0.0.1`, put a reverse proxy in front (domain mode) or use an SSH port
forward / a VPN.

---

## 8b. Second layer: a secret header checked by the proxy (recommended)

The bridge's own token is one lock. This adds a second, independent one **in front** of it: the app adds a header such
as `X-AntiGem-Key: <20 random characters>` to every request, and the reverse proxy closes the connection for any request
without exactly that value. A stranger who finds your sub-domains (certificate names are public) gets nothing from the
bridge or from agy, even if one of them had a bug.

1. In the app, **Settings > Servers > Proxy access header**: keep the name `X-AntiGem-Key` (letters, digits and dashes
   only), tap **Generate** for the value, and tap **Copy nginx rule**. Leave the value empty to turn the feature off.
2. Put the same value in the proxy, in **every** server block of the bridge **and** of the hub, on **every** port that
   reaches them (443, and 80 or 8081 if you serve them there too):

   nginx (a `map` once in `http { }`, the `if` in each `server { }`):

   ```nginx
   map $http_x_antigem_key $antigem_key_ok { default 0; "PASTE-THE-VALUE-HERE" 1; }

   server {
       server_name bridge.example.com;
       if ($antigem_key_ok = 0) { return 444; }      # 444 = close the connection without an answer
       ...
   }
   ```

   Caddy (inside each site block):

   ```caddyfile
   @nokey not header X-AntiGem-Key "PASTE-THE-VALUE-HERE"
   abort @nokey
   ```

   Apache (inside each `<VirtualHost>`; Apache cannot drop silently, it answers 403):

   ```apache
   RewriteEngine On
   RewriteCond %{HTTP:X-AntiGem-Key} !^PASTE-THE-VALUE-HERE$
   RewriteRule ^ - [F]
   ```
3. Reload the proxy, then in the app tap **Test Bridge** and **Test Hub**: both send the header. If you set it in the app
   but the proxy does not check it, nothing breaks; if the proxy checks it and the app does not send the right value,
   every request is refused and the tests say "the connection was closed".

**Do not assume it works because you typed it in.** A proxy rule that is in the wrong block, in a file nginx does not
load, or not reloaded does nothing, and you will not notice until someone walks in. Run the checks in
[section 15.5](#155-test-it-yourself-do-not-skip) from another machine, and repeat them after every change.

Notes: the value is a password: keep it out of screenshots and public repositories, and rotate it by editing the proxy
and the app. Use only letters and digits (nginx treats `$` and `"` inside the value specially, and a header name must not
contain `_`). It only protects traffic through the proxy (not IP mode), and only over `https://`. Use `$remote_addr`
instead of `$proxy_add_x_forwarded_for` in `X-Forwarded-For` (`proxy_set_header X-Forwarded-For $remote_addr;`) so a
client cannot fake its address to the bridge's brute-force limiter.

### Which ports to open

On the server only these should be reachable from the internet:

| Port | Why |
|---|---|
| **443** | the bridge and the hub, through the proxy |
| **80** | certificate renewal and the redirect to https (can be closed if you renew another way) |
| **22** (or your SSH port) | the app starts and stops the bridge, the terminal and the tunnels use SSH |

Not **1234 / 1235** (or 1236 / 1237): in domain mode the bridge listens on `127.0.0.1` only, and the hub (`agy`) also
listens on the loopback only, so nothing can reach them except the proxy and local processes. The SSH reverse tunnels
(phone tools, adb) bind to the server's `127.0.0.1` too. Check, on the server:

```bash
sudo ss -ltnp                      # every line should show 127.0.0.1 / [::1], except sshd (22) and nginx (80, 443)
sudo ufw status verbose            # or: sudo nft list ruleset / your provider's firewall panel
```

Two traps: **Docker** publishes ports around `ufw`, and **IPv6** needs its own rules. If a listener shows `0.0.0.0` or
`[::]` that is not sshd or the proxy, close it. Also protect SSH: key login only (`PasswordAuthentication no`) and
`fail2ban`.

**adb:** the adb tunnel (`127.0.0.1:15555` on the server) is on the server's loopback, so the internet cannot reach it, and
the phone only accepts a server that was paired. Anything running as the server user, including the agents, can use it:
that is its purpose, so only connect a server you trust.

---

## 9. Set up the app

Open **Settings > Servers**.

1. Under **Where the server runs**, pick **SSH tunnel** (recommended) or **Server** (domain or open ports). See
   [Two ways to connect](#two-ways-to-connect-which-one-to-use).
2. **Server addresses**
   * **SSH tunnel:** `http://127.0.0.1:1234` (bridge) and `http://127.0.0.1:1235` (hub), filled in for you. The port is
     the one each service listens on at the server. Both must be `127.0.0.1` (or `localhost`), `http://` and two
     different ports, or Save refuses them.
   * **Server mode:** *IDE Bridge address* `https://bridge.example.com` (or `http://203.0.113.5:1234`), *AGY Hub address*
     `https://hub.example.com` (or `http://203.0.113.5:1235`). `https://` needs no port. Without `https://`, a missing
     port becomes the default (1234 / 1235).
3. **Ports on the server** (Server mode only; leave blank unless you changed them): the ports the bridge and hub really
   listen on, and the bridge bind address. Behind a proxy the defaults are right.
4. **SSH access**: host, port, user, then a password and/or the private key (step 5). Tap **Test SSH**: it should say
   "SSH works" and show `uname`, the user and whether tmux is installed.
5. **Advanced (server paths and ports)** → *Show*. The fields are filled with the defaults. Check each against what
   you really have on the server (step 3 and 4) and change what differs:

   | Field | Default | Meaning |
   |---|---|---|
   | Bridge binary on the server | `~/.antigem/agy_ide_bridge` | where you copied the bridge (step 3) |
   | agy binary on the server | `~/.local/bin/agy` | your Antigravity CLI (step 4) |
   | claude binary on the server | `~/.local/bin/claude` | your Claude Code CLI (step 4) |
   | Bridge folder (in the server home) | `.antigem` | the bridge's folder; its log is `<folder>/bridge.log`. Changing it moves the default bridge path with it |

   `~` means the SSH user's home on the server. The agy path is only used when Antigravity is enabled in the app, and the
   claude path only when Claude Code is enabled. If an agent file does not exist at its path, the bridge still starts and
   looks for it by itself; install it from the app (Settings > Agents / Settings > Claude Code).
6. **Security token**: created by the app. The app starts the server with it; you never type it on the server.
   Do not change it unless you know why (Randomize makes a new one; you must then Restart the server).
7. **Proxy access header** (Server mode only, optional, [step 8b](#8b-second-layer-a-secret-header-checked-by-the-proxy-recommended)):
   generate a value and add the same one to the proxy. Hidden in SSH tunnel mode, where there is no proxy.
8. Tap **Test Hub** and **Test Bridge** once the server is running (step 10). The bridge test also checks the token. In
   SSH tunnel mode the tests only work after you saved and SSH is connected, because they go through the tunnel.
9. **Save Server & Security Settings**, then **Reopen** so every connection uses the new settings.

**Port forwards (server → this phone):** in both modes the *Port forwards* card makes a port of the server reachable on
the phone's `127.0.0.1` over SSH, for example a dev server: run `npm run dev` on the server, add server port `5173`
(phone port blank = the same), and open `http://127.0.0.1:5173` in the app's browser. Each entry has a switch, a copy
button and a status dot (green = active). Changes apply right away, without Save. The program on the server must listen
on `127.0.0.1` or `0.0.0.0`; phone ports below 1024 are not allowed (Android needs root for them), nor ports the app
already uses. Nothing is opened on the server: only this phone can use the forwarded port.

**Profiles:** *Save as profile* stores the current form on this phone under a name (SSH password and key only if you
tick the box). Open *Saved profiles* to load one, **Update** it with the current form, or delete it. Handy for several
servers.

---

## 10. First start and sign-in

After **Reopen** the app connects over SSH and starts the bridge in a tmux session named `antigem-bridge`.
**Settings > Servers > Server control** shows the state, the SSH connection, the tunnels and the server log. You can
also use **Start**, **Restart** and **Stop** there.

* *Start*: starts the bridge if it is not already running. It never restarts a healthy one.
* *Restart*: stops the bridge and its hub cleanly, then starts it again (use after changing paths, ports or the token).
* *Stop*: stops the bridge and checks that it is really down.

If something is wrong the status line says what (missing binary, wrong path, port in use, crash with the last log
lines). See [Debugging](#14-debugging).

### Antigravity (Google) sign-in

The sign-in page redirects to `http://localhost:<port>/…`. With a remote hub that address is the phone, so the page
shows an error. This is expected:

1. Tap *Sign in*, then **Open Browser** and sign in with Google.
2. The browser ends on an error page for `localhost`. Copy the **address of that page**.
3. Paste it into the box in the app's sign-in dialog and tap **Finish**.

The bridge replays the address on the server and the hub completes the sign-in. The app shows "Signing in…" until it is
done.

### Claude Code sign-in

Claude Code uses a code that you paste into the app; follow the prompts in *Settings > Claude Code*.

---

## 11. What runs where

| Feature | Where |
|---|---|
| Chats, agents (agy, Claude Code), tools, plugins, MCP servers you add | server |
| Projects, files, git, code editor backend, search | server |
| Terminal tab, Run configurations, chat commands | server (SSH, each run in its own tmux session) |
| Clone repository | server (the bridge runs `git clone` and streams the log) |
| Browser / Android / Notes / Terminal MCP presets | phone, reached by the server through the SSH reverse tunnels |
| Voice dictation, notifications, floating bubble | phone |
| Backups & Restore screen | hidden in Server mode (see [Security and backups](#15-security-and-backups)) |

The phone must stay online for the phone-side MCP tools. If it goes offline, those tools report an error and recover
when the phone reconnects.

---

## 12. adb from the server to the phone

Agents on the server can run `adb` against your phone (`adb shell`, `adb install`, screenshots …). In Server mode the
normal screen does this: **Settings > Utilities > ADB Connect**. In Server mode every step there works on the
**server's** adb:

1. **Install ADB**: installs adb on the server. This needs root or passwordless sudo for the SSH user (apt, dnf, pacman
   or apk); otherwise run `sudo apt install adb` yourself. Use adb 30 or newer for Wireless debugging.
2. **Wireless debugging**: turn it on in the phone's Developer options.
3. **Pair**: tap *Start pairing*, open *Wireless debugging > Pair device with pairing code* on the phone, and enter the
   6-digit code (in the notification or in the app). The app forwards the pairing port to the server and runs
   `adb pair` there. Do this once.
4. **Connect**: leave the port empty to find it automatically, or type the port shown by Wireless debugging. The app
   forwards the phone's adb to `127.0.0.1:15555` on the server and runs `adb connect` there. It reports success only
   when the server's `adb devices` lists the phone, and it reconnects by itself if the connection or the port changes.
   **Cancel** stops a running search; **Disconnect** removes the phone from the server's adb.

(15555 and not 5555, so adb's emulator auto-scan on the server does not pick the phone up as a second device.)

Check on the server: `adb devices` should show `127.0.0.1:15555  device`. `unauthorized` or `offline` means the server
has not been paired (step 3) or its adb is too old.

Rooted phones can skip pairing: `su -c "setprop service.adb.tcp.port 5555; stop adbd; start adbd"` (lasts until the
phone reboots) and accept the key prompt on the phone.

Security: adb is full control of the phone (install apps, read the screen, run commands). Only connect a server you
trust. The tunnel exists only on the server's loopback, never on its public address.

---

## 13. Updating

The app never updates anything on the server. You update these yourself:

* **The bridge** (after you update the app or the source): build the new binary (step 3.1), then in the app tap
  **Stop**, copy it over, **Start**:
  ```bash
  scp agy_ide_bridge_linux_amd64 antigem@your.server:~/.antigem/agy_ide_bridge.new
  ssh antigem@your.server 'chmod +x ~/.antigem/agy_ide_bridge.new && mv -f ~/.antigem/agy_ide_bridge.new ~/.antigem/agy_ide_bridge'
  ```
  Copying to `.new` and then `mv` avoids "Text file busy" and half-written files. Tap **Restart server** (or **Start**)
  in the app afterwards. The app compares the bridge's version with its own on every launch and shows an
  "Update the IDE bridge" dialog when they differ (*Later* only hides it until you open the app again, so an outdated
  bridge is not forgotten); **Settings > Servers** and **Settings > About & Info** show the connected bridge's version.
* **agy**: it updates itself when you run it in a terminal on the server; see its version in Settings > Agents.
* **claude**: update it the way you installed it (`claude update`). Restart the server from the app afterwards.

---

## 14. Debugging

Work from the bottom up. Each layer must work before the next one can.

### 14.1 Layer by layer

| # | Check | Where | Expected |
|---|---|---|---|
| 1 | **Test SSH** in the app | phone | "SSH works" |
| 2 | binary is there | server: `ls -l ~/.antigem/agy_ide_bridge` | has `x`, right CPU |
| 3 | bridge is running | server: `tmux ls` | session `antigem-bridge` |
| 4 | bridge answers locally | server: `curl -s http://127.0.0.1:1234/api/health` | `{"status":"ok",…}` |
| 5 | ports are open locally | server: `ss -ltnp \| grep -E '1234\|1235'` | both listening |
| 6 | proxy reaches it | anywhere: `curl -s https://bridge.example.com/api/health` | same JSON |
| 7 | token accepted | **Test Bridge** in the app | "Bridge reachable (linux/amd64, home …)" |
| 8 | hub reachable | **Test Hub** in the app | "Hub reachable (answered HTTP …)" |

If step 4 works but 6 does not, the problem is DNS, the firewall, TLS or the proxy config. If 3 fails, look at the log.

### 14.2 Logs

* **In the app:** Settings > Servers > Server control > **Server log** (Refresh to update).
* **On the server:** `tail -f ~/.antigem/bridge.log` (or `<Bridge folder>/bridge.log`).
* **Live:** `tmux attach -t antigem-bridge` (leave with `Ctrl-b` then `d`, never `Ctrl-c`, which stops the bridge).
* **nginx:** `/var/log/nginx/error.log` and `access.log`. **sshd:** `journalctl -u ssh` (or `sshd`).

### 14.3 Messages and what to do

| What the app says | Cause and fix |
|---|---|
| *The bridge is not on the server. Copy agy_ide_bridge_linux_… there yourself* | The file is missing at the configured path. Do step 3 (or fix the path in Advanced). |
| *The bridge on the server is not executable* | `chmod +x ~/.antigem/agy_ide_bridge` |
| *The bridge path on the server is a folder* | The path points to a directory; point it at the file. |
| Settings > Agents: *agy was not found on the server* | Tap **Install agy**, or run the installer from step 4 on the server. If it is installed elsewhere, set *agy binary on the server*. |
| Settings > Agents: *The bridge is older than the app (no agy support)* | Update the bridge (step 13). |
| *Update the IDE bridge* dialog / red version line in Settings > Servers | The bridge version differs from the app's. Rebuild the bridge from this app's source and replace it (step 13). |
| *The bridge stopped right after it was started* + last log lines | It crashed. Read the lines. Common: port already in use, cannot write the `agy` file, wrong CPU. |
| *The bridge did not come up on the server in time* | Slow server or hung start; read the log, then Restart. |
| *The bridge is running on the server but the app cannot reach https://…* | The bridge is fine; the path from the phone is not. Check DNS, firewall, certificate and the proxy (`curl` from your PC). Start does not kill it; Restart does. |
| *The bridge is still running on the server after the stop request* | Something keeps it alive. `pgrep -af agy_ide_bridge`, then `kill` it, or `tmux kill-session -t antigem-bridge`. |
| *Could not stop the bridge: SSH is not connected* | Fix SSH first (Test SSH), then Stop again. |
| *SSH login failed* | Wrong user, password or key (or passphrase). Try the same login from a PC. |
| *SSH connection timed out / refused* | Wrong host or port, firewall, or sshd not running. |
| *The server's SSH host key changed* | Server reinstalled: tap **Forget host key**. |
| *The SSH private key could not be read* | Wrong format or missing passphrase; the key must be the private file. |
| *Test Bridge*: token rejected / 401 on everything | nginx without `underscores_in_headers on;`, or the server was started with another token (Restart from Server control). |
| *Test Hub* green, chats never answer | The proxy does not speak HTTP/2 to the hub or drops gRPC trailers (use nginx or Caddy). |
| Blocked / HTTP 429 | More than 30 wrong tokens from one address; wait 5 minutes. |
| Browser plugins: *Node.js is not installed on the server* | `sudo apt install nodejs` (version 18 or newer). |
| The status says "cannot reach the bridge" a while after it was Running | The phone lost the network, or the bridge died. Tap Start: it shows which. |

### 14.4 Port already in use

If the log says `address already in use`, something else holds the port (often an old bridge):

```bash
ss -ltnp | grep -E '1234|1235'          # who holds it
pgrep -af agy_ide_bridge                # old bridges
tmux kill-session -t antigem-bridge     # the app's session
pkill -f 'agy --hub-port'               # a leftover hub
```

Then Start again in the app. Start and Restart already try to clean these up; this is for stubborn cases.

### 14.5 Testing the proxy by hand

```bash
curl -i https://bridge.example.com/api/health                    # 200, no token needed
curl -i https://hub.example.com/                                  # any answer below 500 means the proxy reaches the hub
curl -i -H "x-ag<token>_9qx: true" https://bridge.example.com/api/server/info   # 200 with the token; 401 without
```

The header name is `x-ag` + the 12-character token + `_9qx` (the app shows it as *HTTP Framed Header* in the Security
Token card). A `401` with the right token almost always means the proxy dropped the underscore header.

### 14.6 Start from scratch

1. In the app: **Stop**.
2. On the server: `tmux kill-session -t antigem-bridge; pkill -f agy_ide_bridge; pkill -f 'agy --hub-port'`.
3. Check `~/.antigem/agy_ide_bridge --help` works, then **Start** in the app.

Your chats and projects are not touched by any of this.

---

## 15. Security: read this, then verify it yourself

### 15.1 There is no guarantee

* AntiGem's bridge gives whoever controls it **the same power as the server user**: read and write every file that user
  can, run the agents (which run commands), use git, read the keys and logins in that home folder.
* The software is young and **has not been independently audited**. It has had bugs in exactly this area (for example a
  version of the bridge once sent its secret token back in a response header; it is fixed, but that shows how things go
  wrong). More will exist. Every layer below can have a bug or be misconfigured.
* **Treat the server as something that will be hacked one day.** Decide now what that costs you, and keep that cost low:
  put it on its own VPS, run it as its own unprivileged user, and do not keep secrets on it that you cannot rotate
  (no password manager exports, no production SSH keys, no cloud-provider keys with wide rights).
* **Do not rely on a single protection, and do not rely on this guide.** Read the code, test it, and keep your own
  backups and monitoring.

### 15.2 What an attacker gets

| If this leaks or breaks | The attacker can |
|---|---|
| the **token** (and can reach the bridge) | use the whole bridge API: read and write all files of the server user, start the agents, use git |
| the same token and the hub address | also use your Antigravity chats (the hub accepts the same secret) |
| the **proxy access header** only | pass the proxy; they still need the token |
| your **SSH key / password** | get a shell as the server user, and stop or replace the bridge |
| your **phone** (unlocked, or its backups) | read the token, the SSH key and password and the header from the app's settings |
| the **server itself** | use everything above, and reach **your phone** through the tunnels the app opens: the phone's browser, Android and notes tools, and adb if you connected it |
| the **agents' accounts** (Google, Anthropic) | use them: they are stored in the server user's home (`~/.gemini`, `~/.claude`) |

### 15.3 The layers, and what each one does not do

1. **TLS (`https://`)**: stops people on the network from reading or changing traffic. It does not protect the server
   itself. Without it the token is visible to everyone on the path.
2. **The proxy access header** (section 8b): stops anyone who lacks a second secret from reaching the bridge or the hub
   at all, so a bug in either cannot be reached by strangers. It is a static shared secret, not a login. It only works for
   traffic that goes through your proxy, and only as well as your proxy is configured.
3. **The token**: the 12-character secret is the *name* of an HTTP header (`x-ag<token>_9qx`). The bridge refuses requests
   without it, and the same name is patched into `agy`, so the hub refuses calls without it too. Known limits:
   * it is sent with every request, so it is only safe over TLS;
   * one secret protects both services and everything they can do; there are no per-device tokens and no revocation other
     than changing the token and restarting;
   * on a server shared with other users, the token is visible in the process list (`--token`) and in the bridge log;
   * the hub's start page and the bridge's `/api/health` are public by design (they show a CSRF value and a version, not
     the secret).
4. **Rate limit**: the bridge blocks an address for 5 minutes after 30 wrong tokens. The token has about 4.7e18
   possibilities, so guessing is not the realistic attack; leaks and misconfiguration are.
5. **Loopback binding and the firewall**: in SSH tunnel mode and behind a proxy the bridge and the hub listen on
   `127.0.0.1` only. In SSH tunnel mode only SSH should be reachable; behind a proxy only 22, 80 and 443. This is only
   true if you check it (15.5).
6. **SSH**: the app uses it to start and stop the bridge, and in SSH tunnel mode it carries all the traffic to the
   bridge and the hub. Whoever has the key has a shell (and the bridge). The tunnel does not replace the token: other
   users on the same server can still connect to `127.0.0.1:1234`, and the token is what stops them.

Other things to know: the app stores the SSH key, password, token and header **unencrypted** in its own settings, and
Android may include them in device backups (the app allows backups). If that worries you, turn off backups for the app
in your device settings and protect the phone with a lock screen.

### 15.4 Hardening checklist (do as much as you can)

* A **dedicated VPS** and a **dedicated user without sudo** for AntiGem. Nothing else of value on that machine.
* **Updates:** keep the OS, nginx/Apache/Caddy and sshd updated; rebuild and replace the bridge when the app shows the
  "Update the IDE bridge" dialog (it contains fixes).
* **Firewall:** allow only SSH, 80 and 443 (section 8b), for IPv4 **and** IPv6. Docker publishes ports around `ufw`.
* **SSH:** key login only (`PasswordAuthentication no`), no root login, `fail2ban`, a passphrase on the key.
* **The proxy access header on every address and port** that reaches the bridge or the hub (443, and 80 / 8081 if used).
* **Prefer a VPN** (WireGuard, Tailscale) over a public address if you can: then nothing is exposed to the internet. A
  client-certificate (mTLS) check at the proxy is stronger than a header; the app does not support it yet.
* **Rotate** the token (Randomize, then Restart), the proxy header and the SSH key regularly and after any doubt.
* **Look at the logs** now and then: the bridge log, the proxy access and error logs, `last` and `journalctl -u ssh`.
  Many 401s, 444s or strange addresses mean someone is trying.
* **Backups** (below) kept off the server; assume a hacked server can also delete the backups it can reach.

### 15.5 Test it yourself (do not skip)

Most real-world failures are not clever attacks, they are protections that were never switched on: a proxy rule in the
wrong file, a block that was not reloaded, a forgotten port. **Run these checks yourself, from another machine** (your
PC, or the phone on mobile data, not the server), and read what actually comes back. Replace the placeholders. Repeat
them after every change to the proxy, the firewall, the app or the bridge. They are examples, not a complete audit.

`KEY` is your proxy access header value, `TOKEN` your 12-character token (Settings > Servers).

**1. The proxy refuses a request without the access header**
```bash
curl -i https://bridge.example.com/api/health
```
Expected with nginx `return 444`: *no answer* (`curl: (52) Empty reply from server`); with Caddy `abort`: similar; with
Apache: `403`. If you get `{"status":"ok",...}` the check is **not active** for this address.

**2. A wrong value is refused too**
```bash
curl -i -H "X-AntiGem-Key: not-the-value" https://bridge.example.com/api/health
```
Same expectation as above. If it answers, the proxy checks that the header exists, not its value.

**3. The right value gets through**
```bash
curl -i -H "X-AntiGem-Key: KEY" https://bridge.example.com/api/health
```
Expected: `200` and a small JSON with `"status":"ok"` and a `"version"`.

**4. The token is required** (the bridge itself, behind the proxy)
```bash
curl -i -H "X-AntiGem-Key: KEY" https://bridge.example.com/api/server/info
```
Expected: `401` (`unauthorized`). **If this answers 200, authentication is broken. Stop and fix it.**

**5. A wrong token is refused**
```bash
curl -i -H "X-AntiGem-Key: KEY" -H "x-agWRONGWRONG1_9qx: true" https://bridge.example.com/api/server/info
```
Expected: `401`.

**6. The token in the URL is not accepted**
```bash
curl -i -H "X-AntiGem-Key: KEY" "https://bridge.example.com/api/server/info?token=TOKEN"
```
Expected: `401`.

**7. The right token works**
```bash
curl -i -H "X-AntiGem-Key: KEY" -H "x-agTOKEN_9qx: true" https://bridge.example.com/api/server/info
```
Expected: `200` with `os`, `arch`, `home`. If it is `401` with the right token, the proxy removes headers with an
underscore (nginx: `underscores_in_headers on;` in `http { }`).

**8. The secret never comes back in an answer**
```bash
curl -si -X OPTIONS -H "X-AntiGem-Key: KEY" https://bridge.example.com/anything
curl -si -H "X-AntiGem-Key: KEY" https://bridge.example.com/api/file/read
curl -si -H "X-AntiGem-Key: KEY" https://bridge.example.com/does-not-exist
```
Read all the response headers and bodies: the token (and `x-ag…_9qx`) must appear **nowhere**. An old bridge did leak it
here, so check this on every bridge you run.

**9. The hub**
```bash
curl -i https://hub.example.com/                              # expected: no answer / 403 (no access header)
curl -i -H "X-AntiGem-Key: KEY" https://hub.example.com/      # expected: 200, an HTML page (public by design)
```
`agy` itself refuses calls without the secret header; the app's **Test Hub** button and a real chat are the real test.

**10. Every other way in**: repeat checks 1 and 4 for **each** address that can reach the bridge or the hub:
plain `http://` on port 80 and 8081 if you serve them, the server's raw IP address, and a different host name:
```bash
curl -i  http://bridge.example.com/api/server/info
curl -ik https://SERVER_IP/api/server/info -H "Host: bridge.example.com"
```
Any of them that answers `200` or `401` from the bridge without the access header has no check on it.

**11. Which ports are open to the world**
```bash
nmap -Pn -p- SERVER_IP            # from another machine; also try nmap -6 for IPv6
sudo ss -ltnp                     # on the server: every listener, and who owns it
sudo ufw status verbose           # or your provider's firewall panel
```
Only your SSH port, 80 and 443 should answer from outside. **1234, 1235, 1236, 1237, 15555 and anything else must not.**
In `ss`, the bridge and the hub should show `127.0.0.1`, not `0.0.0.0` or `[::]`.

**12. TLS is valid**: `curl -v https://bridge.example.com/api/health` works **without** `-k`. A browser or an online
TLS checker should show a valid certificate for both names.

**13. The app really sends the header**: in the app set a *wrong* value, tap **Test Bridge**: it must fail ("the
connection was closed"). Put the right value back: it must succeed. If a wrong value still works, the proxy is not
checking it.

**14. The proxy really runs your file**
```bash
sudo nginx -T | grep -n antigem_key        # your map and your checks must be in the loaded configuration
sudo systemctl status nginx                 # when did it last reload?
```

**Mistakes that make a lock do nothing:** the file is not included by `nginx.conf`; you edited a copy and did not
reload; the check is in one `server { }` but another block (port 80, 8081, a `default_server`, the raw IP) also reaches
the bridge; a CDN or load balancer in front of the proxy rewrites or removes the header; the value has a space, a `"`, a
`$` or a newline in it; the header name contains an underscore; the check is in a `location` that the request does not
match; Apache `RewriteRule` is placed in a context where it does not apply. **A passing result today does not mean it
still passes after the next change.**

### 15.6 Ask an AI model to look for more bugs

Please do this before you trust a server, and again after updates. Take this repository (above all `agy_ide_bridge/`:
`main.go`, `pkg/security`, `pkg/handlers`, `pkg/claude`; and in the app `data/remote`, `data/preferences`, `data/ssh`)
**plus your own nginx / Apache / Caddy configuration**, give them to the strongest AI coding model you have access to,
and ask it, for example:

> Here is the source code of an IDE bridge that runs on a VPS behind nginx, and my proxy configuration. Act as a
> security auditor. Look for: authentication bypass, ways the secret token or the proxy header can leak (responses,
> logs, URLs, error messages), path traversal and arbitrary file access, command injection, SSRF, unsafe defaults, race
> conditions, and any way a request can reach the bridge or the hub without passing the proxy header check (other ports,
> hosts, IPv6, HTTP/2 quirks). For every finding give a concrete request I can send to prove it, and the fix. Then
> write test cases I can run against a copy of my setup.

Run what it suggests against a **copy** of your setup, never only believe it: an AI review is a help, not a guarantee,
and it can be wrong in both directions. If you find a real problem, please report it to the project.

### 15.7 If you think the server was hacked

1. Stop the bridge: `tmux kill-session -t antigem-bridge; pkill -f agy_ide_bridge; pkill -f 'agy --hub-port'`.
2. Remove the SSH keys you used from `~/.ssh/authorized_keys` and change the passwords; check that file, `crontab -l`
   and `~/.bashrc` for things you did not add.
3. On the phone: forget the paired computer in *Developer options > Wireless debugging* and turn adb off.
4. Sign out of the Google and Claude accounts used on the server and revoke their sessions in the account pages.
5. Change the token, the proxy header and the SSH key.
6. **Rebuild the VPS from scratch** instead of cleaning it. Restore data from a backup you trust.

### 15.8 Backups

* The Backups & Restore screen of the app is for the phone's own data and is hidden in Server mode. Your chats,
  credentials and projects are on the server: back up `~/.gemini` (Antigravity chats and sign-in) and your project
  folders there, for example with `rsync`, a cron job or your provider's snapshots.
* Keep backups **off the server**, and encrypted. Do not share a backup of `~/.gemini`: it contains your login.

---

## 16. Running the bridge by hand

The app does this for you inside tmux, but the command is:

```bash
~/.antigem/agy_ide_bridge -f --token <12 chars> -p 1234 --hub-port 1235 --bind 127.0.0.1 \
    --bin ~/.local/bin/agy --claude-bin ~/.local/bin/claude
```

| Flag | Meaning |
|---|---|
| `-f` | start the hub without asking |
| `--token` | the 12-character security token (letters and digits); must be the one in the app |
| `-p`, `--hub-port` | bridge port, hub port |
| `--bind` | bridge address; `127.0.0.1` behind a proxy, `0.0.0.0` (default) for a direct address |
| `--bin`, `--claude-bin` | paths of `agy` and `claude` |
| `--no-hub`, `--no-claude` | do not run Antigravity / Claude Code (the app adds these when you turn an agent off) |
| `--tz-offset` | timezone offset in seconds (the app sends the phone's) |

Run it by hand only to debug (stop the app's session first: `tmux kill-session -t antigem-bridge`), and read its output
directly. The exact command the app uses is visible in `ps aux | grep agy_ide_bridge` while it runs.
