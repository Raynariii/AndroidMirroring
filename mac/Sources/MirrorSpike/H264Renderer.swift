import AVFoundation
import CoreMedia

/// Feeds Annex-B H.264 from the helper into an AVSampleBufferDisplayLayer, which decodes with
/// VideoToolbox in hardware.
final class H264Renderer {
    let layer = AVSampleBufferDisplayLayer()
    private var format: CMVideoFormatDescription?

    init() {
        layer.videoGravity = .resizeAspect
    }

    /// `codecConfig` is the encoder's CODEC_CONFIG buffer (SPS + PPS, Annex-B).
    func configure(codecConfig: ArraySlice<UInt8>) -> Bool {
        let units = AnnexB.split(codecConfig)
        guard let sps = units.first(where: { $0.first.map { $0 & 0x1f == 7 } ?? false }),
              let pps = units.first(where: { $0.first.map { $0 & 0x1f == 8 } ?? false })
        else { return false }

        var description: CMFormatDescription?
        let status = sps.withUnsafeBufferPointer { s in
            pps.withUnsafeBufferPointer { p in
                let pointers: [UnsafePointer<UInt8>] = [s.baseAddress!, p.baseAddress!]
                let sizes: [Int] = [sps.count, pps.count]
                return CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault,
                    parameterSetCount: 2,
                    parameterSetPointers: pointers,
                    parameterSetSizes: sizes,
                    nalUnitHeaderLength: 4,
                    formatDescriptionOut: &description
                )
            }
        }
        guard status == noErr, let description else { return false }
        format = description
        layer.flush()
        return true
    }

    /// Returns false when the layer needs a keyframe to recover.
    func enqueue(frame: ArraySlice<UInt8>) -> Bool {
        guard let format else { return false }
        if layer.status == .failed {
            layer.flush()
            return false
        }

        // Annex-B start codes -> 4-byte big-endian lengths (AVCC), dropping in-band SPS/PPS.
        var avcc: [UInt8] = []
        avcc.reserveCapacity(frame.count + 16)
        for unit in AnnexB.split(frame) {
            let type = unit[0] & 0x1f
            if type == 7 || type == 8 { continue }
            withUnsafeBytes(of: UInt32(unit.count).bigEndian) { avcc.append(contentsOf: $0) }
            avcc.append(contentsOf: unit)
        }
        if avcc.isEmpty { return true }

        var block: CMBlockBuffer?
        guard CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault,
            memoryBlock: nil,
            blockLength: avcc.count,
            blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil,
            offsetToData: 0,
            dataLength: avcc.count,
            flags: 0,
            blockBufferOut: &block
        ) == kCMBlockBufferNoErr, let block else { return false }

        let copied = avcc.withUnsafeBytes {
            CMBlockBufferReplaceDataBytes(with: $0.baseAddress!, blockBuffer: block, offsetIntoDestination: 0, dataLength: avcc.count)
        }
        guard copied == kCMBlockBufferNoErr else { return false }

        var sample: CMSampleBuffer?
        var size = avcc.count
        guard CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault,
            dataBuffer: block,
            formatDescription: format,
            sampleCount: 1,
            sampleTimingEntryCount: 0,
            sampleTimingArray: nil,
            sampleSizeEntryCount: 1,
            sampleSizeArray: &size,
            sampleBufferOut: &sample
        ) == noErr, let sample else { return false }

        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true),
           CFArrayGetCount(attachments) > 0 {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(
                dict,
                Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                Unmanaged.passUnretained(kCFBooleanTrue).toOpaque()
            )
        }
        layer.enqueue(sample)
        return true
    }
}

enum AnnexB {
    /// Splits an Annex-B byte stream into NAL units (without start codes).
    static func split(_ data: ArraySlice<UInt8>) -> [[UInt8]] {
        var units: [[UInt8]] = []
        let bytes = Array(data)
        var i = 0
        var start = -1
        while i + 2 < bytes.count {
            if bytes[i] == 0, bytes[i + 1] == 0, bytes[i + 2] == 1 {
                if start >= 0 {
                    var end = i
                    if end > start, bytes[end - 1] == 0 { end -= 1 }  // 4-byte start code
                    if end > start { units.append(Array(bytes[start..<end])) }
                }
                i += 3
                start = i
            } else {
                i += 1
            }
        }
        if start >= 0, start < bytes.count { units.append(Array(bytes[start...])) }
        return units
    }
}
