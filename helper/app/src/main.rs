//! main.rs: PadLink Helper entry point: loads identity and settings, starts the link thread, and runs the tray.
#![cfg_attr(windows, windows_subsystem = "windows")]

mod adb;
mod link;
mod mouse;
mod pen;
mod store;
mod tether;
#[cfg(windows)]
mod tray;
#[cfg(windows)]
mod win;

/// Starts the helper on Windows.
#[cfg(windows)]
fn main() {
    use std::sync::atomic::Ordering;
    use std::sync::Arc;

    if !win::claim_single_instance() {
        win::error_box("PadLink Helper is already running. Look for its icon in the notification area.");
        return;
    }
    win::enable_dpi_awareness();
    let dir = store::data_dir();
    let config = store::load_config(&dir);
    let token = match store::load_or_create_token(&dir) {
        Ok(token) => token,
        Err(e) => {
            win::error_box(&format!("PadLink Helper cannot save its identity in {}:\n{e}", dir.display()));
            return;
        }
    };
    let name = std::env::var("COMPUTERNAME").unwrap_or_else(|_| "Windows PC".to_owned());
    let monitors = win::monitors();
    let target = win::pick_monitor(&monitors, config.monitor.as_deref())
        .map(|m| m.rect)
        .unwrap_or(pen::Rect { left: 0, top: 0, right: 0, bottom: 0 });

    let shared = Arc::new(link::Shared::new(config.mode, target));
    let link_shared = Arc::clone(&shared);
    let identity = link::Identity { token, name };
    let link_thread = std::thread::Builder::new()
        .name("padlink-link".into())
        .spawn(move || {
            let mut injector = win::WinInjector::new(target);
            link::run(&link_shared, &identity, &mut injector, &win::show_pair_code);
        });
    let link_thread = match link_thread {
        Ok(handle) => handle,
        Err(e) => {
            win::error_box(&format!("PadLink Helper cannot start: {e}"));
            return;
        }
    };

    if let Err(e) = tray::run(Arc::clone(&shared), dir, config) {
        shared.stop.store(true, Ordering::Relaxed);
        win::error_box(&format!("PadLink Helper cannot show its tray icon:\n{e}"));
    }
    let _ = link_thread.join();
}

/// The helper injects Windows input and has no implementation elsewhere.
#[cfg(not(windows))]
fn main() {
    eprintln!("PadLink Helper runs on Windows only.");
    std::process::exit(1);
}
