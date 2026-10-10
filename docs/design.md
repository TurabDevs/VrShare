# VrShare — UI/UX Design Brief

**Purpose:** concrete design instructions for implementing the VrShare desktop
(Windows, Tauri v2) and Android (Kotlin, Material 3) apps. Follow this document;
do not invent new screens without a reason.

**Brand:** name "VrShare", dark-first theme on both platforms, accent color
**#4F8CFF** (bright blue). Rounded corners (12 dp / 8 px radius), SF-like clean
typography, generous spacing. Developer credit in About: "Developed by Turab —
asimcoder@proton.me".

---

## 1. Desktop — Windows (Tauri v2)

Main window: **~960 × 620**, non-resizable minimum 860 × 540, dark theme.
Title bar: "VrShare" + connected-device count.

### 1.1 Layout

```
┌─────────────────────────────────────────────────────────────┐
│ [VrShare]  Device name: [Asim-PC ✎]              [⚙ Settings] │
├──────────┬──────────────────────────────────────────────────┤
│ DEVICES  │  Tabs: [ Files ] [ Clipboard ] [ Activity ]       │
│          │                                                  │
│ ● Asim's │   ┌──────────────────────────────────────────┐   │
│   Phone  │   │                                          │   │
│   ANDROID│   │        (tab content)                     │   │
│          │   │                                          │   │
│ ○ Tab-A7 │   └──────────────────────────────────────────┘   │
│   ANDROID│                                                  │
│          │                                                  │
│ [+ Pair  │                                                  │
│  new]    │                                                  │
└──────────┴──────────────────────────────────────────────────┘
```

- **Left sidebar (240 px):** "DEVICES" heading; list of discovered devices.
  Each row: phone/PC icon, device name (bold), OS badge (`ANDROID`/`WINDOWS`,
  small pill), green dot = online & paired, grey dot = discovered but unpaired,
  red dot = offline. Click a device to select it — tabs act on the selected device.
  Bottom: "+ Pair new" button (puts desktop in "waiting for pairing" hint state).
  Right-click a paired device → context menu: Rename (local alias), Unpair.
- **Top bar:** app name left; center: editable device name field (pencil icon,
  inline edit, Enter to save); right: settings gear.
- **Main area:** three tabs.

### 1.2 Tabs

