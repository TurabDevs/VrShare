// VrShare desktop app - Tauri v2 entry point.
// No license/key system. LAN-only: the only network traffic is mDNS + HTTPS
// to peers on the local network.

mod cert;
mod clip;
mod discovery;
mod input;
mod server;
mod state;
mod store;
mod system;
mod tls_client;
mod transfer;

use serde::Serialize;
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;
use tauri::Manager;

use state::AppState;
use store::{Settings, Store, TrustedDevice};

// ---------------------------------------------------------------------------
// Tauri commands (called from the frontend)
// ---------------------------------------------------------------------------

#[derive(Serialize)]
struct DeviceInfo {
    id: String,
    name: String,
    fp: String,
    os: String,
    version: String,
    settings: Settings,
    trusted_count: usize,
}

#[tauri::command]
fn cmd_get_info(state: tauri::State<Arc<AppState>>) -> DeviceInfo {
    let s = state.inner();
    DeviceInfo {
        id: s.device_id.clone(),
        name: s.device_name(),
        fp: s.fingerprint_hex.clone(),
        os: std::env::consts::OS.to_string(),
        version: env!("CARGO_PKG_VERSION").to_string(),
        settings: s.store.settings.read().unwrap().clone(),
        trusted_count: s.store.trusted.read().unwrap().len(),
    }
}

#[tauri::command]
fn cmd_get_peers(state: tauri::State<Arc<AppState>>) -> Vec<state::PeerInfo> {
    state.inner().peer_list()
}

#[tauri::command]
fn cmd_set_device_name(state: tauri::State<Arc<AppState>>, name: String) -> Result<(), String> {
    let name = name.trim().to_string();
    if name.is_empty() {
        return Err("device name cannot be empty".into());
    }
    let s = state.inner();
    s.store.settings.write().unwrap().device_name = name.clone();
    s.store
        .save_settings()
        .map_err(|e| format!("save failed: {e}"))?;
    s.log(format!(
        "Device name set to '{name}' (restart the app to re-advertise it on the network)"
    ));
    Ok(())
}

#[tauri::command]
fn cmd_set_settings(state: tauri::State<Arc<AppState>>, settings: Settings) -> Result<(), String> {
    let s = state.inner();
    // Never allow an empty download dir - fall back to the default.
    let mut settings = settings;
    if settings.download_dir.trim().is_empty() {
        settings.download_dir = Settings::default().download_dir;
    }
    if settings.device_name.trim().is_empty() {
        settings.device_name = s.device_name();
    }
    *s.store.settings.write().unwrap() = settings;
    s.store
        .save_settings()
        .map_err(|e| format!("save failed: {e}"))?;
    s.log("Settings saved");
    Ok(())
}

#[derive(Serialize)]
struct PairRequestView {
    id: String,
    name: String,
    os: String,
    pin: String,
}

#[tauri::command]
fn cmd_get_pending(state: tauri::State<Arc<AppState>>) -> Vec<PairRequestView> {
    // Drop stale requests (>5 min old).
    state.inner().pending.lock().unwrap().retain(|_, p| {
        p.created.elapsed() < std::time::Duration::from_secs(300)
    });
    state
        .inner()
        .pending
        .lock()
        .unwrap()
        .values()
        .map(|p| PairRequestView {
            id: p.id.clone(),
            name: p.name.clone(),
            os: p.os.clone(),
            pin: p.pin.clone(),
        })
        .collect()
}

#[tauri::command]
fn cmd_approve_pair(state: tauri::State<Arc<AppState>>, id: String) -> Result<String, String> {
    let s = state.inner();
    let pending = s
        .pending
        .lock()
        .unwrap()
        .remove(&id)
        .ok_or_else(|| "no pending pairing request".to_string())?;
    let token = uuid::Uuid::new_v4().simple().to_string();
    s.store
        .add_trusted(TrustedDevice {
            id: pending.id.clone(),
            name: pending.name.clone(),
            fp: pending.fp.clone(),
            os: pending.os.clone(),
            token: token.clone(),
            paired_at: Store::paired_now(),
        })
        .map_err(|e| format!("save failed: {e}"))?;
    s.mark_trusted_in_peers();
    s.emit_peers();
    s.log(format!("Paired with '{}'", pending.name));
    Ok(token)
}

