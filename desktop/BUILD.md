# VrShare Desktop — Build Guide

Tauri v2 app (Rust backend + plain HTML/CSS/JS frontend). No license/key system.
LAN-only: the only network traffic is mDNS discovery and HTTPS to LAN peers.

## Project layout

```
desktop/
  src-tauri/            Rust backend (crate `vrshare-desktop`)
    src/main.rs         entry point, Tauri commands, tray, background tasks
    src/server.rs       axum HTTPS API (port 53417, see endpoints below)
    src/discovery.rs    mDNS register + browse (`_vrshare._tcp.local.`)
    src/tls_client.rs   outgoing HTTPS with certificate fingerprint pinning
    src/transfer.rs     outgoing file uploads with progress
    src/cert.rs         self-signed cert generation (rcgen, persisted)
    src/store.rs        trusted devices + settings (JSON in app data dir)
    src/state.rs        shared AppState
    src/input.rs        mouse/keyboard injection (enigo)
    src/clip.rs         clipboard (arboard, process-wide handle)
    src/system.rs       lock/sleep/shutdown/restart (Win32, cfg-gated)
    tauri.conf.json     productName VrShare, id me.asimcoder.vrshare
    capabilities/       dialog:allow-open, core events
    icons/icon.png      app icon
  frontend/dist/index.html   the whole UI (dark theme, no build step)
```

## Prerequisites

- Rust stable (https://rustup.rs)
- **Windows:** WebView2 (preinstalled on Win10/11), then
  `cargo install tauri-cli --version "^2"`
- **Linux dev:** `webkit2gtk-4.1`, `gtk+-3.0`, `libsoup-3.0` dev packages

## Build the Windows installer

```powershell
cd desktop\src-tauri
cargo tauri build
```

Output: `target\release\bundle\nsis\VrShare_1.0.0_x64-setup.exe`
(portable exe next to it). The installer is unsigned — Windows SmartScreen
will show a warning on first run.

## Linux dev run

```bash
cd desktop/src-tauri
cargo run
```

## Smoke test performed (2026-10-10, Linux container, Xvfb)

- `cargo check --all-targets`: **0 errors, 0 warnings**
- Windows-only code validated separately: the `#[cfg(windows)]` system module
  (direct Win32 FFI: `LockWorkStation`, `SetSuspendState`, `ExitWindowsEx`)
  was extracted verbatim into a scratch crate and
  `cargo check --target x86_64-pc-windows-gnu` passed. (Note: the
  `windows` crate 0.61 was dropped — `LockWorkStation`/`BOOL` paths and the
  `SHUTDOWN_REASON` type didn't match its generated bindings, so system.rs
  uses direct `unsafe extern "system"` declarations instead. Fewer moving parts.)
- App launched headless under `xvfb-run`: no uncaught panics. Tray init is
  wrapped in `catch_unwind` — when the native tray library is missing the app
  logs "tray unavailable" and keeps running (verified: panic caught, server
  kept serving).
- HTTPS API verified with curl (`-k`, self-signed) against the final binary:
  - `GET /api/info` → 200, correct device JSON
  - `POST /api/pair` → `{"status":"pending"}`; `GET /api/pair/status` → pending
  - protected endpoints without token → 403; wrong token → 403
  - `POST /api/text`, `POST /api/clipboard`, `GET /api/clipboard` → 200
    (set→get roundtrip returns the exact text; a process-wide clipboard
    handle is kept alive so X11 ownership survives)
  - `POST /api/files` multipart (+ `X-Offset` resume) → 200
    `{"received":N,"done":true}`; file bytes intact on disk
  - `POST /api/input/move|click|scroll|key|text` → 200 (enigo works under Xvfb)
  - `POST /api/system {"action":"bogus"}` → 500 with clear error message
- Known environment limits of the container (not app bugs):
  - mDNS cannot run here (sandbox blocks loopback UDP sends); on a real
    machine/Windows it registers and browses normally. The failure is logged
    and the app continues.
  - Default download dir fell back to `/tmp/VrShare` because `~/Downloads`
    doesn't exist in the container; on Windows it resolves to the real
    Downloads folder.

## API summary (for the Android team)

Base: `https://<ip>:53417`. mDNS service `_vrshare._tcp.local.`,
TXT: `id` (uuid hex), `name`, `fp` (sha256 hex of server cert),
`os` (`windows`/`linux`/`android`), `v=1`.

- `GET /api/info` → `{id,name,fp,os,v,features:["files","clipboard","input","system"]}`
- `POST /api/pair` `{id,name,fp,os}` → `{"status":"pending"}` (desktop shows
  the 6-digit PIN; user accepts) or `{"status":"approved","token"}` (re-pair
  with unchanged fp). Poll `GET /api/pair/status?id=<own id>`.
- All below need header `X-VrShare-Token: <token>` (else 403):
  - `POST /api/text` `{text, clipboard?}` — clipboard=true also sets OS clipboard
  - `GET /api/clipboard` → `{text}` · `POST /api/clipboard` `{text}`
  - `POST /api/files` multipart fields `id`,`name`,`size`,`file`;
    resume by sending header `X-Offset: <bytes>` → `{received,done}`
  - `POST /api/input/move` `{dx,dy}` · `POST /api/input/click`
    `{button:left|right|middle, action:down|up|click}` ·
    `POST /api/input/scroll` `{dx,dy}` · `POST /api/input/key`
    `{key, action:down|up|press}` (`key`: single char or Enter/Tab/Backspace/
    Escape/Delete/Space/Shift/Control/Alt/arrows/Home/End/PageUp/PageDown/F1-F12) ·
    `POST /api/input/text` `{text}`
  - `POST /api/system` `{action: lock|sleep|shutdown|restart}`
- Outgoing connections from desktop pin the peer cert by `fp` (custom rustls
  verifier); mismatched fingerprint = connection rejected.

## Notes

- Device id, TLS cert/key, trusted devices and settings live in the Tauri app
  data dir (`%APPDATA%\me.asimcoder.vrshare` on Windows).
- Pending pair requests expire after 5 minutes.
- Filenames from peers are sanitized before saving.
- Clipboard auto-sync (optional, off by default) pushes local clipboard
  changes to all online trusted peers, with a loop guard for peer-originated
  changes.
- Windows-only code (`LockWorkStation`, `SetSuspendState`, `ExitWindowsEx`)
  is `#[cfg(windows)]`-gated; non-Windows uses `loginctl lock-session` for
  lock and returns "unsupported on this OS" for the rest.
