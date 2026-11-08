// SPDX-License-Identifier: GPL-2.0-only
// One title's trophies, read-only through TrophyReader. Secret trophies stay hidden until earned or Show hidden is on.

import SwiftUI
import UIKit

struct TrophiesView: View {
    let titleDir: URL
    let gameTitle: String

    @State private var sets: [TrophySet] = []
    @State private var loaded = false
    @State private var failure: Int?
    @State private var showHidden = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if let failure {
                    Text("Couldn't find the trophy folder (status \(failure))").foregroundStyle(.orange)
                } else if loaded && sets.isEmpty {
                    Text("This game has no trophies here yet").foregroundStyle(.secondary)
                }
                if !sets.isEmpty {
                    Toggle("Show hidden", isOn: $showHidden)
                }
                ForEach(sets, id: \.id) { set in
                    Section {
                        ForEach(set.trophies) { trophy in
                            TrophyRow(trophy: trophy, revealed: trophy.isRevealed(showHidden: showHidden))
                        }
                    } header: {
                        Text("\(set.title) · \(set.trophies.filter(\.unlocked).count) of \(set.trophies.count) earned").overlayCaption()
                    }
                }
            }
            // The app's own dark background, as the other sheets draw it, not the list's plain black.
            .scrollContentBackground(.hidden)
            .background(OverlayFrostBackground().ignoresSafeArea())
            .navigationTitle("Trophies")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Done") { dismiss() } }
            }
        }
        .presentationDetents([.medium, .large])
        .preferredColorScheme(.dark)
        .task { await load() }
    }

    private func load() async {
        let (path, status) = await withCheckedContinuation { (continuation: CheckedContinuation<(String, Int), Never>) in
            Rpcs3Bridge.userDirectory { path, status in
                continuation.resume(returning: (path, status))
            }
        }
        guard status == 0, !path.isEmpty else {
            failure = status
            return
        }
        let userDir = URL(fileURLWithPath: path, isDirectory: true)
        sets = TrophyReader.sets(titleDir: titleDir, userDir: userDir).compactMap {
            TrophyReader.read(setDir: userDir.appendingPathComponent("trophy").appendingPathComponent($0))
        }
        loaded = true
    }
}

private struct TrophyRow: View {
    let trophy: Trophy
    let revealed: Bool

    var body: some View {
        HStack(spacing: 12) {
            Group {
                if revealed, let icon = trophy.iconURL, let image = UIImage(contentsOfFile: icon.path) {
                    Image(uiImage: image).resizable().scaledToFit()
                } else {
                    Image(systemName: revealed ? "trophy" : "questionmark.circle").foregroundStyle(OverlayTheme.textSecondary)
                }
            }
            .frame(width: 44, height: 44)
            .opacity(trophy.unlocked ? 1 : 0.4)
            VStack(alignment: .leading, spacing: 2) {
                Text(revealed ? trophy.name : String(localized: "Hidden trophy")).foregroundStyle(OverlayTheme.textPrimary)
                Text(revealed ? trophy.detail : String(localized: "Keep playing to reveal it"))
                    .font(.subheadline)
                    .foregroundStyle(OverlayTheme.textSecondary)
                if let date = trophy.unlockedAt {
                    Text("Earned \(date.formatted(.dateTime.year().month().day().hour().minute()))")
                        .font(.caption)
                        .foregroundStyle(OverlayTheme.textSecondary)
                } else if trophy.unlocked {
                    Text("Earned").font(.caption).foregroundStyle(OverlayTheme.textSecondary)
                }
            }
            Spacer()
            if let grade = trophy.grade {
                Text(Self.label(grade))
                    .font(.caption2.weight(.semibold))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(Capsule().fill(Self.colour(grade).opacity(0.25)))
                    .foregroundStyle(Self.colour(grade))
            }
        }
    }

    static func label(_ grade: TrophyGrade) -> String {
        switch grade {
        case .platinum: return String(localized: "Platinum")
        case .gold: return String(localized: "Gold")
        case .silver: return String(localized: "Silver")
        case .bronze: return String(localized: "Bronze")
        }
    }

    static func colour(_ grade: TrophyGrade) -> Color {
        switch grade {
        case .platinum: return Color(red: 0.75, green: 0.85, blue: 0.95)
        case .gold: return Color(red: 0.95, green: 0.78, blue: 0.3)
        case .silver: return Color(red: 0.8, green: 0.8, blue: 0.82)
        case .bronze: return Color(red: 0.8, green: 0.52, blue: 0.3)
        }
    }
}
