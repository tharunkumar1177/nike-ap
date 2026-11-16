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


