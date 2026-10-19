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

final class AppDelegate: NSObject, NSApplicationDelegate, NSToolbarDelegate {
    private let config: Config
    private let connection: HelperConnection
    private var window: NSWindow!
    private let view = MirrorView(frame: NSRect(x: 0, y: 0, width: 405, height: 900))
    private let audio = AudioPlayer()
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
        addToolbar()
        window.makeKeyAndOrderFront(nil)
        window.makeFirstResponder(view)
        NSApp.activate(ignoringOtherApps: true)

        view.send = { [weak self] type, payload in self?.connection.send(type, payload) }
        view.onPanelToggle = { [weak self] in self?.togglePanel() }
        view.onKeyframeRequest = { [weak self] in self?.connection.send(.requestKeyframe) }
        view.onRotate = { [weak self] in self?.rotate() }

        connection.onStatus = { [weak self] status in
            guard let self else { return }
            print("connection: \(status)")
            self.connectionState = status
            if status.hasPrefix("closed") {
                self.window.title = "Disconnected"
                self.window.subtitle = self.connected ? "" : "The phone refused the connection. Check the token matches the phone app."
                self.connected = false
            }
        }
        connection.onMessage = { [weak self] type, payload in self?.handle(type, payload) }
        connection.start(token: config.token)

        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.tick() }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }

    private func addToolbar() {
        let toolbar = NSToolbar(identifier: "mirror")
        toolbar.delegate = self
        toolbar.displayMode = .iconOnly
        window.toolbar = toolbar
        window.toolbarStyle = .unified
    }

    @objc private func rotate() {
        connection.send(.rotate)
    }

    @objc private func togglePanel() {
        panelOn.toggle()
        connection.send(.panel, [panelOn ? 1 : 0])
    }

    @objc private func toolbarKey(_ sender: NSToolbarItem) {
        var w = ByteWriter()
        w.i32(Int32(sender.tag))
        w.i32(0)
        connection.send(.key, w.bytes)
    }

    // MARK: Toolbar

    private struct ToolbarButton {
        let id: String, symbol: String, label: String, tag: Int32, action: Selector
    }

    private lazy var toolbarButtons: [ToolbarButton] = [
        ToolbarButton(id: "back", symbol: "chevron.backward", label: "Back (Esc)", tag: AndroidKey.back, action: #selector(toolbarKey(_:))),
        ToolbarButton(id: "home", symbol: "circle", label: "Home (⌘1)", tag: AndroidKey.home, action: #selector(toolbarKey(_:))),
        ToolbarButton(id: "recents", symbol: "square.on.square", label: "Recent apps (⌘2)", tag: AndroidKey.appSwitch, action: #selector(toolbarKey(_:))),
        ToolbarButton(id: "rotate", symbol: "rotate.right", label: "Rotate phone (⌘R)", tag: 0, action: #selector(rotate)),
        ToolbarButton(id: "screen", symbol: "lightbulb", label: "Phone screen on/off (⌘L)", tag: 0, action: #selector(togglePanel)),
    ]

    func toolbarDefaultItemIdentifiers(_ toolbar: NSToolbar) -> [NSToolbarItem.Identifier] {
        [.flexibleSpace] + toolbarButtons.map { NSToolbarItem.Identifier($0.id) }
    }

    func toolbarAllowedItemIdentifiers(_ toolbar: NSToolbar) -> [NSToolbarItem.Identifier] {
        toolbarDefaultItemIdentifiers(toolbar)
    }

    func toolbar(_ toolbar: NSToolbar, itemForItemIdentifier id: NSToolbarItem.Identifier, willBeInsertedIntoToolbar flag: Bool) -> NSToolbarItem? {
        guard let spec = toolbarButtons.first(where: { $0.id == id.rawValue }) else { return nil }
        let item = NSToolbarItem(itemIdentifier: id)
        item.image = NSImage(systemSymbolName: spec.symbol, accessibilityDescription: spec.label)
        item.label = spec.label
        item.toolTip = spec.label
        item.tag = Int(spec.tag)
        item.target = self
        item.action = spec.action
        item.isBordered = true
        return item
    }

    /// Keeps the window's longer side when the phone rotates, so landscape doesn't balloon to
    /// portrait height, and keeps it on screen.
    private func resizeForVideo(width: CGFloat, height: CGFloat) {
        let current = view.bounds.size
        let longSide = max(current.width, current.height)
        var size = width >= height
            ? NSSize(width: longSide, height: longSide * height / width)
            : NSSize(width: longSide * width / height, height: longSide)
        if let visible = window.screen?.visibleFrame.size {
            let scale = min(1, visible.width * 0.9 / size.width, (visible.height - 60) / size.height)
            size = NSSize(width: size.width * scale, height: size.height * scale)
        }
        let oldFrame = window.frame
        window.contentAspectRatio = NSSize(width: width, height: height)
        window.setContentSize(size)
        var frame = window.frame
        frame.origin = NSPoint(x: oldFrame.midX - frame.width / 2, y: oldFrame.maxY - frame.height)
        window.setFrame(frame, display: true, animate: false)
    }

    private func tick() {
        if !connected {
            // Say why instead of sitting on "Connecting…" forever.
            if connectionState.hasPrefix("closed") { return }
            let target = "\(config.host):\(config.port)"
            if Date().timeIntervalSince(startedAt) < 5 {
                window.title = "Connecting…"
                window.subtitle = target
            } else {
                window.title = "Can't reach the phone"
                window.subtitle = "\(target) (\(connectionState)). Is the helper running, and are both on the same Wi-Fi with VPN off?"
            }
            return
        }
        var w = ByteWriter()
        w.i64(nowMicros())
        connection.send(.ping, w.bytes)
        let summary = stats.summary(interval: 1)
        window.title = deviceName
        // Short enough to leave the toolbar room; the full line goes to the terminal.
        let parts = summary.components(separatedBy: " · ")
        window.subtitle = parts.count > 2 ? "\(parts[0]) · \(parts[2])" : summary
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
            resizeForVideo(width: CGFloat(width), height: CGFloat(height))
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
        case .audio:
            audio.play(pcm: payload)
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
