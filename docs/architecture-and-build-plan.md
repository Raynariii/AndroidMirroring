# Android Mirroring for Mac: Architecture and Build Plan

Status: draft v1 (2026-10-19). Source: the product spec posted in the project chat.
Stack default (not yet confirmed by you): **native Swift/SwiftUI on macOS, Kotlin on Android**, plus a small Rust or C++ core only where a shared native library pays off (crypto/protocol is a candidate, not a requirement).

---

## 1. The one decision that shapes everything

Android does not let a normal app do three things the spec needs: inject touches/keys into *other* apps in real time, capture the screen without a fresh consent prompt every session, and keep running a mirror while the physical panel is off. There are two ways to get them.

| | **A. Privileged helper (recommended)** | **B. Standard permissions only** |
|---|---|---|
| How | App pairs with the phone's own *Wireless debugging* once and launches a helper process with shell (`adb`) privileges, the same model Shizuku and scrcpy use. | MediaProjection for capture + AccessibilityService for input. |
| Setup | User enables Developer options and Wireless debugging once; app guides them. | Normal permission prompts. |
| After reboot | Helper dies. User taps the Wireless debugging quick-settings tile, app restarts the helper ("reactivate in a couple of taps", exactly as your spec describes). Requires being on Wi-Fi at that moment. | Nothing to reactivate, but… |
| Per session | No prompt. | Android 14+ shows a system "Start recording/casting?" dialog **on the phone** every session. That breaks "phone stays in your pocket". |
| Input quality | Real touch/multi-touch/scroll/keys via `InputManager.injectInputEvent`, low latency. | `dispatchGesture` only: whole gestures, no live drag, poor scrolling, no hardware keys. |
| Screen off during session | Yes (display power mode off while composition continues). | No. |
| Audio | Android 11+ via shell playback/remote-submix capture. | Android 10+ AudioPlaybackCapture, many apps opt out. |
| Distribution risk | Play Store accepts Shizuku-dependent apps today; still a review risk. | Play's AccessibilityService policy makes remote-control use a review risk too. |

Spec items 1.4 ("after the phone restarts, reactivate in a couple of taps") and "phone in your pocket, screen off" only make sense with **A**. Plan: build A as the primary mode and keep B as a degraded fallback for phones where Wireless debugging is blocked (some enterprise/managed devices, Advanced Protection mode, a few OEM builds).

---

## 2. System overview

```
 ┌──────────────────────── Android phone ───────────────────────┐        ┌────────────────────── Mac ───────────────────────┐
 │ Companion app (Kotlin)                                        │        │ Mac app (Swift/SwiftUI)                           │
 │  • Onboarding + permission guide                              │        │  • Mirror window (Metal/AVSampleBufferDisplayLayer)│
 │  • ForegroundService (connectedDevice): BLE presence, GATT    │  BLE   │  • CoreBluetooth scanner + GATT client             │
 │  • NotificationListenerService                                │◄──────►│  • UNUserNotificationCenter bridge                  │
 │  • Mirror IME (InputMethodService) for any-language text      │        │  • Secure Enclave identity key (Touch ID gated)    │
 │  • Session indicator notification ("End" action)              │        │  • NSPasteboard / drag-and-drop bridge             │
 │  • Keystore identity key                                      │ WebRTC │  • CoreWLAN hotspot join                           │
 │                                                               │◄══════►│  • Menu-bar agent (login item) for presence/notifs│
 │ Privileged helper (app_process, shell uid)                    │ (LAN / │                                                    │
 │  • Display capture → MediaCodec H.264/HEVC (surface input)    │ hotspot│                                                    │
 │  • Audio capture → Opus                                       │ / TURN)│                                                    │
 │  • Input injection, panel power, rotation, clipboard, tether  │        │                                                    │
 └───────────────────────────────────────────────────────────────┘        └────────────────────────────────────────────────────┘
                                         ▲                                         ▲
                                         └───────── TURN relay (coturn) + tiny token service ─┘   (only server we run; sees ciphertext only)
```

