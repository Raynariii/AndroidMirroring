# M0 feasibility spikes

Goal: prove or disprove the risky platform pieces before building the product. Each spike ends
as **works**, **works with workaround**, or **no**, with the device and Android version noted.

| # | Spike | Status |
|---|---|---|
| 1 | Self-ADB: app pairs with its own Wireless debugging and starts the helper; reactivation after reboot | Not started |
| 2 | Screen capture → MediaCodec H.264 → Wi-Fi → Mac hardware decode, with latency numbers | **Code ready (this branch)** |
| 3 | Screen off while mirroring (Android 12–16) | **Code ready (this branch)** |
| 4 | Real-time touch, scroll and key injection | **Code ready (this branch)** |
| 5 | Mirror IME: any-language text incl. CJK composition | Not started |
| 6 | Clipboard images from other apps readable by the helper | Not started |
| 7 | Programmatic hotspot from the helper | Not started |

Spikes 2–4 use a computer's `adb` to start the helper, so they can be tested before spike 1 exists.

## Running spikes 2–4

You need: an Android 11+ phone with USB debugging (or Wireless debugging) enabled, a Mac with
Xcode 16+, and both on the same Wi-Fi.

1. Build the APK: `cd android && ./gradlew :app:assembleDebug` (or download `androidmirroring-debug-apk` from the CI run).
2. Connect adb to the phone, then from the repo root: `scripts/run-helper.sh`. It prints the phone's IP and the exact Mac command.
3. On the Mac: `cd mac && swift run -c release MirrorSpike --host <phone-ip> --token <token>`.

In the Mac window:

| Input | Effect |
|---|---|
| Click / drag | Tap / swipe |
| Trackpad or wheel scroll | Scroll |
| Typing | Text (ASCII only in this spike), Return, Delete, arrows, Tab |
| Esc | Back |
| Cmd+1 / Cmd+2 / Cmd+3 | Home / Recent apps / Search |
| Cmd+L | Toggle phone screen off/on (mirroring should continue) |
| Cmd+K | Request a keyframe |

The window title and the Mac terminal show fps, bitrate, round-trip time and capture-to-receive
latency (p50/p95) once per second. Phone-side log lines are prefixed `phone:`.

Helper options (append to `scripts/run-helper.sh`): `max_size=1280`, `bit_rate=4000000`, `max_fps=30`.

## What to record

For each phone, fill a row (copy into `docs/compatibility.md` when results start coming in):

| Phone | Android | Capture path (from log) | Encoder | Latency p50/p95 @1920/8 Mbps | Screen off works? | Touch/scroll OK? | Rotation OK? | Notes |
|---|---|---|---|---|---|---|---|---|

Priority devices: a Pixel on Android 16, a Samsung Galaxy on One UI 7/8, a Xiaomi on HyperOS
(needs "USB debugging (Security settings)" for input injection).
