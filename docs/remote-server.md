# Running AntiGem on a server (Remote mode)

AntiGem can run the IDE bridge, the Antigravity hub (`agy`) and Claude Code on a server (a VPS) instead of on the
phone. The app then only shows the UI: chats, the editor, the terminal. The agents, your projects and the shell live
on the server.

```
phone (AntiGem app)                              server
 ├─ https://bridge.example.com  ──────────────►  bridge   (REST + WebSocket)   :1234
 ├─ https://hub.example.com     ──────────────►  agy hub  (gRPC over HTTP/2)    :1235
 └─ SSH (port 22)               ──────────────►  sshd
      · installs / starts / stops the bridge
      · terminal
      · reverse tunnels: the server's agents reach the phone's MCP tools (browser, Android, notes)
```

You can reach the two services in two ways. Pick one:

| | Address in the app | What you set up |
|---|---|---|
| **Domain + TLS** (recommended) | `https://bridge.example.com`, `https://hub.example.com` | DNS, a certificate, nginx / Apache / Caddy |
| **IP and ports** | `http://203.0.113.5:1234`, `http://203.0.113.5:1235` | open two ports in the firewall |

> **Use TLS on the internet.** The security token is the *name* of an HTTP header, so over plain `http://` anyone on the
> path can read it, and with it read and write files and run commands on your server. Plain `http://` is fine on a
> private network or a VPN (Tailscale, WireGuard).

## 1. Server prerequisites

* Linux, x86_64 or aarch64, with `sshd` and (recommended) `tmux`.
* A normal user for AntiGem (not root). The bridge can read and write everything that user can.
* `agy` (and `claude` if you use Claude Code) installed for that user, e.g. `~/.local/bin/agy`.
  The bridge finds `~/.local/bin`, `~/.gemini/bin`, `~/.antigravity/bin` and `PATH`.
* An SSH login for that user. A private key is better than a password.

You do **not** install the bridge yourself: the app uploads the right build (by CPU) over SFTP into `~/.antigem/`
and keeps it up to date.

## 2. In the app: Settings > Servers

1. Turn on **Remote server (SSH)**.
2. Enter the two addresses (see the table above). `https://` addresses need no port.
3. Enter the SSH host, port, user and a password or private key. Tap **Test SSH**.
4. Tap **Test Hub** / **Test Bridge** once the server is running (the Bridge test also checks the security token).
5. **Save**, then **Reopen**. The app connects over SSH, installs/starts the bridge in a tmux session named
   `antigem-bridge` and connects to it. **Settings > Servers > Server control** shows the state, the tunnels and the
   server log.

The security token is created by the app. The app starts the server with it, so you never copy it by hand.

## 3a. Domain mode: reverse proxy

With an `https://` address, an address without a port, or a host name on a port other than the defaults (for example
`http://hub.example.com:8081`) the app treats it as a reverse proxy: it starts the bridge listening only on the server's
loopback (`127.0.0.1:1234`) and the hub on `127.0.0.1:1235`. The port in the address is the proxy's, never the service's. The ports differ per build
(`com.antigem` uses 1236/1237); change them under *Advanced* in the app.

> **nginx drops headers with an underscore by default.** The security header looks like `x-ag<token>_9qx`, so you
> must set `underscores_in_headers on;` or every request is answered `401`.

### nginx

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
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
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

`underscores_in_headers` is read from the *default* server of an address:port. If you already have other sites on 443,
put it in the `http { }` block instead.

### Caddy (simplest)

```caddyfile
bridge.example.com {
    reverse_proxy 127.0.0.1:1234
}
hub.example.com {
    reverse_proxy h2c://127.0.0.1:1235
}
```

### Apache

Modules: `ssl proxy proxy_http proxy_wstunnel proxy_http2 http2 rewrite headers`.

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

## 3b. IP mode

With `http://IP:1234` and `http://IP:1235` the bridge listens on every interface on the port you typed. Open the two
ports in the firewall (`ufw allow 1234/tcp`, `ufw allow 1235/tcp`) or in the provider's panel. Restrict them to your
own IP if it never changes. Remember the token travels in clear text (see the warning at the top).

`agy --hub` decides on which interface the hub listens. Check with `ss -ltnp | grep 1235`. If it is only on
`127.0.0.1`, put a reverse proxy in front (domain mode) or forward the port with SSH.

