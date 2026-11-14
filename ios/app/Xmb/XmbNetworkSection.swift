// SPDX-License-Identifier: GPL-2.0-only
// The XMB's Network column, after XmbHome.kt.

import SwiftUI

extension XmbHost {
    func networkSection() -> XmbSection {
        var section = XmbSection(column: XmbColumn(
            id: "network", title: firmware.text(XmbRco.ingame, "msg_net", String(localized: "Network")),
            icon: .firmware(rco: XmbRco.ingame, name: "tex_network", symbol: "globe"), items: []))
        let address = "https://armsx2.net/armsx3"
        let browser = XmbNode(id: "net.browser", title: firmware.text(XmbRco.network, "msg_browser", String(localized: "Internet Browser")),
                              subtitle: "armsx2.net/armsx3",
                              icon: .firmware(rco: XmbRco.network, name: "tex_browser", symbol: "globe"), kind: .action)
        section.column.items = [section.action(browser) {
            if let url = URL(string: address) { UIApplication.shared.open(url) }
        }]
        return section
    }
}
