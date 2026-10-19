import AppKit

struct Config {
    var host = ""
    var port: UInt16 = 27183
    var token = ""
    var screenOn = false

    static func parse(_ args: [String]) -> Config {
        var config = Config()
        var it = args.dropFirst().makeIterator()
        while let arg = it.next() {
            switch arg {
            case "--host": config.host = it.next() ?? ""
            case "--port": config.port = UInt16(it.next() ?? "") ?? config.port
            case "--token": config.token = it.next() ?? ""
            case "--screen-on": config.screenOn = true
            default: break
            }
        }
        if config.host.isEmpty || config.token.isEmpty {
            FileHandle.standardError.write(Data("usage: MirrorSpike --host <phone-ip> --token <token> [--port 27183] [--screen-on]\n".utf8))
            exit(2)
        }
        return config
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    private let config: Config
    private let connection: HelperConnection
    private var window: NSWindow!
    private let view = MirrorView(frame: NSRect(x: 0, y: 0, width: 405, height: 900))
    private var stats = StreamStats()
    private var deviceName = "Connecting…"
    private var needsKeyframe = false
    private var panelOn = true
    private var connected = false
    private var connectionState = "starting"
    private let startedAt = Date()
    private var timer: Timer?

    init(config: Config) {
        self.config = config
        connection = HelperConnection(host: config.host, port: config.port)
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        window = NSWindow(
            contentRect: view.frame,
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.contentView = view
        window.title = deviceName
        window.center()
        addRotateButton()
        window.makeKeyAndOrderFront(nil)
        window.makeFirstResponder(view)
        NSApp.activate(ignoringOtherApps: true)

        view.send = { [weak self] type, payload in self?.connection.send(type, payload) }
        view.onPanelToggle = { [weak self] in
            guard let self else { return }
            self.panelOn.toggle()
            self.connection.send(.panel, [self.panelOn ? 1 : 0])
        }
        view.onKeyframeRequest = { [weak self] in self?.connection.send(.requestKeyframe) }
        view.onRotate = { [weak self] in self?.rotate() }

        connection.onStatus = { [weak self] status in
            guard let self else { return }
            print("connection: \(status)")
            self.connectionState = status
            if status.hasPrefix("closed") {
                self.window.title = self.connected
                    ? "Disconnected"
                    : "The phone refused the connection. Check the token matches the phone app."
                self.connected = false
            }
        }
        connection.onMessage = { [weak self] type, payload in self?.handle(type, payload) }
        connection.start(token: config.token)

        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.tick() }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }

    private func addRotateButton() {
        let button = NSButton(
            image: NSImage(systemSymbolName: "rotate.right", accessibilityDescription: "Rotate phone")!,
            target: self,
            action: #selector(rotate)
        )
        button.isBordered = false
        button.toolTip = "Rotate phone (⌘R)"
        button.frame = NSRect(x: 0, y: 0, width: 28, height: 22)
        let accessory = NSTitlebarAccessoryViewController()
        accessory.view = NSView(frame: button.frame)
        accessory.view.addSubview(button)
        accessory.layoutAttribute = .trailing
        window.addTitlebarAccessoryViewController(accessory)
    }

    @objc private func rotate() {
        connection.send(.rotate)
    }

    private func tick() {
        if !connected {
            // Say why instead of sitting on "Connecting…" forever.
            if connectionState.hasPrefix("closed") { return }
            let target = "\(config.host):\(config.port)"
            window.title = Date().timeIntervalSince(startedAt) < 5
                ? "Connecting to \(target)…"
                : "Can't reach the phone at \(target) (\(connectionState)). Is the helper running, and are both on the same Wi-Fi with VPN off?"
            return
        }
        var w = ByteWriter()
        w.i64(nowMicros())
        connection.send(.ping, w.bytes)
        let summary = stats.summary(interval: 1)
        window.title = "\(deviceName) — \(summary)"
        print(summary)
    }

    private func handle(_ type: MsgType, _ payload: [UInt8]) {
        var r = ByteReader(payload)
        switch type {
        case .helloAck:
            deviceName = String(decoding: payload, as: UTF8.self)
            connected = true
            print("connected to \(deviceName)")
            // Like iPhone Mirroring, the phone screen stays dark while it is mirrored.
            if !config.screenOn {
                panelOn = false
                connection.send(.panel, [0])
            }
        case .videoConfig:
            let width = r.u32(), height = r.u32()
            window.contentAspectRatio = NSSize(width: Int(width), height: Int(height))
            var frame = window.frame
            frame.size.width = frame.size.height * CGFloat(width) / CGFloat(height)
            window.setFrame(frame, display: true, animate: false)
            needsKeyframe = !view.renderer.configure(codecConfig: r.remaining)
            print("video \(width)x\(height)")
        case .videoFrame:
            let pts = r.i64()
            let isKey = r.u8() != 0
            stats.frame(captureMicros: pts, size: payload.count)
            if needsKeyframe && !isKey { return }
            if view.renderer.enqueue(frame: r.remaining) {
                needsKeyframe = false
            } else if !needsKeyframe {
                needsKeyframe = true
                connection.send(.requestKeyframe)
            }
        case .pong:
            stats.pong(echo: r.i64(), phoneNow: r.i64())
        case .log:
            print("phone: \(String(decoding: payload, as: UTF8.self))")
        default:
            break
        }
    }
}

let config = Config.parse(CommandLine.arguments)
let app = NSApplication.shared
app.setActivationPolicy(.regular)
let delegate = AppDelegate(config: config)
app.delegate = delegate
app.run()
