# 07 · Privacy

## A. Protecting what we capture

Captured traffic contains passwords, tokens, cookies and private messages.

1. **It stays on the phone**: app storage only. Never uploaded, never in crash reports or telemetry; the engine's log
   has no traffic content.
2. **Secrets are hidden from the AI by default**: `Authorization`, `Cookie`, `Set-Cookie`, `Proxy-Authorization`,
   API-key headers, and body fields named like password, secret, token or otp become `[hidden]` in MCP output and in
   the AI files.
   - A hidden value keeps a short fingerprint (`[hidden #a1f3]`), so the AI can still tell "same token in both
     requests".
   - The "Show secrets to AI" setting is off by default and shows a warning. `show_secrets` on a call only works when
     the setting is on.
   - The GUI shows everything, because it stays on the phone.
3. **The CA key**: one per install, encrypted with an Android Keystore key, decrypted only into engine memory, never
   sent over the API or MCP. Buttons: "Remove certificate", "Create new CA".
4. **The user stays in control**: "Allow agents to start capture" is off by default, so the first time an agent asks
   for an app, the app asks the user to confirm. The notification always shows what's being captured, with a Stop
   button.
5. **Banking and payment apps**: a warning when one is picked; they may detect it and lock the account.
6. **Cleanup**: a size cap per session, "Clear all", and an optional "delete when capture stops".

## B. Privacy report

What it looks for, per app and per host:

| Data | How |
|---|---|
| Device IDs | exact match with the real values from `NetcapDeviceFacts` (Android ID, advertising ID, model and build), also in base64, URL-encoded, md5, sha1 and sha256 form (apps often send hashes) |
| Installed apps list | a body holding many real package names (compared with the `QUERY_ALL_PACKAGES` list) |
| Location | latitude/longitude pairs, Wi-Fi network names |
| Email, phone number | patterns; masked in the report (`j***@gmail.com`) |
| Trackers | the host is on the tracker list (`data/trackers.json`, updatable; source and license chosen in Phase 6) |
| Weak transport | plain http, TLS 1.0/1.1, secrets in URLs |
| Timeline | what was sent in the first 10 s after launch, before the user did anything |

Output: a short summary for the AI, plus a full Markdown file in the session's `out/` folder with flow ids as proof.

```
com.some.app · 2 min · 184 flows · 11 hosts
HIGH  advertising ID → graph.facebook.com (22×), app-measurement.com (9×) · first at +1.2 s
HIGH  installed apps list (143 packages) → api.some.app /v2/device · f88
MED   Android ID (sha256) → api.some.app (4×)
LOW   plain http → img.some.app (images only)
full report: ~/.antigem/netcap/sessions/s5/out/privacy-com.some.app.md
```

## C. Docs page

A page on the website: the data stays on the device, what goes to the AI, how hiding works, and that the tool is for
your own apps and your own traffic.
