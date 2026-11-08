// SPDX-License-Identifier: GPL-2.0-only
// The game's CAMetalLayer view from Rpcs3Bridge.makeGameView, hosted in SwiftUI.

import SwiftUI
import UIKit

// Attach lives in makeUIView, not .onAppear: a re-appearance would move the display epoch and rebuild the swapchain.
struct GameRenderView: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView {
        // A refused view is a blank placeholder, never handed to attach.
        guard let view = Rpcs3Bridge.makeGameView() else {
            return UIView()
        }
        // Idempotent on the C++ side; the app root's .task makes the first call.
        Rpcs3Bridge.installLifecycleObservers()
        Rpcs3Bridge.attachGameView(view)
        return view
    }

    func updateUIView(_ view: UIView, context: Context) {
        // Empty: resizes, rotation included, reach the renderer through the view's own layoutSubviews.
    }

    static func dismantleUIView(_ view: UIView, coordinator: ()) {
        Rpcs3Bridge.detachGameView()
    }
}
