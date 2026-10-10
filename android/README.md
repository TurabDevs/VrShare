# VrShare Android

Android app — **Kotlin, Material 3, dark default** (minSdk 26, targetSdk 34).

Implements the **client side** of the VrShare Protocol v1 (see `../INTERFACE.md`)
and the mobile UI described in `../docs/design.md` (Section 2).

Key components: NSD/mDNS browsing (`NsdManager`), OkHttp with cert pinning,
touchpad view (custom `View` with multi-touch), clipboard listener
(foreground service), MediaSession-style key mapping is server-side.

Work starts in Phase 2. Do not invent protocol changes — propose them in
`INTERFACE.md` first.