#[tauri::command]
fn cmd_reject_pair(state: tauri::State<Arc<AppState>>, id: String) -> Result<(), String> {
    let s = state.inner();
    let pending = s
        .pending
        .lock()
        .unwrap()
        .remove(&id)
        .ok_or_else(|| "no pending pairing request".to_string())?;
    s.log(format!("Rejected pairing request from '{}'", pending.name));
    Ok(())
}

#[tauri::command]
fn cmd_unpair(state: tauri::State<Arc<AppState>>, id: String) -> Result<(), String> {
    let s = state.inner();
    let name = s
        .store
        .trusted
        .read()
        .unwrap()
        .get(&id)
        .map(|d| d.name.clone())
        .unwrap_or_else(|| id.clone());
    if s
        .store
        .remove_trusted(&id)
        .map_err(|e| format!("save failed: {e}"))?
    {
        s.mark_trusted_in_peers();
        s.emit_peers();
        s.log(format!("Unpaired '{name}'"));
        Ok(())
    } else {
        Err("device was not paired".into())
    }
}

/// Ask a discovered peer to pair with us (the peer shows the PIN dialog).
#[tauri::command]
async fn cmd_request_pair(
    state: tauri::State<'_, Arc<AppState>>,
    peer_id: String,
) -> Result<String, String> {
    let s = state.inner().clone();
    let status = tls_client::request_pair(&s, &peer_id).await?;
    s.log(format!("Pair request sent ({status})"));
    Ok(status)
}

#[tauri::command]
async fn cmd_poll_pair_status(
    state: tauri::State<'_, Arc<AppState>>,
    peer_id: String,
) -> Result<String, String> {
    let s = state.inner().clone();
    tls_client::poll_pair_status(&s, &peer_id).await
}

#[tauri::command]
async fn cmd_send_text(
    state: tauri::State<'_, Arc<AppState>>,
    peer_id: String,
    text: String,
    to_clipboard: bool,
) -> Result<(), String> {
    let s = state.inner().clone();
    tls_client::post_json(
        &s,
        &peer_id,
        "/api/text",
        serde_json::json!({"text": text, "clipboard": to_clipboard}),
    )
    .await?;
    s.log(format!("Sent text to peer ({} chars)", text.chars().count()));
    Ok(())
}

#[tauri::command]
async fn cmd_send_clipboard(
    state: tauri::State<'_, Arc<AppState>>,
    peer_id: String,
) -> Result<(), String> {
    let s = state.inner().clone();
    let text = clip::get_text()?;
    if text.is_empty() {
        return Err("clipboard is empty".into());
    }
    tls_client::post_json(&s, &peer_id, "/api/clipboard", serde_json::json!({"text": text}))
        .await?;
    s.log("Sent clipboard to peer");
    Ok(())
}

#[tauri::command]
fn cmd_get_local_clipboard() -> Result<String, String> {
    clip::get_text()
}

#[tauri::command]
async fn cmd_send_file(
    app: tauri::AppHandle,
    state: tauri::State<'_, Arc<AppState>>,
    peer_id: String,
) -> Result<Vec<String>, String> {
    use tauri_plugin_dialog::{DialogExt, FilePath};
    let s = state.inner().clone();
    let picked = tauri::async_runtime::spawn_blocking(move || {
        app.dialog().file().blocking_pick_files()
    })
    .await
    .map_err(|e| format!("dialog failed: {e}"))?;
    let paths: Vec<std::path::PathBuf> = picked
        .unwrap_or_default()
        .into_iter()
        .filter_map(|p| match p {
            FilePath::Path(pb) => Some(pb),
            FilePath::Url(u) => u.to_file_path().ok(),
        })
        .collect();
    if paths.is_empty() {
        return Ok(vec![]);
    }
    let mut started = Vec::new();
    for path in paths {
        let name = path
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("file")
            .to_string();
        let s2 = s.clone();
        let pid = peer_id.clone();
        tauri::async_runtime::spawn(async move {
            if let Err(e) = transfer::send_file(s2.clone(), &pid, path).await {
                s2.log(format!("Send failed: {e}"));
            }
        });
        started.push(name);
    }
    Ok(started)
}

