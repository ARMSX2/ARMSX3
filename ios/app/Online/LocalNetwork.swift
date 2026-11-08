// SPDX-License-Identifier: GPL-2.0-only
// Raises iOS's Local Network prompt from Settings, never in a game, by Apple's TN3179 technique.

import Foundation
import Network

enum LocalNetwork {
    static var sentence: String {
        String(localized: "iOS asks next whether ARMSX3 can use your local network. Games need it to reach a player on the same Wi-Fi.")
    }

    /// Best effort, as TN3179 says: errors are ignored and nothing guarantees the prompt. No byte is written.
    @MainActor static func trigger() {
        // TN3179's listing from here down, renamed into this enum.
        for address in selectedLinkLocalIPv6Addresses() {
            let sock6 = socket(AF_INET6, SOCK_DGRAM, 0)
            guard sock6 >= 0 else { return }
            defer { close(sock6) }

            withUnsafePointer(to: address) { sa6 in
                sa6.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                    _ = connect(sock6, sa, socklen_t(sa.pointee.sa_len)) >= 0
                }
            }
        }
    }

    /// Port 9 is the discard service, so traffic would be discarded even if any were sent.
    private static func selectedLinkLocalIPv6Addresses() -> [sockaddr_in6] {
        let r1 = (0..<8).map { _ in UInt8.random(in: 0...255) }
        let r2 = (0..<8).map { _ in UInt8.random(in: 0...255) }
        return Array(ipv6AddressesOfBroadcastCapableInterfaces()
            .filter { isIPv6AddressLinkLocal($0) }
            .map { var addr = $0; addr.sin6_port = UInt16(9).bigEndian; return addr }
            .map { [setIPv6LinkLocalAddressHostPart(of: $0, to: r1), setIPv6LinkLocalAddressHostPart(of: $0, to: r2)] }
            .joined())
    }

    /// Replaces the bottom 64 bits, the interface ID, of a link-local address.
    private static func setIPv6LinkLocalAddressHostPart(of address: sockaddr_in6, to hostPart: [UInt8]) -> sockaddr_in6 {
        precondition(hostPart.count == 8)
        var result = address
        withUnsafeMutableBytes(of: &result.sin6_addr) { buf in
            buf[8...].copyBytes(from: hostPart)
        }
        return result
    }

    private static func isIPv6AddressLinkLocal(_ address: sockaddr_in6) -> Bool {
        address.sin6_addr.__u6_addr.__u6_addr8.0 == 0xfe
            && (address.sin6_addr.__u6_addr.__u6_addr8.1 & 0xc0) == 0x80
    }

    private static func ipv6AddressesOfBroadcastCapableInterfaces() -> [sockaddr_in6] {
        var addrList: UnsafeMutablePointer<ifaddrs>? = nil
        let err = getifaddrs(&addrList)
        guard err == 0, let start = addrList else { return [] }
        defer { freeifaddrs(start) }
        return sequence(first: start, next: { $0.pointee.ifa_next })
            .compactMap { i -> sockaddr_in6? in
                guard
                    (i.pointee.ifa_flags & UInt32(bitPattern: IFF_BROADCAST)) != 0,
                    let sa = i.pointee.ifa_addr,
                    sa.pointee.sa_family == AF_INET6,
                    sa.pointee.sa_len >= MemoryLayout<sockaddr_in6>.size
                else { return nil }
                return UnsafeRawPointer(sa).load(as: sockaddr_in6.self)
            }
    }

    static var offValue: String { String(localized: "Off") }
    static var fix: String {
        String(localized: "Games can't reach a player on the same Wi-Fi. Turn on Local Network for ARMSX3 in the Settings app, under Privacy & Security.")
    }

    /// iOS has no permission state to read, so a probe that goes `.waiting` with `.localNetworkDenied` is the only signal.
    @MainActor static func isDenied() async -> Bool {
        return await probeDenied()
    }

    /// The connection runs on a queue of its own, never the main queue, and is cancelled once it has answered.
    private static func probeDenied() async -> Bool {
        guard let host = probeHost() else { return false }
        return await withCheckedContinuation { continuation in
            DenialProbe(NWConnection(host: host, port: 9, using: .udp), continuation).start()
        }
    }

    /// The `%` suffix carries the interface. Nil when no such interface is up, and then nothing is probed.
    private static func probeHost() -> NWEndpoint.Host? {
        var addrList: UnsafeMutablePointer<ifaddrs>? = nil
        guard getifaddrs(&addrList) == 0, let start = addrList else { return nil }
        defer { freeifaddrs(start) }
        for i in sequence(first: start, next: { $0.pointee.ifa_next }) {
            guard (i.pointee.ifa_flags & UInt32(bitPattern: IFF_BROADCAST)) != 0, let sa = i.pointee.ifa_addr,
                  sa.pointee.sa_family == AF_INET6, sa.pointee.sa_len >= MemoryLayout<sockaddr_in6>.size,
                  isIPv6AddressLinkLocal(UnsafeRawPointer(sa).load(as: sockaddr_in6.self)) else { continue }
            let hostPart = (0..<4).map { _ in String(UInt16.random(in: 1...0xffff), radix: 16) }.joined(separator: ":")
            return IPv6Address("fe80::\(hostPart)%\(String(cString: i.pointee.ifa_name))").map { .ipv6($0) }
        }
        return nil
    }
}

/// Its state is touched only on its own serial queue, which is what makes the unchecked Sendable sound.
private final class DenialProbe: @unchecked Sendable {
    private let queue = DispatchQueue(label: "armsx3.localnetwork", qos: .utility)
    private let connection: NWConnection
    private var continuation: CheckedContinuation<Bool, Never>?

    init(_ connection: NWConnection, _ continuation: CheckedContinuation<Bool, Never>) {
        self.connection = connection
        self.continuation = continuation
    }

    func start() {
        connection.stateUpdateHandler = { [self] state in
            switch state {
            case .waiting:
                if case .localNetworkDenied? = connection.currentPath?.unsatisfiedReason { finish(true) }
            case .ready, .failed, .cancelled:
                finish(false)
            default:
                break
            }
        }
        connection.start(queue: queue)
        queue.asyncAfter(deadline: .now() + 2) { [self] in finish(false) }
    }

    private func finish(_ denied: Bool) {
        guard let continuation else { return }
        self.continuation = nil
        connection.stateUpdateHandler = nil
        connection.cancel()
        continuation.resume(returning: denied)
    }
}
