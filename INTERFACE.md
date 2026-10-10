# VrShare Protocol v1 — Interface Specification

**Status:** draft v1 (2026-10-10)
**Scope:** the wire contract between VrShare desktop (Windows, Tauri) and VrShare Android (Kotlin).
Both sides MUST implement this exact contract. No macOS / iOS in v1.

Transport is HTTPS on TCP port **53417** on the local network only. All request/response
bodies are JSON (`Content-Type: application/json`) unless the endpoint says otherwise.
Timestamps are Unix epoch milliseconds. Sizes are bytes.

---

## 1. Discovery (mDNS)

- Service type: `_vrshare._tcp.local`
- TCP port: **53417**
- Host name: `<device-id-short>.local` (or the OS hostname; must resolve on the LAN)
- TXT records (all required):
  - `id` — device UUID v4, lowercase, e.g. `a3f1c9e2-4b5d-4c6e-8f7a-9b0c1d2e3f4a`
  - `name` — human-readable device name, UTF-8, max 64 chars, e.g. `Asim-PC`
  - `fp` — SHA-256 hex fingerprint (lowercase, 64 chars) of the device's self-signed TLS certificate
  - `os` — `windows` or `android`
  - `v` — protocol version, `"1"` for this spec
- Both apps MUST announce on startup and send a goodbye on clean exit.
- Both apps MUST browse `_vrshare._tcp.local` continuously while running and keep a
  nearby-device list. A device that disappears from mDNS for > 30 s is marked offline.
- Devices MUST ignore mDNS records whose `v` they do not understand (forward-compat).

## 2. Security model

VrShare has no cloud, no accounts, no central server. Trust is pairwise per device.

### 2.1 Device identity

- On first run each device generates a self-signed X.509 certificate (ECDSA P-256 or
  RSA-2048, validity ~10 years) and a UUID v4 `id`. Both are persisted in local app
  storage and NEVER change afterwards (unless the user resets the app).
- `fp` = SHA-256 of the DER encoding of the certificate, lowercase hex.

### 2.2 Certificate pinning

- Every TLS connection is verified against the pinned fingerprint:
  - For an **unpaired** device the fingerprint from the mDNS TXT record is used as the
    *expected* fingerprint for the pairing handshake (TOFU — trust on first use), and the
    pairing PIN (Section 2.3) protects against MITM.
  - For a **paired** device the fingerprint stored at pairing time is used. Any mismatch
    → drop the connection, mark device untrusted, notify the user.
- Clients MUST set SNI/hostname verification OFF for self-signed certs and rely on
  pinning only.

### 2.3 Pairing flow (PIN approval)

The mobile device initiates; the desktop approves. (Desktop-initiated pairing is NOT in v1.)

1. Mobile → Desktop: `POST /api/pair` with body `{id, name, fp, os}` (Section 3.2).
   The request is made over TLS with the desktop's pinned `fp` (from mDNS).
2. Desktop creates a pending request, generates a random 6-digit PIN (100000–999999,
   leading zeros allowed — always display 6 digits), and shows a modal popup:
   device name, OS, IP, PIN. User clicks **Accept** or **Reject** (timeout 120 s → reject).