/// Send files by absolute path (used for drag-and-drop from the webview).
#[tauri::command]
async fn cmd_send_paths(
    state: tauri::State<'_, Arc<AppState>>,
    peer_id: String,
    paths: Vec<String>,
) -> Result<Vec<String>, String> {
    let s = state.inner().clone();
    let mut started = Vec::new();
    for p in paths {
        let path = std::path::PathBuf::from(&p);
        if !path.is_file() {
            continue;
        }
        let name = path
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("file")
            .to_string();
        let s2 = s.clone();
        let pid = peer_id.clone();
        tauri::async_runtime::spawn(async move {
            if let Err(e) = transfer::send_file(s2.clone(), &pid, path).await {
                s2.log(format!("Send failed: {e}"));
            }
        });
        started.push(name);
    }
    Ok(started)
}

#[tauri::command]
async fn cmd_system_action(
    state: tauri::State<'_, Arc<AppState>>,
    peer_id: String,
    action: String,
) -> Result<(), String> {
    let s = state.inner().clone();
    tls_client::post_json(
        &s,
        &peer_id,
        "/api/system",
        serde_json::json!({"action": action}),
    )
    .await?;
    s.log(format!("Sent system action '{action}' to peer"));
    Ok(())
}

// ---------------------------------------------------------------------------
// Background tasks
// ---------------------------------------------------------------------------

/// Watches the local clipboard and pushes changes to all online trusted peers
/// when auto-sync is enabled.
async fn clipboard_watcher(state: Arc<AppState>) {
    let mut last = String::new();
    loop {
        tokio::time::sleep(Duration::from_millis(800)).await;
        if !state.store.settings.read().unwrap().auto_sync_clipboard {
            continue;
        }
        let text = match clip::get_text() {
            Ok(t) => t,
            Err(_) => continue,
        };
        if text.is_empty() || text == last {
            continue;
        }
        // Loop guard: ignore changes that came from a peer moments ago.
        if let Some((remote, when)) = state.last_remote_clipboard.lock().unwrap().clone() {
            if remote == text && when.elapsed() < Duration::from_secs(5) {
                last = text;
                continue;
            }
        }
        last = text.clone();
        let peers: Vec<String> = state
            .peers
            .lock()
            .unwrap()
            .values()
            .filter(|p| p.trusted)
            .map(|p| p.id.clone())
            .collect();
        for id in peers {
            let _ = tls_client::post_json(
                &state,
                &id,
                "/api/clipboard",
                serde_json::json!({"text": text}),
            )
            .await;
        }
    }
}

// ---------------------------------------------------------------------------
// Tray
// ---------------------------------------------------------------------------

fn build_tray(app: &tauri::AppHandle, state: &Arc<AppState>) -> anyhow::Result<()> {
    use tauri::menu::{Menu, MenuItem};
    use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};

    let icon = tauri::image::Image::from_bytes(include_bytes!("../icons/icon.png"))?;
    let show = MenuItem::with_id(app, "show", "Show VrShare", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&show, &quit])?;

    let state_c = state.clone();
    TrayIconBuilder::with_id("main")
        .icon(icon)
        .tooltip("VrShare")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(move |app, event| match event.id.as_ref() {
            "show" => {
                if let Some(w) = app.get_webview_window("main") {
                    let _ = w.show();
                    let _ = w.set_focus();
                }
            }
            "quit" => {
                state_c.log("Quitting VrShare");
                app.exit(0);
            }
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                let app = tray.app_handle();
                if let Some(w) = app.get_webview_window("main") {
                    let _ = w.show();
                    let _ = w.set_focus();
                }
            }
        })
        .build(app)?;
    Ok(())
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