**Files tab**
- Large drag-and-drop zone at top ("Drop files here to send to *<device>* or click
  to browse"). Clicking opens a native file picker (multi-select).
- Below: transfer list. Each row: file icon, name, size, progress bar with %,
  speed (MB/s), status text (Waiting / Sending… / Receiving… / Done / Failed),
  cancel ✕ button while active. Completed rows collapse to a single "✓ name —
  size" line with "Open folder" link.
- Empty state: illustration + "No transfers yet. Drag files here."

**Clipboard tab**
- Multiline text area (read-only preview of current desktop clipboard, refreshes
  live) with buttons: **Send to device**, **Copy from device** (pulls via
  GET /api/clipboard).
- Toggle: "Auto-sync clipboard with *<device>*" (when on: any local clipboard
  change is pushed; incoming changes applied automatically; show last-sync time).
- "Send text message" field + Send button → POST /api/text.

**Activity tab**
- Scrollable event log, newest at bottom, monospace timestamps:
  `14:02:11  📁 Received photo.jpg (2.4 MB) from Asim's Phone`
  `14:03:40  📋 Clipboard synced (128 chars)`
  `14:05:02  🔗 Asim's Phone connected`
- Buttons: Clear, "Export log" (saves .txt).

### 1.3 Pairing popup (modal, always on top)

```
┌─────────────────────────────────┐
│  New pairing request            │
│                                 │
│  📱 Asim's Phone (ANDROID)      │
│  192.168.1.42                   │
│                                 │
│      ┌───┐                      │
│      │ 4 │ 2 │ 9 │ 8 │ 1 │ 3 │   │
│      └───┘                      │
│                                 │
│  [ Reject ]      [ Accept ]     │
│  Auto-rejects in 1:47           │
└─────────────────────────────────┘
```
- 6-digit PIN in large mono boxes; countdown timer (120 s); Accept = primary blue.
- On accept: toast "Asim's Phone paired ✓". On reject/timeout: nothing (requester
  sees rejection).

### 1.4 System tray

- Tray icon (VrShare logo; dot overlay when a transfer is active).
- Left-click: show/hide main window. Right-click menu:
  `Open VrShare | ── | Receive folder: <path> | Auto-start: ✓ | ── | Quit`
- Incoming file → Windows toast notification: "📁 photo.jpg received from Asim's
  Phone — click to open folder".
- Incoming pairing request → toast + popup (popup takes focus).

### 1.5 Settings window (modal)

Sections: **General** (device name, auto-start with Windows, start minimized to
tray), **Files** (download folder picker, "ask before saving" toggle),
**Trusted devices** (list with Unpair buttons), **About** (VrShare v1.0.0,
"Developed by Turab — asimcoder@proton.me", "Free for non-commercial use").

---

## 2. Android — Kotlin, Material 3, dark default

Single-activity app, bottom navigation with 4 destinations:
**Devices | Remote | Files | Settings**. Top app bar shows "VrShare" + connection
status dot of the currently selected device.

### 2.1 Devices tab

- "NEARBY" section: cards for each discovered device — icon, name, OS label,
  status line ("Paired • Online" green / "Tap to pair" / "Offline" grey).
- Tapping an unpaired device → starts pairing: show "Waiting for approval on
  *<name>*…" with animated dots and Cancel; on approval → success snackbar
  "Paired with *<name>* ✓"; on reject → "Pairing rejected".
- Tapping a paired online device selects it as the active device (checkmark).
- Long-press a paired device → menu: Rename, Unpair.
- Pull-to-refresh re-triggers mDNS browse. Empty state: "No devices found —
  make sure both are on the same WiFi."

### 2.2 Remote tab (the touchpad)

Layout, top → bottom:

```
┌─────────────────────────────────┐
│  Active: Asim-PC        [⌨]     │  ← keyboard toggle button
│ ┌─────────────────────────────┐ │
│ │                             │ │
│ │      TOUCHPAD (~60%)         │ │  ← swipe = move, tap = left click,
│ │   "Swipe to move • Tap to   │ │     long-press = right click,
│ │    click • 2-finger scroll"  │ │     2-finger swipe = scroll
│ │                             │ │
│ └─────────────────────────────┘ │
│ [ Left ] [ Middle ] [ Right ]   │  ← mouse button row (tap = click,
│                                 │     hold = down/up)
│ ── System ──                    │
│ [🔒 Lock] [💤 Sleep]            │
│ [⏻ Shutdown] [↻ Restart]        │
│ ── Media ──                     │
│ [⏮] [⏯] [⏭]   [🔉][🔊][🔇]      │
└─────────────────────────────────┘
```
- Touchpad: dark surface with subtle grid/dots texture and the hint text fading
  after first use. Multi-touch supported.
- Keyboard button (top-right): opens system keyboard; typed text streams via
  POST /api/input/text; also a row of special keys: Esc, Tab, Enter, arrows,
  Ctrl/Alt/Shift toggles (sticky modifiers for combos like Ctrl+C).
- **Shutdown / Restart / Sleep buttons MUST show a confirmation dialog**
  ("Shut down Asim-PC? [Cancel] [Shut down]") before sending.
- Disabled state: if no device selected/online, whole tab shows "Select a device
  in the Devices tab" empty state.

### 2.3 Files tab

- Big **"Send files"** button (opens system file picker, multi-select) → starts
  uploads to the active device with per-file progress bars and cancel buttons.
- "History" list: past transfers (name, size, direction arrow ↑/↓, time, status
  icon). Tap → open file (if still present) or reveal in folder.
- Storage permission rationale shown inline on first use (Android 13+ photo
  picker needs no permission; explain in one line).

### 2.4 Clipboard card (inside Files tab top, or its own section)

- Card showing last synced text (truncated to 3 lines, tap to expand).
- Toggle: "Auto-sync clipboard" (default ON after pairing).
- Buttons: "Push to PC" (sends current phone clipboard), "Pull from PC".

### 2.5 Settings tab

- **Device name** (editable, inline).
- **Download folder** (picker, default `Download/VrShare`).
- **Trusted devices** (list; each with Unpair).
- **Connection**: "Forget all pairings" (danger, confirm dialog).
- **About**: VrShare logo, version, "Developed by Turab", "Contact:
  asimcoder@proton.me" (tap → email intent), "Free for non-commercial use —
  not for resale", link-less plain text license summary.

---

## 3. Shared UX rules

- **Offline handling:** any action on an offline device → snackbar "Device offline"
  (never a crash, never a silent no-op).
- **Token expired (403):** show "Pairing expired — please pair again" and open the
  Devices tab pairing flow.
- **Transfers:** progress always visible; failed transfers show Retry.
- **Text:** all user-facing strings in English for v1 (localization later).
- **Accessibility:** touch targets ≥ 48 dp on Android; desktop buttons keyboard-focusable.
- **No ads, no analytics, no accounts.** The only "network" indicator is the LAN
  status dot.