3. Mobile polls `GET /api/pair/status?id=<mobile-id>` (no auth needed; id must match the
   pending request's source IP fp):
   - `{"state":"pending"}` (HTTP 200) — keep polling, every 2 s, max 120 s
   - `{"state":"approved","token":"<256-bit hex>"}` (HTTP 200) — store token + desktop fp
   - `{"state":"rejected"}` (HTTP 403 body) — show rejection, stop polling
4. On approval the desktop stores `{id, fp, name, os, paired_at}` in its trusted-device
   list and returns a random 256-bit token (hex, 64 chars).
5. All later requests from the mobile MUST include header `X-VrShare-Token: <token>`.
   Unknown / missing token → HTTP 403 `{error:"unauthorized", code:"bad_token"}`.
6. Mobile-to-desktop direction only in v1: the desktop never needs to pair *to* the
   phone for the defined feature set (files/clipboard/input/system all flow phone→PC or
   are pulled by the phone). Desktop→phone push is a future extension.

### 2.4 Unpairing

- Mobile: `DELETE /api/pair` with valid token → server deletes the trust entry, returns
  200. Mobile deletes its stored token.
- Desktop: removing a device from Settings → Trusted devices deletes the entry locally.
  The mobile's token silently stops working (403 on next use); the app should surface a
  "device unpaired" notice and offer re-pairing.

### 2.5 Notes

- The desktop MAY optionally require pairing approval for *every* new IP even from a
  known fp — v1 does not (fp pinning is sufficient).
- Rate limiting: max ~60 input events/sec per device (Section 5); pairing attempts max
  5/minute per source IP (excess → 429).

---

## 3. Endpoints

Base URL: `https://<ip>:53417`
Auth: `X-VrShare-Token` header required on ALL endpoints EXCEPT `/api/info`,
`/api/pair`, and `/api/pair/status`.

### 3.1 GET /api/info — no auth

Returns device identity and capabilities.

Response 200:
```json
{
  "id": "a3f1c9e2-4b5d-4c6e-8f7a-9b0c1d2e3f4a",
  "name": "Asim-PC",
  "os": "windows",
  "v": "1",
  "features": ["files", "clipboard", "input", "system"]
}
```

### 3.2 POST /api/pair — no auth

Body: `{"id":"<uuid>","name":"<string>","fp":"<64-hex>","os":"android"}`

- 202 `{"state":"pending"}` — request created, waiting for user approval
- 200 `{"state":"approved","token":"<64-hex>"}` — already trusted (re-pair of known fp:
  desktop may auto-approve if fp matches a stored entry, returning a fresh token)
- 409 `{error, code:"already_pending"}` — a request from this id is already pending
- 429 `{error, code:"rate_limited"}` — too many attempts

### 3.3 GET /api/pair/status?id=<uuid> — no auth

- 200 `{"state":"pending"}`
- 200 `{"state":"approved","token":"<64-hex>"}`
- 403 `{"state":"rejected"}` (body carries state, status is 403)
- 404 `{error, code:"no_pending_request"}`

### 3.4 DELETE /api/pair — auth required

Deletes the caller's trust entry. Response 200 `{"ok":true}`.

### 3.5 POST /api/text — auth required

Send a text snippet (shows as a notification / chat message; does NOT touch clipboard
unless `clipboard:true`).

Body: `{"text":"<string, max 100_000 chars>","clipboard":false}`
Response 200 `{"ok":true}`.

### 3.6 GET /api/clipboard — auth required

Response 200 `{"text":"<string>","updated_at":<epoch-ms>}`.
Empty clipboard → `{"text":"","updated_at":0}`.

### 3.7 POST /api/clipboard — auth required

Body: `{"text":"<string, max 10_000_000 chars>"}` — sets the desktop clipboard.
Response 200 `{"ok":true}`.
Android auto-sync: when enabled, the phone watches its own clipboard and POSTs on change
(debounced 500 ms); it also polls `GET /api/clipboard` every 3 s and copies new
`updated_at` values locally.

### 3.8 POST /api/files — auth required — binary

Multipart upload of one file per request (`Content-Type: multipart/form-data`).

Form fields:
- `file` — the file bytes (part filename = original name)
- `transfer_id` — client-generated UUID v4 for this transfer (dedup/resume key)
- `name` — original file name (UTF-8)
- `size` — total bytes (integer)
- `sha256` — optional hex; server verifies after completion, deletes + 422 on mismatch

Behavior:
- New transfer → server replies 200 `{"id":"<transfer_id>","received":0,"status":"receiving"}`
  after storing headers, then streams to a temp file.
- The client SHOULD send `Expect: 100-continue` OR check resume first (below).
- On full receipt → 200 `{"id":"<transfer_id>","received":<size>,"status":"complete"}`,
  file moved to the download folder, desktop shows a received-file notification.
- Duplicate `transfer_id` with identical size+name → 200 `{"status":"complete"}` (idempotent).

**Resume:**
1. Client: `GET /api/files/status?id=<transfer_id>` → 200
   `{"id":"...","received":<bytes>,"status":"receiving"|"complete"|"unknown"}`.
2. Client: `POST /api/files?id=<transfer_id>` with raw body
   (`Content-Type: application/octet-stream`) starting at byte `received`, plus header
   `X-Offset: <received>`.
3. Server appends; replies 200 `{"id":"...","received":<new>,"status":"receiving"|"complete"}`.
4. If `X-Offset` doesn't match server state → 409 `{error, code:"offset_mismatch",
   "received":<bytes>}` and the client retries from the returned offset.

Desktop → phone file send is NOT in v1 (future extension); the desktop UI may offer
"request file" which just sends a `/api/text` hint.

### 3.9 POST /api/input/move — auth required

Relative mouse movement in pixels. Body: `{"dx":<int>,"dy":<int>}`.
Response 200 `{"ok":true}`.
Clients should batch small deltas and stay under the rate limit (Section 5).

### 3.10 POST /api/input/click — auth required

Body: `{"button":"left"|"right"|"middle","action":"down"|"up"|"click"}`.
`"click"` = down+up. Response 200 `{"ok":true}`.

### 3.11 POST /api/input/scroll — auth required

Body: `{"dx":<int>,"dy":<int>}` — pixels; positive `dy` = scroll up (toward user
expectation: two-finger swipe up scrolls content up; implement as you document it in
the app and keep consistent). Response 200 `{"ok":true}`.

### 3.12 POST /api/input/key — auth required

Body: `{"key":"<name>","action":"down"|"up"|"press"}`.
Key names: single printable chars (`"a"`, `"A"`, `"5"`, `" "` … — case-sensitive;
shift is the client's job to express via `"A"` vs `"a"`), plus:
`"Enter" "Escape" "Tab" "Backspace" "Delete" "Insert" "Home" "End" "PageUp" "PageDown"
"Up" "Down" "Left" "Right" "F1".."F12" "CapsLock" "NumLock" "PrintScreen"`,
modifiers as separate events: `"Shift" "Control" "Alt" "Meta"` (Meta = Windows key).
Example Ctrl+C: key Control/down, key c/down, key c/up, key Control/up.
Response 200 `{"ok":true}`. Unknown key name → 400 `{error, code:"unknown_key"}`.

### 3.13 POST /api/input/text — auth required

Type literal text. Body: `{"text":"<string, max 10_000 chars>"}`.
Server types it via Unicode-aware injection (no layout dependence).
Response 200 `{"ok":true}`.

### 3.14 POST /api/system — auth required

Body: `{"action":"<one of>"}`. Actions:
- `"lock"` — lock the workstation (Win+L equivalent)
- `"sleep"` — suspend
- `"shutdown"` — power off (no forced delay; OS default grace applies)
- `"restart"` — reboot
- `"screen_off"` — turn displays off
- `"volume_up"` / `"volume_down"` / `"mute"` — master volume
- `"play_pause"` / `"media_next"` / `"media_prev"` — media keys

Response 200 `{"ok":true,"action":"lock"}`. Dangerous actions (`shutdown`, `restart`)
MUST be confirmed in the Android UI before sending (the protocol itself does not
re-confirm). Unknown action → 400 `{error, code:"unknown_action"}`.

---

## 4. Errors & status conventions

Error body (all 4xx/5xx): `{"error":"<human-readable>","code":"<machine code>"}`.

| Status | Meaning |
|---|---|
| 200 | OK |
| 202 | Pairing request pending (POST /api/pair only) |
| 400 | Bad request (`bad_json`, `missing_field`, `unknown_key`, `unknown_action`, `bad_offset`…) |
| 403 | Forbidden: bad/missing token, cert fp mismatch, pairing rejected |
| 404 | Unknown endpoint or transfer id (`not_found`, `no_pending_request`) |
| 409 | Conflict (`already_pending`, `offset_mismatch` — includes `received`) |
| 413 | Payload too large (text > 100 KB on /api/text, clipboard > 10 MB) |
| 422 | Integrity failure (sha256 mismatch on file) |
| 429 | Rate limited (`rate_limited`) — client MUST back off ≥ 5 s |
| 500 | Server error (`internal`) |

## 5. Rate limiting & performance guidance

- Input events (`/api/input/*`): token bucket, max **60 events/sec** sustained per device,
  burst 120. Excess → 429.
- All other endpoints: max 30 req/sec per device.
- Pairing: max 5 attempts/minute per source IP.
- File uploads: single stream per transfer; clients MAY open up to 2 parallel transfers.
- The Android touchpad should coalesce move events (send at most every 16 ms).
- Desktop file receive: stream to `<download-dir>/.vrshare-incoming/`, atomic rename on
  complete. Name collisions → append ` (2)`, ` (3)`, … before the extension.

## 6. Future extensions (NOT v1 — do not implement)

- Desktop → phone file/text push (reverse direction of 3.5/3.8)
- Notification mirroring (phone notifications → desktop toasts)
- Desktop-initiated pairing / desktop remote-control of phone
- Multiple simultaneous trusted phones controlling one desktop (input arbitration)
- End-to-end encrypted relay for off-LAN use
- iOS / macOS clients (protocol already OS-agnostic; `os` field extensible)

---

## 7. Implementation checklist (per side)

- [ ] Cert + id generation & persistence; fp computation
- [ ] mDNS announce + browse; TXT parsing; 30 s offline timeout
- [ ] TLS server (desktop) / TLS client with pinning (both)
- [ ] `/api/info`, pairing endpoints, PIN popup (desktop), poll loop (android)
- [ ] Token auth middleware (403 on bad token)
- [ ] Files: multipart receive, resume protocol, sha256 verify, atomic rename
- [ ] Clipboard get/set; android auto-sync both directions
- [ ] Input injection: move/click/scroll/key/text (desktop: native injection)
- [ ] System actions incl. lock/sleep/shutdown/restart/media/volume
- [ ] Trusted-device store; unpair both directions
- [ ] Error bodies per Section 4; rate limiter per Section 5