fn data_dir() -> std::path::PathBuf {
    dirs::data_dir()
        .unwrap_or_else(std::env::temp_dir)
        .join("me.asimcoder.vrshare")
}

fn load_or_create_device_id(dir: &std::path::Path) -> String {
    let path = dir.join("device_id.txt");
    if let Ok(id) = std::fs::read_to_string(&path) {
        let id = id.trim().to_string();
        if !id.is_empty() {
            return id;
        }
    }
    let id = uuid::Uuid::new_v4().simple().to_string();
    let _ = std::fs::write(&path, &id);
    id
}

fn main() {
    // rustls needs a process-wide crypto provider (we use `ring` everywhere).
    // Must be installed before any TLS use (server or outgoing client).
    let _ = rustls::crypto::ring::default_provider().install_default();

    let dir = data_dir();
    let identity = cert::load_or_generate(&dir).expect("failed to init TLS identity");
    let store = Store::load(dir.join("store")).expect("failed to load store");
    let device_id = load_or_create_device_id(&dir);

    let state = Arc::new(AppState {
        device_id,
        fingerprint_hex: identity.fingerprint_hex,
        cert_der: identity.cert_der,
        key_der: identity.key_der,
        store,
        peers: std::sync::Mutex::new(HashMap::new()),
        instance_to_id: std::sync::Mutex::new(HashMap::new()),
        pending: std::sync::Mutex::new(HashMap::new()),
        transfers: std::sync::Mutex::new(HashMap::new()),
        last_remote_clipboard: std::sync::Mutex::new(None),
        app: std::sync::OnceLock::new(),
    });

    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .manage(state.clone())
        .setup({
            let state = state.clone();
            move |app| {
                let _ = state.app.set(app.handle().clone());

                // HTTPS API server.
                {
                    let s = state.clone();
                    tauri::async_runtime::spawn(async move {
                        if let Err(e) = server::serve(s.clone()).await {
                            s.log(format!("Server error: {e}"));
                            eprintln!("[vrshare] server error: {e}");
                        }
                    });
                }

                // mDNS discovery.
                discovery::run(state.clone());

                // Clipboard auto-sync watcher.
                {
                    let s = state.clone();
                    tauri::async_runtime::spawn(clipboard_watcher(s));
                }

                // System tray (may be unavailable headless - never let it
                // take the app down; the tray dependency panics when the
                // native tray library is missing).
                let tray_outcome = std::panic::catch_unwind(
                    std::panic::AssertUnwindSafe(|| build_tray(app.handle(), &state)),
                );
                match tray_outcome {
                    Ok(Ok(())) => state.log("System tray ready"),
                    Ok(Err(e)) => {
                        eprintln!("[vrshare] tray unavailable: {e}");
                        state.log(format!("System tray unavailable: {e}"));
                    }
                    Err(_) => {
                        eprintln!(
                            "[vrshare] tray unavailable (native tray library missing)"
                        );
                        state.log(
                            "System tray unavailable on this system - running without it",
                        );
                    }
                }

                // Hide to tray instead of quitting.
                if let Some(w) = app.get_webview_window("main") {
                    let w2 = w.clone();
                    w.on_window_event(move |event| {
                        if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                            api.prevent_close();
                            let _ = w2.hide();
                        }
                    });
                }

                state.log(format!(
                    "VrShare started - device id {}",
                    &state.device_id[..8.min(state.device_id.len())]
                ));
                Ok(())
            }
        })
        .invoke_handler(tauri::generate_handler![
            cmd_get_info,
            cmd_get_peers,
            cmd_set_device_name,
            cmd_set_settings,
            cmd_get_pending,
            cmd_approve_pair,
            cmd_reject_pair,
            cmd_unpair,
            cmd_request_pair,
            cmd_poll_pair_status,
            cmd_send_text,
            cmd_send_clipboard,
            cmd_get_local_clipboard,
            cmd_send_file,
            cmd_send_paths,
            cmd_system_action,
        ])
        .run(tauri::generate_context!())
        .expect("error while running VrShare");
}
