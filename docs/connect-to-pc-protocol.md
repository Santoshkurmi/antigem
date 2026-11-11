# Connect to PC: pairing protocol

This describes how the AntiGem app (phone) pairs with AntiGem in VS Code (PC). After pairing, the phone uses the
agents of the bridge VS Code runs on the PC, through an SSH tunnel.

- Phone side, already built: `app/.../data/pc/PcConnect.kt` and `PcPairingCrypto.kt`.
- PC side: the VS Code extension, still to be built. It must match this document byte for byte.
- Test implementation of the PC side, used to test the phone: `docs/connect-to-pc-mock-server.mjs`.

## Overview

1. On the PC, the user opens **Connect phone** in AntiGem. While that screen is open the extension:
   - shows a random **6-digit code**;
   - listens on a **pairing port** (default **1250**, on the LAN);
   - announces itself on mDNS.

   It stops all three when the screen closes, or after 2 minutes.
2. On the phone, the user opens Add a PC, picks the PC from **Nearby PCs** (mDNS) or types the address shown on the
   PC (`ip:port`), and enters the code.
3. Pairing uses **SPAKE2** over plain HTTP. Both sides prove they know the code without sending it, so someone
   listening on the Wi-Fi cannot test codes offline. Over that protected channel:
   - the phone sends its SSH public key;
   - the PC sends its SSH details, host keys, bridge ports and bridge token.
4. The PC adds the phone's key to the user's `authorized_keys`, limited to SFTP and the tunnel (see below).
5. From then on the phone connects over SSH with its key. It pins the PC's host key from step 3, reads the current
   bridge token over SFTP, and forwards phone `127.0.0.1:<bridgePort>` and `127.0.0.1:<hubPort>` to the same ports on
   the PC. It never starts, stops or installs anything on the PC.

## mDNS announcement

- Service type: `_antigem-pc._tcp`
- Instance name: the PC's name, for example the hostname.
- Port: the pairing port.
- TXT records: `v=1`, `id=<pcId>`, `name=<pc name>`, `os=<linux|darwin|win32>`

## Crypto (SPAKE2, finite field)

| Item | Value |
|---|---|
| Label | `antigem-pair-v1` |
| Group | RFC 3526 2048-bit MODP group 14: prime `p`, `g = 2`, subgroup order `q = (p-1)/2`. |
| M, N | `H(seed) = SHA-256(seed ‖ byte(i))` for i = 0..8, concatenated (288 bytes) → integer mod p → **squared** mod p. Seeds: `"antigem-pair-v1 M"`, `"antigem-pair-v1 N"`. |
| w | `SHA-256("antigem-pair-v1 w" ‖ code)` → integer mod q; 0 becomes 1. `code` is the 6 digits as UTF-8. |
| Phone | random x in [1, q); `X = g^x · M^w mod p` |
| PC | random y in [1, q); `Y = g^y · N^w mod p` |
| Check | Each side rejects a received value unless `1 < V < p-1` and `V^q mod p = 1`. |
| Z | Phone: `(Y · (N^w)^-1)^x mod p`. PC: `(X · (M^w)^-1)^y mod p`. |
| TT | Each item as a 4-byte big-endian length followed by its bytes: `"antigem-pair-v1"`, phoneId (UTF-8), X, Y, Z, w. X, Y, Z and w are each 256-byte big-endian values. |
| K | `SHA-256(TT)` |
| Keys | `HMAC-SHA256(K, label)` for the labels `confirm-pc`, `confirm-phone`, `enc-phone-to-pc`, `enc-pc-to-phone`. |
| Confirmations | `confirmPc = HMAC-SHA256(key(confirm-pc), TT)`, `confirmPhone = HMAC-SHA256(key(confirm-phone), TT)` |
| Box | AES-256-GCM. Output: base64 of (12-byte random nonce ‖ ciphertext ‖ 16-byte tag). Additional data: `"antigem-pair-v1"`. |

All numbers in JSON are lowercase hex of the 256-byte big-endian value.

## HTTP API (pairing port, JSON)

### `POST /antigem/pair/start`

Request:

```json
{ "v": 1, "phoneId": "<uuid>", "phoneName": "OnePlus CPH2487", "x": "<hex X>" }
```

Response 200:

```json
{ "v": 1, "session": "<id>", "y": "<hex Y>", "confirm": "<hex confirmPc>" }
```

- Every `start` counts as one attempt.
- After **3 attempts** with the same code, the PC shows a new code and answers **423** to further requests.
- If the code is wrong, the phone sees that `confirm` doesn't match and shows "Wrong code". It never calls `finish`.

### `POST /antigem/pair/finish`

Request:

