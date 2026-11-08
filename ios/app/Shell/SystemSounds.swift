// SPDX-License-Identifier: GPL-2.0-only
// Plays the core's overlay sounds from imported files. At most three players at once, desktop RPCS3's limit.

import AVFoundation

@MainActor final class SystemSounds {
    static let shared = SystemSounds()
    static let maxPlayers = 3

    private var players: [AVAudioPlayer] = []

    // True when a player started.
    @discardableResult
    func play(path: String, volume: Float) -> Bool {
        let defaults = UserDefaults.standard
        let menu = (defaults.object(forKey: SoundRouting.menuVolumeKey) as? NSNumber)?.intValue ?? SoundRouting.defaultMenuPercent
        let trophy = (defaults.object(forKey: SoundRouting.trophyVolumeKey) as? NSNumber)?.intValue
            ?? SoundRouting.defaultTrophyPercent
        let url = URL(fileURLWithPath: path)
        let file = url.lastPathComponent
        guard let row = SoundRouting.volume(forFile: file, menuPercent: menu, trophyPercent: trophy) else { return false }
        guard FileManager.default.fileExists(atPath: path), let player = try? AVAudioPlayer(contentsOf: url) else {
            return false
        }
        player.volume = volume >= 0 ? row * min(volume, 1) : row
        players.removeAll { !$0.isPlaying }
        while players.count >= Self.maxPlayers {
            players.removeFirst().stop()
        }
        let played = player.play()
        if played { players.append(player) }
        return played
    }
}