Channels:
- **BLE** (always on while nearby): presence/proximity, rendezvous/signaling, notifications and small control messages when no mirror session is running. Cheap on battery, works with no shared network.
- **WebRTC peer connection** (during a session): video track, audio track, and data channels for input, clipboard, files, control. ICE gives us "pick the best path automatically" and the path/quality readout for free.

---

## 3. Feature-by-feature mapping

Feasibility key: ✅ full, 🟡 works with caveats, 🔴 not possible as specified (alternative given).

### 3.1 First-time setup

| Spec | Android | macOS | Feasibility |
|---|---|---|---|
| Install both apps | Play Store or direct APK | Developer ID + notarized DMG (not Mac App Store; see §7) | ✅ |
| Mac shows QR, phone scans | CameraX + ML Kit barcode scanning. QR carries Mac identity public key fingerprint, one-time pairing secret, BLE rendezvous hint. | Generate P-256 identity key in Secure Enclave; render QR with CoreImage `CIQRCodeGenerator`. | ✅ |
| "Only this Mac can connect" | Phone stores paired Mac public keys; rejects any other key. Multiple trusted Macs allowed (Settings lists them). | | ✅ |
| One-time guided permissions | Sequence: Notifications → Nearby devices (BLE) → Notification access → Battery optimization exemption → Display over other apps (for drag-out drop target) → Enable Developer options → Wireless debugging pairing (in-app pairing code entry via notification reply, as Shizuku does) → set Mirror keyboard as an enabled IME. Each step has an explainer screen and a deep link (`Settings.ACTION_*`). | Bluetooth, Local Network, Notifications, Location (needed by CoreWLAN to see SSIDs). | 🟡 Developer options step is the clunky one; OEM-specific screenshots help (Xiaomi also needs "USB debugging (Security settings)" for input injection). |
| Reactivate after reboot | Boot receiver posts a notification: "Turn on Wireless debugging to reconnect". User taps the quick-settings tile; app self-connects over localhost ADB and starts the helper; can turn Wireless debugging back off afterward (helper keeps running until next reboot). | | 🟡 Needs Wi-Fi connected at that moment (Android requirement for Wireless debugging). |

### 3.2 Everyday use

| Spec | Approach | Feasibility |
|---|---|---|
| Phone appears within a couple of seconds | Phone's foreground service advertises a BLE beacon whose payload is `HMAC(pairing key, time slot)` (rotates every ~15 min, unlinkable to outsiders). Mac scans continuously from its menu-bar agent, recognizes the phone, opens GATT, exchanges ICE candidates, starts WebRTC. | ✅ |
| Touch ID / password before connecting | The Mac's identity private key lives in the Secure Enclave with `SecAccessControl(.userPresence)`. The session handshake requires a signature with that key, so Touch ID is **cryptographically** required, not just a UI gate. "Don't require every time" setting = reuse an `LAContext` with `touchIDAuthenticationAllowableReuseDuration` or a second non-gated key. | ✅ |
| Phone screen turns off during session | Helper sets physical display power mode to OFF (`SurfaceControl`/`DisplayControl.setDisplayPowerMode`, as scrcpy `--turn-screen-off`) while the display keeps composing; holds a wake lock so the device does not sleep. Restores on session end. | 🟡 Hidden-API; method moved in Android 14/15, needs per-version code and OEM testing. |
| Phone is locked when session starts | The mirrored lock screen appears in the Mac window; user types the PIN with the Mac keyboard (or we show "Unlock your phone" when biometric-only). We do **not** store or replay the PIN. | 🟡 iPhone Mirroring works while the iPhone stays locked; Android has no public equivalent. Unlocking via the window is the honest substitute. |
| Pick up phone → session pauses | Panel is "off" but the system thinks it is awake, so a power-button press is caught by a display-state listener; also accelerometer pick-up detection (`TYPE_SIGNIFICANT_MOTION` / raw accel threshold) and proximity sensor leaving the pocket. On trigger: pause stream, restore panel, then put the device to sleep so it shows the keyguard. Mac shows "Paused: phone in use". Resume when phone locks/idles again. | ✅ |
| Phone taken far away → session ends | BLE RSSI with hysteresis (e.g. below threshold for N seconds) or BLE link lost for >10 s ends the session and locks the phone. This applies even when video is on the relay path. | ✅ (RSSI is coarse: "same room vs. down the hall", not metres) |

