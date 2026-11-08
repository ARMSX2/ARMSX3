// SPDX-License-Identifier: GPL-2.0-only
// Android's bundled game patches, installed and switched on at launch by Android's rules, so a player's OFF sticks.

import Foundation

enum BundledPatches {
    struct Entry: Equatable {
        let hash: String
        let name: String
        let serial: String
        let appVersion: String
        let sinceRevision: Int

        var key: String { [hash, name, serial].joined(separator: "\u{1F}") }
    }

    static let file = "armsx3_patch.yml"
    // Ps3PatchRepo.BUNDLED_REVISION and its preference name.
    static let revision = 28
    static let revisionKey = "ps3_bundled_patch_revision"
    static let enabledKey = "ps3_bundled_patch_enabled"

    // Ps3PatchRepo.BUNDLED, in its order; a host test keeps the two equal.
    static let entries: [Entry] = [
        Entry(hash: "SPU-30d17ae952dd9ccc1abc46b631654e1fe432fde9", name: "ARMSX3 raw SPU park fix", serial: "BLUS30203", appVersion: "All", sinceRevision: 18),
        Entry(hash: "SPU-30d17ae952dd9ccc1abc46b631654e1fe432fde9", name: "ARMSX3 raw SPU park fix", serial: "BLES00397", appVersion: "All", sinceRevision: 18),
        Entry(hash: "SPU-93f3b15e03c594d9eaf5a0d8521854fd6eb2a8c7", name: "ARMSX3 raw SPU park fix", serial: "BLUS30203", appVersion: "All", sinceRevision: 18),
        Entry(hash: "SPU-93f3b15e03c594d9eaf5a0d8521854fd6eb2a8c7", name: "ARMSX3 raw SPU park fix", serial: "BLES00397", appVersion: "All", sinceRevision: 18),
        Entry(hash: "PPU-f942422316f63af3d64dc940b35ec8fddade0f44", name: "ARMSX3 boot crash fix", serial: "NPJB00337", appVersion: "01.00", sinceRevision: 16),
        Entry(hash: "SPU-d0a1508208613e2184fbd64906d34d5f07890033", name: "ARMSX3 Approximate collision fix", serial: "BLUS30682", appVersion: "All", sinceRevision: 14),
        Entry(hash: "SPU-d0a1508208613e2184fbd64906d34d5f07890033", name: "ARMSX3 Approximate collision fix", serial: "BLUS30127", appVersion: "All", sinceRevision: 14),
        Entry(hash: "SPU-d0a1508208613e2184fbd64906d34d5f07890033", name: "ARMSX3 Approximate collision fix", serial: "NPUB30702", appVersion: "All", sinceRevision: 13),
        Entry(hash: "PPU-0e747cbf4d807d363b085d983fff749c2c9cbdee", name: "ARMSX3 phase start crash fix", serial: "BLUS30335", appVersion: "All", sinceRevision: 14),
        Entry(hash: "PPU-0e747cbf4d807d363b085d983fff749c2c9cbdee", name: "ARMSX3 phase start crash fix", serial: "BLES01120", appVersion: "All", sinceRevision: 12),
        Entry(hash: "PPU-30ce8c9f0a9552914275c90e2980749630f3ea18", name: "ARMSX3 quickplay loading fix", serial: "BCES00112", appVersion: "01.00", sinceRevision: 7),
        Entry(hash: "PPU-30ce8c9f0a9552914275c90e2980749630f3ea18", name: "ARMSX3 intro movie crash fix", serial: "BCES00112", appVersion: "01.00", sinceRevision: 6),
        Entry(hash: "PPU-56101fbdbac186fddef72207145015fd314a0be5", name: "ARMSX3 Junkyard crash fix", serial: "BLUS30061", appVersion: "01.00", sinceRevision: 6),
        Entry(hash: "PPU-aa798f32a1fda1c23a20066edb1c623c486d53cc", name: "Disable MLAA", serial: "BLUS30736", appVersion: "01.00", sinceRevision: 5),
        Entry(hash: "PPU-42367707f4caac2668f10cb46498f64bde9db440", name: "Disable MLAA", serial: "BCUS99247", appVersion: "01.22", sinceRevision: 9),
        Entry(hash: "PPU-42367707f4caac2668f10cb46498f64bde9db440", name: "Disable Motion Blur", serial: "BCUS99247", appVersion: "01.22", sinceRevision: 9),
        Entry(hash: "PPU-4f1e9acd7d98961b4b742fb324a2faba6212ea67", name: "Disable MLAA", serial: "BCUS98296", appVersion: "01.22", sinceRevision: 9),
        Entry(hash: "PPU-4f1e9acd7d98961b4b742fb324a2faba6212ea67", name: "Disable Motion Blur", serial: "BCUS98296", appVersion: "01.22", sinceRevision: 9),
        Entry(hash: "PPU-6ac472e1f334f54ec4753cde9e8e1515bfab4d17", name: "Disable MLAA", serial: "BCUS98296", appVersion: "01.22", sinceRevision: 10),
        Entry(hash: "PPU-6ac472e1f334f54ec4753cde9e8e1515bfab4d17", name: "Disable Motion Blur", serial: "BCUS98296", appVersion: "01.22", sinceRevision: 10),
        Entry(hash: "PPU-638ef307e2b35d593f599efec0dc0c37059a984b", name: "Disable MLAA", serial: "BCES01893", appVersion: "01.22", sinceRevision: 11),
        Entry(hash: "PPU-638ef307e2b35d593f599efec0dc0c37059a984b", name: "Disable Motion Blur", serial: "BCES01893", appVersion: "01.22", sinceRevision: 11),
        Entry(hash: "PPU-80f0d67b29d3d17ac885a309a3fc4cd8dcf50658", name: "Disable MLAA", serial: "NPEA00502", appVersion: "01.22", sinceRevision: 11),
        Entry(hash: "PPU-80f0d67b29d3d17ac885a309a3fc4cd8dcf50658", name: "Disable Motion Blur", serial: "NPEA00502", appVersion: "01.22", sinceRevision: 11),
        Entry(hash: "PPU-4213aba5ed17f7e3cd3f299fa44d9d9acef0ba18", name: "Disable MLAA", serial: "NPUA81049", appVersion: "01.22", sinceRevision: 11),
        Entry(hash: "PPU-4213aba5ed17f7e3cd3f299fa44d9d9acef0ba18", name: "Disable Motion Blur", serial: "NPUA81049", appVersion: "01.22", sinceRevision: 11),
        Entry(hash: "PPU-6a7de7fdfd7e7cbd1031bf28a7104b29c308136c", name: "Disable MLAA", serial: "NPHA80269", appVersion: "01.22", sinceRevision: 11),
        Entry(hash: "PPU-6a7de7fdfd7e7cbd1031bf28a7104b29c308136c", name: "Disable Motion Blur", serial: "NPHA80269", appVersion: "01.22", sinceRevision: 11),
        Entry(hash: "PPU-43e618448eed82aa96856338d072f1e4978c36a8", name: "Disable MLAA", serial: "NPJA00113", appVersion: "01.22", sinceRevision: 11),
        Entry(hash: "PPU-43e618448eed82aa96856338d072f1e4978c36a8", name: "Disable Motion Blur", serial: "NPJA00113", appVersion: "01.22", sinceRevision: 11),
        Entry(hash: "PPU-4b46d0161ca657ab16b0a779d9062810ea5ea2dd", name: "Graphics Fix", serial: "BLUS30008", appVersion: "01.01", sinceRevision: 1),
        Entry(hash: "SPU-42bae8e5d6a9304068ba1c6bbfdc18d656e287a1", name: "Bink overlay skip", serial: "BLES00928", appVersion: "All", sinceRevision: 2),
        Entry(hash: "PPU-ae204e2198c9a051a44a69913c48f6591b811082", name: "Disable MLAA(Post-processing On SPU)", serial: "BCES01007", appVersion: "01.00", sinceRevision: 14),
        Entry(hash: "PPU-ae204e2198c9a051a44a69913c48f6591b811082", name: "Disable MLAA(Post-processing On SPU)", serial: "BCUS98234", appVersion: "01.00", sinceRevision: 8),
        Entry(hash: "PPU-4836b8e74c47919f50b030ee6b47d96bc7305387", name: "Disable MLAA (Post-processing on SPU)", serial: "BCES01007", appVersion: "01.14", sinceRevision: 14),
        Entry(hash: "PPU-4836b8e74c47919f50b030ee6b47d96bc7305387", name: "Disable MLAA (Post-processing on SPU)", serial: "BCUS98234", appVersion: "01.14", sinceRevision: 8),
        Entry(hash: "PPU-c4e26433d1eed9166eb0c67b6f66b2268f3704e2", name: "Freeze Fix", serial: "BCES00052", appVersion: "All", sinceRevision: 4),
        Entry(hash: "PPU-ec77eaf73a4f55d1c4ece532c3be6db0011e49ca", name: "Freeze Fix", serial: "NPEA00452", appVersion: "All", sinceRevision: 4),
        Entry(hash: "PPU-f67f3e99077ba256728ffa16800c75e006661158", name: "Freeze Fix", serial: "BCUS98127", appVersion: "01.00", sinceRevision: 4),
        Entry(hash: "PPU-c14042df6304d3e420a9917e6f8e5fc05cc38b4c", name: "Freeze Fix", serial: "BCUS98127", appVersion: "01.00", sinceRevision: 4),
        Entry(hash: "PPU-16506d9d5bf692d615645accd24bca1ee1f8f9a6", name: "Freeze Fix", serial: "NPUA80965", appVersion: "All", sinceRevision: 4),
        Entry(hash: "PPU-4c819c69904784a56685c31df12f5d492bc0ed64", name: "Freeze Fix", serial: "BCAS20200", appVersion: "01.03", sinceRevision: 14),
        Entry(hash: "PPU-4c819c69904784a56685c31df12f5d492bc0ed64", name: "Freeze Fix", serial: "BCES01141", appVersion: "01.03", sinceRevision: 14),
        Entry(hash: "PPU-4c819c69904784a56685c31df12f5d492bc0ed64", name: "Freeze Fix", serial: "BCES01142", appVersion: "01.03", sinceRevision: 14),
        Entry(hash: "PPU-4c819c69904784a56685c31df12f5d492bc0ed64", name: "Freeze Fix", serial: "BCUS98175", appVersion: "01.03", sinceRevision: 4),
        Entry(hash: "PPU-ed460e668a491f5e39f6547f3a24c2f20e2cb39b", name: "Freeze fix", serial: "BCES01908", appVersion: "01.00", sinceRevision: 14),
        Entry(hash: "PPU-ed460e668a491f5e39f6547f3a24c2f20e2cb39b", name: "Freeze fix", serial: "BCES01949", appVersion: "01.00", sinceRevision: 4),
        Entry(hash: "PPU-fadb0af6fb7bd0113e88fb9af3eb78fe3be05d08", name: "Freeze fix (all regions)", serial: "BCUS99245", appVersion: "01.01", sinceRevision: 15),
        Entry(hash: "PPU-fadb0af6fb7bd0113e88fb9af3eb78fe3be05d08", name: "Freeze fix (all regions)", serial: "NPUA80908", appVersion: "01.01", sinceRevision: 15),
        Entry(hash: "PPU-fadb0af6fb7bd0113e88fb9af3eb78fe3be05d08", name: "Freeze fix (all regions)", serial: "BCES01908", appVersion: "01.01", sinceRevision: 15),
        Entry(hash: "PPU-fadb0af6fb7bd0113e88fb9af3eb78fe3be05d08", name: "Freeze fix (all regions)", serial: "BCES01949", appVersion: "01.01", sinceRevision: 15),
        Entry(hash: "PPU-fadb0af6fb7bd0113e88fb9af3eb78fe3be05d08", name: "Freeze fix (all regions)", serial: "NPEA00457", appVersion: "01.01", sinceRevision: 15),
        Entry(hash: "PPU-f07f7086588a4ea86a28bd768f0cbe710f5b813b", name: "Freeze Fix", serial: "BCAS20052", appVersion: "All", sinceRevision: 14),
        Entry(hash: "PPU-f07f7086588a4ea86a28bd768f0cbe710f5b813b", name: "Freeze Fix", serial: "BCES00301", appVersion: "All", sinceRevision: 14),
        Entry(hash: "PPU-f07f7086588a4ea86a28bd768f0cbe710f5b813b", name: "Freeze Fix", serial: "NPEA00088", appVersion: "All", sinceRevision: 14),
        Entry(hash: "PPU-f07f7086588a4ea86a28bd768f0cbe710f5b813b", name: "Freeze Fix", serial: "NPEA00106", appVersion: "All", sinceRevision: 4),
        Entry(hash: "PPU-1d9e99e1f091cfbdf1714d04e690d9cd816e2971", name: "Freeze Fix", serial: "NPUA80145", appVersion: "All", sinceRevision: 4),
        Entry(hash: "PPU-0997e35d2b6738f5cecfda1d76380acca0828365", name: "Freeze Fix", serial: "BCUS98124", appVersion: "01.00", sinceRevision: 4),
        Entry(hash: "PPU-3e9032a3f7857057ef783810bd8f34a439baaa59", name: "Field at 30 fps with a 60 fps limit", serial: "BLUS30903", appVersion: "All", sinceRevision: 20),
        Entry(hash: "PPU-db1e09abb1a2785532b89e9a366d2510ba8e070c", name: "Battle freeze fix", serial: "BLUS31519", appVersion: "01.00", sinceRevision: 21),
        Entry(hash: "PPU-6e828d3466bb1272243213bef0df0e499f3965a5", name: "Freeze fix", serial: "BLUS30779", appVersion: "01.00", sinceRevision: 22),
        Entry(hash: "SPU-9001b44fd7278b5a6fa5385939fe928a0e549394", name: "Disable SPU MLAA (Journey)", serial: "NPUA80275", appVersion: "All", sinceRevision: 24),
        Entry(hash: "SPU-9001b44fd7278b5a6fa5385939fe928a0e549394", name: "Disable SPU MLAA (Journey)", serial: "NPEA00288", appVersion: "All", sinceRevision: 24),
        Entry(hash: "SPU-9001b44fd7278b5a6fa5385939fe928a0e549394", name: "Disable SPU MLAA (Journey)", serial: "NPHA80188", appVersion: "All", sinceRevision: 24),
        Entry(hash: "SPU-9001b44fd7278b5a6fa5385939fe928a0e549394", name: "Disable SPU MLAA (Journey)", serial: "NPUA70218", appVersion: "All", sinceRevision: 24),
        Entry(hash: "SPU-9001b44fd7278b5a6fa5385939fe928a0e549394", name: "Disable SPU MLAA (Journey)", serial: "NPEA00438", appVersion: "All", sinceRevision: 24),
        Entry(hash: "SPU-9001b44fd7278b5a6fa5385939fe928a0e549394", name: "Disable SPU MLAA (Journey)", serial: "BCUS98377", appVersion: "All", sinceRevision: 24),
        Entry(hash: "SPU-9001b44fd7278b5a6fa5385939fe928a0e549394", name: "Disable SPU MLAA (Journey)", serial: "BCES01740", appVersion: "All", sinceRevision: 24),
        Entry(hash: "SPU-9001b44fd7278b5a6fa5385939fe928a0e549394", name: "Disable SPU MLAA (Journey)", serial: "NPUA70230", appVersion: "All", sinceRevision: 24),
        Entry(hash: "SPU-7d3b6a847736ef3198deee3620f00c648e5e9588", name: "ARMSX3 graphics SPU speed-up", serial: "NPUB30828", appVersion: "All", sinceRevision: 27),
        Entry(hash: "SPU-7d3b6a847736ef3198deee3620f00c648e5e9588", name: "ARMSX3 graphics SPU speed-up", serial: "NPEB01054", appVersion: "All", sinceRevision: 27),
    ]

