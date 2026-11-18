// SPDX-License-Identifier: GPL-2.0-only
// The Change Disc picker. It only picks; GameScreenView does the swap and holds a Files pick's access until the game stops.

import SwiftUI
import UniformTypeIdentifiers

struct DiscPicker: View {
    // The disc's path, and the Files URL whose access the caller must start and hold (nil for a library row).
    let onPick: (String, URL?) -> Void
    let onCancel: () -> Void

    @State private var discs: [Rpcs3GameEntry] = []
    @State private var filesShown = false

    static let fileTypes: [UTType] = [.folder] + ["iso", "chd"].compactMap { UTType(filenameExtension: $0) }

    static func isDisc(_ game: Rpcs3GameEntry) -> Bool { game.isISO || game.category == "DG" }

    // What the player is told after a swap, by the bridge's swap result (0-4).
    static func message(for result: Int) -> String {
        switch result {
        case 0: return String(localized: "Disc changed")
        case 1: return String(localized: "This game isn't asking for a disc change")
        case 2: return String(localized: "That isn't a PS3 disc")
        case 3: return String(localized: "The game didn't finish ejecting the disc")
        default: return String(localized: "The game refused the new disc")
        }
    }

    var body: some View {
        NavigationStack {
            List {
                if !discs.isEmpty {
                    Section("Library") {
                        ForEach(discs, id: \.bootPath) { game in
                            Button(game.title) { onPick(game.bootPath, nil) }
                        }
                    }
                }
                Section {
                    Button("From Files…") { filesShown = true }
                }
            }
            .navigationTitle("Change Disc")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onCancel) } }
            .fileImporter(isPresented: $filesShown, allowedContentTypes: Self.fileTypes) { result in
                if case .success(let url) = result { onPick(url.path, url) }
            }
        }
        .preferredColorScheme(.dark)
        .task { discs = await Rpcs3Bridge.scanLibrary().0.filter(Self.isDisc) }
    }
}
