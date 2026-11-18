// SPDX-License-Identifier: GPL-2.0-only
// The one place Swift reads the JIT verdict, the emulator's state and input ownership, only through the façade.

import Foundation
import Observation

@MainActor @Observable final class EmulatorSession {
    static let shared = EmulatorSession()

    // The ABI's state values (rpcs3_ios_abi.h), which the façade passes through unchanged.
    static let stateStopped = 100
    // A teardown still in flight. It is stopped-bound.
    static let stateStopping = 102
    static let stateRunning = 103
    static let statePaused = 104

    // The ABI's file slots: 0-9 the numbered slots, 10 the exit autosave, 11 the quick slot.
    nonisolated static let autoFileSlot = 10
    nonisolated static let quickFileSlot = 11

    // The one handoff between the menus and gameplay. Only GameScreenView writes it.
    enum InputOwner { case menu, game }

    // Menu backgrounds release their renderers, videos and images on this, so a title gets the GPU and memory to itself.
    nonisolated static let releaseMenuBackgroundResourcesNotification = Notification.Name("ARMSX3ReleaseMenuBackgroundResources")
    // The library plays in a full-screen cover, so MenuTabView's onAppear never fires again after a game; this replaces it.
    nonisolated static let menuBackgroundReturnNotification = Notification.Name("ARMSX3MenuBackgroundReturn")

    private(set) var jitAvailable: Bool?
    private(set) var jitReason = ""
    // The built-in helper's fine word, or "" when it never ran.
    private(set) var jitHelperOutcome = ""
    private(set) var state = EmulatorSession.stateStopped
    var inputOwner: InputOwner = .menu
    // The core reads STOPPED during a save or load restart, and that must not unhide the library or flip the menu router.
    private(set) var restartPending = false
    // A boot re-reads the configuration and drops live changes, so a save or load restart applies these again.
    private(set) var liveOverrides: [Int: Int] = [:]
    // Lets the menu router navigate the quick menu while the library underneath stays inert.
    var quickMenuOpen = false
    // A test build opened the menus without JIT: the core was never prepared, so nothing may boot or scan.
    var browsing = false
    // What an install or a sign-in says instead while `browsing`.
    static var browsingRefusal: String { String(localized: "This needs JIT. This test build is only showing the menus.") }
    // For play time. Never written to the session record or a log.
    private var playingSerial: String?

    // STARTING and STOPPING count as live, and so does a save or load restart, whose brief STOPPED must not end it.
    var isRunning: Bool { state != Self.stateStopped || restartPending }

    // During a restore and until the next launch nothing boots or writes, so nothing overwrites a restored file.
    enum RestorePhase { case idle, restoring, restored }
    private(set) var restorePhase = RestorePhase.idle
    // Outside the ABI's status range, so it never reads as a core status.
    nonisolated static let restoreRefused = -100

    func beginRestore() -> Bool {
        guard !isRunning, restorePhase == .idle else { return false }
        restorePhase = .restoring
        return true
    }

    func endRestore(succeeded: Bool) {
        restorePhase = succeeded ? .restored : .idle
    }

    private init() {}

