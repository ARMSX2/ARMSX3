// SPDX-License-Identifier: GPL-2.0-only
// The XMB's Users column: Turn Off System and Exit XMB Mode, ported from the users column in Android's XmbHome.kt.

import SwiftUI

extension XmbHost {
    func usersSection() -> XmbSection {
        var section = XmbSection(column: XmbColumn(
            id: "users", title: firmware.text(XmbRco.ingame, "msg_user", String(localized: "Users")),
            icon: .firmware(rco: XmbRco.ingame, name: "tex_user", symbol: "person.crop.circle"), items: []))
        let turnOff = section.action(XmbNode(
            id: "users.off", title: firmware.text(XmbRco.full, "msg_power_off_console2", String(localized: "Turn Off System")), subtitle: nil,
            icon: .firmware(rco: XmbRco.user, name: "tex_power_off", symbol: "power"), kind: .action)) { [weak self] in
            guard let self else { return }
            ask(firmware.text(XmbRco.full, "msg_turnoff_system_ask", String(localized: "Do you want to turn off the system?")),
                buttons: [yes, no]) { button in
                // iOS has no call to quit an app; _exit skips the core's static destructors, as Close ARMSX3 does. The
                // session is marked a chosen close first, so the next launch does not take it for a crash.
                guard button == 0 else { return }
                DiagnosticsSession.shared.mark(.background)
                _exit(0)
            }
        }
        let exit = section.action(XmbNode(
            id: "users.exit", title: String(localized: "Exit XMB Mode"), subtitle: String(localized: "Back to the regular library"),
            icon: .mark, kind: .action)) { [weak self] in
            guard let self else { return }
            ask(String(localized: "Exit XMB Mode and go back to the regular library?"), buttons: [yes, no]) { [settings] button in
                guard button == 0 else { return }
                XmbBootClock.shared.shutDown { settings.xmbMode = false }
            }
        }
        section.column.items = [turnOff, exit]
        section.column.defaultFocus = 1
        return section
    }
}
