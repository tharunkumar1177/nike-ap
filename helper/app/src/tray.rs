//! tray.rs: notification-area icon with status, USB mode choice, pen monitor choice, and Quit.

use crate::link::{Shared, Status};
use crate::store::{save_config, HelperConfig, Mode};
use crate::win::{monitors, pick_monitor, Monitor};
use std::path::{Path, PathBuf};
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tray_icon::menu::{CheckMenuItem, Menu, MenuEvent, MenuItem, PredefinedMenuItem, Submenu};
use tray_icon::{Icon, TrayIconBuilder};
use windows_sys::Win32::UI::WindowsAndMessaging::{DispatchMessageW, PeekMessageW, TranslateMessage, MSG, PM_REMOVE};

const ICON_SIZE: u32 = 32;
const STATUS_REFRESH: Duration = Duration::from_millis(250);
const MONITOR_REFRESH: Duration = Duration::from_secs(3);
const FRAME: Duration = Duration::from_millis(15);

/// Draws the tray icon: a trackpad-blue rounded square with a light pen dot (RGBA, `size`x`size`).
pub fn icon_rgba(size: u32) -> Vec<u8> {
    let s = size as f32;
    let radius = s * 0.22;
    let mut out = Vec::with_capacity((size * size * 4) as usize);
    for y in 0..size {
        for x in 0..size {
            let (fx, fy) = (x as f32 + 0.5, y as f32 + 0.5);
            let cx = fx.clamp(radius, s - radius);
            let cy = fy.clamp(radius, s - radius);
            let inside = (fx - cx).powi(2) + (fy - cy).powi(2) <= radius * radius;
            let dot = (fx - s * 0.62).powi(2) + (fy - s * 0.62).powi(2) <= (s * 0.16).powi(2);
            let pixel = match (inside, dot) {
                (true, true) => [0xCF, 0xE5, 0xFF, 0xFF],
                (true, false) => [0x00, 0x4B, 0x70, 0xFF],
                _ => [0, 0, 0, 0],
            };
            out.extend_from_slice(&pixel);
        }
    }
    out
}

/// Tray text for a status and mode.
pub fn status_text(status: &Status, mode: Mode) -> String {
    let via = match mode {
        Mode::Adb => "USB (ADB)",
        Mode::Tether => "USB (Tethering)",
    };
    match status {
        Status::Searching(reason) if reason.is_empty() => format!("Looking for the phone over {via}"),
        Status::Searching(reason) => format!("Looking for the phone over {via}: {reason}"),
        Status::Pairing(code) => format!("Approve code {:03} {:03} on the phone", code / 1000, code % 1000),
        Status::Connected => format!("Connected over {via}"),
        Status::Rejected => "The phone denied this computer".to_owned(),
        Status::VersionMismatch => "Update PadLink on the phone and the helper".to_owned(),
    }
}

/// Menu label for a monitor.
fn monitor_label(index: usize, m: &Monitor) -> String {
    let primary = if m.primary { " (primary)" } else { "" };
    format!("Display {}: {}x{}{}", index + 1, m.rect.width(), m.rect.height(), primary)
}

/// Monitor submenu entries.
struct MonitorMenu {
    submenu: Submenu,
    items: Vec<(CheckMenuItem, Monitor)>,
}

impl MonitorMenu {
    /// Replaces the entries with `list`, checking `selected`.
    fn rebuild(&mut self, list: &[Monitor], selected: &str) -> Result<(), String> {
        for (item, _) in self.items.drain(..) {
            self.submenu.remove(&item).map_err(|e| e.to_string())?;
        }
        for (i, m) in list.iter().enumerate() {
            let item = CheckMenuItem::new(monitor_label(i, m), true, m.name == selected, None);
            self.submenu.append(&item).map_err(|e| e.to_string())?;
            self.items.push((item, m.clone()));
        }
        Ok(())
    }
}

/// Points the pen at `monitor` and checks its menu entry; `persist` stores it as the user's choice.
fn select_monitor(shared: &Shared, menu: &MonitorMenu, monitor: &Monitor, persist: Option<(&mut HelperConfig, &Path)>) {
    *shared.target.lock().unwrap_or_else(|e| e.into_inner()) = monitor.rect;
    shared.target_generation.fetch_add(1, Ordering::Relaxed);
    if let Some((config, dir)) = persist {
        config.monitor = Some(monitor.name.clone());
        let _ = save_config(dir, config);
    }
    for (item, m) in &menu.items {
        item.set_checked(m.name == monitor.name);
    }
}

