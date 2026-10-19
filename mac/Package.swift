// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "AndroidMirroringMac",
    platforms: [.macOS(.v14)],
    targets: [
        // M0 feasibility viewer: connects to the phone helper over TCP, shows the stream,
        // forwards mouse/keyboard, and reports latency. Replaced by the real app in M1.
        .executableTarget(name: "MirrorSpike", path: "Sources/MirrorSpike"),
    ]
)
