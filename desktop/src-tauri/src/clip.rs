//! OS clipboard access via the `arboard` crate.
//!
//! A single process-wide Clipboard handle is kept alive for the whole app
//! lifetime. This matters on X11 where clipboard ownership dies with the
//! handle (on Windows/macOS the system owns the clipboard, but keeping one
//! handle is harmless and avoids re-open races).

use arboard::Clipboard;
use std::sync::{LazyLock, Mutex, MutexGuard};

static CLIPBOARD: LazyLock<Result<Mutex<Clipboard>, String>> = LazyLock::new(|| {
    Clipboard::new()
        .map(Mutex::new)
        .map_err(|e| format!("clipboard unavailable: {e}"))
});

fn shared() -> Result<MutexGuard<'static, Clipboard>, String> {
    match &*CLIPBOARD {
        Ok(m) => m.lock().map_err(|_| "clipboard lock poisoned".to_string()),
        Err(e) => Err(e.clone()),
    }
}

pub fn get_text() -> Result<String, String> {
    let mut cb = shared()?;
    cb.get_text()
        .map_err(|e| format!("clipboard read failed: {e}"))
}

pub fn set_text(text: &str) -> Result<(), String> {
    let mut cb = shared()?;
    cb.set_text(text)
        .map_err(|e| format!("clipboard write failed: {e}"))
}
