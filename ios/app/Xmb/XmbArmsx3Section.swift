// SPDX-License-Identifier: GPL-2.0-only
// The XMB's ARMSX3 column, where the console had PlayStation Network, ported from XmbHome.kt and xmbNewsNode in XmbInfo.kt.

import SwiftUI

extension XmbHost {
    func armsx3Section() -> XmbSection {
        var section = XmbSection(column: XmbColumn(id: "armsx3", title: "ARMSX3", icon: .badge, items: []))
        let discord = section.action(XmbNode(id: "armsx3.discord", title: "Discord", subtitle: nil, icon: .asset("XmbDiscord"),
                                             kind: .action)) {
            UIApplication.shared.open(URL(string: "https://discord.gg/2Tynvwhc4A")!)
        }
        let github = section.action(XmbNode(id: "armsx3.github", title: "GitHub", subtitle: nil, icon: .asset("XmbGitHub"),
                                            kind: .action)) {
            UIApplication.shared.open(URL(string: "https://github.com/ARMSX2/ARMSX3")!)
        }
        let news = newsFolder(&section)
        let about = aboutNode(in: &section)
        section.column.items = [news, discord, github, about]
        return section
    }

    /// What's new: each release opens its notes to read, under who published it and who it credits.
    private func newsFolder(_ section: inout XmbSection) -> XmbNode {
        let news = XmbNews.shared
        news.folderOpen(isOpen("armsx3.news"))
        let rows: [XmbNode]
        switch news.items {
        case nil:
            rows = [XmbNode(id: "news.loading", title: String(localized: "Loading release notes…"), subtitle: nil, icon: .mark, kind: .info)]
        case let items? where items.isEmpty:
            rows = [XmbNode(id: "news.none", title: String(localized: "Release notes are unavailable right now."), subtitle: nil,
                            icon: .mark, kind: .info)]
        case let items?:
            rows = items.map { item in
                var heading: [String] = []
                for part in [item.tag, item.title] where !part.isEmpty && !heading.contains(part) { heading.append(part) }
                let title = heading.joined(separator: "  ")
                let icon = item.authorAvatar.map(XmbIcon.remote) ?? .mark
                let node = XmbNode(id: "news." + item.tag, title: title,
                                   subtitle: [item.published, item.authorLogin].filter { !$0.isEmpty }.joined(separator: "   "),
                                   icon: icon, kind: .action)
                return section.action(node) { XmbReading.shared.open(title, icon: icon, blocks: Self.releasePage(item)) }
            }
        }
        return XmbNode(id: "armsx3.news", title: String(localized: "What's new"), subtitle: nil, icon: .mark, kind: .folder(rows))
    }

    /// The notes under the header Android draws: publisher, date and pre-release, then the people credited.
    private static func releasePage(_ item: XmbNews.Item) -> [XmbReading.Block] {
        let notes = item.blocks.flatMap { block -> [XmbReading.Block] in
            switch block {
            case .text(let text): XmbReading.paragraphs(of: text).map(XmbReading.Block.text)
            case .image(let url, let alt, let aspect): [.image(url, alt: alt, aspect: aspect)]
            }
        }
        return [.release(item)] + (notes.isEmpty ? [.text(String(localized: "No notes for this release."))] : notes)
    }
}
