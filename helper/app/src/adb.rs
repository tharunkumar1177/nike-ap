//! adb.rs: locates adb, picks the connected phone, and forwards the PadLink port over USB.

use padlink_protocol::PORT;
use std::fmt;
use std::net::{Ipv4Addr, SocketAddr};
use std::path::{Path, PathBuf};
use std::process::Command;

/// Why the ADB path is not ready.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AdbError {
    /// No adb executable next to the helper or on PATH.
    NotFound,
    /// adb is present but no phone is attached.
    NoDevice,
    /// The phone has not authorized this computer for USB debugging.
    Unauthorized,
    /// adb ran but failed.
    Failed(String),
}

impl fmt::Display for AdbError {
    /// User-facing text shown in the tray tooltip.
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            AdbError::NotFound => write!(f, "adb not found"),
            AdbError::NoDevice => write!(f, "no phone on USB (enable USB debugging)"),
            AdbError::Unauthorized => write!(f, "allow USB debugging on the phone"),
            AdbError::Failed(msg) => write!(f, "adb failed: {msg}"),
        }
    }
}

/// One line of `adb devices`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Device {
    /// Device serial.
    pub serial: String,
    /// State such as `device`, `unauthorized`, or `offline`.
    pub state: String,
}

/// Parses `adb devices` output, skipping the header and daemon messages.
pub fn parse_devices(output: &str) -> Vec<Device> {
    output
        .lines()
        .map(str::trim)
        .filter(|l| !l.is_empty() && !l.starts_with('*') && !l.starts_with("List of devices"))
        .filter_map(|l| {
            let mut parts = l.split_whitespace();
            let serial = parts.next()?;
            let state = parts.next()?;
            Some(Device { serial: serial.to_owned(), state: state.to_owned() })
        })
        .collect()
}

/// Chooses the device to use: the first authorized one, or the reason none is usable.
pub fn choose_device(devices: &[Device]) -> Result<&Device, AdbError> {
    if let Some(d) = devices.iter().find(|d| d.state == "device") {
        return Ok(d);
    }
    if devices.iter().any(|d| d.state == "unauthorized") {
        return Err(AdbError::Unauthorized);
    }
    Err(AdbError::NoDevice)
}

/// Finds adb next to the helper (bundled platform-tools) or on PATH.
pub fn find_adb() -> Option<PathBuf> {
    let exe_name = if cfg!(windows) { "adb.exe" } else { "adb" };
    if let Some(dir) = std::env::current_exe().ok().and_then(|p| p.parent().map(Path::to_path_buf)) {
        for candidate in [dir.join(exe_name), dir.join("platform-tools").join(exe_name)] {
            if candidate.is_file() {
                return Some(candidate);
            }
        }
    }
    let path = std::env::var_os("PATH")?;
    std::env::split_paths(&path).map(|d| d.join(exe_name)).find(|p| p.is_file())
}

/// Builds an adb command that never opens a console window.
fn command(adb: &Path) -> Command {
    #[allow(unused_mut)]
    let mut cmd = Command::new(adb);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    cmd
}

/// Runs adb with `args` and returns stdout, or the trimmed stderr as the failure.
fn run(adb: &Path, args: &[&str]) -> Result<String, AdbError> {
    let out = command(adb).args(args).output().map_err(|e| AdbError::Failed(e.to_string()))?;
    if out.status.success() {
        Ok(String::from_utf8_lossy(&out.stdout).into_owned())
    } else {
        Err(AdbError::Failed(String::from_utf8_lossy(&out.stderr).trim().to_owned()))
    }
}

/// Ensures `adb forward tcp:PORT tcp:PORT` for the attached phone and returns the local endpoint.
pub fn prepare_forward() -> Result<(SocketAddr, String), AdbError> {
    let adb = find_adb().ok_or(AdbError::NotFound)?;
    let devices = parse_devices(&run(&adb, &["devices"])?);
    let device = choose_device(&devices)?;
    let spec = format!("tcp:{PORT}");
    run(&adb, &["-s", &device.serial, "forward", &spec, &spec])?;
    Ok((SocketAddr::from((Ipv4Addr::LOCALHOST, PORT)), device.serial.clone()))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Header, daemon lines, and blank lines are skipped; states are kept.
    #[test]
    fn parses_devices_output() {
        let out = "* daemon not running; starting now at tcp:5037\n* daemon started successfully\n\
                   List of devices attached\nR5CW123ABC\tdevice\nemulator-5554\toffline\n\n";
        assert_eq!(
            parse_devices(out),
            vec![
                Device { serial: "R5CW123ABC".into(), state: "device".into() },
                Device { serial: "emulator-5554".into(), state: "offline".into() },
            ]
        );
    }

    /// The first authorized device wins; otherwise unauthorized beats no device.
    #[test]
    fn chooses_device() {
        let d = |s: &str, st: &str| Device { serial: s.into(), state: st.into() };
        assert_eq!(choose_device(&[d("a", "offline"), d("b", "device")]).unwrap().serial, "b");
        assert_eq!(choose_device(&[d("a", "unauthorized")]), Err(AdbError::Unauthorized));
        assert_eq!(choose_device(&[]), Err(AdbError::NoDevice));
    }
}
