//! Mouse/keyboard injection via the `enigo` crate.
//! Used for the /api/input/* endpoints (phone acts as remote mouse/keyboard).

use enigo::{Button, Coordinate, Direction, Enigo, Key, Keyboard, Mouse, Settings};

fn enigo() -> Result<Enigo, String> {
    Enigo::new(&Settings::default()).map_err(|e| format!("input backend unavailable: {e}"))
}

pub fn move_rel(dx: i32, dy: i32) -> Result<(), String> {
    let mut e = enigo()?;
    e.move_mouse(dx, dy, Coordinate::Rel)
        .map_err(|e| format!("move failed: {e}"))
}

fn parse_button(s: &str) -> Result<Button, String> {
    match s.to_lowercase().as_str() {
        "left" => Ok(Button::Left),
        "right" => Ok(Button::Right),
        "middle" => Ok(Button::Middle),
        _ => Err(format!("unknown button: {s}")),
    }
}

pub fn click(button: &str, action: &str) -> Result<(), String> {
    let mut e = enigo()?;
    let b = parse_button(button)?;
    let dir = match action.to_lowercase().as_str() {
        "down" => Direction::Press,
        "up" => Direction::Release,
        "click" => Direction::Click,
        _ => return Err(format!("unknown click action: {action}")),
    };
    e.button(b, dir).map_err(|e| format!("click failed: {e}"))
}

pub fn scroll(dx: i32, dy: i32) -> Result<(), String> {
    let mut e = enigo()?;
    if dx != 0 {
        e.scroll(dx, enigo::Axis::Horizontal)
            .map_err(|e| format!("scroll failed: {e}"))?;
    }
    if dy != 0 {
        e.scroll(dy, enigo::Axis::Vertical)
            .map_err(|e| format!("scroll failed: {e}"))?;
    }
    Ok(())
}

fn parse_key(s: &str) -> Result<Key, String> {
    if s.chars().count() == 1 {
        return Ok(Key::Unicode(s.chars().next().unwrap()));
    }
    match s.to_lowercase().as_str() {
        "enter" | "return" => Ok(Key::Return),
        "tab" => Ok(Key::Tab),
        "backspace" => Ok(Key::Backspace),
        "escape" | "esc" => Ok(Key::Escape),
        "delete" | "del" => Ok(Key::Delete),
        "space" => Ok(Key::Space),
        "shift" => Ok(Key::Shift),
        "control" | "ctrl" => Ok(Key::Control),
        "alt" => Ok(Key::Alt),
        "up" => Ok(Key::UpArrow),
        "down" => Ok(Key::DownArrow),
        "left" => Ok(Key::LeftArrow),
        "right" => Ok(Key::RightArrow),
        "home" => Ok(Key::Home),
        "end" => Ok(Key::End),
        "pageup" => Ok(Key::PageUp),
        "pagedown" => Ok(Key::PageDown),
        "f1" => Ok(Key::F1),
        "f2" => Ok(Key::F2),
        "f3" => Ok(Key::F3),
        "f4" => Ok(Key::F4),
        "f5" => Ok(Key::F5),
        "f6" => Ok(Key::F6),
        "f7" => Ok(Key::F7),
        "f8" => Ok(Key::F8),
        "f9" => Ok(Key::F9),
        "f10" => Ok(Key::F10),
        "f11" => Ok(Key::F11),
        "f12" => Ok(Key::F12),
        _ => Err(format!("unknown key: {s}")),
    }
}

pub fn key(key: &str, action: &str) -> Result<(), String> {
    let mut e = enigo()?;
    let k = parse_key(key)?;
    let dir = match action.to_lowercase().as_str() {
        "down" => Direction::Press,
        "up" => Direction::Release,
        "press" => Direction::Click,
        _ => return Err(format!("unknown key action: {action}")),
    };
    e.key(k, dir).map_err(|e| format!("key failed: {e}"))
}

pub fn type_text(text: &str) -> Result<(), String> {
    let mut e = enigo()?;
    e.text(text).map_err(|e| format!("type failed: {e}"))
}
