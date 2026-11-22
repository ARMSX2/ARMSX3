// SPDX-License-Identifier: GPL-2.0-only

import ExternalAccessory
import Foundation
import GameController
import UIKit

final class GameSirG8MFiRumble: NSObject, StreamDelegate {
    private static let accessoryProtocol = "com.xiaoji.M2boot"
    private static let repeatInterval: TimeInterval = 0.167253
    private static let closeDrainTimeout: TimeInterval = 0.5
    private static let registerAccessoryNotifications: Void = {
        EAAccessoryManager.shared().registerForLocalNotifications()
    }()

    private let stateLock = NSLock()
    private var accessoryAvailable = false
    private var invalidated = false

    private var session: EASession?
    private var inFlightPacket: Data?
    private var inFlightOffset = 0
    private var queuedPacket: Data?
    private var lastSentPacket: Data?
    private var activePacket: Data?
    private var repeatTimer: Timer?
    private var appActive = true
    private var closeAfterDrain = false
    private var closeTimeoutWork: DispatchWorkItem?

    private var hasPendingWrites: Bool { inFlightPacket != nil || queuedPacket != nil }

    static var isAccessoryConnected: Bool {
        _ = registerAccessoryNotifications
        return matchingAccessory(in: EAAccessoryManager.shared().connectedAccessories) != nil
    }

    static func supports(_ controller: GCController) -> Bool {
        guard let name = controller.vendorName?.lowercased() else { return false }
        return name.contains("gamesir") && name.contains("g8+")
    }

    override init() {
        super.init()
        appActive = UIApplication.shared.applicationState == .active

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(accessoryDidChange(_:)),
            name: .EAAccessoryDidConnect,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(accessoryDidChange(_:)),
            name: .EAAccessoryDidDisconnect,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(applicationWillResignActive(_:)),
            name: UIApplication.willResignActiveNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(applicationDidBecomeActive(_:)),
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )

        refreshAccessory()
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    func rumble(left: Float, right: Float) -> Bool {
        dispatchPrecondition(condition: .onQueue(.main))
        stateLock.lock()
        let shouldHandle = accessoryAvailable && !invalidated
        stateLock.unlock()
        guard shouldHandle, appActive, openSessionIfNeeded() else { return false }
        send(left: left, right: right)
        return true
    }

    func stop() {
        if Thread.isMainThread {
            stopRumble()
        } else {
            DispatchQueue.main.async { [weak self] in self?.stopRumble() }
        }
    }

    func invalidate() {
        stateLock.lock()
        invalidated = true
        accessoryAvailable = false
        stateLock.unlock()

        if Thread.isMainThread {
            stopAndClose()
        } else {
            DispatchQueue.main.async { [self] in stopAndClose() }
        }
    }