## 4. What runs where

| Feature | Where |
|---|---|
| Chats, agents (agy, Claude Code), tools, plugins, MCP servers you add | server |
| Projects, files, git, code editor backend, search | server |
| Terminal tab, Run configurations, chat commands | server (SSH, each run in its own tmux session) |
| Clone repository | server (the bridge runs `git clone` and streams the log) |
| Browser / Android / Notes / Terminal MCP presets | phone, reached by the server through the SSH reverse tunnels |
| Voice dictation, notifications, floating bubble | phone |

The phone must stay online for the phone-side MCP tools. If it goes offline, those tools report an error and recover
when the phone reconnects.

## 5. Signing in to Antigravity (Google)

The sign-in page redirects to `http://localhost:<port>/…`. With a remote hub that address is the phone, so the page
shows an error. This is expected: copy the **address of that error page** and paste it into the box the app shows
under the sign-in dialog. The bridge replays it on the server and the hub completes the sign-in.

Claude Code sign-in already uses a pasted code.

## 5b. adb from the server to the phone

Agents on the server can run `adb` against this phone (`adb shell`, `adb install`, screenshots ...). In
*Settings > Servers > Server control* turn on **Let the server use this phone's adb**. The app then keeps an SSH
reverse tunnel from `127.0.0.1:15555` on the server to the phone's adb daemon and follows it when the port changes.
(15555 and not 5555, so adb's emulator auto-scan on the server does not pick the phone up as a second device.)

adb must be installed on the server. If it is missing, the app shows **Install adb on the server**, which works when the
SSH user is root or has passwordless sudo (apt, dnf, pacman or apk); otherwise run `sudo apt install adb` yourself.
The app runs `adb connect 127.0.0.1:15555` on the server for you when the tunnel opens. Two ways to make the phone's
adb daemon available:

* **Fixed port, no pairing (rooted phone):** `su -c "setprop service.adb.tcp.port 5555; stop adbd; start adbd"`.
  Lasts until the phone reboots. The first connect asks you to allow the server's key on the phone. Any port works: the
  app reads `service.adb.tcp.port` (for example one set earlier with `adb tcpip 6666`).
* **Wireless debugging:** the port changes each time it is switched on; the tunnel follows it. The server must be
  **paired once**, all from the app: tap *Pair the server (Wireless debugging)*, open *Wireless debugging > Pair device
  with pairing code* on the phone, type the 6-digit code in the app and tap *Pair*. The app forwards the pairing port,
  runs `adb pair` on the server and connects. Nothing to type on the server.

Security: adb is full control of the phone (install apps, read the screen, run commands). Only enable this for a server
you trust; the tunnel only exists on the server's loopback, never on its public address.

## 6. Troubleshooting

| Symptom | Cause |
|---|---|
| *Test Bridge*: token does not match / 401 everywhere | nginx without `underscores_in_headers on;`, or the server was started with another token (Restart from Server control) |
| *Test Hub* green, chats never answer | proxy does not speak HTTP/2 to the hub, or drops gRPC trailers (use nginx/Caddy) |
| Bridge "running on the server but the app cannot reach it" | proxy, firewall, DNS or a wrong address; test with `curl https://bridge.example.com/api/health` |
| "SSH host key changed" | the server was reinstalled; tap *Forget host key* |
| "no Linux server bridge" | this APK was built without `scripts/build_server_bridge.sh`; run it, rebuild, or install the bridge yourself and set *Bridge binary on the server* |
| Blocked / 429 | more than 30 wrong tokens from one address; wait 5 minutes |

Logs: *Settings > Servers > Server control > Server log*, or `~/.antigem/bridge.log` on the server.
You can also attach to the bridge with `tmux attach -t antigem-bridge`.

## 7. Running the bridge yourself

The app does this for you, but the command is:

```bash
~/.antigem/agy_ide_bridge -f --token <12 chars> -p 1234 --hub-port 1235 --bind 127.0.0.1 [--bin ~/.local/bin/agy] [--claude-bin claude]
```

`--bind` defaults to `0.0.0.0`; use `127.0.0.1` behind a reverse proxy. Build it with
`scripts/build_server_bridge.sh` (Linux amd64 and arm64).
