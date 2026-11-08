// SPDX-License-Identifier: GPL-2.0-or-later
// Adapted from ARMSX2's iOS app.

import Foundation
import Observation
import UIKit

@MainActor
@Observable
final class ARMSX2LogoStore {
    static let shared = ARMSX2LogoStore()

    private(set) var image: UIImage?

    var hasLogo: Bool { image != nil }

    private init() {
        image = Self.loadStoredImage()
    }

    private static func loadStoredImage() -> UIImage? {
        UIImage(contentsOfFile: logoURL.path)
    }

    private nonisolated static var storageDirectoryURL: URL {
        let manager = FileManager.default
        let base = manager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? manager.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? manager.temporaryDirectory
        return base
            .appendingPathComponent("ARMSX2", isDirectory: true)
            .appendingPathComponent("Branding", isDirectory: true)
    }

    private nonisolated static var logoURL: URL {
        storageDirectoryURL.appendingPathComponent("logo.png", isDirectory: false)
    }
}
