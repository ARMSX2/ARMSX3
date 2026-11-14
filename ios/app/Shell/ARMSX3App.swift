// SPDX-License-Identifier: GPL-2.0-only

import SwiftUI

@main
struct ARMSX3App: App {
    @UIApplicationDelegateAdaptor(ARMSX3AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            ARMSX3FrontendRoot()
        }
        .backgroundTask(.urlSession(UpdateStore.sessionID)) { await UpdateStore.finishBackgroundEvents() }
    }
}
