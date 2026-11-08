// SPDX-License-Identifier: GPL-2.0-only
// A title's cover from a local file (ICON0, or PIC1 for the backdrop), decoded off the main thread and cached.

import SwiftUI
import UIKit

struct CoverImageView: View {
    let path: String?
    // The backdrop draws nothing rather than the fallback when the title has no PIC1.
    var showsFallback = true

    @State private var image: UIImage?

    // shortcut: count-limited only; add a cost limit if large PIC1s show up in memory readings.
    private static let cache: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 200
        return cache
    }()

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else if showsFallback {
                fallback
            }
        }
        .task(id: path) { image = await Self.load(path) }
    }

    private var fallback: some View {
        ZStack {
            LinearGradient(colors: [OverlayTheme.card, OverlayTheme.shell], startPoint: .top, endPoint: .bottom)
            Image(systemName: "opticaldisc")
                .font(.system(size: 40, weight: .light))
                .foregroundStyle(OverlayTheme.textSecondary)
        }
        .accessibilityHidden(true)
    }

    // A cover file that was replaced or removed is decoded afresh next time.
    static func forget(path: String) {
        cache.removeObject(forKey: path as NSString)
    }

    private static func load(_ path: String?) async -> UIImage? {
        guard let path, !path.isEmpty else { return nil }
        if let hit = cache.object(forKey: path as NSString) { return hit }
        let decoded = await Task.detached(priority: .utility) { UIImage(contentsOfFile: path) }.value
        if let decoded { cache.setObject(decoded, forKey: path as NSString) }
        return decoded
    }
}
