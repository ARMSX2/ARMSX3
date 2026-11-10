// Copyright 2025 DolphiniOS Project
// SPDX-License-Identifier: GPL-2.0-or-later

// Adapted from DolphiniOS pull request #277: the helper's one pre-main JIT pass, plus disk-image Reset and Mount after main().

import Foundation
import idevice

// This port's XPC contract; the selectors match StikJITExtensionLauncher.h so the anonymous connection binds across targets.
@objc(Armsx3JITEnablerRunner)
protocol Armsx3JITEnablerRunner {
    func prepare(forParentPID pid: Int32,
                 ranges: Data,
                 pairing: Data,
                 withReply reply: @escaping ([String: Any]) -> Void)
    // resetDDIWithReply:, matching the header.
    func resetDDI(withReply reply: @escaping ([String: Any]) -> Void)
    // mountDDIWithPairing:withReply:, matching the header.
    func mountDDI(withPairing pairing: Data, withReply reply: @escaping ([String: Any]) -> Void)
    // checkSetupWithPairing:withReply:, a check-only request that never mounts.
    func checkSetup(withPairing pairing: Data, withReply reply: @escaping ([String: Any]) -> Void)
}

@objc(Armsx3JITEnablerHost)
protocol Armsx3JITEnablerHost {
    func helperLog(_ line: String)
    // One mount stage word, forward only.
    func helperStage(_ word: String)
}

private let kListenerEndpointKey = "Armsx3JITEnablerListenerEndpoint"

// Literals, so both targets agree without a shared header; StikJITExtensionLauncher.m declares the same strings.
private enum ReplyKey {
    static let outcome = "outcome"
    static let attachMs = "attach_ms"
    static let prepareMs = "prepare_ms"
    static let detachMs = "detach_ms"
    static let pagesRequested = "pages_requested"
    static let pagesWritten = "pages_written"
}

// The disk-image reply keys (kArmsx3DDIResult, kArmsx3DDIReason on the host side).
private enum DDIReplyKey {
    static let result = "result"
    static let reason = "reason"
}

// The RSD endpoint the software-TCP tunnel exposes (StikJIT's Configuration defaults; not a device id).
private let kRSDAddress = "10.7.0.1"
private let kRSDPort: UInt16 = 49152

@objc(JITEnablerRequestHandler)
final class JITEnablerRequestHandler: NSObject, NSExtensionRequestHandling, Armsx3JITEnablerRunner {
    private var connection: NSXPCConnection?
    private var host: Armsx3JITEnablerHost?
    // Every disk-image operation runs on this one serial background queue.
    private let ddiQueue = DispatchQueue(label: "armsx3.jitenabler.ddi")

    func beginRequest(with context: NSExtensionContext) {
        NSLog("[JITEnabler] helper beginRequest (one-pass request handler)")

        guard let item = context.inputItems.first as? NSExtensionItem,
              let endpoint = item.userInfo?[kListenerEndpointKey] as? NSXPCListenerEndpoint else {
            NSLog("[JITEnabler] missing listener endpoint")
            context.cancelRequest(withError: NSError(domain: "JITEnablerRequestHandler", code: 1))
            return
        }

        let connection = NSXPCConnection(listenerEndpoint: endpoint)
        connection.exportedInterface = NSXPCInterface(with: Armsx3JITEnablerRunner.self)
        connection.exportedObject = self
        connection.remoteObjectInterface = NSXPCInterface(with: Armsx3JITEnablerHost.self)
        connection.resume()
        self.connection = connection
        self.host = connection.remoteObjectProxyWithErrorHandler { _ in } as? Armsx3JITEnablerHost
        host?.helperLog("JITEnabler helper alive, pid \(getpid())")
    }

    func prepare(forParentPID pid: Int32,
                 ranges: Data,
                 pairing: Data,
                 withReply reply: @escaping ([String: Any]) -> Void) {
        host?.helperLog("prepare requested for pid \(pid), \(ranges.count / 16) range(s)")
        // performOnePass frees everything and removes the staged pairing file at its scope exit, before this reply.
        let result = performOnePass(pid: pid, ranges: ranges, pairing: pairing)
        reply(result)
    }

    // MARK: - disk-image operations after main()

