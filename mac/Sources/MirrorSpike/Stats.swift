import Foundation

/// Rolling stream statistics. Latency is capture-on-phone to receive-on-Mac, using a clock
/// offset estimated from PING/PONG (the sample with the lowest round trip wins).
struct StreamStats {
    private(set) var clockOffset: Int64?  // phone clock minus Mac clock, microseconds
    private(set) var bestRtt = Int64.max
    private var lastRtt: Int64 = 0
    private var frames = 0
    private var bytes = 0
    private var latencies: [Int64] = []

    mutating func pong(echo: Int64, phoneNow: Int64) {
        let now = nowMicros()
        let rtt = now - echo
        lastRtt = rtt
        if rtt <= bestRtt {
            bestRtt = rtt
            clockOffset = phoneNow - (echo + rtt / 2)
        }
    }

    mutating func frame(captureMicros: Int64, size: Int) {
        frames += 1
        bytes += size
        if let clockOffset {
            latencies.append(nowMicros() - (captureMicros - clockOffset))
        }
    }

    /// Returns a one-line summary of the last interval and resets the counters.
    mutating func summary(interval: Double) -> String {
        let fps = Double(frames) / interval
        let mbps = Double(bytes) * 8 / interval / 1_000_000
        var text = String(format: "%.0f fps · %.1f Mbps · RTT %.1f ms", fps, mbps, Double(lastRtt) / 1000)
        if !latencies.isEmpty {
            let sorted = latencies.sorted()
            let p50 = Double(sorted[sorted.count / 2]) / 1000
            let p95 = Double(sorted[min(sorted.count - 1, sorted.count * 95 / 100)]) / 1000
            text += String(format: " · latency p50 %.0f ms p95 %.0f ms", p50, p95)
        }
        frames = 0
        bytes = 0
        latencies.removeAll(keepingCapacity: true)
        return text
    }
}
