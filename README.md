# Android Mirroring for Mac

Use your Android phone from your Mac: the phone's screen in a Mac window, driven with mouse and keyboard, with audio, notifications, clipboard and file drag-and-drop. Wireless only, paired by QR code, every session confirmed with Touch ID.

Status: early feasibility work (milestone M0). Nothing here is usable by end users yet.

- Architecture and build plan: [docs/architecture-and-build-plan.md](docs/architecture-and-build-plan.md)

## Layout (planned)

| Path | What |
|---|---|
| `android/` | Kotlin phone app, including the privileged helper and the Mirror keyboard |
| `mac/` | Swift/SwiftUI Mac app |
| `protocol/` | Wire formats shared by both apps |
| `relay/` | TURN relay config and token service |
| `docs/` | Plans, spike results, compatibility matrix |
