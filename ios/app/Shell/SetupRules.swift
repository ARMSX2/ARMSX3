// SPDX-License-Identifier: GPL-2.0-only
// First-run setup's rules. Foundation only, for the host test; the firmware check never opens the file.

import Foundation

enum SetupRules {
    static let stepCount = 7
    static let steps = 1...stepCount

    /// A saved step resumes; a fresh install starts at 1; an existing player is marked finished and never interrupted.
    static func launchStep(finished: Bool, savedStep: Int, hasFolders: Bool, hasFirmware: Bool) -> (present: Int?, markFinished: Bool) {
        if steps.contains(savedStep) { return (savedStep, false) }
        if finished { return (nil, false) }
        if !hasFolders && !hasFirmware { return (1, false) }
        return (nil, true)
    }

    /// The step after `step`, or nil after the last one and outside 1...7.
    static func next(_ step: Int) -> Int? {
        step >= 1 && step < stepCount ? step + 1 : nil
    }

    /// The step before `step`, or nil on the first one and outside 1...7.
    static func previous(_ step: Int) -> Int? {
        step > 1 && step <= stepCount ? step - 1 : nil
    }

    /// RPCS3's default dev_flash plus the version.txt that sysinfo.cpp reads, under the core's config folder.
    static func firmwareMarker(applicationSupport: URL) -> URL {
        applicationSupport.appendingPathComponent("rpcs3", isDirectory: true)
            .appendingPathComponent("dev_flash/vsh/etc/version.txt", isDirectory: false)
    }

    /// With JIT off games are not counted, because a scan needs the emulator.
    static func folderText(folders: Int, games: Int?, jitOn: Bool) -> String {
        guard folders > 0 else { return String(localized: "None") }
        let folderWords = folders == 1 ? String(localized: "1 folder") : String(localized: "\(folders) folders")
        guard jitOn, let games else { return folderWords }
        let gameWords = games == 1 ? String(localized: "1 game") : String(localized: "\(games) games")
        return "\(folderWords), \(gameWords)"
    }

    static func gamesPrimary(folderCount: Int) -> SetupPrimary {
        folderCount > 0 ? .continueStep : .addGameFolder
    }

    /// None with JIT off, because installing firmware needs the emulator.
    static func firmwarePrimary(jitOn: Bool, installed: Bool) -> SetupPrimary? {
        guard jitOn else { return nil }
        return installed ? .continueStep : .installFirmware
    }

    /// "Needs JIT" with JIT off, because the version is never read then.
    static func firmwareValue(jitOn: Bool, version: String, installing: Bool, reading: Bool) -> String {
        guard jitOn else { return String(localized: "Needs JIT") }
        if installing { return String(localized: "Installing…") }
        if reading { return String(localized: "Reading…") }
        return version.isEmpty ? String(localized: "Not installed") : version
    }
}

enum SetupPrimary: String {
    case continueStep, installFirmware, addGameFolder, finishSetup

    var title: String {
        switch self {
        case .continueStep: return String(localized: "Continue")
        case .installFirmware: return String(localized: "Install Firmware")
        case .addGameFolder: return String(localized: "Add Game Folder")
        case .finishSetup: return String(localized: "Finish Setup")
        }
    }
}
