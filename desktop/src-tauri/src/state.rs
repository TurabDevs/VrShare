//! Shared application state for the VrShare desktop app.

use serde::Serialize;
use std::collections::HashMap;
use std::sync::Mutex;
use std::time::Instant;

use crate::store::Store;

pub const SERVICE_TYPE: &str = "_vrshare._tcp.local.";
pub const PORT: u16 = 53417;
pub const PROTOCOL_VERSION: u8 = 1;

#[derive(Debug, Clone, Serialize)]
pub struct PeerInfo {
    pub id: String,
    pub name: String,
    pub fp: String,
    pub os: String,
    pub ip: String,
    pub port: u16,
    pub trusted: bool,
}

#[derive(Debug, Clone)]
pub struct PendingPair {
    pub id: String,
    pub name: String,
    pub os: String,
    pub fp: String,
    pub pin: String,
    pub created: Instant,
}

#[derive(Debug, Clone, Serialize)]
pub struct TransferProgress {
    pub id: String,
    pub name: String,
    pub received: u64,
    pub size: u64,
    pub done: bool,
    pub direction: String, // "down" | "up"
    pub peer: String,
}

pub struct AppState {
    pub device_id: String,
    pub fingerprint_hex: String,
    pub cert_der: Vec<u8>,
    pub key_der: Vec<u8>,
    pub store: Store,
    /// Discovered peers, keyed by device id.
    pub peers: Mutex<HashMap<String, PeerInfo>>,
    /// mDNS instance name -> device id (for removal events).
    pub instance_to_id: Mutex<HashMap<String, String>>,
    /// Pending pairing requests, keyed by device id.
    pub pending: Mutex<HashMap<String, PendingPair>>,
    /// Active transfers, keyed by transfer id.
    pub transfers: Mutex<HashMap<String, TransferProgress>>,
    /// Last text we pushed to the OS clipboard because a peer sent it
    /// (loop guard for the auto-sync watcher).
    pub last_remote_clipboard: Mutex<Option<(String, Instant)>>,
    /// Set once during Tauri setup; emit helpers no-op before that.
    pub app: std::sync::OnceLock<tauri::AppHandle>,
}

impl AppState {
    pub fn device_name(&self) -> String {
        self.store.settings.read().unwrap().device_name.clone()
    }

    pub fn is_trusted(&self, id: &str) -> bool {
        self.store.trusted.read().unwrap().contains_key(id)
    }

    pub fn mark_trusted_in_peers(&self) {
        let trusted_ids: Vec<String> = self
            .store
            .trusted
            .read()
            .unwrap()
            .keys()
            .cloned()
            .collect();
        let mut peers = self.peers.lock().unwrap();
        for p in peers.values_mut() {
            p.trusted = trusted_ids.contains(&p.id);
        }
    }

    pub fn peer_list(&self) -> Vec<PeerInfo> {
        let mut v: Vec<PeerInfo> = self.peers.lock().unwrap().values().cloned().collect();
        v.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));
        v
    }

    pub fn emit_peers(&self) {
        use tauri::Emitter;
        if let Some(app) = self.app.get() {
            let _ = app.emit("peers", self.peer_list());
        }
    }

    pub fn log(&self, msg: impl Into<String>) {
        use tauri::Emitter;
        if let Some(app) = self.app.get() {
            let _ = app.emit("log", msg.into());
        }
    }

    pub fn emit(&self, event: &str, payload: serde_json::Value) {
        use tauri::Emitter;
        if let Some(app) = self.app.get() {
            let _ = app.emit(event, payload);
        }
    }
}
