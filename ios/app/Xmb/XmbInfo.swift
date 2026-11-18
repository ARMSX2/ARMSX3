// SPDX-License-Identifier: GPL-2.0-only
// Text to read in an XMB panel, and the About ARMSX3 folder that opens it, ported from Android's XmbInfo.kt.

import Metal
import SwiftUI
import UIKit

/// What the XMB has open to read: paragraphs in a panel over it, as the console shows its information screens.
@MainActor @Observable final class XmbReading {
    static let shared = XmbReading()

    struct Page: Equatable {
        let title: String
        let icon: XmbIcon
        let blocks: [Block]
    }

    enum Block: Equatable {
        case text(String)
        /// A picture at the panel's width, width over height `aspect`; `alt` is all VoiceOver says of it.
        case image(URL, alt: String, aspect: Double)
        /// A release's publisher and the people it credits, over its notes.
        case release(XmbNews.Item)
    }

    private(set) var page: Page?
    /// The block at the top: the pad steps it, and a finger's scroll moves it too.
    var focus = 0

    func open(_ title: String, icon: XmbIcon, paragraphs: [String]) {
        open(title, icon: icon, blocks: paragraphs.map(Block.text))
    }

    func open(_ title: String, icon: XmbIcon, blocks: [Block]) {
        focus = 0
        page = Page(title: title, icon: icon, blocks: blocks)
    }

    func close() { page = nil }

    /// Text split into paragraphs at blank lines, each kept whole.
    nonisolated static func paragraphs(of text: String) -> [String] {
        text.replacingOccurrences(of: "\r", with: "")
            .replacingOccurrences(of: "\n\\s*\n", with: "\u{0}", options: .regularExpression)
            .split(separator: "\u{0}")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }

    /// Up and Down go a paragraph or a picture at a time and Circle closes. Answers the console's sound for the press.
    func apply(_ action: XmbAction) -> XmbSound? {
        guard let page else { return nil }
        switch action {
        case .move(_, let dy) where dy != 0:
            let next = min(max(focus + dy, 0), page.blocks.count - 1)
            guard next != focus else { return nil }
            withAnimation(.xmb(XmbTiming.childFocus)) { focus = next }
            return .cursor
        case .back:
            close()
            return .cancel
        default:
            return nil
        }
    }
}

/// The panel: the item's icon and title over dark glass, Back in the corner, and the text in the XMB's font.
struct XmbTextPanel: View {
    let firmware: XmbFirmware
    let m: XmbMetrics