    @discardableResult
    func checkJIT() async -> Bool {
        let (available, reason) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.isJITAvailable { available, reason in continuation.resume(returning: (available, reason)) }
        }
        jitAvailable = available
        jitReason = reason
        jitHelperOutcome = Rpcs3Bridge.jitHelperOutcome()
        return available
    }

    // Leaves `state` as it was when the query fails, and returns the query's status.
    @discardableResult
    func refreshState() async -> Int {
        let (value, status) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.state { value, status in continuation.resume(returning: (value, status)) }
        }
        if status == 0 { state = value }
        // Not during a restore, whose playtime.json the in-memory totals must not overwrite.
        if restorePhase == .idle { PlayTime.shared.observe(serial: playingSerial, running: state == Self.stateRunning, at: Date()) }
        return status
    }

    func boot(_ game: Rpcs3GameEntry) async -> Int {
        guard restorePhase == .idle else {
            await deactivateAudio()
            return Self.restoreRefused
        }
        liveOverrides = [:]
        let status: Int = await withCheckedContinuation { continuation in
            Rpcs3Bridge.bootGame(game) { status in continuation.resume(returning: status) }
        }
        if status == 0 { playingSerial = game.serial }
        await refreshState()
        // Nothing runs after a failed boot, so the session activated for it goes.
        if status != 0 { await deactivateAudio() }
        return status
    }

    // From STOPPED the core builds the title's own configuration. The caller activates audio first, as for boot(_:).
    func boot(_ game: Rpcs3GameEntry, fromState path: String) async -> Int {
        guard restorePhase == .idle else {
            await deactivateAudio()
            return Self.restoreRefused
        }
        let status = await loadState(path: path, game: game)
        if status == 0 { playingSerial = game.serial }
        if status != 0 { await deactivateAudio() }
        return status
    }

    // 0 only once the emulator reads STOPPED; -9 after the façade's bound. The caller must not boot again on anything else.
    func stop() async -> Int {
        let status = await withCheckedContinuation { continuation in
            Rpcs3Bridge.requestVMStop { status in continuation.resume(returning: status) }
        }
        await refreshState()
        // Only once the core reads STOPPED; a timed-out stop may still own the stream.
        if status == 0 { await deactivateAudio() }
        // One final non-running observation, so the last interval is closed and saved.
        PlayTime.shared.observe(serial: playingSerial, running: false, at: Date())
        if status == 0 { playingSerial = nil }
        return status
    }

    // `restartPending` is held across the stop and boot, so the library never shows in between.
    func resetGame(_ game: Rpcs3GameEntry) async -> Int {
        guard !restartPending else { return Self.wrongState }
        restartPending = true
        let stopped = await stop()
        var booted = Self.wrongState
        if stopped == 0 {
            _ = await activateAudio()
            booted = await boot(game)
            if booted == 0 { await awaitRunning() }
        }
        restartPending = false
        return stopped != 0 ? stopped : booted
    }

    // 0 saved; -18 the kill happened and nothing was written; -13 or -26 the core gave up and the game ran on; -9 busy.
    func saveState(fileSlot: Int, resume: Bool, fromPanel: Bool = false) async -> (status: Int, historyName: String) {
        guard !restartPending else { return (status: Self.wrongState, historyName: "") }
        restartPending = resume
        SaveProgress.shared.start(fromPanel: fromPanel)
        let (status, name) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.saveState(toSlot: fileSlot, resume: resume) { status, name in
                continuation.resume(returning: (status, name))
            }
        }
        SaveProgress.shared.end(status: status)
        // The slot just written is in the current format.
        if status == 0 { await recordEpoch(fileSlot: fileSlot) }
        await refreshState()
        await awaitSettled()
        if resume && (status == 0 || status == Self.savestateNotWritten) && state != Self.stateStopped && state != Self.stateStopping {
            await awaitRunning()
            await reapplyLive()
        }
        restartPending = false
        return (status: status, historyName: name)
    }

    // From a running game the core keeps the configuration it booted with; from STOPPED the game's own settings are used.
    func loadState(path: String, game: Rpcs3GameEntry) async -> Int {
        guard !restartPending else { return Self.wrongState }
        // A state made before the USB bus returned is refused before the façade, so the core never sees it.
        if Self.stateEpoch(path: path) < SaveStateFiles.formatEpoch {
            return Self.savestateTooOld
        }
        await refreshState()
        restartPending = state == Self.stateRunning || state == Self.statePaused
        // A load from STOPPED is a new session: the last game's overrides do not carry into it.
        if !restartPending { liveOverrides = [:] }
        let status = await withCheckedContinuation { continuation in
            Rpcs3Bridge.loadState(atPath: path, game: game) { status in continuation.resume(returning: status) }
        }
        if status == 0 {
            await awaitRunning()
            await reapplyLive()
        } else {
            await refreshState()
            await awaitSettled()
        }
        restartPending = false
        return status
    }

    // The only Swift caller of the façade's pause. Returns 0 when the pause machine owed nothing.
    @discardableResult
    func setMenuPaused(_ paused: Bool) async -> Int {
        let (status, _) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.setMenuPaused(paused) { status, called in continuation.resume(returning: (status, called)) }
        }
        return status
    }

    // 1 a disc is in and can be swapped, 2 the tray is empty, 0 no swap.
    func discSwapState() async -> Int {
        let (status, state) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.discSwapState { status, state in continuation.resume(returning: (status, state)) }
        }
        return status == 0 ? state : 0
    }

    // `result`: 0 changed, 1 no swaps, 2 not a disc, 3 the eject did not finish, 4 the core refused the disc. Up to about 13 s.
    func changeDisc(path: String) async -> (status: Int, result: Int) {
        let (status, result) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.changeDisc(atPath: path) { status, result in continuation.resume(returning: (status, result)) }
        }
        return (status: status, result: result)
    }

    // Stop: 0 once the file is finished, -9 nothing was recording, -16 the file was not completed.
    func startRecording(path: String) async -> Int {
        await withCheckedContinuation { continuation in
            Rpcs3Bridge.startRecording(atPath: path) { continuation.resume(returning: $0) }
        }
    }

    func stopRecording() async -> Int {
        await withCheckedContinuation { continuation in
            Rpcs3Bridge.stopRecording { continuation.resume(returning: $0) }
        }
    }

    // On 0 the value is kept as an override for the rest of this session; -7 is a value outside the setting's set.
    @discardableResult
    func setLive(_ which: Rpcs3LiveSetting, _ value: Int) async -> Int {
        let status = await applyLive(which.rawValue, value)
        if status == 0 { liveOverrides[which.rawValue] = value }
        return status
    }

    // A restart re-reads the configuration and drops the overrides, so they are applied again once it reads RUNNING.
    private func reapplyLive() async {
        guard state == Self.stateRunning else { return }
        let overrides = liveOverrides.sorted { $0.key < $1.key }
        for (which, value) in overrides {
            _ = await applyLive(which, value)
        }
    }

    private func applyLive(_ which: Int, _ value: Int) async -> Int {
        await withCheckedContinuation { continuation in
            Rpcs3Bridge.setLiveSetting(which, value: value) { status in continuation.resume(returning: status) }
        }
    }

    // On the façade's input queue, so it keeps the presses' order.
    @discardableResult
    func setPressureModifier(_ held: Bool) async -> Int {
        let status = await withCheckedContinuation { continuation in
            Rpcs3Bridge.setPressureModifier(held) { status in continuation.resume(returning: status) }
        }
        return status
    }

    // The value the core holds for `which` now, or nil when the read failed.
    func liveValue(_ which: Rpcs3LiveSetting) async -> Int? {
        let (value, status) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.liveSetting(which.rawValue) { value, status in continuation.resume(returning: (value, status)) }
        }
        return status == 0 ? value : nil
    }

    func settingsDump(serial: String?) async -> (data: Data?, status: Int) {
        let (data, status) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.settings(forSerial: serial) { data, status in continuation.resume(returning: (data, status)) }
        }
        return (data, status)
    }

    func inputConfigDump(serial: String?) async -> (data: Data?, status: Int) {
        let (data, status) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.inputConfig(forSerial: serial) { data, status in continuation.resume(returning: (data, status)) }
        }
        return (data, status)
    }

    // -19 the setting refused the value, -20 the file was not written; `live` says a running game took it at once.
    func setSetting(_ path: [String], value: String, serial: String?, liveIds: [Int]) async -> (status: Int, live: Bool) {
        guard restorePhase == .idle else { return (Self.restoreRefused, false) }
        let joined = path.joined(separator: SettingsModel.pathSeparator)
        let (status, live) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.setSetting(joined, value: value, serial: serial) { status, live in
                continuation.resume(returning: (status, live))
            }
        }
        if status == 0 { clearLiveOverrides(liveIds) }
        return (status, live)
    }

    // With a serial, out of the title's file (no paths deletes it); else back to the core's default in config.yml.
    func resetSettings(_ paths: [[String]], serial: String?, liveIds: [Int]) async -> (status: Int, live: Bool) {
        guard restorePhase == .idle else { return (Self.restoreRefused, false) }
        let joined = paths.map { $0.joined(separator: SettingsModel.pathSeparator) }
        let (status, live) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.resetSettings(joined, serial: serial) { status, live in
                continuation.resume(returning: (status, live))
            }
        }
        if status == 0 { clearLiveOverrides(liveIds) }
        return (status, live)
    }

    // A saved change to the same key supersedes its session value, so a restart does not re-impose the stale one.
    private func clearLiveOverrides(_ liveIds: [Int]) {
        for id in liveIds { liveOverrides[id] = nil }
    }

    // Writes nothing.
    func normaliseSettings(_ yaml: String) async -> (yaml: String, counts: SettingsImportCounts, status: Int) {
        let (text, counts, status) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.normaliseSettings(yaml) { text, kept, unknown, defaulted, locked, status in
                continuation.resume(returning: (text, SettingsImportCounts(kept: kept, unknown: unknown,
                                                                           defaulted: defaulted, locked: locked), status))
            }
        }
        return (text, counts, status)
    }

    func applySettings(_ yaml: String, serial: String?) async -> (changed: Int, live: Bool, status: Int) {
        guard restorePhase == .idle else { return (0, false, Self.restoreRefused) }
        let (changed, live, status) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.applySettings(yaml, serial: serial) { changed, live, status in
                continuation.resume(returning: (changed, live, status))
            }
        }
        // The running game now holds what the files give it, so a later restart must not re-apply the older overrides.
        if live { liveOverrides = [:] }
        return (changed, live, status)
    }

    func exportSettings(serial: String?) async -> (yaml: String, status: Int) {
        let (text, status) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.exportSettings(forSerial: serial) { text, status in continuation.resume(returning: (text, status)) }
        }
        return (text, status)
    }

    // The core's history files sit beside the slot folder, never in it.
    func slotFile(fileSlot: Int, serial: String) async -> String? {
        guard let directory = await saveStateDirectory(serial: serial) else { return nil }
        return SaveStateFiles.scan(directory: directory, appHistory: []).slots[fileSlot]?.url.path
    }

    // nil when the façade refused the serial.
    func saveStateDirectory(serial: String) async -> URL? {
        let (directory, status) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.saveStateDirectory(forSerial: serial) { path, status in continuation.resume(returning: (path, status)) }
        }
        return status == 0 ? URL(fileURLWithPath: directory, isDirectory: true) : nil
    }

    // Outside the ABI's status range, as restoreRefused is.
    static let savestateTooOld = -101

    static func stateEpoch(path: String) -> Int {
        SaveStateFiles.epoch(ofStateAt: URL(fileURLWithPath: path),
                             since: UserDefaults.standard.object(forKey: SaveStateFiles.epochSinceKey) as? Date)
    }

    // A record that cannot be written is printed, never fatal: the slot then reads by its date.
    private func recordEpoch(fileSlot: Int) async {
        guard let serial = playingSerial, let directory = await saveStateDirectory(serial: serial),
              (try? SaveStateFiles.recordEpoch(fileSlot: fileSlot, in: directory.appendingPathComponent(SaveStateFiles.slotsFolder))) != nil
        else {
            return
        }
    }

    // The ABI's WRONG_STATE, returned here when a save or load is already in flight.
    static let wrongState = -9
    // The ABI's SAVESTATE_NOT_WRITTEN: the kill happened and no slot was written.
    static let savestateNotWritten = -18
    // The ABI's SAVESTATE_SPU_BUSY: the core could not lock the SPU threads with Compatible Savestate Mode off.
    static let savestateSpuBusy = -26
    // The ABI's RENDERER_DIED: the renderer's thread died and nothing was stopped.
    static let rendererDied = -27
    // The ABI's JIT_EXHAUSTED: a JIT pool filled and nothing was stopped.
    static let jitExhausted = -28
    // The ABI's GAME_FATAL: a fatal error or a freeze after a guest fault; offered, never refused.
    static let gameFatal = -29
    // The ABI's STOP_HUNG: the core's Stop Watchdog fired and the stop has not finished.
    static let stopHung = -30

    // Polls every 0.25 s until the emulator reads RUNNING, for at most 120 s.
    private func awaitRunning() async {
        for _ in 0..<480 {
            await refreshState()
            if state == Self.stateRunning { return }
            try? await Task.sleep(nanoseconds: 250_000_000)
        }
    }

    // A save's kill can still be writing when the bridge returns, and nothing may wait for RUNNING over STOPPING.
    private func awaitSettled() async {
        guard state == Self.stateStopping else { return }
        for _ in 0..<120 {
            try? await Task.sleep(nanoseconds: 250_000_000)
            await refreshState()
            if state == Self.stateStopped || state == Self.stateRunning || state == Self.statePaused { return }
        }
    }

    // A refusal is not a boot failure.
    func activateAudio() async -> Int {
        await withCheckedContinuation { continuation in
            Rpcs3Bridge.activateAudioSession { status in continuation.resume(returning: status) }
        }
    }

    // The session is not mixable, so the user's music stays interrupted until this deactivates it.
    @discardableResult
    func deactivateAudio() async -> Int {
        let status = await withCheckedContinuation { continuation in
            Rpcs3Bridge.deactivateAudioSession { status in continuation.resume(returning: status) }
        }
        return status
    }
}
