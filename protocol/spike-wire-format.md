# M0 spike wire format

Temporary, for feasibility testing only. Plain TCP (default port 27183), one Mac at a time,
guarded by a shared token. No encryption. M1 replaces this with BLE discovery, Noise and
WebRTC as described in `docs/architecture-and-build-plan.md`.

Every message: `type: u8` · `length: u32` · `payload[length]`. Big-endian throughout.

## Helper → Mac

| Type | Name | Payload |
|---|---|---|
| 0x01 | HELLO_ACK | UTF-8 device description |
| 0x02 | VIDEO_CONFIG | `width: u32`, `height: u32`, H.264 SPS+PPS (Annex-B). Sent on every encoder (re)start, e.g. rotation. |
| 0x03 | VIDEO_FRAME | `capture_us: i64` (phone CLOCK_MONOTONIC), `keyframe: u8`, H.264 access unit (Annex-B) |
| 0x04 | PONG | `echo_us: i64` (from PING), `phone_now_us: i64` (CLOCK_MONOTONIC) |
| 0x05 | LOG | UTF-8 text |

## Mac → helper

| Type | Name | Payload |
|---|---|---|
| 0x10 | HELLO | UTF-8 token. Must be the first message. |
| 0x11 | TOUCH | `action: u8` (0 down, 1 up, 2 move), `x: f32`, `y: f32` normalized 0..1 from top-left |
| 0x12 | SCROLL | `x: f32`, `y: f32`, `horizontal: f32`, `vertical: f32` (AXIS_HSCROLL / AXIS_VSCROLL) |
| 0x13 | KEY | `android_keycode: i32`, `meta_state: i32`; helper sends down + up |
| 0x14 | TEXT | UTF-8 text (spike path, ASCII only) |
| 0x15 | PANEL | `on: u8`; turns the physical panel on/off while mirroring continues |
| 0x16 | PING | `mac_now_us: i64` |
| 0x17 | REQUEST_KEYFRAME | empty |

Latency: the Mac estimates `offset = phone_now - (echo + rtt/2)` from the lowest-RTT PONG, then
`latency = mac_receive_time - (capture_us - offset)`. This covers capture, encode and network,
not decode and display.
