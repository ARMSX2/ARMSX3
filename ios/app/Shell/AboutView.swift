// SPDX-License-Identifier: GPL-2.0-only
// Settings > About. A new linked component owes a row in the notices.

import SwiftUI

struct AboutView: View {
    @Binding var path: [SettingsPane]
    var controller = SettingsControllerContext()
    @State private var core: String?
    @State private var coreLoaded = false

    struct Notice: Identifiable {
        let component: String
        let licence: String
        let note: String?
        var id: String { component }
    }

    static let notices: [Notice] = [
        Notice(component: "Abseil", licence: "Apache-2.0", note: nil),
        Notice(component: "AsmJit", licence: "Zlib", note: nil),
        Notice(component: "cubeb", licence: "ISC", note: nil),
        Notice(component: "curl", licence: "curl", note: nil),
        Notice(component: "FFmpeg 5.1", licence: "LGPL-2.1-or-later",
               note: String(localized: "Built from source with GPL parts disabled.")),
        Notice(component: "Fusion", licence: "MIT", note: nil),
        Notice(component: "glslang", licence: "BSD-3-Clause, BSD-2-Clause, MIT, Apache-2.0",
               note: String(localized: "Each part under the licence its LICENSE.txt names.")),
        Notice(component: "libchdr", licence: "BSD-3-Clause", note: nil),
        Notice(component: "libpng", licence: "libpng-2.0", note: nil),
        Notice(component: "LLVM", licence: "Apache-2.0 WITH LLVM-exception", note: nil),
        Notice(component: "MiniUPnPc", licence: "BSD-3-Clause", note: nil),
        Notice(component: "MoltenVK", licence: "Apache-2.0", note: nil),
        Notice(component: "OpenAL Soft", licence: "LGPL-2.0-or-later",
               note: String(localized: "Includes a modified PFFFT under its own BSD-style licence.")),
        Notice(component: "Protocol Buffers", licence: "BSD-3-Clause", note: nil),
        Notice(component: "pugixml", licence: "MIT", note: nil),
        Notice(component: "RtMidi", licence: "MIT", note: nil),
        Notice(component: "SoundTouch", licence: "LGPL-2.1-or-later", note: nil),
        Notice(component: "wolfSSL", licence: "GPL-2.0",
               note: String(localized: "Offered under GPL-3.0; wolfSSL's exception for RPCS3 allows GPL-2.0.")),
        Notice(component: "yaml-cpp", licence: "MIT", note: nil),
        Notice(component: "zlib", licence: "Zlib", note: nil),
        Notice(component: "Zstandard", licence: "BSD-3-Clause",
               note: String(localized: "Offered as BSD-3-Clause OR GPL-2.0; used under BSD-3-Clause.")),
        Notice(component: "StikJIT", licence: "MPL-2.0",
               note: String(localized: "The JIT enabler's engine. The modified file stays under MPL-2.0 and its source is offered.")),
        Notice(component: "Dolphin for iOS JIT launcher", licence: "GPL-2.0-or-later",
               note: String(localized: "The JIT enabler's launcher and request handler.")),
        Notice(component: "idevice", licence: "MIT", note: String(localized: "Built from source for the JIT enabler.")),
        Notice(component: "librashader", licence: "MPL-2.0",
               note: String(localized: "Used under MPL-2.0 (offered as MPL-2.0 OR GPL-3.0-only). Ships as its own file, Frameworks/librashader.dylib, loaded at runtime with dlopen and never linked into ARMSX3. Source: github.com/SnowflakePowered/librashader at commit 87e8a97b.")),
    ]

    static var appVersion: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? ""
        let build = info?["CFBundleVersion"] as? String ?? ""
        return short.isEmpty ? "" : "\(short) (\(build))"
    }

    static func licenceText() -> String? {
        guard let url = Bundle.main.url(forResource: "GPL-2.0", withExtension: "txt") else { return nil }
        return try? String(contentsOf: url, encoding: .utf8)
    }

    /// The core's version line, or nil when the façade answers with a non-zero status.
    static func coreVersion() async -> String? {
        let (version, status) = await Rpcs3Bridge.version()
        return status == 0 && !version.isEmpty ? version : nil
    }

    var body: some View {
        Form {
            Section {
                LabeledContent("Version") { Text(verbatim: Self.appVersion) }
            } header: { Text("ARMSX3").overlayCaption() }
            Section {
                if !coreLoaded {
                    ProgressView()
                } else if let core {
                    Text(verbatim: core).font(.footnote).textSelection(.enabled)
                } else {
                    Text("Unavailable").foregroundStyle(.secondary)
                }
            } header: { Text("Emulator").overlayCaption() }
            Section {
                Text("ARMSX3 is free software under the GNU General Public License version 2 only. It comes with no warranty. Its source is the repository it was built from.")
                    .font(.footnote)
                NavigationLink("GNU General Public License v2.0") { LicenceTextView() }
            } header: { Text("Licence").overlayCaption() }
            Section {
                ForEach(Self.notices) { notice in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: notice.component).font(.subheadline.weight(.semibold))
                        Text(verbatim: notice.licence).font(.caption).foregroundStyle(.secondary)
                        if let note = notice.note {
                            Text(note).font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                }
            } header: { Text("Third-party notices").overlayCaption() }
        }
        .scrollContentBackground(.hidden)
        .background(OverlayFrostBackground().ignoresSafeArea())
        // Dark like every other Settings pane, whatever the system appearance.
        .environment(\.colorScheme, .dark)
        .navigationTitle(SettingsPane.about.title)
        .navigationBarTitleDisplayMode(.inline)
        .controllerAccessibilityNavigation(controllerInput: controller.input, scopeKey: "settings.pane." + SettingsPane.about.navigationKey,
                                           onBack: { pop() }, onPreviousTab: controller.onPreviousTab,
                                           onNextTab: controller.onNextTab, onBoundary: controller.onBoundary)
        .task {
            core = await Self.coreVersion()
            coreLoaded = true
        }
    }

    private func pop() -> Bool {
        guard !path.isEmpty else { return false }
        path.removeLast()
        return true
    }
}

private struct LicenceTextView: View {
    var body: some View {
        ScrollView {
            Text(verbatim: AboutView.licenceText() ?? "")
                .font(.system(.caption, design: .monospaced))
                .textSelection(.enabled)
                .padding()
        }
        .background(OverlayFrostBackground().ignoresSafeArea())
        .environment(\.colorScheme, .dark)
        .navigationTitle("GNU General Public License v2.0")
        .navigationBarTitleDisplayMode(.inline)
    }
}
