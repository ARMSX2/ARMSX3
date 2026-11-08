// SPDX-License-Identifier: GPL-2.0-or-later
// Adapted from ARMSX2's iOS app.

import CoreGraphics
import Foundation
import ImageIO
import Observation
import SwiftUI

struct GameCoverThemePreview: Equatable {
    let gameID: String
    let backgroundPalette: SavedPaletteColor
    let accentPalette: SavedPaletteColor
    let primaryTextPalette: SavedPaletteColor
    let secondaryTextPalette: SavedPaletteColor

    var accentColor: Color { accentPalette.color }
    var primaryTextColor: Color { primaryTextPalette.color }
    var secondaryTextColor: Color { secondaryTextPalette.color }
}

/// Owns a single transient preview without mutating the user's saved theme.
@MainActor
@Observable
final class GameCoverThemePreviewStore {
    static let shared = GameCoverThemePreviewStore()

    private(set) var preview: GameCoverThemePreview?

    private init() {}
}
