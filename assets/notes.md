I'm working out how S Pen input shows up on the S25 Ultra: the stylus arrives as MotionEvents with a stylus tool type, carrying pressure, tilt, hover, and the side button. I'll need to cover those in the approach and update the plan to match.

I'm noting that the S25 Ultra and S26 Ultra dropped Bluetooth Air Actions, so the S Pen works only through the touch digitizer and the remote-button route is out. I'm also comparing the original app's pen drawing mode, which likely sends relative mouse input, against a Bluetooth HID pen digitizer with absolute positioning and pressure that Windows Ink can use, and I need to settle that descriptor choice now because re-pairing would otherwise be required.

I'm laying out the wired route, where a helper would use the Windows Pointer Injection API to feed in pen input with pressure, tilt, and hover so Windows Ink picks it up. I'm also keeping a plain relative pen-as-mouse mode as a fallback, matching the original pen drawing mode, and sorting out how absolute mapping, hover, and the side button would behave.

I'm now covering how the input path handles palm rejection: while the pen is hovering or touching, finger touches on the drawing area get ignored, and fingers only take over as a trackpad once the pen has been out of range for a while. For latency, I'm looking at pulling the historical samples from each MotionEvent, using unbuffered dispatch so stylus events aren't held back for vsync, and noting the panel samples touch at 240Hz while the display refreshes at 120Hz.

I'm now looking at the latency side of the phone-to-PC link: local ink preview via a front-buffered renderer and motion prediction to fill the trail. On the Bluetooth side, I'm checking that pen reports at roughly 133Hz fit within the HID interval, coalescing to the newest sample when the queue backs up, and requesting high connection priority.

I'm now considering the remaining input-environment details: edge-to-edge layout with system gesture exclusion on the trackpad edges so back swipes don't interrupt drawing, and whether landscape orientation with letterboxing to a 16:9 monitor gives a usable drawing surface.

I'm checking the Samsung-specific bits now: the S Pen Remote SDK and the older Pen SDK are both deprecated, and with Bluetooth dropped on the S25 and S26 Ultra, standard Android APIs are enough. Hover should arrive as stylus hover events within about a centimeter of the screen, though I still need to confirm on a device whether the Air view setting affects delivery.

I'm weighing whether the S25 Ultra's S Pen tilt is actually reported to apps, since I recall that being inconsistent on Galaxy phones and not sure about it, so I'll have the helper send tilt only if the device reports it and verify on hardware. I also want to see how the original app's PointerPathView and its pen drawing mode handle the trail, so I'll take a quick look at that code before deciding on the first design question about absolute digitizer mapping.

I'm deciding between absolute and relative pen mapping, plus whether to show a faint local trail on the phone, and I'll default to both modes with absolute as the default. I'll update the plan with that and check how the original app's PointerPathView draws its pen trail.

I'm checking the pen input mode, which is relative mouse with the pen likely acting as a held left button and no pressure, and noting that a true digitizer would do better. I'm also confirming the defaults: mouse buttons at the bottom, left and right visible, scroll bar on the right. Next I need to read the plan file, add an "S Pen and Galaxy S Ultra" section with todos, and then explain the changes.

-----------


The original (Dec App) app's pen mode only moves a relative pointer while the left button is held, and the on-screen trail is a cosmetic fade with no pressure or absolute positioning.

----------------

The pen can work seamlessly if the phone presents itself to the PC as a real pen tablet, not a mouse. Windows then sees a native Windows Ink pen with hover, pressure and the side button, the way it does with a Wacom tablet. The original app's "Pen drawing mode" doesn't do this: it only moves a relative mouse pointer with the left button held, and the trail you see on the phone is cosmetic.

I've added this to the plan. Nothing is built yet; plan mode is still on.

**Telling pen from fingers.** Android labels every touch point as pen, eraser or finger, so the app can route each one separately. From the pen we get:
- the tip touching the screen;
- hover, so the cursor follows the pen while it's about 1 cm above the screen;
- the side button;
- pressure, at 4096 levels;
- tilt, if the phone reports it.

**Palm rejection.** While the pen is near or touching the screen, finger touches are ignored. They become the trackpad again shortly after the pen leaves, so one hand can draw while the other scrolls or clicks.

**Latency.** The app asks Android for the raw 240 Hz pen samples instead of the usual once-per-frame batches, and sends every sample, not just the latest.

**Getting pen data to the PC:**
- **Bluetooth:** I'd add a standard HID pen report to the device description the phone registers at pairing. Windows supports these natively. This needs deciding now, because PCs remember that description when they pair; changing it later means everyone has to pair again.
- **USB:** the Rust helper creates a virtual Windows pen and feeds it the samples, using the pointer-injection API that's been in Windows since 2018. If the cable is pulled it lifts the pen, so strokes never get stuck.