/// Drains pending window messages for the tray icon.
fn pump_messages() {
    // SAFETY: MSG is plain data and the loop follows the standard PeekMessage pattern.
    unsafe {
        let mut msg: MSG = std::mem::zeroed();
        while PeekMessageW(&mut msg, std::ptr::null_mut(), 0, 0, PM_REMOVE) != 0 {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
    }
}

/// Runs the tray until Quit; sets `shared.stop` before returning.
pub fn run(shared: Arc<Shared>, dir: PathBuf, mut config: HelperConfig) -> Result<(), String> {
    let status_item = MenuItem::new("Starting…", false, None);
    let adb_item = CheckMenuItem::new("USB (ADB)", true, config.mode == Mode::Adb, None);
    let tether_item = CheckMenuItem::new("USB (Tethering)", true, config.mode == Mode::Tether, None);
    let mut monitor_menu = MonitorMenu { submenu: Submenu::new("Pen display", true), items: Vec::new() };
    let quit_item = MenuItem::new("Quit", true, None);

    let menu = Menu::new();
    menu.append_items(&[
        &status_item,
        &PredefinedMenuItem::separator(),
        &adb_item,
        &tether_item,
        &monitor_menu.submenu,
        &PredefinedMenuItem::separator(),
        &quit_item,
    ])
    .map_err(|e| e.to_string())?;

    let mut list = monitors();
    let mut selected = pick_monitor(&list, config.monitor.as_deref()).map(|m| m.name.clone()).unwrap_or_default();
    monitor_menu.rebuild(&list, &selected)?;

    let icon = Icon::from_rgba(icon_rgba(ICON_SIZE), ICON_SIZE, ICON_SIZE).map_err(|e| e.to_string())?;
    let tray = TrayIconBuilder::new()
        .with_menu(Box::new(menu))
        .with_tooltip("PadLink Helper")
        .with_icon(icon)
        .build()
        .map_err(|e| e.to_string())?;

    let mut last_status = String::new();
    let mut last_status_check = Instant::now();
    let mut last_monitor_check = Instant::now();
    let events = MenuEvent::receiver();
    loop {
        pump_messages();
        while let Ok(event) = events.try_recv() {
            if event.id == *quit_item.id() {
                shared.stop.store(true, Ordering::Relaxed);
                return Ok(());
            }
            let new_mode = if event.id == *adb_item.id() {
                Some(Mode::Adb)
            } else if event.id == *tether_item.id() {
                Some(Mode::Tether)
            } else {
                None
            };
            if let Some(mode) = new_mode {
                adb_item.set_checked(mode == Mode::Adb);
                tether_item.set_checked(mode == Mode::Tether);
                if mode != config.mode {
                    config.mode = mode;
                    *shared.mode.lock().unwrap_or_else(|e| e.into_inner()) = mode;
                    shared.mode_generation.fetch_add(1, Ordering::Relaxed);
                    let _ = save_config(&dir, &config);
                }
                continue;
            }
            let chosen = monitor_menu.items.iter().find(|(item, _)| event.id == *item.id()).map(|(_, m)| m.clone());
            if let Some(m) = chosen {
                selected = m.name.clone();
                select_monitor(&shared, &monitor_menu, &m, Some((&mut config, dir.as_path())));
            }
        }

        if last_status.is_empty() || last_status_check.elapsed() >= STATUS_REFRESH {
            last_status_check = Instant::now();
            let status = shared.status.lock().unwrap_or_else(|e| e.into_inner()).clone();
            let text = status_text(&status, config.mode);
            if text != last_status {
                status_item.set_text(&text);
                let tip: String = format!("PadLink Helper: {text}").chars().take(120).collect();
                let _ = tray.set_tooltip(Some(tip));
                last_status = text;
            }
        }

        if last_monitor_check.elapsed() >= MONITOR_REFRESH {
            last_monitor_check = Instant::now();
            let fresh = monitors();
            if fresh != list {
                list = fresh;
                let preferred = config.monitor.clone().unwrap_or_else(|| selected.clone());
                if let Some(m) = pick_monitor(&list, Some(&preferred)).cloned() {
                    selected = m.name.clone();
                    monitor_menu.rebuild(&list, &selected)?;
                    select_monitor(&shared, &monitor_menu, &m, None);
                } else {
                    monitor_menu.rebuild(&list, &selected)?;
                }
            }
        }

        std::thread::sleep(FRAME);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The icon has the right size, transparent corners, and an opaque center.
    #[test]
    fn icon_shape() {
        let px = icon_rgba(32);
        assert_eq!(px.len(), 32 * 32 * 4);
        assert_eq!(px[3], 0);
        let center = ((16 * 32 + 8) * 4) as usize;
        assert_eq!(&px[center..center + 4], &[0x00, 0x4B, 0x70, 0xFF]);
    }

    /// Status texts include the code split in two groups and the active mode.
    #[test]
    fn status_texts() {
        assert_eq!(status_text(&Status::Pairing(42), Mode::Tether), "Approve code 000 042 on the phone");
        assert_eq!(status_text(&Status::Connected, Mode::Adb), "Connected over USB (ADB)");
        assert!(status_text(&Status::Searching(String::new()), Mode::Tether).contains("Tethering"));
    }
}
