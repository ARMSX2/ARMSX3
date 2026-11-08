// SPDX-License-Identifier: GPL-2.0-only
// Which of the core's overlay sounds play, and how loud. Nothing ships with the app, so an unimported sound is silent.

import Foundation

enum SoundRouting {
    static let menuNames = ["snd_cursor", "snd_decide", "snd_cancel", "snd_oskenter", "snd_oskcancel",
                            "snd_system_ok", "snd_system_ng"]
    static let trophyName = "snd_trophy"
    static let defaultMenuPercent = 15
    static let defaultTrophyPercent = 70
    // A menu blip is a few kilobytes, so a larger import is skipped.
    static let maxBytes = 4 * 1024 * 1024
    // App preferences, not core settings.
    static let menuVolumeKey = "armsx3.sounds.menuVolume"
    static let trophyVolumeKey = "armsx3.sounds.trophyVolume"

    // Nil unless the name is one of the eight names plus `.wav`.
    static func volume(forFile name: String, menuPercent: Int, trophyPercent: Int) -> Float? {
        guard name.hasSuffix(".wav") else { return nil }
        let stem = String(name.dropLast(4))
        let percent: Int
        if stem == trophyName {
            percent = trophyPercent
        } else if menuNames.contains(stem) {
            percent = menuPercent
        } else {
            return nil
        }
        return Float(min(max(percent, 0), 100)) / 100
    }

    // A symlink is never followed, so nothing outside the picked folder is read.
    static func importFolder(_ source: URL, into sounds: URL) -> (copied: Int, ignored: Int) {
        let items = (try? FileManager.default.contentsOfDirectory(at: source, includingPropertiesForKeys: fileKeys,
                                                                   options: [.skipsHiddenFiles])) ?? []
        var copied = 0
        for item in items where menuNames.contains(where: { $0 + ".wav" == item.lastPathComponent }) {
            if copy(item, to: sounds.appendingPathComponent(item.lastPathComponent)) { copied += 1 }
        }
        return (copied, items.count - copied)
    }

    // Kept as `snd_trophy.wav` whatever it was called.
    static func importTrophy(_ file: URL, into sounds: URL) -> Bool {
        file.pathExtension.lowercased() == "wav" && copy(file, to: sounds.appendingPathComponent(trophyName + ".wav"))
    }

    private static let fileKeys: [URLResourceKey] = [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey]

    // False, and nothing written, otherwise.
    private static func copy(_ file: URL, to destination: URL) -> Bool {
        guard let values = try? file.resourceValues(forKeys: Set(fileKeys)), values.isSymbolicLink != true,
              values.isRegularFile == true, let size = values.fileSize, size <= maxBytes,
              let data = try? Data(contentsOf: file), data.count <= maxBytes else { return false }
        do {
            try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            try data.write(to: destination, options: .atomic)
            return true
        } catch {
            return false
        }
    }
}