**Two pen modes:**
- **Tablet (the default):** the drawing area maps to the PC screen or a chosen monitor, with the aspect ratio kept so lines don't stretch.
- **Pointer:** the original behaviour, where the pen moves the cursor like a mouse.

**What you see on the phone.** A fast, short-lived ink trail shows where the pen is while you watch the PC. The drawing surface goes landscape and full screen.

**Tuning for Galaxy S Ultra:**
- Run at the panel's 120 Hz.
- Block the edge swipe-back gesture over the trackpad and drawing area, so strokes near the edge don't trigger it.
- Keep the screen on in pen mode, and size layouts for the 6.8–6.9 inch QHD+ display.
- Show a hint card about Samsung settings that interfere: the Air command floating icon, Air view, and Edge panels.

Some limits to know:
- **No Samsung pen SDK.** It's deprecated, and the S25/S26 Ultra pens have no Bluetooth, so remote pen gestures aren't possible. Everything uses standard Android touch input.
- **Pressure is Windows-only.** macOS doesn't handle generic Bluetooth pens well, so you get no pressure there.
- **Tilt and hover need checking on a real S Ultra.** I'll mark them unverified until we test on one.

The plan now has five new tasks (pen input, the Bluetooth pen report, USB pen injection, the pen screen, and S Ultra tuning) and a `stylus-pen.md` blueprint. If you're happy with Tablet as the default pen mode, approve the plan and I'll start building.

---



No. Reflashing the HP dongle wouldn't help, because the problem is the radio hardware, not the firmware.

**Why reflashing doesn't fix it:** The phone can only transmit Wi-Fi and Bluetooth. Most HP wireless mouse dongles use cheap 2.4 GHz chips such as the Nordic nRF24LU1+, Beken BK24xx/BK25xx, or similar. Those radios only speak simple private formats like Nordic's "Enhanced ShockBurst". They can't receive Wi-Fi at all. They can't hold a proper Bluetooth Low Energy (BLE) connection either, which needs frequency hopping on a fixed schedule and specific acknowledgements. New firmware changes what the chip does with the packets it receives. It can't change what the radio is physically able to receive.

**Other obstacles, even if the radio could do it:**
- The firmware is usually read-protected, so there's nothing to modify. You'd have to write a complete replacement, and often there's no public toolchain for the chip.
- Only one family is well documented: Logitech's nRF24LU1+ dongles, which security researchers reflashed (the "MouseJack" work). Even there, the result still uses the nRF24 radio format, not BLE.
- Some newer dongles use Telink chips that do support BLE in hardware. But that depends on the exact chip, the SDKs are obscure, and you could easily brick the dongle.

**If you're curious which chip yours has:** with the dongle plugged in, run this in PowerShell and send me the `VID_xxxx&PID_xxxx` part:

```powershell
Get-PnpDevice -PresentOnly | Where-Object { $_.InstanceId -like 'USB\VID_*' } | Format-Table FriendlyName, InstanceId -AutoSize
```

That identifies the chip maker. I'd still expect it to be a chip that can't receive from a phone.

**What works:** a board that already has BLE and native USB, such as the Nordic nRF52840 USB dongle (about $10) or an ESP32-S3 stick. It does exactly what you want from the HP dongle. It plugs into the PC, shows up as a normal USB mouse and pen, and receives the signal from the app. It's designed to be reflashed, so the firmware we'd write is fully under our control. And it avoids Zscaler, the helper, and the Windows Bluetooth policy.

If you want to go that way, tell me which board you can get and I'll plan the receiver mode.



---


**If it still waits after switching**
Hover over the tray icon and check the status text:
- **"turn on USB tethering on the phone":** Windows has no active tethering network adapter. Open Device Manager and look under "Network adapters" for "Remote NDIS…" or "UsbNcm…". If it's missing or has a warning icon, the USB tethering driver needs installing. Unplugging and replugging the cable or toggling tethering off and on can also bring it up.
- **"start PadLink on the phone and pick this USB mode":** the adapter was found but connecting failed. Make sure the phone's transport picker says **USB (Tethering)** and the app is open. Then run `ipconfig` and check that the Remote NDIS/NCM adapter shows a Default Gateway. That gateway address is the phone, and the helper connects to it.

I couldn't check this from here. This machine is a Hyper-V virtual machine with no phone attached, and the helper has never run on it. So I'm going by how the code behaves, not by your PC's state. If you send me the tray status text, I can narrow it down.

automatic behavior: use tethering whenever a tethering adapter is present, otherwise ADB, with the tray choice as an override. 


