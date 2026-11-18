// SPDX-License-Identifier: GPL-2.0-only

import UIKit

/// Holds the window in landscape while the XMB is up, as the console's screen is; a game turns freely.
final class ARMSX3AppDelegate: NSObject, UIApplicationDelegate {
    @MainActor static var landscapeOnly = false {
        didSet {
            guard landscapeOnly != oldValue else { return }
            for case let scene as UIWindowScene in UIApplication.shared.connectedScenes {
                for window in scene.windows { window.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations() }
                if landscapeOnly { scene.requestGeometryUpdate(.iOS(interfaceOrientations: .landscape)) }
            }
        }
    }

    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        if Self.landscapeOnly { return .landscape }
        // The same orientations Info.plist lists.
        return UIDevice.current.userInterfaceIdiom == .pad ? .all : .allButUpsideDown
    }
}
