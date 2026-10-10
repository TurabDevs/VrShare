//! Persistent store: trusted (paired) devices and user settings.
//! Stored as JSON in the app data directory.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::RwLock;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TrustedDevice {
    pub id: String,
    pub name: String,
    pub fp: String,
    pub os: String,
    pub token: String,
    pub paired_at: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Settings {
    pub device_name: String,
    pub download_dir: String,
    pub auto_accept_files: bool,
    pub auto_sync_clipboard: bool,
}

impl Default for Settings {
    fn default() -> Self {
        let download_dir = dirs::download_dir()
            .map(|d| d.join("VrShare"))
            .unwrap_or_else(|| std::env::temp_dir().join("VrShare"));
        Self {
            device_name: default_device_name(),
            download_dir: download_dir.to_string_lossy().to_string(),
            auto_accept_files: true,
            auto_sync_clipboard: false,
        }
    }
}

fn default_device_name() -> String {
    std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .unwrap_or_else(|_| "VrShare-PC".to_string())
}

fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

pub struct Store {
    dir: PathBuf,
    pub trusted: RwLock<HashMap<String, TrustedDevice>>, // keyed by device id
    pub settings: RwLock<Settings>,
}

impl Store {
    pub fn load(dir: PathBuf) -> anyhow::Result<Self> {
        std::fs::create_dir_all(&dir)?;
        let trusted_path = dir.join("trusted.json");
        let settings_path = dir.join("settings.json");

        let trusted: HashMap<String, TrustedDevice> = std::fs::read(&trusted_path)
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_default();

        let mut settings: Settings = std::fs::read(&settings_path)
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_default();
        // Keep download dir valid even if the stored path was removed.
        if settings.download_dir.is_empty() {
            settings = Settings::default();
        }

        Ok(Self {
            dir,
            trusted: RwLock::new(trusted),
            settings: RwLock::new(settings),
        })
    }

    fn trusted_path(&self) -> PathBuf {
        self.dir.join("trusted.json")
    }

    fn settings_path(&self) -> PathBuf {
        self.dir.join("settings.json")
    }

    pub fn save_trusted(&self) -> anyhow::Result<()> {
        let map = self.trusted.read().unwrap();
        let data = serde_json::to_vec_pretty(&*map)?;
        std::fs::write(self.trusted_path(), data)?;
        Ok(())
    }

    pub fn save_settings(&self) -> anyhow::Result<()> {
        let s = self.settings.read().unwrap();
        let data = serde_json::to_vec_pretty(&*s)?;
        std::fs::write(self.settings_path(), data)?;
        Ok(())
    }

    pub fn add_trusted(&self, device: TrustedDevice) -> anyhow::Result<()> {
        self.trusted
            .write()
            .unwrap()
            .insert(device.id.clone(), device);
        self.save_trusted()
    }

    pub fn remove_trusted(&self, id: &str) -> anyhow::Result<bool> {
        let removed = self.trusted.write().unwrap().remove(id).is_some();
        if removed {
            self.save_trusted()?;
        }
        Ok(removed)
    }

    /// Returns the device id for a bearer token, if it belongs to a trusted device.
    pub fn id_for_token(&self, token: &str) -> Option<String> {
        self.trusted
            .read()
            .unwrap()
            .values()
            .find(|d| d.token == token)
            .map(|d| d.id.clone())
    }

    pub fn paired_now() -> u64 {
        now_secs()
    }
}
