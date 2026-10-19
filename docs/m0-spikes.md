# M0 feasibility spikes

Goal: prove or disprove the risky platform pieces before building the product. Each spike ends
as **works**, **works with workaround**, or **no**, with the device and Android version noted.

| # | Spike | Status |
|---|---|---|
| 1 | Self-ADB: app pairs with its own Wireless debugging and starts the helper; reactivation after reboot | **Code ready** |
| 2 | Screen capture → MediaCodec H.264 → Wi-Fi → Mac hardware decode, with latency numbers | **Code ready** |
| 3 | Screen off while mirroring (Android 12–16) | **Code ready** |
| 4 | Real-time touch, scroll and key injection | **Code ready** |
| 5 | Mirror IME: any-language text incl. CJK composition | Not started |
| 6 | Clipboard images from other apps readable by the helper | Not started |
| 7 | Programmatic hotspot from the helper | Not started |

Spikes 2–4 can be started either from a computer's `adb` (below) or by the phone itself (spike 1).

## Running spike 1 (no computer)

1. Install the APK from CI on an Android 11+ phone and open **Android Mirroring (spike)**. Allow notifications.
2. Enable Developer options (Settings → About phone → tap Build number 7 times). Tap **Open Developer options** and turn on **Wireless debugging** (the phone must be on Wi-Fi).
3. Tap **Show pairing notification**. In Wireless debugging, tap **Pair device with pairing code**, pull down the notification shade, and type the 6-digit code into the notification. It should say **Paired**.
4. Back in the app, tap **Start helper**. The screen shows the exact `MirrorSpike` command for the Mac.
5. Restart the phone. A **Reconnect** notification should appear. Turn Wireless debugging back on (the Quick Settings tile is fastest), then tap **Start**.

What to record for spike 1: does pairing succeed first time; does the helper keep running after Wireless debugging turns off; does the reboot flow work; time from tapping Start to the Mac showing the screen.

The helper's own log is at `/data/local/tmp/androidmirroring-helper.log`.

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
| Typing | Text in whatever language the Mac keyboard is set to (non-ASCII goes through the phone clipboard, which is put back), Return, Delete, Tab |
| Arrow keys | Swipe in that direction |
| Option+arrow keys | Real arrow keys (move a text cursor) |
| Esc | Back |
| Cmd+1 / Cmd+2 / Cmd+3 | Home / Recent apps / Search |
| Cmd+L | Toggle phone screen off/on. The screen goes dark when a session starts (pass `--screen-on` to keep it lit), and the phone is kept awake until the Mac disconnects. |
| Cmd+K | Request a keyframe |

The window title and the Mac terminal show fps, bitrate, round-trip time and capture-to-receive
latency (p50/p95) once per second. Phone-side log lines are prefixed `phone:`.

Helper options (append to `scripts/run-helper.sh`): `max_size=1280`, `bit_rate=4000000`, `max_fps=30`.

## What to record

For each phone, fill a row (copy into `docs/compatibility.md` when results start coming in):

| Phone | Android | Self-start works? | Capture path (from log) | Encoder | Latency p50/p95 @1920/8 Mbps | Screen off works? | Touch/scroll OK? | Rotation OK? | Notes |
|---|---|---|---|---|---|---|---|---|---|

Priority devices: a Pixel on Android 16, a Samsung Galaxy on One UI 7/8, a Xiaomi on HyperOS
(needs "USB debugging (Security settings)" for input injection).
