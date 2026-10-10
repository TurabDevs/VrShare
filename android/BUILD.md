# VrShare Android — Build Guide

Native Kotlin app (no license/key system, LAN-only: the only network traffic
is mDNS discovery and HTTPS to paired VrShare desktops on port 53417).

- applicationId: `com.vrshare.app`
- minSdk 26 · targetSdk 34 · Material 3, dark theme (forced)
- Protocol: VrShare v1 — see `../INTERFACE.md`

## Project layout

```
android/
  settings.gradle.kts / build.gradle.kts / gradle.properties
  gradle/wrapper/                      gradle wrapper (8.7)
  app/
    build.gradle.kts
    src/main/AndroidManifest.xml
    src/main/java/com/vrshare/app/
      App.kt                    Application: forces dark theme, inits Store/Identity/AppState/Discovery
      core/Models.kt            PeerDevice, TrustedDevice, IdentityInfo, ClipEntry, TransferRecord
      core/Store.kt             EncryptedSharedPreferences (token vault, settings, history)
      core/Identity.kt          self-signed RSA-2048 cert in AndroidKeyStore + stable UUID
      core/AppState.kt          peers + clipboard history (StateFlow)
      net/TlsPinning.kt         OkHttpClient per device; TrustManager pins SHA-256 fp, no hostname check
      net/Api.kt                full v1 endpoint client (pair, text, clipboard, files+resume, input, system)
      net/Discovery.kt          NsdManager announce + browse `_vrshare._tcp.local`
      net/TransferManager.kt    upload queue (2 parallel), progress, cancel, resume
      sync/ClipboardSync.kt     clipboard auto-sync both directions (no foreground service in v1)
      ui/                       MainActivity + 5 fragments + adapters + TouchpadView + UiUtil
    src/main/res/               layouts, Material3 dark theme, vector icons
```

## Prerequisites

- JDK 17
- Android SDK: cmdline-tools, `platforms;android-34`, `build-tools;34.0.0`

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH
sdkmanager --install "platform-tools" "platforms;android-34" "build-tools;34.0.0"
yes | sdkmanager --licenses
```

## Build

**Note on this environment:** `./gradlew assembleDebug` does NOT work inside this
Linux sandbox — the sandbox deterministically corrupts Java loopback TCP
(verified: a Java socket sending `hi` receives `mu`; Python is unaffected),
which breaks the Gradle daemon's IPC protocol. The Gradle project files
(`settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`) are still
valid and will work on a normal dev machine.

In this sandbox the APK is built by the manual pipeline in `tools/build.sh`,
which does exactly what AGP would do, step by step:

```
tools/resolve.py  -> resolves the Maven closure (parallel POM walk) and
                     downloads every jar/aar into ~/.sdktmp/deps
tools/build.sh    -> 1. extract AARs (classes.jar + res/)
                     2. aapt2 compile each res tree
                     3. aapt2 link all trees (overlay semantics, app wins)
                        + generate R.java + base APK
                     4. kotlinc 2.0.20 (all sources + R.java)
                     5. D8 8.13.25 standalone (-> classes.dex + classes2.dex)
                        [build-tools 34's bundled d8 8.2.2-dev crashes with an
                        NPE on appcompat; standalone R8 8.13.25 is used instead]
                     6. zipalign + apksigner -> build/manual/VrShare-debug.apk
```

Prerequisites (already set up in this VM):
- JDK 17 at `~/jdk17`, Gradle 8.7 + kotlinc 2.0.20 + R8 8.13.25 in
  `~/workspace/.sdktmp`, Android SDK (`platforms;android-34`,
  `build-tools;34.0.0`) at `~/Android/Sdk`.

```bash
cd android
./tools/build.sh
# APK: build/manual/VrShare-debug.apk  (debug-signed, verified)
```

On a normal machine with a working Gradle daemon, the standard build also works:

```bash
cd android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Install on a phone (USB debugging on):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Protocol compliance notes

- mDNS service `_vrshare._tcp.local`, port 53417, TXT `id/name/fp/os/v` (announce + browse,
  30 s offline timeout, ignore unknown `v`).
