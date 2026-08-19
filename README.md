# OpenWatch (LJ736 / LY736D)

A minimal, local, no-cloud Android companion app for the generic "Apple Watch Ultra clone" smartwatch boards sold under many names — **LJ736(d)** motherboard / **JQ7011** CPU, also marketed as **T900**, **Y20**, **Ultra 8**, and paired with official apps like **LaxaFit Pro**, **FitPro**, or **HiWatch Plus**.

Those official apps require a cloud account, bundle a full ad-mediation SDK stack (AdColony, Vungle, IronSource, Mintegral, Pangle, Facebook SDK, Baidu Maps, WeChat SDK, Aliyun logging...), and upload your health data to a third-party server (`jusonsmart.com`) — for a device whose actual job is just showing notifications and the time.

OpenWatch replaces that entirely. It talks directly to the watch over Bluetooth LE using the reverse-engineered wire protocol, does everything on-device, and sends nothing anywhere.

Built by reverse-engineering the LaxaFit Pro APK (decompiled with jadx) and validating every command against real BLE traffic captured from the official app's own debug logging — not guesswork. See [Protocol notes](#protocol-notes) below.

## Features

**Working, confirmed against real hardware:**
- Persistent BLE connection — foreground service, auto-reconnect with backoff, survives reboot, battery-optimization exemption
- One-time pair/bind handshake (byte-exact match to the official app's traffic)
- Forward any Android notification to the watch, with app→icon-category mapping (WhatsApp, WeChat, QQ, Facebook, Twitter, Skype, Line, KakaoTalk, Instagram, LinkedIn, SMS)
- Clock sync
- "Find my watch" (buzzes it)
- Custom watch face: pick a photo, auto-center-crop to the correct aspect ratio with live preview, convert and push over BLE — protocol structure confirmed byte-exact against a real transfer (see **Known bugs**)
- Reset-pairing button (for after a watch factory reset)
- In-app error/connection log, no need for `adb logcat` to debug a stuck connection

**Implemented at the protocol level, not yet wired into the UI:**
- Battery level readback (standard BLE Battery Service — untested on hardware)
- Step goal push (protocol confirmed from source, no UI yet)

## Enabling the custom watch face

`app/src/main/res/raw/watch_font.bin` ships as an empty placeholder — everything except the custom watch face works fine without it. The real font/glyph asset is a proprietary resource belonging to LaxaFit Pro's font, so it isn't distributed here. To enable it yourself:

1. Install the official LaxaFit Pro app, pair it with your watch, and push any custom photo as a watch face once (Settings → Notifications → enable notification access isn't needed for this — just use their app's photo watch-face flow).
2. Pull the generated font file from your phone (no root needed):
   ```
   adb pull /sdcard/Android/data/com.legend.LaxasfiPro.app/other/WatchTheme/FONT_*.bin
   ```
3. Rename it to `watch_font.bin` and place it at `app/src/main/res/raw/watch_font.bin`, replacing the empty placeholder.
4. Rebuild. It's a fixed per-watch-model asset — pull it once, reuse it for every photo you push afterward.

## Known bugs / unfinished

- **Custom watch face renders as a blank black screen.** The full transfer protocol is verified correct: START/FILE/FINISH command sequence matches a real captured transfer exactly, the file format (font binary + 6-byte proprietary header + RGB565 pixel data run through the vendor's `rotatDerection` byte-reversal transform, ported 1:1 from their source) matches in length and structure, and the FINISH checksum is computed and accepted. Something more subtle — likely a pixel byte-order edge case — is still wrong. **This needs one more precise live-capture comparison to pin down.** PRs welcome.
- **DND sync and alarms are not implemented.** The DND encoding in the vendor's own source has what looks like a genuine bug/edge-case in its conditional byte-packing; alarms use a 5-byte bit-packed format with an ambiguous weekday-bitmask order and an unclear `num` field. Both need empirical testing against real hardware before shipping.
- **Only tested against one specific board** (`LJ736`, 240×296 display, protocol "algorithm 2" — confirmed by reading the values directly out of LaxaFit Pro's own local SQLite database). Other board variants may use different dimensions or a different algorithm branch (0/1/3 — see `WatchThemeTools.startFile()` in the decompiled source for what each does). The watch itself exposes a "read dial info" command (`cd0005200102 0000`, cmd `0x20`) that reports this per-device, but OpenWatch doesn't query it automatically yet — you'd need to hardcode your board's values.

## Hardware compatibility

If your watch pairs with **LaxaFit Pro**, **FitPro**, or **HiWatch Plus** and uses the same BLE UART service (`6e400001-b5a3-f393-e0a9-e50e24dcca9d`), this should work or be very close. Tags: `LY736D`, `LJ736`, `T900`, `Y20`, `Ultra 8`, Apple Watch Ultra clone, smartwatch, BLE reverse engineering.

## Building

Requires Android Studio / JDK 17 / Gradle 8. `minSdk` 26.

```
./gradlew assembleDebug
```

Sideload the resulting APK — no Play Store distribution, no signing infra needed for personal use.

## Protocol notes

The full protocol (packet framing, pairing handshake, notification push, clock sync, watch face transfer) was reverse-engineered by:
1. Decompiling the LaxaFit Pro APK with `jadx`
2. Sniffing real BLE traffic via the app's own `CommandPool` debug logging (visible via `adb logcat`, no root needed)
3. Cross-referencing both against the app's local SQLite database (`/sdcard/Android/data/com.legend.LaxasfiPro.app/db/fitPro`) for per-device config (`ALGORITHM`, `WIDTH`, `HEIGHT`, etc.)

See `Protocol.kt` and `WatchfaceConverter.kt` for the fully-commented result.

## Credits

Built by [Mehran Shahmiri](https://mehranshahmiri.com/resources/).

## License

MIT — see [LICENSE](LICENSE).
