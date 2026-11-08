import Foundation
import JavaScriptCore
@_implementationOnly import idevice

final class ScriptRunner {

    private static let jitPageSize: UInt64 = 16384
    private static let commandsPerBatch = 128

    private let targetPID: Int32
    private let debugProxy: OpaquePointer
    private let script: StikJIT.Script
    private let progress: (String) -> Void
    private var context: JSContext?
    private var executionError: StikJITError?

    init(targetPID: Int32, debugProxy: OpaquePointer, script: StikJIT.Script, progress: @escaping (String) -> Void) {
        self.targetPID = targetPID
        self.debugProxy = debugProxy
        self.script = script
        self.progress = progress
    }

    func run() throws {
        let source = try BundledScript.source(for: script)

        guard let context = JSContext() else { throw StikJITError.scriptUnavailable }
        self.context = context

        context.exceptionHandler = { [weak self] _, value in
            let detail = value?.toString() ?? "unknown JavaScript exception"
            self?.recordExecutionError(.scriptExecution(detail))
        }

        let getPID: @convention(block) () -> Int = { [targetPID] in Int(targetPID) }
        let send: @convention(block) (String?) -> String = { [weak self] command in
            guard let self, let command else { return "" }
            return self.sendCommand(command) ?? ""
        }
        let prepare: @convention(block) (Double, Double) -> String = { [weak self] address, length in
            guard let self else { return "" }
            return self.prepareMemoryRegion(UInt64(address), length: UInt64(length)) ?? ""
        }
        let log: @convention(block) (JSValue?) -> Void = { [weak self] value in
            self?.progress(value?.toString() ?? "")
        }

        context.setObject(getPID,  forKeyedSubscript: "get_pid" as NSString)
        context.setObject(send,    forKeyedSubscript: "send_command" as NSString)
        context.setObject(prepare, forKeyedSubscript: "prepare_memory_region" as NSString)
        context.setObject(log,     forKeyedSubscript: "log" as NSString)

        progress("Running \(script.name) against pid \(targetPID)…")
        context.evaluateScript(source)
        if let executionError { throw executionError }
        progress("JIT script finished (region blessed, detached).")
    }

    private func sendCommand(_ command: String) -> String? {
        guard let handle = command.withCString({ debugserver_command_new($0, nil, 0) }) else { return nil }
        defer { debugserver_command_free(handle) }
        var response: UnsafeMutablePointer<CChar>?
        if let error = debug_proxy_send_command(debugProxy, handle, &response) {
            recordExecutionError(IdeviceFFI.consume(error, fallback: "send_command"))
            return nil
        }
        guard let response else { return "" }
        defer { idevice_string_free(response) }
        return String(cString: response)
    }

    private func prepareMemoryRegion(_ address: UInt64, length: UInt64) -> String? {
        guard length > 0 else { return "OK" }
        let pageCount = Int((length - 1) / Self.jitPageSize + 1)

        // Full-width addresses make each command's length vary, so batches slice by recorded offsets.
        let (commandBuffer, commandOffsets) = Self.makeBlessCommands(startAddress: address, pageCount: pageCount)

        for batchStart in stride(from: 0, to: pageCount, by: Self.commandsPerBatch) {
            let commandsInBatch = min(Self.commandsPerBatch, pageCount - batchStart)
            let byteOffset = commandOffsets[batchStart]
            let byteCount = commandOffsets[batchStart + commandsInBatch] - byteOffset

            let sendError = commandBuffer.withUnsafeBytes { rawBuffer -> UnsafeMutablePointer<IdeviceFfiError>? in
                let base = rawBuffer.bindMemory(to: UInt8.self).baseAddress!
                return debug_proxy_send_raw(debugProxy, base.advanced(by: byteOffset), UInt(byteCount))
            }
            if let sendError {
                recordExecutionError(IdeviceFFI.consume(sendError, fallback: "debug_proxy_send_raw"))
                return nil
            }

            for _ in 0..<commandsInBatch {
                var response: UnsafeMutablePointer<CChar>?
                let readError = debug_proxy_read_response(debugProxy, &response)
                if let response {
                    idevice_string_free(response)
                }
                if let readError {
                    recordExecutionError(IdeviceFFI.consume(readError, fallback: "debug_proxy_read_response"))
                    return nil
                }
            }
        }

        progress("Blessed \(pageCount) JIT page(s) at 0x\(String(address, radix: 16))")
        return "OK"
    }

    private func recordExecutionError(_ error: StikJITError) {
        if executionError == nil {
            executionError = error
            progress(error.localizedDescription)
        }
    }

    /// Returns the packed `$M<addr>,1:69#cs` commands and `pageCount + 1` byte offsets (command `i` spans
    /// `offsets[i]..<offsets[i + 1]`).
    private static func makeBlessCommands(startAddress: UInt64, pageCount: Int) -> ([UInt8], [Int]) {
        var buffer = [UInt8]()
        buffer.reserveCapacity(pageCount * 26)
        var offsets = [Int]()
        offsets.reserveCapacity(pageCount + 1)

        for page in 0..<pageCount {
            let pageAddress = startAddress + UInt64(page) * jitPageSize
            offsets.append(buffer.count)

            buffer.append(UInt8(ascii: "$"))
            let bodyStart = buffer.count
            buffer.append(UInt8(ascii: "M"))
            writeHexAddress(pageAddress, into: &buffer)
            buffer.append(contentsOf: Array(",1:69".utf8))
            let hashIndex = buffer.count
            buffer.append(contentsOf: [UInt8(ascii: "#"), 0, 0])
            writeChecksum(into: &buffer, bodyStart: bodyStart, hashIndex: hashIndex)
        }
        offsets.append(buffer.count)

        return (buffer, offsets)
    }

    /// Full width: as many hex nibbles as the address needs, no leading zeros. The nine-nibble writer this
    /// replaces dropped bits 36 and up, so 0x7000000000 was written as 0x0.
    private static func writeHexAddress(_ address: UInt64, into buffer: inout [UInt8]) {
        var top = 15
        while top > 0 && (address >> UInt64(top * 4)) & 0xf == 0 {
            top -= 1
        }
        for nibble in stride(from: top, through: 0, by: -1) {
            buffer.append(hexDigit(UInt8((address >> UInt64(nibble * 4)) & 0xf)))
        }
    }

    private static func writeChecksum(into buffer: inout [UInt8], bodyStart: Int, hashIndex: Int) {
        var checksum: UInt8 = 0
        for index in bodyStart..<hashIndex {
            checksum &+= buffer[index]
        }
        buffer[hashIndex + 1] = hexDigit((checksum & 0xf0) >> 4)
        buffer[hashIndex + 2] = hexDigit(checksum & 0x0f)
    }

    private static func hexDigit(_ value: UInt8) -> UInt8 {
        value < 10 ? value + UInt8(ascii: "0") : value - 10 + UInt8(ascii: "a")
    }
}
