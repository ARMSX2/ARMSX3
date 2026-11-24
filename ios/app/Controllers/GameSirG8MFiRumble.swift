// SPDX-License-Identifier: GPL-2.0-only
// Rumble for the GameSir G8+ MFi controller over its External Accessory session.

import ExternalAccessory
import Foundation
import GameController
import UIKit

// One instance for the app: iOS opens one session per accessory and protocol, so every slot and Test Rumble share it.
// Everything runs on the main thread: the streams are scheduled on the main run loop and the notifications arrive there.
@MainActor final class GameSirG8MFiRumble: NSObject, StreamDelegate {
    static let shared = GameSirG8MFiRumble()

    private static let accessoryProtocol = "com.xiaoji.M2boot"
    // Resend period of the active rumble packet while a motor is on.
    private static let repeatInterval: TimeInterval = 0.167253
    private static let closeDrainTimeout: TimeInterval = 0.5
    // After a session fails to open or errors out, no new one is tried for this long.
    private static let reopenDelay: TimeInterval = 1
    private static let stopPacket = Data(RumbleMath.gameSirStopPacket)

    // How long both lists must hold exactly one G8+ before they are paired, so the two halves of one device's
    // connect or disconnect, which arrive as separate notifications, are never paired across two devices.
    private static let pairSettle: TimeInterval = 1

    private var appActive: Bool
    // The accessory and the controller of one G8+, paired as a unit. iOS does not say which controller is which
    // accessory, so they are paired only while each list holds exactly one G8+, and dropped together when either
    // half leaves. A second G8+ connecting later never moves the pair; with two and no pair, neither rumbles.
    // Kept from the connect and disconnect notifications, so the 30 Hz poll never walks either list.
    private var pinnedAccessory: Int?
    private var pinnedController: ObjectIdentifier?
    // Some G8+ accessory is connected, paired or not.
    private var hasAccessory = false
    private var pairWork: DispatchWorkItem?

    var isConnected: Bool { pinnedAccessory != nil }

    private var session: EASession?
    private var inFlightPacket: Data?
    private var inFlightOffset = 0
    private var queuedPacket: Data?
    private var lastSentPacket: Data?
    private var activePacket: Data?
    private var repeatTimer: Timer?
    private var closeAfterDrain = false
    private var closeTimeoutWork: DispatchWorkItem?
    // System uptime before which openSessionIfNeeded() does not try again; 0 when it may.
    private var reopenNotBefore: TimeInterval = 0

    private var hasPendingWrites: Bool { inFlightPacket != nil || queuedPacket != nil }

