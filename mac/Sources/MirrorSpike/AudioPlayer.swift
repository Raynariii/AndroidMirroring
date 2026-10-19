import AVFoundation

/// Plays the phone's sound: raw PCM, 48 kHz, 16-bit little-endian, stereo interleaved.
/// Keeps at most a quarter second queued so audio stays in step with the video.
final class AudioPlayer {
    private let engine = AVAudioEngine()
    private let node = AVAudioPlayerNode()
    private let format = AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 2)!
    private let lock = NSLock()
    private var queuedFrames = 0
    private var started = false

    private static let maxQueuedFrames = 12_000 // 250 ms
    private static let startAfterFrames = 2_400 // 50 ms of cushion before playing

    init() {
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: format)
    }

    func play(pcm: [UInt8]) {
        let frames = pcm.count / 4
        guard frames > 0 else { return }
        lock.lock()
        let queued = queuedFrames
        lock.unlock()
        if queued > Self.maxQueuedFrames { return }

        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frames)) else { return }
        buffer.frameLength = AVAudioFrameCount(frames)
        let left = buffer.floatChannelData![0], right = buffer.floatChannelData![1]
        for i in 0..<frames {
            let b = i * 4
            left[i] = Float(Int16(bitPattern: UInt16(pcm[b]) | UInt16(pcm[b + 1]) << 8)) / 32768
            right[i] = Float(Int16(bitPattern: UInt16(pcm[b + 2]) | UInt16(pcm[b + 3]) << 8)) / 32768
        }

        lock.lock()
        queuedFrames += frames
        let total = queuedFrames
        lock.unlock()
        node.scheduleBuffer(buffer) { [weak self] in
            guard let self else { return }
            self.lock.lock()
            self.queuedFrames -= frames
            self.lock.unlock()
        }

        if !started && total >= Self.startAfterFrames {
            do {
                try engine.start()
                node.play()
                started = true
            } catch {
                print("audio: cannot start output (\(error))")
            }
        }
    }
}
