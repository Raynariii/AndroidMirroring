import Foundation
import Network

/// Framed TCP connection to the phone helper. Callbacks arrive on the main queue.
final class HelperConnection {
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "mirror.connection")

    var onMessage: ((MsgType, [UInt8]) -> Void)?
    var onStatus: ((String) -> Void)?

    init(host: String, port: UInt16) {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        let params = NWParameters(tls: nil, tcp: tcp)
        connection = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!, using: params)
    }

    func start(token: String) {
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            self.status("\(state)")
            if case .ready = state {
                self.send(.hello, Array(token.utf8))
                self.readHeader()
            }
        }
        connection.start(queue: queue)
    }

    func send(_ type: MsgType, _ payload: [UInt8] = []) {
        var frame: [UInt8] = [type.rawValue]
        withUnsafeBytes(of: UInt32(payload.count).bigEndian) { frame.append(contentsOf: $0) }
        frame.append(contentsOf: payload)
        connection.send(content: Data(frame), completion: .contentProcessed { _ in })
    }

    private func status(_ text: String) {
        DispatchQueue.main.async { self.onStatus?(text) }
    }

    private func readHeader() {
        connection.receive(minimumIncompleteLength: 5, maximumLength: 5) { [weak self] data, _, _, error in
            guard let self else { return }
            guard let data, data.count == 5, error == nil else {
                self.status("closed \(error.map { "\($0)" } ?? "")")
                return
            }
            let header = [UInt8](data)
            let length = Int(header[1]) << 24 | Int(header[2]) << 16 | Int(header[3]) << 8 | Int(header[4])
            if length == 0 {
                self.dispatch(header[0], [])
                self.readHeader()
            } else {
                self.readBody(type: header[0], length: length)
            }
        }
    }

    private func readBody(type: UInt8, length: Int) {
        connection.receive(minimumIncompleteLength: length, maximumLength: length) { [weak self] data, _, _, error in
            guard let self else { return }
            guard let data, data.count == length, error == nil else {
                self.status("closed \(error.map { "\($0)" } ?? "")")
                return
            }
            self.dispatch(type, [UInt8](data))
            self.readHeader()
        }
    }

    private func dispatch(_ rawType: UInt8, _ payload: [UInt8]) {
        guard let type = MsgType(rawValue: rawType) else { return }
        DispatchQueue.main.async { self.onMessage?(type, payload) }
    }
}
