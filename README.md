I couldn't find the cause in the code, and I need a few details from your test PC before I change anything.

**Why the code doesn't look like the problem:** USB ADB, USB tethering and Wi-Fi hotspot all run through the same server and pairing code on the phone, and the same connect and handshake code in the helper. The only differences are how the address is found: ADB forwards to `127.0.0.1`, tethering uses the USB network adapter, and hotspot uses the Wi-Fi adapter. Since Wi-Fi now works end to end, the shared code is fine. Whatever is blocking the two USB modes is in how this PC reaches the phone over USB.

**What I tried to check:** I ran read-only checks here, but this workspace is a Hyper-V virtual machine. It has no Bluetooth radio, no USB tethering adapter, and no `adb`, so I couldn't look at the PC you're testing on.

**Bluetooth:** The app's Classic HID code connects, adopts the host, and retries every 5 seconds. "Connected for a second, then Connecting..." means Windows accepts the link and then drops it. The phone isn't the one ending it. This usually has one of two causes:

1. **Old pairing on Windows.** If the phone was paired with this PC before PadLink registered as a mouse, Windows still treats it as a phone and drops the mouse connection. To fix it: open Windows Settings, then Bluetooth & devices, and remove the phone. On the phone, unpair the PC too. Then open PadLink, tap "Pair a new computer", and pair again from the PC side.
2. **Company Bluetooth policy.** Managed PCs (the Zscaler suggests yours is one) often allow Bluetooth audio but block Bluetooth keyboards and mice. That gives exactly this pattern: a brief connect, then an immediate drop.

If re-pairing doesn't fix it, please send:

- **Bluetooth:** after the drop, where does the phone appear in Windows Device Manager: under "Mice and other pointing devices", or under "Other devices" with a yellow warning icon? If you can, also send what the PC shows when you run this in PowerShell:
  ```powershell
  Get-PnpDevice -Class Bluetooth,HIDClass | Where-Object Status -ne 'OK' | Format-Table Status,FriendlyName
  ```
- **USB ADB:** the exact text in the helper's tray tooltip, and whether the phone ever showed the "Allow USB debugging?" prompt for this PC.
- **USB tethering:** the exact helper tray text, and the PadLink card text on the phone. In particular, does the phone show "No computer has reached this phone yet"?

Those messages will tell me whether the helper can't find the phone at all, or finds it and gets blocked.



