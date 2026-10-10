//! System power actions: lock / sleep / shutdown / restart.
//!
//! Windows uses direct Win32 FFI declarations (user32/powrprof) so the code
//! does not depend on windows-rs metadata quirks. On other OSes a small
//! fallback is provided (lock via `loginctl`); everything else reports
//! "unsupported".

#[cfg(windows)]
mod imp {
    #[link(name = "user32")]
    unsafe extern "system" {
        fn LockWorkStation() -> i32;
        fn ExitWindowsEx(uFlags: u32, dwReason: u32) -> i32;
    }

    #[link(name = "powrprof")]
    unsafe extern "system" {
        fn SetSuspendState(bHibernate: i32, bForce: i32, bWakeupEventsDisabled: i32) -> i32;
    }

    const EWX_SHUTDOWN: u32 = 0x00000001;
    const EWX_REBOOT: u32 = 0x00000002;
    const EWX_FORCE: u32 = 0x00000004;

    pub fn lock() -> Result<(), String> {
        // SAFETY: LockWorkStation takes no parameters and has no preconditions.
        if unsafe { LockWorkStation() } != 0 {
            Ok(())
        } else {
            Err("LockWorkStation failed".to_string())
        }
    }

    pub fn sleep() -> Result<(), String> {
        // SAFETY: plain FFI call with integer args, no pointers involved.
        if unsafe { SetSuspendState(0, 1, 0) } != 0 {
            Ok(())
        } else {
            Err("SetSuspendState failed".to_string())
        }
    }

    pub fn shutdown() -> Result<(), String> {
        // SAFETY: plain FFI call with integer args, no pointers involved.
        if unsafe { ExitWindowsEx(EWX_SHUTDOWN | EWX_FORCE, 0) } != 0 {
            Ok(())
        } else {
            Err("shutdown failed - the app may need the shutdown privilege".to_string())
        }
    }

    pub fn restart() -> Result<(), String> {
        // SAFETY: plain FFI call with integer args, no pointers involved.
        if unsafe { ExitWindowsEx(EWX_REBOOT | EWX_FORCE, 0) } != 0 {
            Ok(())
        } else {
            Err("restart failed - the app may need the shutdown privilege".to_string())
        }
    }
}

#[cfg(not(windows))]
mod imp {
    pub fn lock() -> Result<(), String> {
        let status = std::process::Command::new("loginctl")
            .arg("lock-session")
            .status()
            .map_err(|e| format!("loginctl not available: {e}"))?;
        if status.success() {
            Ok(())
        } else {
            Err("loginctl lock-session failed".to_string())
        }
    }

    pub fn sleep() -> Result<(), String> {
        Err("sleep is not supported on this OS".to_string())
    }

    pub fn shutdown() -> Result<(), String> {
        Err("shutdown is not supported on this OS".to_string())
    }

    pub fn restart() -> Result<(), String> {
        Err("restart is not supported on this OS".to_string())
    }
}

pub fn run(action: &str) -> Result<(), String> {
    match action.to_lowercase().as_str() {
        "lock" => imp::lock(),
        "sleep" => imp::sleep(),
        "shutdown" => imp::shutdown(),
        "restart" => imp::restart(),
        other => Err(format!("unknown system action: {other}")),
    }
}