    private var isInvalidated: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return invalidated
    }

    private static func matchingAccessory(in accessories: [EAAccessory]) -> EAAccessory? {
        accessories.first { accessory in
            accessory.isConnected &&
                accessory.manufacturer.caseInsensitiveCompare("GameSir") == .orderedSame &&
                accessory.modelNumber.caseInsensitiveCompare("G8+ MFi") == .orderedSame &&
                accessory.protocolStrings.contains(accessoryProtocol)
        }
    }

    private func matchingAccessory() -> EAAccessory? {
        Self.matchingAccessory(in: EAAccessoryManager.shared().connectedAccessories)
    }

    @objc private func accessoryDidChange(_ notification: Notification) {
        refreshAccessory()
    }

    @objc private func applicationWillResignActive(_ notification: Notification) {
        appActive = false
        stopAndClose()
    }

    @objc private func applicationDidBecomeActive(_ notification: Notification) {
        appActive = true
        refreshAccessory()
    }

    private func refreshAccessory() {
        dispatchPrecondition(condition: .onQueue(.main))
        guard !isInvalidated else { return }

        let available = matchingAccessory() != nil
        stateLock.lock()
        accessoryAvailable = available
        stateLock.unlock()

        if !available {
            stopAndClose(drain: false)
        }
    }

    private func send(left: Float, right: Float) {
        dispatchPrecondition(condition: .onQueue(.main))
        guard !isInvalidated, appActive else { return }

        let packet = Data(RumbleMath.gameSirPacket(left: left, right: right))
        if packet == Data(RumbleMath.gameSirStopPacket) {
            activePacket = nil
            stopRepeatTimer()
        } else {
            activePacket = packet
            startRepeatTimer()
        }
        queuePacket(packet, force: packet == Data(RumbleMath.gameSirStopPacket))
    }

    private func openSessionIfNeeded() -> Bool {
        if session != nil { return true }

        guard let accessory = matchingAccessory(),
              let newSession = EASession(accessory: accessory, forProtocol: Self.accessoryProtocol),
              let inputStream = newSession.inputStream,
              let outputStream = newSession.outputStream else {
            return false
        }

        session = newSession
        inputStream.delegate = self
        outputStream.delegate = self
        inputStream.schedule(in: .main, forMode: .common)
        outputStream.schedule(in: .main, forMode: .common)
        inputStream.open()
        outputStream.open()
        return true
    }

    private func queuePacket(_ packet: Data, force: Bool = false) {
        guard openSessionIfNeeded() else { return }
        guard force ||
                (packet != inFlightPacket && packet != queuedPacket && packet != lastSentPacket) else {
            return
        }

        if inFlightPacket == nil {
            inFlightPacket = packet
            inFlightOffset = 0
        } else {
            queuedPacket = packet
        }
        flushOutput()
    }

    private func flushOutput() {
        guard let outputStream = session?.outputStream else { return }
        defer { closeIfDrained() }

        while outputStream.streamStatus == .open, outputStream.hasSpaceAvailable {
            if inFlightPacket == nil {
                guard let nextPacket = queuedPacket else { return }
                inFlightPacket = nextPacket
                inFlightOffset = 0
                queuedPacket = nil
            }

            guard let packet = inFlightPacket else { return }
            let remainingLength = packet.count - inFlightOffset
            let written = packet.withUnsafeBytes { bytes -> Int in
                guard let baseAddress = bytes.bindMemory(to: UInt8.self).baseAddress else { return -1 }
                return outputStream.write(baseAddress.advanced(by: inFlightOffset), maxLength: remainingLength)
            }

            guard written > 0 else {
                if written < 0 {
                    closeSession()
                }
                return
            }

            inFlightOffset += written
            if inFlightOffset == packet.count {
                lastSentPacket = packet
                inFlightPacket = nil
                inFlightOffset = 0
            }
        }
    }

    private func closeIfDrained() {
        guard closeAfterDrain, session != nil, !hasPendingWrites else { return }
        closeSession()
    }

    private func startRepeatTimer() {
        repeatTimer?.invalidate()
        let timer = Timer(timeInterval: Self.repeatInterval, repeats: true) { [weak self] _ in
            guard let self, let activePacket = self.activePacket, !self.isInvalidated else { return }
            self.queuePacket(activePacket, force: true)
        }
        repeatTimer = timer
        RunLoop.main.add(timer, forMode: .common)
    }

    private func stopRepeatTimer() {
        repeatTimer?.invalidate()
        repeatTimer = nil
    }

    private func stopRumble() {
        guard activePacket != nil || repeatTimer != nil else { return }
        activePacket = nil
        stopRepeatTimer()
        queuePacket(Data(RumbleMath.gameSirStopPacket), force: true)
    }

    private func stopAndClose(drain: Bool = true) {
        dispatchPrecondition(condition: .onQueue(.main))
        let wasActive = activePacket != nil || repeatTimer != nil
        activePacket = nil
        stopRepeatTimer()

        guard session != nil else { return }
        guard drain, wasActive || hasPendingWrites else {
            closeSession()
            return
        }

        // The stop packet can't be written while another packet is in flight, so queue it and
        // keep the session open until the pending writes drain.
        closeAfterDrain = true
        if wasActive {
            queuePacket(Data(RumbleMath.gameSirStopPacket), force: true)
        } else {
            flushOutput()
        }
        guard session != nil else { return }

        // Close anyway if the stream never drains.
        closeTimeoutWork?.cancel()
        let work = DispatchWorkItem { [self] in closeSession() }
        closeTimeoutWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.closeDrainTimeout, execute: work)
    }

    private func closeSession() {
        closeAfterDrain = false
        closeTimeoutWork?.cancel()
        closeTimeoutWork = nil

        guard let session else { return }
        let streams: [Stream?] = [session.inputStream, session.outputStream]
        for stream in streams.compactMap({ $0 }) {
            stream.delegate = nil
            stream.close()
            stream.remove(from: .main, forMode: .common)
        }
        self.session = nil
        inFlightPacket = nil
        inFlightOffset = 0
        queuedPacket = nil
        lastSentPacket = nil
    }

    func stream(_ stream: Stream, handle eventCode: Stream.Event) {
        guard let session else { return }

        if stream === session.inputStream {
            if eventCode.contains(.hasBytesAvailable), let inputStream = session.inputStream {
                var buffer = [UInt8](repeating: 0, count: 64)
                while inputStream.hasBytesAvailable {
                    if inputStream.read(&buffer, maxLength: buffer.count) <= 0 { break }
                }
            }
        } else if stream === session.outputStream {
            if eventCode.contains(.openCompleted) || eventCode.contains(.hasSpaceAvailable) {
                flushOutput()
            }
        }

        if eventCode.contains(.errorOccurred) || eventCode.contains(.endEncountered) {
            closeSession()
        }
    }
}
