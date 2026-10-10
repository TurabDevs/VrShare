# VrShare

**VrShare** is a free, local-network sharing and remote-control app — a LocalSend-style
tool with KDE Connect-style superpowers. No cloud, no accounts, no internet needed:
everything works over your own WiFi.

![License](https://img.shields.io/badge/license-PolyForm--Noncommercial--1.0.0-blue)
![Platform](https://img.shields.io/badge/platform-Windows%20%7C%20Android-green)

- 📁 **File sharing** — send files between your Android phone and Windows PC, with
  resume support for big transfers and live progress
- 📋 **Clipboard sharing** — copy on one device, paste on the other; optional auto-sync
  both ways, plus plain text messages
- 🖱️ **Remote control** — turn your phone into a touchpad: move, click, scroll, and
  type on your PC from your phone's keyboard
- 🔒 **System commands** — lock, sleep, shutdown, or restart your PC, plus volume and
  media keys — all from your phone

## Getting started

1. Install the **VrShare** desktop app on your Windows PC and the **VrShare** app on
   your Android phone (get both from the
   [releases](../../releases) page, or build them below).
2. Connect both devices to the **same WiFi network**.
3. Open VrShare on your phone → **Devices** → tap your PC → **Connect**.
4. Your PC shows a **6-digit PIN** — approve it. Done. The devices now trust each
   other and VrShare just works: send files, sync clipboard, open the **Remote**
   tab to drive your PC.

**Platforms:** Windows (desktop) + Android (mobile). No macOS/iOS in v1.

## How pairing & security work

Both apps discover each other automatically on your LAN via mDNS (`_vrshare._tcp`,
port `53417`). Each device generates its own self-signed TLS certificate on first
run. Pairing is phone-initiated and requires physical approval: the desktop shows a
6-digit PIN plus the phone's certificate fingerprint, and only issues a random
256-bit pairing token after you accept. Every API call afterwards carries that token
(`X-VrShare-Token`) and the TLS certificate fingerprint is pinned — unknown tokens
get a `403`, and either side can unpair at any time. Nothing ever leaves your local
network: no cloud, no telemetry, no accounts.

## Repository layout

```
vrshare/
├── INTERFACE.md            # Protocol v1 spec — the contract both apps are built against
├── README.md               # This file
├── LICENSE                 # PolyForm-Noncommercial-1.0.0
├── PUSH_CHECKLIST.md       # Steps to publish this repo + run CI builds
├── .github/workflows/      # CI: Windows installer + Android APK builds
├── docs/
│   └── design.md           # UI/UX design brief for both apps
├── desktop/                # Windows app (Rust + Tauri v2)
│   ├── BUILD.md            # Desktop build notes + API reference for the Android team
│   └── src-tauri/          # Rust backend (HTTPS server, mDNS, input, clipboard…)
└── android/                # Android app (Kotlin, Material 3)
    ├── BUILD.md            # Android build notes + on-device test checklist
    └── app/src/            # Kotlin sources
```

Protocol details live in [`INTERFACE.md`](INTERFACE.md); UI mockups and UX rules in
[`docs/design.md`](docs/design.md).

## Building

### Desktop (Windows installer)

Prerequisites: Rust (stable toolchain), Node.js 20+.

```powershell
cd desktop
cargo install tauri-cli --version "^2" --locked   # once
cargo tauri build
```

The NSIS installer lands in `desktop/src-tauri/target/release/bundle/nsis/`
(`*-setup.exe`). The frontend is prebuilt static files in `desktop/frontend/dist`,
so no Node build step is needed. Prefer CI? Pushing a `v*` tag (or pressing
**Run workflow**) in the Actions tab builds the installer on a real Windows runner —
see [`.github/workflows/build.yml`](.github/workflows/build.yml).

### Android (APK)

Prerequisites: JDK 17, Android SDK 34 (Android Studio Hedgehog+ sets this up).

```powershell
cd android
./gradlew assembleRelease
```

The APK lands in `android/app/build/outputs/apk/release/`. This builds an **unsigned**
release APK — fine for personal sideloading. For a signed release, add your keystore
as repo secrets and wire up `signingConfig` (see the comment in
[`.github/workflows/build.yml`](.github/workflows/build.yml)).

## Contributing

Bug reports and pull requests are welcome. Ground rules:

- `INTERFACE.md` is the source of truth — **no protocol changes without updating it
  first**, and both apps must stay compatible with it.
- Keep the LAN-only promise: no code that phones home, no analytics, no cloud.
- Test on real devices (phone + PC on the same WiFi) before calling something done —
  the on-device checklist in `android/BUILD.md` is a good template.

## License

PolyForm-Noncommercial-1.0.0 — free to use and share for non-commercial purposes.
**Not for resale.** See [`LICENSE`](LICENSE) for the full text.

## Developer

VrShare is developed by **Turab**.
Contact: **TurabDev@proton.me**
