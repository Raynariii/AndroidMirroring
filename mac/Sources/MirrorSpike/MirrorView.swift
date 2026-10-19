import AppKit
import Carbon.HIToolbox

/// Shows the phone screen and turns mouse/keyboard input into helper control messages.
final class MirrorView: NSView {
    let renderer = H264Renderer()
    var send: ((MsgType, [UInt8]) -> Void)?
    var onPanelToggle: (() -> Void)?
    var onKeyframeRequest: (() -> Void)?
    var onRotate: (() -> Void)?

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layer = CALayer()
        layer?.backgroundColor = NSColor.black.cgColor
        layer?.addSublayer(renderer.layer)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override var acceptsFirstResponder: Bool { true }
    override var isFlipped: Bool { true }

    override func layout() {
        super.layout()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        renderer.layer.frame = bounds
        CATransaction.commit()
    }

    // MARK: Pointer

    private func normalized(_ event: NSEvent) -> (Float, Float) {
        let p = convert(event.locationInWindow, from: nil)
        return (Float(p.x / max(bounds.width, 1)), Float(p.y / max(bounds.height, 1)))
    }

    private func touch(_ action: UInt8, _ event: NSEvent) {
        let (x, y) = normalized(event)
        var w = ByteWriter()
        w.u8(action)
        w.f32(x)
        w.f32(y)
        send?(.touch, w.bytes)
    }

    override func mouseDown(with event: NSEvent) { touch(0, event) }
    override func mouseDragged(with event: NSEvent) { touch(2, event) }
    override func mouseUp(with event: NSEvent) { touch(1, event) }

    override func scrollWheel(with event: NSEvent) {
        let (x, y) = normalized(event)
        // Trackpads report pixel deltas; wheels report lines. Android expects roughly "notches".
        let divisor: CGFloat = event.hasPreciseScrollingDeltas ? 30 : 1
        var w = ByteWriter()
        w.f32(x)
        w.f32(y)
        w.f32(Float(-event.scrollingDeltaX / divisor))
        w.f32(Float(event.scrollingDeltaY / divisor))
        send?(.scroll, w.bytes)
    }

    // MARK: Keyboard

    private func sendKey(_ androidKeyCode: Int32, meta: Int32 = 0) {
        var w = ByteWriter()
        w.i32(androidKeyCode)
        w.i32(meta)
        send?(.key, w.bytes)
    }

    /// A quick finger swipe through the middle of the screen, in the direction the arrow points.
    private func swipe(_ keyCode: Int) {
        // fromX, fromY, toX, toY, normalized.
        let path: [Float] = switch keyCode {
        case kVK_UpArrow: [0.5, 0.7, 0.5, 0.3]
        case kVK_DownArrow: [0.5, 0.3, 0.5, 0.7]
        case kVK_LeftArrow: [0.8, 0.5, 0.2, 0.5]
        default: [0.2, 0.5, 0.8, 0.5]
        }
        var w = ByteWriter()
        path.forEach { w.f32($0) }
        w.i32(150)
        send?(.swipe, w.bytes)
    }

    /// Cmd shortcuts arrive here, not in keyDown, so they are handled before AppKit's own
    /// key-equivalent handling swallows them. Matched by physical key so they work whatever
    /// keyboard language is active.
    override func performKeyEquivalent(with event: NSEvent) -> Bool {
        guard event.type == .keyDown,
              event.modifierFlags.intersection(.deviceIndependentFlagsMask).contains(.command)
        else { return super.performKeyEquivalent(with: event) }
        switch Int(event.keyCode) {
        case kVK_ANSI_1: sendKey(AndroidKey.home)
        case kVK_ANSI_2: sendKey(AndroidKey.appSwitch)
        case kVK_ANSI_3: sendKey(AndroidKey.search)
        case kVK_ANSI_L: onPanelToggle?()
        case kVK_ANSI_K: onKeyframeRequest?()
        case kVK_ANSI_R: onRotate?()
        default: return super.performKeyEquivalent(with: event)
        }
        return true
    }

    override func keyDown(with event: NSEvent) {
        let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        let keyCode = Int(event.keyCode)
        if flags.contains(.command) {
            super.keyDown(with: event)
            return
        }
        // Arrows swipe; with Option held they are real arrow keys (moving a text cursor).
        if AndroidKey.isArrow(keyCode) && !flags.contains(.option) {
            swipe(keyCode)
            return
        }
        if let code = AndroidKey.forMacKeyCode(keyCode) {
            sendKey(code)
            return
        }
        if let text = event.characters, !text.isEmpty {
            send?(.text, Array(text.utf8))
        }
    }
}

enum AndroidKey {
    static let home: Int32 = 3
    static let back: Int32 = 4
    static let dpadUp: Int32 = 19
    static let dpadDown: Int32 = 20
    static let dpadLeft: Int32 = 21
    static let dpadRight: Int32 = 22
    static let tab: Int32 = 61
    static let enter: Int32 = 66
    static let del: Int32 = 67
    static let search: Int32 = 84
    static let forwardDel: Int32 = 112
    static let appSwitch: Int32 = 187

    static func isArrow(_ code: Int) -> Bool {
        [kVK_UpArrow, kVK_DownArrow, kVK_LeftArrow, kVK_RightArrow].contains(code)
    }

    static func forMacKeyCode(_ code: Int) -> Int32? {
        switch code {
        case kVK_Return, kVK_ANSI_KeypadEnter: return enter
        case kVK_Delete: return del
        case kVK_ForwardDelete: return forwardDel
        case kVK_Tab: return tab
        case kVK_Escape: return back
        case kVK_UpArrow: return dpadUp
        case kVK_DownArrow: return dpadDown
        case kVK_LeftArrow: return dpadLeft
        case kVK_RightArrow: return dpadRight
        default: return nil
        }
    }
}
