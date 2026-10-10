# Push checklist — publish VrShare to GitHub

Everything in this repo is prepared for a public push to
`github.com/theasxd/vrshare` (the connected GitHub identity). GitHub is **not**
connected yet, so nothing has been pushed. Do these steps in order once it is.

## 1. Create the repo

- On GitHub, create a new repository named **`vrshare`** under
  `theasxd`.
- Visibility: **Public** (Actions minutes are free and unlimited on public repos).
- Do **not** tick "Add a README / .gitignore / license" — this repo already has them.

## 2. Push the code

```bash
cd ~/workspace/vrshare
git init
git add .
git commit -m "VrShare v1.0.0: LAN file sharing, clipboard sync, phone-as-touchpad remote, PC lock/sleep/shutdown (Windows + Android)"
git branch -M main
git remote add origin https://github.com/theasxd/vrshare.git
git push -u origin main
```

Sanity check after push: the repo page should show this README, the `LICENSE` file,
and the workflow badge under the **Actions** tab (`Build VrShare`).

## 3. Confirm the workflow is picked up

- Open the repo → **Actions** tab → you should see the **Build VrShare** workflow
  (from `.github/workflows/build.yml`).
- It runs on `workflow_dispatch` (manual) and on any `v*` tag push.

## 4. Build the Windows installer + Android APK

- **Manual:** Actions → *Build VrShare* → **Run workflow** → Run.
- **On tag:** `git tag v1.0.0 && git push origin v1.0.0` triggers it automatically.
- Two jobs run in parallel:
  - `windows` (windows-latest): builds the Tauri NSIS `*-setup.exe` installer.
  - `android` (ubuntu-latest): builds the release APK (`assembleRelease`).
- When the run finishes, both files are under the run's **Artifacts** section:
  `vrshare-windows-installer` and `vrshare-android-apk`. Download from there.

## 5. After the build

- Smoke-test the installer on a real Windows PC and the APK on a real phone, both
  on the same WiFi (pairing → file send → touchpad → lock).
- The APK from CI is **unsigned** — fine for sideloading/testing. Before any public
  release, sign it with a release keystore (see the note in
  `.github/workflows/build.yml`) and keep the keystore **out of the repo**.
- Optional: attach the artifacts to a GitHub **Release** for easy downloads.

## Notes

- First Windows build takes ~10–20 minutes (Rust dependency compile). Later builds
  are faster thanks to cargo caching on the runner.
- The NSIS installer produced by CI is **unsigned** — Windows SmartScreen will show
  the standard "unknown publisher" prompt. Code-signing needs a paid certificate
  and is out of scope for v1.
