//! store.rs: helper token and settings persisted under %APPDATA%\PadLink.

use padlink_protocol::{from_hex, to_hex, TOKEN_LEN};
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

/// Which USB path the helper uses.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Mode {
    /// `adb forward` over USB debugging.
    Adb,
    /// Direct TCP over the USB tethering network.
    Tether,
}

impl Mode {
    /// Persisted key.
    pub fn key(self) -> &'static str {
        match self {
            Mode::Adb => "adb",
            Mode::Tether => "tether",
        }
    }

    /// Parses a persisted key, defaulting to ADB.
    pub fn from_key(s: &str) -> Mode {
        if s.trim() == "tether" {
            Mode::Tether
        } else {
            Mode::Adb
        }
    }
}

/// Persisted helper settings.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HelperConfig {
    /// Selected USB path.
    pub mode: Mode,
    /// Device name of the pen target monitor (`\\.\DISPLAY1`); `None` means the primary monitor.
    pub monitor: Option<String>,
}

impl Default for HelperConfig {
    /// ADB on the primary monitor.
    fn default() -> Self {
        Self { mode: Mode::Adb, monitor: None }
    }
}

impl HelperConfig {
    /// Parses `key=value` lines; unknown keys are ignored.
    pub fn parse(text: &str) -> Self {
        let mut cfg = Self::default();
        for line in text.lines() {
            match line.split_once('=') {
                Some(("mode", v)) => cfg.mode = Mode::from_key(v),
                Some(("monitor", v)) if !v.trim().is_empty() => cfg.monitor = Some(v.trim().to_owned()),
                _ => {}
            }
        }
        cfg
    }

    /// Serializes to `key=value` lines.
    pub fn render(&self) -> String {
        format!("mode={}\nmonitor={}\n", self.mode.key(), self.monitor.as_deref().unwrap_or(""))
    }
}

/// `%APPDATA%\PadLink`, falling back to the working directory when APPDATA is unset.
pub fn data_dir() -> PathBuf {
    std::env::var_os("APPDATA").map(PathBuf::from).unwrap_or_else(|| PathBuf::from(".")).join("PadLink")
}

/// Loads the helper token, creating a random one on first run.
pub fn load_or_create_token(dir: &Path) -> io::Result<[u8; TOKEN_LEN]> {
    let path = dir.join("helper-token");
    if let Ok(text) = fs::read_to_string(&path) {
        if let Some(bytes) = from_hex(text.trim()) {
            if let Ok(token) = <[u8; TOKEN_LEN]>::try_from(bytes.as_slice()) {
                return Ok(token);
            }
        }
    }
    let mut token = [0u8; TOKEN_LEN];
    getrandom::fill(&mut token).map_err(|e| io::Error::other(e.to_string()))?;
    fs::create_dir_all(dir)?;
    fs::write(&path, to_hex(&token))?;
    Ok(token)
}

/// Loads settings, or defaults when missing or unreadable.
pub fn load_config(dir: &Path) -> HelperConfig {
    fs::read_to_string(dir.join("helper.conf")).map(|t| HelperConfig::parse(&t)).unwrap_or_default()
}

/// Saves settings.
pub fn save_config(dir: &Path, cfg: &HelperConfig) -> io::Result<()> {
    fs::create_dir_all(dir)?;
    fs::write(dir.join("helper.conf"), cfg.render())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A unique scratch directory under the system temp dir.
    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("padlink-test-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        dir
    }

    /// The token is created once and then reloaded unchanged.
    #[test]
    fn token_is_stable() {
        let dir = scratch("token");
        let first = load_or_create_token(&dir).unwrap();
        let second = load_or_create_token(&dir).unwrap();
        assert_eq!(first, second);
        assert_eq!(fs::read_to_string(dir.join("helper-token")).unwrap().len(), 32);
        let _ = fs::remove_dir_all(&dir);
    }

    /// A corrupt token file is replaced.
    #[test]
    fn corrupt_token_is_replaced() {
        let dir = scratch("corrupt");
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("helper-token"), "not hex").unwrap();
        let token = load_or_create_token(&dir).unwrap();
        assert_eq!(fs::read_to_string(dir.join("helper-token")).unwrap(), to_hex(&token));
        let _ = fs::remove_dir_all(&dir);
    }

    /// Settings round-trip and default sensibly.
    #[test]
    fn config_round_trip() {
        let cfg = HelperConfig { mode: Mode::Tether, monitor: Some(r"\\.\DISPLAY2".into()) };
        assert_eq!(HelperConfig::parse(&cfg.render()), cfg);
        assert_eq!(HelperConfig::parse("mode=adb\nmonitor=\n"), HelperConfig::default());
        assert_eq!(HelperConfig::parse("garbage"), HelperConfig::default());
    }
}