    static var bundledYAML: String? {
        Bundle.main.url(forResource: "canary_patches", withExtension: "yml")
            .flatMap { try? String(contentsOf: $0, encoding: .utf8) }
    }

    /// Remembers each entry that switched on, because a patch in the downloaded patch.yml can only switch on after a download.
    static func ensure(yaml: String? = bundledYAML, defaults: UserDefaults = .standard) async
        -> (status: String, enabled: Int, failed: Int) {
        let stored = defaults.integer(forKey: revisionKey)
        let (config, configStatus) = await Rpcs3Bridge.configDirectory()
        let present = configStatus == 0 && FileManager.default.fileExists(atPath: config + "patches/" + file)
        if stored >= revision && present { return ("current", 0, 0) }

        guard let yaml, !yaml.isEmpty else { return ("missing", 0, 0) }
        guard await Rpcs3Bridge.writePatchFile(file, yaml: yaml) == 0 else { return ("write-failed", 0, 0) }

        var done = Set(defaults.stringArray(forKey: enabledKey) ?? [])
        var enabled = 0, failed = 0
        for entry in entries where entry.sinceRevision > stored && !done.contains(entry.key) {
            let status = await Rpcs3Bridge.setPatchEnabled(true, hash: entry.hash, patchDescription: entry.name,
                                                           serial: entry.serial, appVersion: entry.appVersion)
            if status == 0 {
                // Saved as it lands, so a launch killed mid-way resumes where it stopped.
                done.insert(entry.key)
                defaults.set(done.sorted(), forKey: enabledKey)
                enabled += 1
            } else {
                failed += 1
            }
        }

        if failed == 0 {
            defaults.set(revision, forKey: revisionKey)
            defaults.removeObject(forKey: enabledKey)
            return (enabled == 0 && done.isEmpty ? "written" : "enabled", enabled, 0)
        }
        return ("partial", enabled, failed)
    }
}