```json
{ "v": 1, "session": "<id>", "confirm": "<hex confirmPhone>",
  "box": "<seal(key(enc-phone-to-pc), {phoneId, phoneName, publicKey})>" }
```

- `publicKey` is a single OpenSSH line: `ecdsa-sha2-nistp256 AAAA… antigem-phone:<phoneId>`.
- The PC checks `confirm` (in constant time) and that `box.phoneId` equals the `phoneId` sent in `start`.

Response 200:

```json
{ "v": 1, "box": "<seal(key(enc-pc-to-phone), info)>" }
```

`info` contains:

```json
{ "pcId": "<stable id>", "pcName": "cat-pc", "os": "linux",
  "user": "cat", "sshPort": 22,
  "addresses": ["192.168.1.71"],
  "hostKeys": [ { "type": "ecdsa-sha2-nistp256", "key": "AAAA…" }, { "type": "ssh-rsa", "key": "AAAA…" } ],
  "bridgePort": 1244, "hubPort": 1245, "token": "<12 chars>" }
```

- `hostKeys` are the fields of the PC's sshd host key `.pub` files.
- The phone prefers ECDSA P-256, then RSA, then Ed25519. It pins that key for every address and tells JSch to ask
  for that key type only.

### Errors

Errors return `{ "error": "<message for the user>" }` with one of these statuses:

| Status | Meaning |
|---|---|
| 404 | Not pairing right now |
| 410 | Code expired |
| 423 | Too many tries, a new code is shown |
| 403 | Wrong confirmation |

## The phone's key on the PC

Write one line per phone. Pairing the same phone again replaces its line, and the comment identifies it:

```
command="internal-sftp",restrict,port-forwarding,permitopen="127.0.0.1:1244",permitopen="127.0.0.1:1245" ecdsa-sha2-nistp256 AAAA… antigem-phone:<phoneId>
```

What this line allows, tested with OpenSSH 10.5:

- SFTP works, which the phone uses to read the token.
- `-L` to the two permitted ports works.
- A shell or any other command gets "This service allows sftp connections only".
- A tunnel to any other port is refused.

**Allow terminal from phone** (setting on the PC, off by default): when it's on, the line has no
`command="internal-sftp"`, so the phone can open a shell:

```
restrict,port-forwarding,pty,permitopen="127.0.0.1:1244",permitopen="127.0.0.1:1245" ecdsa-sha2-nistp256 AAAA… antigem-phone:<phoneId>
```

At every connect the phone runs `echo antigem-shell-ok`. If that answers, the app's terminal and Run configurations
open on the PC, over SSH with tmux, like Server mode. If not, they run in the phone's own terminal. Changing the
setting rewrites the phone's line; the phone notices at its next connect.

Where the line goes:

- **Linux and macOS:** `~/.ssh/authorized_keys`.
- **Windows:** for an admin account, `C:\ProgramData\ssh\administrators_authorized_keys`, which needs one UAC prompt
  and the right file permissions. Otherwise `%USERPROFILE%\.ssh\authorized_keys`.

**Phones allowed** (setting on the PC):

- **One phone** (default): pairing a new phone replaces the old phone's line, after the user confirms.
- **Several phones:** each phone keeps its own line.

**Remove** deletes the phone's line and restarts the bridge with a new token. That ends a phone that is connected at
that moment.

## The token

The phone reads `.antigem_bridge/csrf_token.txt` relative to the SSH home, over SFTP, at every connect. It falls back
to `cat ~/…` or `type %USERPROFILE%\…`. The token from pairing is only used when both fail.

## Claude subagents

The two bridges list subagents differently, and the phone understands both:

- **App's bridge:** each subagent run is a read-only chat of its own, `agent-<id>`, with `subagent`, `parent_id` and
  `agent_type` in `/api/claude/sessions`, and history at `/sessions/agent-<id>/history`.
- **VS Code's bridge:** each chat has a `subagents` array in `/api/claude/sessions`, and history is at
  `/sessions/<chat>/subagents/<agent>/history`. The phone turns each run into an `agent-<id>` entry
  (`ClaudeBridgeClient.expandSubagentRuns`).

## AGY

VS Code runs the standard agy, which isn't patched like the phone's. While a PC is in use, the phone also sends
`x-codeium-csrf-token: <csrf>` next to its own token-framed header.

## Test mock

`docs/connect-to-pc-mock-server.mjs` is a test-only PC side used to test the phone (run with
`node connect-to-pc-mock-server.mjs <dir with sshd host keys and authorized_keys>`):

- It binds to `127.0.0.1` and is reached from the phone through `adb reverse`.
- It uses a throwaway sshd on port 2222 with its own `authorized_keys`.
- The VS Code extension should reproduce its crypto exactly.
