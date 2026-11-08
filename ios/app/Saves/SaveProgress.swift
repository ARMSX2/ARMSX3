// SPDX-License-Identifier: GPL-2.0-only
// The save-state progress bar. It polls on the input queue, because the save holds the bridge queue.

import SwiftUI

@MainActor @Observable final class SaveProgress {
    static let shared = SaveProgress()
    static let pollNanoseconds: UInt64 = 500_000_000

    private(set) var active = false
    // The quick-save hotkey starts saves outside the panel.
    private(set) var fromPanel = false
    private(set) var seconds = 0
    // Nil shows seconds.
    private(set) var bytes: UInt64?
    private(set) var lastStatus: Int?
    // The smoke scene's witness.
    private(set) var drawnInPanel = false
    private(set) var drawnAsBanner = false
    private var poll: Task<Void, Never>?

    func start(fromPanel: Bool) {
        poll?.cancel()
        self.fromPanel = fromPanel
        seconds = 0
        bytes = nil
        drawnInPanel = false
        drawnAsBanner = false
        active = true
        let started = Date()
        poll = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: Self.pollNanoseconds)
                guard let self, !Task.isCancelled else { return }
                seconds = Int(Date().timeIntervalSince(started))
                let (status, written) = await withCheckedContinuation { continuation in
                    Rpcs3Bridge.saveStateProgress { status, written, _ in continuation.resume(returning: (status, written)) }
                }
                if !Task.isCancelled, status == 0, written > 0 { bytes = written }
            }
        }
    }

    func end(status: Int) {
        poll?.cancel()
        poll = nil
        active = false
        lastStatus = status
    }

    func noteDrawn(inPanel: Bool) {
        if inPanel { drawnInPanel = true } else { drawnAsBanner = true }
    }

    var label: String {
        if let bytes {
            return String(localized: "Saving… \(ByteCountFormatter.string(fromByteCount: Int64(clamping: bytes), countStyle: .file)) written")
        }
        return String(localized: "Saving… \(seconds) s")
    }
}

struct SaveProgressBar: View {
    let inPanel: Bool

    var body: some View {
        let progress = SaveProgress.shared
        HStack(spacing: 10) {
            ProgressView()
                .tint(.white)
            Text(progress.label)
                .monospacedDigit()
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(.ultraThinMaterial, in: Capsule())
        .accessibilityElement(children: .combine)
        .accessibilityLabel(progress.label)
        .onAppear { progress.noteDrawn(inPanel: inPanel) }
    }
}