    var body: some View {
        let reading = XmbReading.shared
        if let page = reading.page {
            VStack(spacing: 0) {
                HStack(spacing: 16 * m.u) {
                    XmbIconView(icon: page.icon, firmware: firmware)
                        .frame(width: 44 * m.u, height: 44 * m.u)
                    Text(verbatim: page.title)
                        .font(firmware.font(size: m.titleFont))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                        .xmbShadow()
                        .frame(maxWidth: .infinity, alignment: .leading)
                    XmbHintChip(circle: true, label: firmware.text(XmbRco.ingame, "msg_help_back", String(localized: ": Back")),
                                firmware: firmware, m: m) {
                        reading.close()
                        firmware.play(.cancel)
                    }
                }
                .padding(.leading, 40 * m.u)
                .padding(.trailing, 24 * m.u)
                .padding(.top, 18 * m.u)
                .padding(.bottom, 8 * m.u)
                ScrollView {
                    // Android sizes the text in sp and dp, which are points here.
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(page.blocks.enumerated()), id: \.offset) { index, block in
                            blockView(block).id(index)
                        }
                    }
                    .scrollTargetLayout()
                    .padding(.horizontal, 24)
                    .padding(.top, 8)
                    .padding(.bottom, 88)
                }
                .scrollPosition(id: Binding(get: { reading.focus }, set: { if let top = $0 { reading.focus = top } }), anchor: .top)
                // Clear of the Dynamic Island on whichever side it is.
                .padding(.leading, max(24 * m.u, m.leftInset - 24))
                .padding(.trailing, max(24 * m.u, m.rightInset - 24))
            }
            .background(XmbPanelGround().ignoresSafeArea())
            .transition(.opacity.animation(.xmb(0.2)))
            .accessibilityElement(children: .contain)
            .accessibilityAddTraits(.isModal)
            .accessibilityAction(.escape) { reading.close() }
        }
    }

    @ViewBuilder private func blockView(_ block: XmbReading.Block) -> some View {
        switch block {
        case .text(let paragraph):
            Text(verbatim: paragraph)
                .font(firmware.font(size: 17))
                .lineSpacing(4)
                .foregroundStyle(.white.opacity(0.92))
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 5)
        case .image(let url, let alt, let aspect):
            // The clear box holds the picture's shape before it arrives, no taller than a modest height.
            Color.clear
                .aspectRatio(aspect, contentMode: .fit)
                .frame(maxWidth: .infinity, maxHeight: 360)
                .overlay {
                    AsyncImage(url: url) { phase in
                        if let image = phase.image {
                            image.resizable().scaledToFit()
                        } else if phase.error == nil {
                            RoundedRectangle(cornerRadius: 6).fill(.white.opacity(0.08))
                        }
                    }
                }
                .padding(.vertical, 8)
                .accessibilityElement()
                .accessibilityLabel(Text(verbatim: alt))
                .accessibilityAddTraits(.isImage)
                // GitHub writes "image" for every pasted picture, which says nothing.
                .accessibilityHidden(alt.isEmpty || alt.caseInsensitiveCompare("image") == .orderedSame)
        case .release(let item):
            releaseHeader(item)
        }
    }

    /// Who published the release, with the date, then each person the notes credit, as Android heads them.
    private func releaseHeader(_ item: XmbNews.Item) -> some View {
        let line = [item.authorLogin, item.published, item.prerelease ? String(localized: "Pre-release") : ""]
            .filter { !$0.isEmpty }.joined(separator: "   ")
        return VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                if let avatar = item.authorAvatar { avatarView(avatar, size: 38) }
                Text(verbatim: line)
                    .font(firmware.font(size: 16))
                    .foregroundStyle(.white.opacity(0.9))
            }
            .accessibilityElement(children: .combine)
            if !item.credited.isEmpty {
                Text("Contributors")
                    .font(firmware.font(size: 14))
                    .foregroundStyle(.white.opacity(0.7))
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 14) {
                        ForEach(item.credited, id: \.self) { login in
                            VStack(spacing: 4) {
                                if let avatar = XmbNews.avatar(of: login) { avatarView(avatar, size: 42) }
                                Text(verbatim: login)
                                    .font(firmware.font(size: 12))
                                    .foregroundStyle(.white.opacity(0.85))
                                    .lineLimit(1)
                            }
                            .frame(width: 66)
                            .accessibilityElement(children: .combine)
                        }
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 6)
    }

    private func avatarView(_ url: URL, size: CGFloat) -> some View {
        AsyncImage(url: url) { image in
            image.resizable().scaledToFill()
        } placeholder: {
            Color.white.opacity(0.12)
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .overlay(Circle().stroke(.white.opacity(0.6), lineWidth: 1))
        .accessibilityHidden(true)
    }
}

/// The emulator's version line, asked of the core once.
@MainActor @Observable private final class XmbCoreVersion {
    static let shared = XmbCoreVersion()

    private(set) var line: String?
    @ObservationIgnored private var asked = false

    func load() {
        guard !asked else { return }
        asked = true
        Task { line = await AboutView.coreVersion() }
    }
}

/// What About says of the device, read once.
@MainActor private enum XmbDevice {
    static let model: String = {
        var size = 0
        guard sysctlbyname("hw.machine", nil, &size, nil, 0) == 0, size > 0 else { return UIDevice.current.model }
        var bytes = [CChar](repeating: 0, count: size)
        guard sysctlbyname("hw.machine", &bytes, &size, nil, 0) == 0 else { return UIDevice.current.model }
        return "Apple " + bytes.withUnsafeBufferPointer { String(cString: $0.baseAddress!) }
    }()
    static let system = UIDevice.current.systemName + " " + UIDevice.current.systemVersion
    static let gpu = MTLCreateSystemDefaultDevice()?.name ?? ""
    static let cores = String(ProcessInfo.processInfo.processorCount)
    static let memory = XmbFileSize.text(Int64(ProcessInfo.processInfo.physicalMemory))
    static let display: String = {
        // The XMB is a landscape screen, so the long side comes first.
        let pixels = UIScreen.main.nativeBounds.size
        return "\(Int(max(pixels.width, pixels.height))) × \(Int(min(pixels.width, pixels.height))) · @\(Int(UIScreen.main.scale))x"
    }()
    #if arch(arm64)
    static let architecture = "arm64"
    #else
    static let architecture = "x86_64"
    #endif
    static let page: String = {
        let kilobytes = sysconf(_SC_PAGESIZE) / 1024
        return kilobytes >= 16 ? "\(kilobytes) KB (16K)" : "\(kilobytes) KB"
    }()
}

