# VrShare Desktop (Windows)

Windows app — **Rust + Tauri v2**.

Implements the **server side** of the VrShare Protocol v1 (see `../INTERFACE.md`)
and the desktop UI described in `../docs/design.md` (Section 1).

Suggested crate set: `axum` (HTTPS server), `mdns-sd` (discovery), `arboard`
(clipboard), `enigo` (mouse/keyboard injection), `windows` / `windows-rs`
(LockWorkStation, volume/media keys), `rcgen` (self-signed cert), `tauri` v2.

Work starts in Phase 1. Do not invent protocol changes — propose them in
`INTERFACE.md` first.