### 3.3 In the window

| Spec | Approach | Feasibility |
|---|---|---|
| Click, drag, scroll | Mac `NSView` mouse events → normalized coords → helper injects `MotionEvent` (touchscreen source). Scroll wheel/trackpad scroll → `ACTION_SCROLL` with `AXIS_VSCROLL/HSCROLL` (smooth, momentum preserved by forwarding phases). | ✅ |
| Swipe with trackpad | Two-finger trackpad swipe phases → synthesized one-finger drag (for app pagers); pinch (`magnify(with:)`) → two-pointer pinch synthesis. | ✅ |
| Type in any language | A bundled **Mirror IME** (InputMethodService) is switched in automatically by the helper for the session (`ime set`) and restored afterward. The Mac uses its own input sources; committed text goes via `commitText`, CJK marked text via `setComposingText`. Non-text keys (arrows, Enter, Backspace, Tab, Esc) are injected as `KeyEvent`s. | ✅ Better than scrcpy's key-map approach, which fails for non-Latin text. |
| Familiar keyboard shortcuts | Cmd+C/V/X/A/Z → Ctrl+ equivalents; Cmd+1 Home, Cmd+2 Recents, Cmd+3 app search (same as iPhone Mirroring); Cmd+[ / Esc Back; Cmd+↑/↓ volume. Configurable map. | ✅ |
| Phone audio on the Mac | Helper captures playback audio (Android 11+; `REMOTE_SUBMIX`/playback-capture path as scrcpy), encodes Opus, sends as WebRTC audio track. Phone speaker muted during session. | 🟡 Android 11+ only. Apps that set `allowAudioPlaybackCapture=false` and phone-call audio are not captured. |
| Notifications in macOS | `NotificationListenerService` → title/text/app icon/key → over BLE (no session needed) or data channel → Mac posts via `UNUserNotificationCenter` with the app's icon as attachment. Dismissals sync both ways. | ✅ |
| Click a notification → opens the app in the window | Mac sends notification key → phone starts the mirror session if not running (Touch ID first) → helper fires the notification's `contentIntent` from the shell uid (bypasses Android 14+ background-activity-launch limits); fallback: launch the app's main activity. | ✅ |
| Copy/paste text | Phone→Mac: helper's clipboard listener → Mac writes `NSPasteboard` (writes never prompt). Mac→phone: synced when the user presses Cmd+V in the window (user-initiated read, avoids macOS pasteboard-privacy alerts), then injected paste. | ✅ |
| Copy/paste images | Same flow; image bytes sent over data channel, phone side exposes them via our `FileProvider` URI in the clip. Reading image clips copied *from* other Android apps needs a URI grant to the reader. | 🟡 Mac→phone ✅. Phone→Mac depends on the source app's clip URI being readable by the helper; spike in M0. |
| Drag files into window → phone | `NSDraggingDestination` on the window → file streamed over a reliable data channel → saved via `MediaStore` to Downloads; notification "Saved from Mac" with Open/Share. Drop onto a specific app (e.g. into a chat) is a stretch goal using a global drag into that app. | ✅ (to Downloads) |
| Drag from window → desktop | When a long-press drag inside the mirror leaves the window edge, the helper continues the injected touch onto an invisible edge overlay window owned by our app that accepts `DRAG_FLAG_GLOBAL` drops; we receive the `ClipData` URIs, stream the files to the Mac, which completes an `NSDraggingSource` file promise. Fallback for apps without cross-app drag: our "Send to Mac" share target. | 🟡 Works with apps that support cross-app drag (Files, Photos/Gallery on recent Android, Samsung apps). Not universal. |
| Resize window | Aspect-locked `NSWindow`; Metal scaling. Quality setting may also ask the encoder for a different size. | ✅ |
| Portrait/landscape | Mac toolbar button → helper sets user rotation (`IWindowManager.freezeRotation`). Encoder reconfigures on size change; window animates to new aspect. | ✅ |
| Home / Back / Recents | Injected `KEYCODE_HOME`, `KEYCODE_BACK`, `KEYCODE_APP_SWITCH`. | ✅ |
| Search across phone apps | Phone sends installed launchable apps (`PackageManager.queryIntentActivities` for `MAIN/LAUNCHER`, label + icon) once and on change. Mac shows a Spotlight-style overlay, launches by package. | ✅ (needs `<queries>` launcher intent declaration; no `QUERY_ALL_PACKAGES`) |

### 3.4 Security for the user

| Spec | Approach | Feasibility |
|---|---|---|
| Only a paired Mac can connect | Mutual authentication with long-term identity keys from pairing on every session (see §5). | ✅ |
| Other devices on the network can't see the phone | No mDNS/Bonjour advertisement at all. Discovery is BLE-only with rotating, keyed beacons. The phone opens no listening port; WebRTC ICE ports are ephemeral and only exist during a session. | ✅ |
| Touch ID every session | Secure Enclave key gating (§3.2). | ✅ |
| All traffic encrypted | DTLS-SRTP (WebRTC) with the DTLS certificate fingerprints signed by both identity keys; BLE payloads encrypted at app layer (Noise), not relying on BLE pairing. TURN relay sees only ciphertext. | ✅ |
| Protected apps hidden | `FLAG_SECURE` windows render black in captures on Android 12+ when the helper creates a non-secure virtual display mirror. On Android 11 and below the shell *can* create a secure display, so we deliberately don't. Mac overlays "Protected content" when the frame is secured (detected via window list from the helper). | ✅ |
| Phone always shows session active, one-tap end | Ongoing high-priority notification "Mirroring to <Mac name>" with an **End** action, plus a status bar icon; shown on the lock screen too. Our helper-based capture does not trigger Android's own casting chip, so this notification is mandatory and cannot be dismissed while active. | ✅ |
| Revoke a Mac | Settings → Trusted Macs → Remove: deletes its public key; any live session ends immediately; beacon key rotates. | ✅ |

### 3.5 Where it works (connection paths)

| Situation | Path | Notes |
|---|---|---|
| Home/office network | ICE `host` candidates over LAN | Typical glass-to-glass target < 60 ms at 1080p60. |
| Café / public Wi-Fi (client isolation, captive portals) | **Phone hotspot.** Phone proposes it over BLE; on accept, helper starts tethering (`TetheringManager.startTethering` via shell privileges) and sends SSID/password to the Mac over the encrypted BLE channel; Mac joins with `CWInterface.associate(to:password:)`. Mac keeps internet via the phone's cellular data. | 🟡 Programmatic tethering from the shell uid needs per-version/OEM verification. Fallback: deep-link the user to the hotspot toggle (one extra tap). Uses mobile data, so we show it. |
| No direct path possible | **TURN relay** (`relay` ICE candidates), TLS/443 for restrictive networks. | Slightly higher latency; adaptive bitrate lowers quality first. |
| Path/quality indicator | WebRTC `getStats()`: selected candidate pair type (host/hotspot/relay), RTT, packet loss, current fps/bitrate → "Direct · Excellent", "Hotspot · Good", "Relay · Fair". | ✅ |

Note on the relay and "far away": because sessions end when BLE proximity is lost, the relay is only used when the phone *is* near but the networks can't reach each other directly. It is not a "use my phone from another city" feature, which matches the spec.

### 3.6 Settings

| Setting | Implementation |
|---|---|
| Picture quality vs. responsiveness | Slider mapping to resolution cap, fps (30/60/90), bitrate ceiling, codec (HEVC when both sides support it), and receiver jitter-buffer target. |
| Audio / notifications on/off | Toggles that stop the audio track / the listener forwarding. |
| Require Touch ID every connection | Key access-control policy (§3.2). |
| Connect automatically when nearby | Mac agent auto-starts session on beacon detection (still Touch ID if required). |
| Trusted Macs list + removal | Phone-side list (§3.4); Mac side mirror list "Paired phones" with Forget. |

---

## 4. Media pipeline

- **Capture (helper):** create a mirror virtual display of the main display (`DisplayManager.createVirtualDisplay` / `SurfaceControl` path per Android version) targeting the encoder input `Surface`. Zero-copy GPU path.
- **Encode:** `MediaCodec` H.264 baseline/high or HEVC, `KEY_PRIORITY=0` (realtime), `KEY_LATENCY=1` where supported, CBR-ish with frequent IDR on loss, repeat-previous-frame when static (`KEY_REPEAT_PREVIOUS_FRAME_AFTER`) to save battery.
- **Transport:** fed into libwebrtc as pre-encoded frames through a custom `VideoEncoderFactory` wrapper, so WebRTC handles pacing, NACK/FEC and congestion control (GCC) but not the encoding itself.
- **Decode/render (Mac):** VideoToolbox hardware decode, render with Metal or `AVSampleBufferDisplayLayer`, playout-delay hint 0 for minimum buffering.
- **Audio:** 48 kHz Opus, low-delay mode, separate track; small receiver buffer.

Why WebRTC instead of a custom QUIC/TCP stream (scrcpy-style): it gives ICE (LAN/hotspot/relay selection and failover), TURN, encryption, congestion control and stats in one battle-tested library on both platforms. A raw stream would be a little lower latency on LAN but we would rebuild NAT traversal and relay ourselves. If LAN latency measurements in M0 disappoint, we can add a raw LAN fast path later behind the same protocol.

---

## 5. Pairing and security design

- **Identities.** Mac: P-256 key in Secure Enclave (Touch ID gated). Phone: P-256 key in Android Keystore (StrongBox when available). P-256 because the Secure Enclave only supports P-256.
- **Pairing.** QR contains Mac public key hash + 128-bit one-time secret + expiry (2 min). Phone connects over BLE and runs a Noise `XXpsk` handshake using the secret as PSK, which authenticates both sides and exchanges long-term public keys. Secret is single-use. Both sides show a 6-digit confirmation code as a belt-and-braces check.
- **Session.** BLE control channel: Noise `KK` with the long-term keys (Mac signature requires Touch ID). Inside it, the two sides exchange WebRTC SDP and their DTLS certificate fingerprints; WebRTC then refuses any peer whose fingerprint doesn't match, so neither a café network nor the TURN server can MITM.
- **Beacon privacy.** BLE advertisement = `HMAC(beacon key, floor(time/15min))[0..N]`, random BLE address rotation. Outsiders can't identify or track the phone.
- **Relay auth.** TURN uses short-lived REST-style credentials from our token service; the service authenticates devices by a signature from the paired identity key, never sees content.
- **Local state.** Keys never leave secure hardware. Revocation deletes keys and rotates beacon key.
- **Threats we accept/mitigate:** a stolen *unlocked-but-dark* phone mid-session (mitigated by pickup detection → keyguard); a compromised Mac account (Touch ID gate + per-session prompt by default); helper process running with shell privileges (bound only to our app via an authenticated local socket; exposes a narrow command set, not a shell).

---

## 6. Honest limits (what Android won't let us match exactly)

1. **Developer options + Wireless debugging are required** for the full experience, and must be re-armed after every reboot while on Wi-Fi. This is the price of no per-session prompt and real input. Without it we fall back to mode B with a phone-side prompt each session.
2. **Phone may need unlocking** through the mirror window at session start. iPhone Mirroring works with the phone locked; Android exposes no API for that.
3. **Screen-off mirroring uses hidden APIs** that change between Android versions and some OEMs. Expect a compatibility matrix and per-version code.
4. **Audio needs Android 11+**, and apps can opt out of capture; call audio is never captured.
5. **Drag-out of files** only works from apps that support cross-app drag and drop; others go through the share sheet.
6. **Phone→Mac image paste** depends on the source app's clipboard URI permissions (to be measured).
7. **Programmatic hotspot** may need one manual tap on some phones.
8. **OEM quirks:** Xiaomi/HyperOS needs an extra security toggle for input injection; some Samsung/enterprise builds disable Wireless debugging; Android Advanced Protection mode may block it entirely.
9. **Store policy:** both modes touch sensitive Play policies (shell helper or AccessibilityService). Plan for a direct-APK channel in addition to Play.
10. **macOS:** auto-joining the phone's hotspot (CoreWLAN) and an always-on Bluetooth agent push us to Developer ID distribution rather than the Mac App Store.

Minimum versions proposed: **Android 11+** (Wireless debugging, audio capture), **macOS 14+** (current Network/VideoToolbox/SwiftUI APIs; adjust if you need older).

---

## 7. Repository layout (proposal, not created yet)

```
android-mirroring/
  android/        Kotlin app (Gradle), modules: app, helper (app_process server), ime, protocol
  mac/            Xcode project: app, menu-bar agent (login item), Protocol Swift package
  protocol/       Protobuf schemas for control/data-channel messages + Noise framing spec
  relay/          coturn config + token service (small Go or Kotlin service), deploy scripts
  docs/           this plan, compatibility matrix, threat model
```

Distribution: Mac via Developer ID + notarization + Sparkle updates. Android via Play (if approved) and signed direct APK.

---

## 8. Milestone plan

Sizes are relative (S/M/L/XL), not dates; each milestone has an exit test.

**M0 — Feasibility spikes (M).** Prove the risky pieces on a Pixel, a Samsung and a Xiaomi:
self-ADB pairing + helper launch and reboot reactivation; display capture → MediaCodec → LAN → VideoToolbox with latency measurement; screen-off-while-mirroring on Android 12/13/14/15/16; Mirror IME text commit incl. CJK; clipboard image URI reading; programmatic tethering.
*Exit:* each spike marked works / works-with-workaround / no, and §6 updated with facts.

**M1 — LAN MVP (L).** QR pairing + Noise; BLE discovery; Touch ID gate; WebRTC on LAN; video; mouse/scroll input; Home/Back/Recents; session notification with End; revoke.
*Exit:* pair a fresh phone and use any app from the Mac on home Wi-Fi.

**M2 — Daily driver (L).** Audio; keyboard in any language + shortcuts; text clipboard both ways; resize/rotation; screen-off; pickup pause; proximity end; auto-connect; settings screen; reboot reactivation flow; onboarding guide.
*Exit:* a full working day with the phone in a pocket.

**M3 — Continuity features (M).** Notifications to macOS with click-to-open; app search; file drag-in; image clipboard; drag-out (where supported) + share-to-Mac.
*Exit:* every "What you can do in the window" bullet demonstrable.

**M4 — Works anywhere (L).** Phone-hotspot flow; TURN relay + token service; path/quality indicator; adaptive bitrate; network handoff without dropping the session.
*Exit:* sessions in a café with client isolation, and on a network blocking UDP.

**M5 — Hardening and release (L).** Mode B fallback; OEM compatibility matrix; security review and threat-model test; battery profiling; crash reporting; notarization, Play submission, direct APK, auto-update.
*Exit:* beta release.

---

## 9. Open questions for you

1. Confirm native Swift + Kotlin (vs. a cross-platform stack). Native is strongly preferred here because nearly every feature is a platform API.
2. ~~Confirm mode A as the primary experience.~~ Decided 2026-10-19: build both, helper mode (A) primary, standard mode (B) as fallback.
3. Distribution: Play Store, direct APK, or both? Mac App Store not recommended.
4. Relay hosting: OK to run our own TURN servers (small monthly cost, scales with relay usage)?
5. Where should the code live (new GitHub repo name/owner)?
