import Foundation

/// Spike wire format, mirrored from android/.../helper/Protocol.kt.
/// Every message is `type: u8, length: u32 BE, payload`, big-endian throughout.
enum MsgType: UInt8 {
    // Helper -> Mac
    case helloAck = 0x01
    case videoConfig = 0x02
    case videoFrame = 0x03
    case pong = 0x04
    case log = 0x05

    // Mac -> helper
    case hello = 0x10
    case touch = 0x11
    case scroll = 0x12
    case key = 0x13
    case text = 0x14
    case panel = 0x15
    case ping = 0x16
    case requestKeyframe = 0x17
}

struct ByteWriter {
    private(set) var bytes: [UInt8] = []

    mutating func u8(_ v: UInt8) { bytes.append(v) }

    mutating func i32(_ v: Int32) {
        withUnsafeBytes(of: v.bigEndian) { bytes.append(contentsOf: $0) }
    }

    mutating func i64(_ v: Int64) {
        withUnsafeBytes(of: v.bigEndian) { bytes.append(contentsOf: $0) }
    }

    mutating func f32(_ v: Float) { i32(Int32(bitPattern: v.bitPattern)) }
}

struct ByteReader {
    let bytes: [UInt8]
    private(set) var offset = 0

    init(_ bytes: [UInt8]) { self.bytes = bytes }

    var remaining: ArraySlice<UInt8> { bytes[offset...] }

    mutating func u8() -> UInt8 {
        defer { offset += 1 }
        return bytes[offset]
    }

    mutating func u32() -> UInt32 {
        var v: UInt32 = 0
        for _ in 0..<4 { v = v << 8 | UInt32(u8()) }
        return v
    }

    mutating func i64() -> Int64 {
        var v: UInt64 = 0
        for _ in 0..<8 { v = v << 8 | UInt64(u8()) }
        return Int64(bitPattern: v)
    }
}

/// Monotonic microseconds on the Mac.
func nowMicros() -> Int64 {
    Int64(DispatchTime.now().uptimeNanoseconds / 1000)
}