    private override init() {
        appActive = UIApplication.shared.applicationState == .active
        super.init()

        EAAccessoryManager.shared().registerForLocalNotifications()
        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(devicesDidChange(_:)), name: .EAAccessoryDidConnect, object: nil)
        center.addObserver(self, selector: #selector(devicesDidChange(_:)), name: .EAAccessoryDidDisconnect, object: nil)
        center.addObserver(self, selector: #selector(applicationWillResignActive(_:)),
                           name: UIApplication.willResignActiveNotification, object: nil)
        center.addObserver(self, selector: #selector(applicationDidBecomeActive(_:)),
                           name: UIApplication.didBecomeActiveNotification, object: nil)
        center.addObserver(self, selector: #selector(devicesDidChange(_:)), name: .GCControllerDidConnect, object: nil)
        center.addObserver(self, selector: #selector(devicesDidChange(_:)), name: .GCControllerDidDisconnect, object: nil)
        refreshPair()
    }

    static func supports(_ controller: GCController) -> Bool {
        guard let name = controller.vendorName?.lowercased() else { return false }
        return name.contains("gamesir") && name.contains("g8+")
    }

    /// Whether `controller` rumbles here: the paired G8+.
    func drives(_ controller: GCController) -> Bool {
        isConnected && pinnedController == ObjectIdentifier(controller)
    }

    /// A G8+ that is not the paired one while some G8+ accessory is connected: it keeps its own haptics, if any,
    /// but never the accessory or the phone, either of which may be in another player's hands.
    func isSpare(_ controller: GCController) -> Bool {
        hasAccessory && Self.supports(controller) && !drives(controller)
    }

    /// False when nothing could be sent: no accessory, the app inactive, or the session would not open.
    func rumble(left: Float, right: Float) -> Bool {
        guard isConnected, appActive, openSessionIfNeeded() else { return false }

        let packet = Data(RumbleMath.gameSirPacket(left: left, right: right))
        let isStop = packet == Self.stopPacket
        if isStop {
            activePacket = nil
            stopRepeatTimer()
        } else {
            activePacket = packet
            startRepeatTimer()
        }
        queuePacket(packet, force: isStop)
        return true
    }

    func stop() {
        guard activePacket != nil || repeatTimer != nil else { return }
        activePacket = nil
        stopRepeatTimer()
        queuePacket(Self.stopPacket, force: true)
    }

    private static func matchingAccessories() -> [EAAccessory] {
        EAAccessoryManager.shared().connectedAccessories.filter { accessory in
            accessory.isConnected &&
                accessory.manufacturer.caseInsensitiveCompare("GameSir") == .orderedSame &&
                accessory.modelNumber.caseInsensitiveCompare("G8+ MFi") == .orderedSame &&
                accessory.protocolStrings.contains(accessoryProtocol)
        }
    }

    // Only the pinned accessory: a reopen after an error never moves the session to a second G8+.
    private func pinnedMatchingAccessory() -> EAAccessory? {
        guard let pinnedAccessory else { return nil }
        return Self.matchingAccessories().first { $0.connectionID == pinnedAccessory }
    }

    private static func matchingControllers() -> [ObjectIdentifier] {
        GCController.controllers().filter(supports).map(ObjectIdentifier.init)
    }

    @objc private func devicesDidChange(_ notification: Notification) {
        refreshPair()
    }

    @objc private func applicationWillResignActive(_ notification: Notification) {
        appActive = false
        stopAndClose()
    }

    @objc private func applicationDidBecomeActive(_ notification: Notification) {
        appActive = true
        // Back within the drain timeout: the session the resign was closing stays open for the next rumble.
        closeAfterDrain = false
        closeTimeoutWork?.cancel()
        closeTimeoutWork = nil
        refreshPair()
    }

    // A pair whose two halves are still connected stays, so a second G8+ connecting or leaving never touches its
    // session. Otherwise the pair, and its session, go, and a new one is tried once the lists settle.
    private func refreshPair() {
        let accessories = Self.matchingAccessories()
        hasAccessory = !accessories.isEmpty
        // A connect or a return to the foreground may open at once, whatever failed before.
        reopenNotBefore = 0
        if let pinnedAccessory, let pinnedController {
            if accessories.contains(where: { $0.connectionID == pinnedAccessory }),
               Self.matchingControllers().contains(pinnedController) {
                return
            }
            // Both halves go together, so the half left behind is never paired with another G8+.
            self.pinnedAccessory = nil
            self.pinnedController = nil
            stopAndClose(drain: false)
        }
        schedulePair()
    }

    // Each change restarts the wait, so the pair is made only after the lists stop changing.
    private func schedulePair() {
        pairWork?.cancel()
        let work = DispatchWorkItem {
            MainActor.assumeIsolated { GameSirG8MFiRumble.shared.pairIfUnambiguous() }
        }
        pairWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.pairSettle, execute: work)
    }

    // Exactly one G8+ accessory and one G8+ controller can only be the same device; anything else stays unpaired.
    private func pairIfUnambiguous() {
        pairWork = nil
        guard pinnedAccessory == nil else { return }
        let accessories = Self.matchingAccessories()
        let controllers = Self.matchingControllers()
        hasAccessory = !accessories.isEmpty
        guard accessories.count == 1, controllers.count == 1 else { return }
        pinnedAccessory = accessories[0].connectionID
        pinnedController = controllers[0]
        reopenNotBefore = 0
    }

    // A failure waits `reopenDelay`, so the 30 Hz poll and the repeat timer never retry the open every tick.
    private func openSessionIfNeeded() -> Bool {
        if session != nil { return true }
        guard ProcessInfo.processInfo.systemUptime >= reopenNotBefore else { return false }

        guard let accessory = pinnedMatchingAccessory(),
              let newSession = EASession(accessory: accessory, forProtocol: Self.accessoryProtocol),
              let inputStream = newSession.inputStream,
              let outputStream = newSession.outputStream else {
            deferReopen()
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
                    deferReopen()
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
        let timer = Timer(timeInterval: Self.repeatInterval, repeats: true) { _ in
            MainActor.assumeIsolated {
                let rumble = GameSirG8MFiRumble.shared
                guard let packet = rumble.activePacket else { return }
                rumble.queuePacket(packet, force: true)
            }
        }
        repeatTimer = timer
        RunLoop.main.add(timer, forMode: .common)
    }

    private func stopRepeatTimer() {
        repeatTimer?.invalidate()
        repeatTimer = nil
    }

    private func stopAndClose(drain: Bool = true) {
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
            queuePacket(Self.stopPacket, force: true)
        } else {
            flushOutput()
        }
        guard session != nil else { return }

        // Close anyway if the stream never drains.
        closeTimeoutWork?.cancel()
        let work = DispatchWorkItem {
            MainActor.assumeIsolated { GameSirG8MFiRumble.shared.closeSession() }
        }
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

    // The streams are scheduled on the main run loop, so their events arrive on the main thread.
    nonisolated func stream(_ stream: Stream, handle eventCode: Stream.Event) {
        MainActor.assumeIsolated { handle(stream, eventCode) }
    }

    private func handle(_ stream: Stream, _ eventCode: Stream.Event) {
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
            deferReopen()
        }
    }

    private func deferReopen() {
        reopenNotBefore = ProcessInfo.processInfo.systemUptime + Self.reopenDelay
    }
}
