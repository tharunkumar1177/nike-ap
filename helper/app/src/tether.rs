//! tether.rs: finds the phone's address on the USB tethering (RNDIS/NCM) network adapter.

use std::fmt;

/// Why the tethering path is not ready.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TetherError {
    /// No up RNDIS/NCM adapter with an IPv4 gateway.
    NoAdapter,
    /// Adapter enumeration failed.
    Failed(String),
}

impl fmt::Display for TetherError {
    /// User-facing text shown in the tray tooltip.
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            TetherError::NoAdapter => write!(f, "turn on USB tethering on the phone"),
            TetherError::Failed(msg) => write!(f, "network lookup failed: {msg}"),
        }
    }
}

/// True when an adapter description or friendly name belongs to Android USB tethering
/// ("Remote NDIS based Internet Sharing Device", "UsbNcm Host Device", and similar).
pub fn is_tether_adapter(description: &str, friendly_name: &str) -> bool {
    [description, friendly_name].iter().any(|s| {
        let s = s.to_ascii_lowercase();
        s.contains("remote ndis") || s.contains("rndis") || s.contains("usbncm") || s.contains("ncm host")
    })
}

/// IPv4 gateway (the phone) of the first up tethering adapter.
#[cfg(windows)]
pub fn gateway() -> Result<std::net::SocketAddr, TetherError> {
    use ipconfig::OperStatus;
    use std::net::IpAddr;
    let adapters = ipconfig::get_adapters().map_err(|e| TetherError::Failed(e.to_string()))?;
    adapters
        .iter()
        .filter(|a| a.oper_status() == OperStatus::IfOperStatusUp)
        .filter(|a| is_tether_adapter(a.description(), a.friendly_name()))
        .find_map(|a| a.gateways().iter().find(|g| matches!(g, IpAddr::V4(_))).copied())
        .map(|ip| std::net::SocketAddr::new(ip, padlink_protocol::PORT))
        .ok_or(TetherError::NoAdapter)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// RNDIS and NCM tethering adapters match; Wi-Fi, Ethernet, and virtual adapters do not.
    #[test]
    fn matches_tether_adapters() {
        assert!(is_tether_adapter("Remote NDIS based Internet Sharing Device", "Ethernet 3"));
        assert!(is_tether_adapter("UsbNcm Host Device", "Ethernet 4"));
        assert!(is_tether_adapter("Intel(R) Ethernet", "RNDIS phone"));
        assert!(!is_tether_adapter("Intel(R) Wi-Fi 6E AX211 160MHz", "Wi-Fi"));
        assert!(!is_tether_adapter("Realtek PCIe GbE Family Controller", "Ethernet"));
        assert!(!is_tether_adapter("Hyper-V Virtual Ethernet Adapter", "vEthernet (WSL)"));
    }
}