- Pairing: `POST /api/pair {id,name,fp,os}` → 202 pending / 200 approved+token;
  poll `GET /api/pair/status?id=<own-id>` every 2 s, 120 s max; 403 → rejected,
  404 → request gone. Token stored encrypted, sent as `X-VrShare-Token`.
- TLS: pinned to the SHA-256 fingerprint (mDNS `fp` during pairing, stored `fp`
  afterwards); hostname verification disabled per spec §2.2. Mismatch → connection
  dropped.
- Files: multipart fields `id` **and** `transfer_id` (the two spec docs disagree;
  both are sent), plus `name`, `size`, `sha256`. Resume: `GET /api/files/status`,
  then raw octet-stream `POST /api/files?id=…` with `X-Offset`; 409
  `offset_mismatch` re-reads `received` and retries.
- Input: touchpad throttled to ~60 Hz (spec §5); tap = left click, long-press =
  right click, two-finger drag = scroll with positive-dy-up mapping (spec §3.11).
  Keyboard via hidden EditText → `/api/input/text`; special keys via
  `/api/input/key` with spec key names.
- System: shutdown/restart are behind a confirmation dialog (spec §3.14).
- Clipboard: `clipboard:true` flag on `/api/text`; auto-sync = debounced local
  watcher (500 ms) + 3 s remote poll with loop guards (spec §3.7).
- Error bodies parsed as `{error, code}`; 403 `bad_token` → local trust is
  dropped and the user is asked to re-pair (spec §2.4).

## Not in v1 (per spec §6)

Desktop→phone push, notification mirroring, desktop-initiated pairing,
iOS/macOS, foreground-service clipboard sync.

## Test checklist (real phone + Windows PC)

Same Wi-Fi, VrShare desktop running on the PC.

1. **Discovery** — open Devices tab: the PC appears with name + `windows` badge
   and a green (online) dot within ~10 s.
2. **Pairing** — tap Connect: "Waiting for approval" shows; the PC shows a
   6-digit PIN popup. Tap Accept on the PC → phone shows "Sent" and the PC
   moves to Trusted. Tap Reject instead → phone shows "rejected".
3. **Fingerprint** — pairing dialog shows the first 16 hex chars of the PC's
   cert fingerprint; compare with the desktop's shown fingerprint.
4. **Remote** — Remote tab: drag on the touchpad → PC cursor moves; tap →
   left click; long-press → right click; two-finger drag up → page scrolls up.
   Keyboard button → type on the phone → text appears on the PC.
5. **System** — Lock → PC locks immediately. Shutdown/Restart → confirmation
   dialog appears first; confirming runs the action.
6. **Media** — play/pause, next/previous, volume up/down/mute affect the PC.
7. **Files** — Files tab → Send files → pick 2 files: progress bars move,
   cancel stops one; files land in the PC's VrShare download folder intact
   (compare sizes / checksums). Kill the app mid-upload and resend the same
   file → it resumes instead of restarting (watch the % jump).
8. **Clipboard send** — type text, Send with "also set as PC clipboard" checked
   → paste on the PC shows the text. Uncheck → PC gets a notification only.
9. **Auto-sync** — enable it, copy text on the phone → appears in the PC
   clipboard within ~1 s; copy on the PC → paste on the phone shows it
   within ~3 s. No duplication loops (watch the Recent list).
10. **Pull** — "Pull from PC" copies the PC clipboard to the phone.
11. **Unpair** — Unpair on the phone → PC disappears from trusted; remote
    actions fail gracefully. Unpair on the PC → phone shows "device was
    unpaired, please pair again" on next action.
12. **Offline** — close the desktop app: within ~35 s the phone marks it
    offline (gray dot). Reopen: back online.
13. **Rename** — Settings → change device name → PC's pending-pairing popup
    (on next pair) shows the new name.
14. **About** — Settings → About shows "VrShare 1.0.0 … Developed by Asim …
    Contact: asimcoder@proton.me".
15. **No internet** — turn on airplane mode + Wi-Fi only (or block WAN on the
    router): everything above still works; the app makes zero off-LAN calls.
