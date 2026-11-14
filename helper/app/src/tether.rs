//! tether.rs: finds the phone's address as the gateway of the USB tethering (RNDIS/NCM) adapter or of the Wi-Fi adapter joined to the phone's hotspot.

use std::fmt;

/// Why the tethering or hotspot path is not ready.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TetherError {
    /// No up RNDIS/NCM adapter with an IPv4 gateway.
    NoAdapter,
    /// No up Wi-Fi adapter with an IPv4 gateway.
    NoWifi,
    /// Adapter enumeration failed.
    Failed(String),
}

impl fmt::Display for TetherError {
    /// User-facing text shown in the tray tooltip.
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            TetherError::NoAdapter => write!(f, "turn on USB tethering on the phone"),
            TetherError::NoWifi => write!(f, "connect this PC's Wi-Fi to the phone's hotspot"),
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

/// True for a physical Wi-Fi adapter: IEEE 802.11 interface type, excluding the Wi-Fi Direct and
/// hosted-network virtual adapters Windows creates on the same radio.
pub fn is_wifi_adapter(is_80211: bool, description: &str) -> bool {
    let d = description.to_ascii_lowercase();
    is_80211 && !d.contains("virtual") && !d.contains("wi-fi direct")
}

/// IPv4 gateway (the phone) of the first up tethering adapter.
#[cfg(windows)]
pub fn gateway() -> Result<std::net::SocketAddr, TetherError> {
    first_gateway(|a| is_tether_adapter(a.description(), a.friendly_name()), TetherError::NoAdapter)
}

/// IPv4 gateway of the first up Wi-Fi adapter; the phone when this PC is joined to its hotspot.
#[cfg(windows)]
pub fn hotspot_gateway() -> Result<std::net::SocketAddr, TetherError> {
    first_gateway(
        |a| is_wifi_adapter(a.if_type() == ipconfig::IfType::Ieee80211, a.description()),
        TetherError::NoWifi,
    )
}

/// IPv4 gateway of the first up adapter accepted by `accept`, on the protocol port; `missing` otherwise.
#[cfg(windows)]
fn first_gateway(
    accept: impl Fn(&ipconfig::Adapter) -> bool,
    missing: TetherError,
) -> Result<std::net::SocketAddr, TetherError> {
    use ipconfig::OperStatus;
    use std::net::IpAddr;
    let adapters = ipconfig::get_adapters().map_err(|e| TetherError::Failed(e.to_string()))?;
    adapters
        .iter()
        .filter(|a| a.oper_status() == OperStatus::IfOperStatusUp)
        .filter(|a| accept(a))
        .find_map(|a| a.gateways().iter().find(|g| matches!(g, IpAddr::V4(_))).copied())
        .map(|ip| std::net::SocketAddr::new(ip, padlink_protocol::PORT))
        .ok_or(missing)
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

    /// Physical 802.11 adapters match; Wi-Fi Direct and other virtual adapters and non-802.11 types do not.
    #[test]
    fn matches_wifi_adapters() {
        assert!(is_wifi_adapter(true, "Intel(R) Wi-Fi 6E AX211 160MHz"));
        assert!(is_wifi_adapter(true, "Realtek RTL8821CE 802.11ac PCIe Adapter"));
        assert!(!is_wifi_adapter(true, "Microsoft Wi-Fi Direct Virtual Adapter #2"));
        assert!(!is_wifi_adapter(true, "Microsoft Hosted Network Virtual Adapter"));
        assert!(!is_wifi_adapter(false, "Remote NDIS based Internet Sharing Device"));
        assert!(!is_wifi_adapter(false, "Realtek PCIe GbE Family Controller"));
    }
}
