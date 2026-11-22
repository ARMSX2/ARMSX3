// SPDX-License-Identifier: GPL-2.0-only

import SwiftUI

@main
struct ARMSX3App: App {
    @UIApplicationDelegateAdaptor(ARMSX3AppDelegate.self) private var appDelegate

    init() {
        for entry in ConfigFolderMigration.run(home: URL(fileURLWithPath: NSHomeDirectory(), isDirectory: true)) {
            NSLog("ARMSX3: config folder entry left in place: %@", entry)
        }
    }

    var body: some Scene {
        WindowGroup {
            ARMSX3FrontendRoot()
        }
        .backgroundTask(.urlSession(UpdateStore.sessionID)) { await UpdateStore.finishBackgroundEvents() }
    }
}