extension XmbHost {
    /// About ARMSX3: its version and the device's, where it comes from, and the licences of what it uses.
    func aboutNode(in section: inout XmbSection) -> XmbNode {
        let info = XmbIcon.firmware(rco: XmbRco.sysconf, name: "tex_console", symbol: "info.circle")
        func row(_ key: String, _ label: String, _ value: String) -> XmbNode {
            XmbNode(id: "about." + key, title: label, subtitle: value.isEmpty ? "-" : value, icon: info, kind: .info)
        }
        func folder(_ key: String, _ title: String, _ subtitle: String, _ items: [XmbNode]) -> XmbNode {
            XmbNode(id: "about." + key, title: title, subtitle: subtitle, icon: info, kind: .folder(items))
        }
        func link(_ key: String, _ title: String, _ subtitle: String, _ address: String) -> XmbNode {
            section.action(XmbNode(id: "about." + key, title: title, subtitle: subtitle, icon: info, kind: .action)) {
                if let url = URL(string: address) { UIApplication.shared.open(url) }
            }
        }
        let core = XmbCoreVersion.shared
        core.load()
        let app = AboutView.appVersion, emulator = core.line ?? ""
        let build = folder("build", String(localized: "Build information"), app + "   " + emulator, [
            row("app", String(localized: "App version"), app),
            row("core", String(localized: "Emulator version"), emulator),
        ])
        // The chipset row is left out: iOS does not name the chip, and the GPU's name already does.
        let hardware = folder("hardware", String(localized: "Device information"), XmbDevice.model, [
            row("device", String(localized: "Device"), XmbDevice.model),
            row("system", String(localized: "iOS version"), XmbDevice.system),
            row("gpu", String(localized: "GPU"), XmbDevice.gpu),
            row("cpu", String(localized: "CPU cores"), XmbDevice.cores),
            row("memory", String(localized: "Memory"), XmbDevice.memory),
            row("display", String(localized: "Display"), XmbDevice.display),
            row("arch", String(localized: "Architecture"), XmbDevice.architecture),
            row("page", String(localized: "Memory page"), XmbDevice.page),
        ])
        let repository = link("repo", String(localized: "GitHub repository"),
                              String(localized: "Open the source code, releases, and issue tracker."), "https://github.com/ARMSX2/ARMSX3")
        let rpcs3 = link("rpcs3", String(localized: "RPCS3 project"),
                         String(localized: "ARMSX3 is built on the open-source RPCS3 emulator."), "https://github.com/RPCS3/rpcs3")
        let licences = folder("licenses", String(localized: "Open-source licenses"),
                              String(localized: "The third-party software inside ARMSX3 and its licenses."), licenceNodes(in: &section, icon: info))
        return XmbNode(id: "armsx3.about", title: String(localized: "About app"),
                       subtitle: String(localized: "Fast, modern PlayStation 3 emulation for iPhone and iPad."), icon: .mark,
                       kind: .folder([build, hardware, repository, rpcs3, licences]))
    }

    /// ARMSX3's own licence to read in full, then each component it uses: one with a note opens it, the rest only name
    /// their licence, since the app carries no other licence text.
    private func licenceNodes(in section: inout XmbSection, icon: XmbIcon) -> [XmbNode] {
        let own = section.action(XmbNode(id: "lic.armsx3", title: "ARMSX3", subtitle: "GPL-2.0-only", icon: icon, kind: .action)) {
            let terms = String(localized: "ARMSX3 is free software under the GNU General Public License version 2 only. It comes with no warranty. Its source is the repository it was built from.")
            XmbReading.shared.open("ARMSX3", icon: icon, paragraphs: [terms] + XmbReading.paragraphs(of: AboutView.licenceText() ?? ""))
        }
        return [own] + AboutView.notices.enumerated().map { index, notice in
            let node = XmbNode(id: "lic.\(index)", title: notice.component, subtitle: notice.licence, icon: icon,
                               kind: notice.note == nil ? .info : .action)
            guard let note = notice.note else { return node }
            return section.action(node) {
                XmbReading.shared.open(notice.component, icon: icon, paragraphs: [notice.licence, note])
            }
        }
    }
}
