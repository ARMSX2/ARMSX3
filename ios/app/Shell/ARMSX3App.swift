// SPDX-License-Identifier: GPL-2.0-only

import SwiftUI

@main
struct ARMSX3App: App {
    var body: some Scene {
        WindowGroup {
            ARMSX3FrontendRoot()
        }
        .backgroundTask(.urlSession(UpdateStore.sessionID)) { await UpdateStore.finishBackgroundEvents() }
    }
}