    /// Deletes only this helper's cached disk-image files; StikJIT's error text is never sent, since it embeds paths.
    func resetDDI(withReply reply: @escaping ([String: Any]) -> Void) {
        ddiQueue.async {
            let library = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask)[0]
            let paths = DDIPaths.default(in: library.appendingPathComponent("StikJIT"))
            var word = "deleted"
            var reason = "none"
            do {
                try paths.removeCachedFiles()
            } catch {
                word = "failed"
                reason = "remove-failed"
            }
            self.host?.helperLog("ddi reset \(word)")
            reply([DDIReplyKey.result: word, DDIReplyKey.reason: reason])
        }
    }

    /// One mount attempt through DDIMount.run on the DDI queue; the staged pairing file is removed before the reply.
    func mountDDI(withPairing pairing: Data, withReply reply: @escaping ([String: Any]) -> Void) {
        ddiQueue.async {
            let result = self.performMount(pairing: pairing)
            self.host?.helperLog("ddi mount \((result[DDIReplyKey.result] as? String) ?? "other")")
            reply(result)
        }
    }

    /// One check-only request on the DDI queue: probe, then ask whether the image is mounted; it never downloads or mounts.
    @objc(checkSetupWithPairing:withReply:)
    func checkSetup(withPairing pairing: Data, withReply reply: @escaping ([String: Any]) -> Void) {
        ddiQueue.async {
            let result = self.performCheck(pairing: pairing)
            self.host?.helperLog("ddi check \((result[DDIReplyKey.result] as? String) ?? "other")")
            reply(result)
        }
    }

    private func performCheck(pairing: Data) -> [String: Any] {
        if pairing.isEmpty || pairing.count > 65536 {
            return [DDIReplyKey.result: "pairing", DDIReplyKey.reason: "invalid-request"]
        }
        let tempPath = (NSTemporaryDirectory() as NSString)
            .appendingPathComponent("armsx3-pairing-\(UUID().uuidString).plist")
        defer { try? FileManager.default.removeItem(atPath: tempPath) }
        do {
            try pairing.write(to: URL(fileURLWithPath: tempPath), options: .atomic)
        } catch {
            return [DDIReplyKey.result: "other", DDIReplyKey.reason: "stage-failed"]
        }
        let r = DDIMount.check(pairingPath: tempPath, stage: { self.host?.helperStage($0) })
        return [DDIReplyKey.result: r.result.rawValue, DDIReplyKey.reason: r.reason]
    }

    private func performMount(pairing: Data) -> [String: Any] {
        // Refused before any file is written: an RPPairing file is a small plist.
        if pairing.isEmpty || pairing.count > 65536 {
            return [DDIReplyKey.result: "pairing", DDIReplyKey.reason: "invalid-request"]
        }
        let tempPath = (NSTemporaryDirectory() as NSString)
            .appendingPathComponent("armsx3-pairing-\(UUID().uuidString).plist")
        defer { try? FileManager.default.removeItem(atPath: tempPath) }
        do {
            try pairing.write(to: URL(fileURLWithPath: tempPath), options: .atomic)
        } catch {
            return [DDIReplyKey.result: "other", DDIReplyKey.reason: "stage-failed"]
        }
        let library = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask)[0]
        let paths = DDIPaths.default(in: library.appendingPathComponent("StikJIT"))
        let r = DDIMount.run(pairingPath: tempPath, paths: paths, stage: { self.host?.helperStage($0) })
        return [DDIReplyKey.result: r.result.rawValue, DDIReplyKey.reason: r.reason]
    }

    // MARK: - request validation (before any side effect)

    /// Rejects a malformed request before any side effect, so every count and timing stays -1.
    private func invalidRequestResult() -> [String: Any] {
        return [
            ReplyKey.outcome: "invalid-request",
            ReplyKey.attachMs: NSNumber(value: -1),
            ReplyKey.prepareMs: NSNumber(value: -1),
            ReplyKey.detachMs: NSNumber(value: -1),
            ReplyKey.pagesRequested: NSNumber(value: -1),
            ReplyKey.pagesWritten: NSNumber(value: -1),
        ]
    }

    /// The exact page count of a well-formed request, else nil: bad input would trap BlessCommands' checked arithmetic.
    private func validatedPageCount(pid: Int32, base: UInt64, size: UInt64) -> Int? {
        guard pid > 0 else { return nil }
        guard base != 0, base % BlessCommands.jitPageSize == 0 else { return nil }
        guard size != 0, size % BlessCommands.jitPageSize == 0 else { return nil }
        guard base <= UInt64.max - size else { return nil }        // base + size must not overflow
        let pages = size / BlessCommands.jitPageSize                // exact: size is a nonzero multiple
        guard pages <= UInt64(Int.max) else { return nil }          // representable as Int
        return Int(pages)
    }

    /// The most ranges one request may carry; the dual map sends three.
    private static let maxRanges = 8

    /// Every range as (base, page count), or nil if any range or the page total is malformed.
    private func validatedRanges(pid: Int32, ranges: Data) -> [(base: UInt64, pages: Int)]? {
        guard ranges.count % 16 == 0, (1...Self.maxRanges).contains(ranges.count / 16) else { return nil }
        var out: [(base: UInt64, pages: Int)] = []
        var total = 0
        for i in 0..<(ranges.count / 16) {
            let base = ranges.withUnsafeBytes { UInt64(littleEndian: $0.loadUnaligned(fromByteOffset: i * 16, as: UInt64.self)) }
            let size = ranges.withUnsafeBytes { UInt64(littleEndian: $0.loadUnaligned(fromByteOffset: i * 16 + 8, as: UInt64.self)) }
            guard let pages = validatedPageCount(pid: pid, base: base, size: size) else { return nil }
            let (sum, overflow) = total.addingReportingOverflow(pages)
            guard !overflow else { return nil }
            total = sum
            out.append((base, pages))
        }
        return out
    }

    // MARK: - the one pass

    private func performOnePass(pid: Int32, ranges rawRanges: Data, pairing: Data) -> [String: Any] {
        // Validate first: a malformed request gets no file, tunnel, attach, write or detach.
        guard let ranges = validatedRanges(pid: pid, ranges: rawRanges) else {
            return invalidRequestResult()
        }
        let pagesRequested = ranges.reduce(0) { $0 + $1.pages }
        var result: [String: Any] = [
            ReplyKey.outcome: "connect-failed",
            ReplyKey.attachMs: NSNumber(value: -1),
            ReplyKey.prepareMs: NSNumber(value: -1),
            ReplyKey.detachMs: NSNumber(value: -1),
            ReplyKey.pagesRequested: NSNumber(value: pagesRequested),
            ReplyKey.pagesWritten: NSNumber(value: -1),
        ]

        // 1. Stage the pairing bytes in this helper's own temp directory, and remove them on every path.
        let tempPath = (NSTemporaryDirectory() as NSString)
            .appendingPathComponent("armsx3-pairing-\(UUID().uuidString).plist")
        do {
            try pairing.write(to: URL(fileURLWithPath: tempPath), options: .atomic)
        } catch {
            result[ReplyKey.outcome] = "pairing-absent"
            return result
        }
        defer { try? FileManager.default.removeItem(atPath: tempPath) }

        // 2. Read the staged pairing file.
        var pairingHandle: OpaquePointer?
        // The bytes arrived but are not an RPPairing file.
        guard ok(tempPath.withCString { rp_pairing_file_read($0, &pairingHandle) }), pairingHandle != nil else {
            result[ReplyKey.outcome] = "pairing-rejected"
            return result
        }
        defer { rp_pairing_file_free(pairingHandle) }

        // 3. Tunnel to the device's RSD endpoint: a bounded probe first, then at most one retry after an early failure.
        let passStart = DispatchTime.now()
        if EndpointProbe.failureReason(address: kRSDAddress, port: kRSDPort, timeout: HelperDecisions.probeTimeout) != nil {
            host?.helperLog("probe failed after \(elapsedMs(since: passStart)) ms")
            result[ReplyKey.outcome] = "connect-failed"
            return result
        }

        var adapter: OpaquePointer?
        var handshake: OpaquePointer?
        var attempts = 1
        var tunnelOK = createTunnel(pairingHandle, &adapter, &handshake)
        if !tunnelOK && HelperDecisions.shouldRetryTunnel(elapsedMs: elapsedMs(since: passStart)) {
            attempts = 2
            tunnelOK = createTunnel(pairingHandle, &adapter, &handshake)
        }
        host?.helperLog("tunnel attempts=\(attempts) ok=\(tunnelOK) after \(elapsedMs(since: passStart)) ms")
        guard tunnelOK else {
            // The endpoint answered the probe, so the tunnel or its pair-verify was refused.
            result[ReplyKey.outcome] = "tunnel-failed"
            return result
        }
        defer {
            if let handshake { rsd_handshake_free(handshake) }
            if let adapter { adapter_free(adapter) }
        }

        // 4. Before main() the disk image must already be mounted: check only, never mount or download; unknown goes on.
        if developerDiskImageState(adapter: adapter, handshake: handshake) == .notMounted {
            result[ReplyKey.outcome] = "ddi-not-mounted"
            return result
        }

        // 5. RSD remote server + debug proxy over the one tunnel.
        var remoteServer: OpaquePointer?
        guard ok(remote_server_connect_rsd(adapter, handshake, &remoteServer)) else {
            result[ReplyKey.outcome] = "connect-failed"
            return result
        }
        defer { if let remoteServer { remote_server_free(remoteServer) } }

        var debugProxy: OpaquePointer?
        guard ok(debug_proxy_connect_rsd(adapter, handshake, &debugProxy)), let proxy = debugProxy else {
            result[ReplyKey.outcome] = "connect-failed"
            return result
        }
        defer { debug_proxy_free(proxy) }

        // 6. Attach.
        let attachStart = DispatchTime.now()
        debug_proxy_send_ack(proxy)
        _ = sendCommand("QStartNoAckMode", over: proxy)
        debug_proxy_set_ack_mode(proxy, 0)
        let (attachOK, attachResponse) = sendCommand("vAttach;\(String(pid, radix: 16))", over: proxy)
        result[ReplyKey.attachMs] = NSNumber(value: elapsedMs(since: attachStart))
        guard attachOK, let response = attachResponse, !response.isEmpty, !response.hasPrefix("$E"), !response.hasPrefix("E") else {
            result[ReplyKey.outcome] = "attach-failed"
            return result
        }

        // 7. Prepare: one full-width M write at each 16 KiB page start, in batches of 128, range by range.
        let prepareStart = DispatchTime.now()
        var pagesWritten = 0
        var prepareComplete = true
        for (base, rangePages) in ranges where prepareComplete {
            let (commandBuffer, commandOffsets) = BlessCommands.makeBlessCommands(startAddress: base, pageCount: rangePages)
            var batchStart = 0
            for batch in BlessCommands.batches(offsets: commandOffsets, pageCount: rangePages) {
                let commandsInBatch = min(BlessCommands.commandsPerBatch, rangePages - batchStart)

                let sendError = commandBuffer.withUnsafeBytes { raw -> UnsafeMutablePointer<IdeviceFfiError>? in
                    let bytes = raw.bindMemory(to: UInt8.self).baseAddress!
                    return debug_proxy_send_raw(proxy, bytes.advanced(by: batch.byteOffset), UInt(batch.byteCount))
                }
                if !ok(sendError) { prepareComplete = false; break }

                for _ in 0..<commandsInBatch {
                    var response: UnsafeMutablePointer<CChar>?
                    let readError = debug_proxy_read_response(proxy, &response)
                    if let response {
                        if String(cString: response).contains("OK") { pagesWritten += 1 }
                        idevice_string_free(response)
                    }
                    if !ok(readError) { prepareComplete = false; break }
                }
                if !prepareComplete { break }
                batchStart += commandsInBatch
            }
        }
        result[ReplyKey.prepareMs] = NSNumber(value: elapsedMs(since: prepareStart))
        result[ReplyKey.pagesWritten] = NSNumber(value: pagesWritten)

        // 8. Detach, because the attach succeeded, even if the prepare fell short.
        let detachStart = DispatchTime.now()
        let (detachOK, _) = sendCommand("D", over: proxy)
        result[ReplyKey.detachMs] = NSNumber(value: elapsedMs(since: detachStart))

        // 9. The outcome word. A partial prepare stays partial and is never reported as finished.
        if !prepareComplete || pagesWritten != pagesRequested {
            result[ReplyKey.outcome] = "prepare-incomplete"
        } else if !detachOK {
            result[ReplyKey.outcome] = "detach-failed"
        } else {
            result[ReplyKey.outcome] = "finished"
        }
        return result
    }

    // MARK: - small FFI helpers

    /// True on success. A non-nil IdeviceFfiError is freed and reported as failure.
    private func ok(_ error: UnsafeMutablePointer<IdeviceFfiError>?) -> Bool {
        if let error {
            idevice_error_free(error)
            return false
        }
        return true
    }

    private func elapsedMs(since start: DispatchTime) -> Int64 {
        Int64((DispatchTime.now().uptimeNanoseconds &- start.uptimeNanoseconds) / 1_000_000)
    }

    private func sendCommand(_ command: String, over proxy: OpaquePointer) -> (ok: Bool, response: String?) {
        guard let handle = command.withCString({ debugserver_command_new($0, nil, 0) }) else { return (false, nil) }
        defer { debugserver_command_free(handle) }
        var response: UnsafeMutablePointer<CChar>?
        let error = debug_proxy_send_command(proxy, handle, &response)
        if !ok(error) {
            if let response { idevice_string_free(response) }
            return (false, nil)
        }
        guard let response else { return (true, nil) }
        defer { idevice_string_free(response) }
        return (true, String(cString: response))
    }

    /// One software-TCP RPPairing tunnel attempt to the RSD endpoint. On failure both handles stay nil.
    private func createTunnel(_ pairing: OpaquePointer?, _ adapter: inout OpaquePointer?,
                              _ handshake: inout OpaquePointer?) -> Bool {
        var address = sockaddr_in()
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = kRSDPort.bigEndian
        _ = kRSDAddress.withCString { inet_pton(AF_INET, $0, &address.sin_addr) }

        let tunnelError = "ARMSX3".withCString { hostname -> UnsafeMutablePointer<IdeviceFfiError>? in
            withUnsafePointer(to: &address) { pointer in
                pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                    tunnel_create_rppairing(sa, socklen_t(MemoryLayout<sockaddr_in>.stride),
                                            hostname, pairing, nil, nil, &adapter, &handshake)
                }
            }
        }
        return ok(tunnelError)
    }

    /// The disk image's state from two read-only answers: RSD's debug proxy listing, then the legacy mounter only if that failed.
    private func developerDiskImageState(adapter: OpaquePointer?, handshake: OpaquePointer?) -> DDIAnswer {
        var available = false
        let listed: Bool? = ok("com.apple.internal.dt.remote.debugproxy".withCString {
            rsd_service_available(handshake, $0, &available)
        }) ? available : nil
        let legacy = listed == nil ? legacyImageMounted(adapter: adapter, handshake: handshake) : nil
        let verdict = HelperDecisions.ddiVerdict(debugProxyListed: listed, legacy: legacy)
        func word(_ answer: Bool?) -> String { answer.map { $0 ? "y" : "n" } ?? "err" }
        host?.helperLog("ddi rsd=\(word(listed)) legacy=\(listed == nil ? word(legacy) : "skip") -> \(verdict.rawValue)")
        return verdict
    }

    /// nil when the mounter could not be reached, else whether an image is mounted.
    private func legacyImageMounted(adapter: OpaquePointer?, handshake: OpaquePointer?) -> Bool? {
        var client: OpaquePointer?
        guard ok(image_mounter_connect_rsd(adapter, handshake, &client)), client != nil else { return nil }
        defer { image_mounter_free(client) }

        var devices: UnsafeMutablePointer<plist_t?>?
        var deviceCount = 0
        guard ok(image_mounter_copy_devices(client, &devices, &deviceCount)) else { return nil }
        if let devices {
            for index in 0..<deviceCount { plist_free(devices[index]) }
            idevice_data_free(
                UnsafeMutableRawPointer(devices).assumingMemoryBound(to: UInt8.self),
                UInt(deviceCount * MemoryLayout<plist_t?>.stride))
        }
        return deviceCount > 0
    }
}
