// SPDX-License-Identifier: GPL-2.0-only
// The XMB's notices, glass boxes under the clock's bar that come and go by themselves, ported from Android's XmbNotice.kt.

import SwiftUI
import UIKit

@MainActor @Observable final class XmbNotices {
    static let shared = XmbNotices()

    /// The notices in the order they stack. A new one takes the place of the one of its kind and starts its time again.
    enum Kind: Int {
        case note
        case friendOnline
        case invite
        case request

        /// How long each stays, by Android's clocks.
        var seconds: Double {
            switch self {
            case .note: return 4.25
            case .friendOnline: return 4.2
            case .invite: return 8
            case .request: return 6
            }
        }
    }

    struct Notice: Identifiable, Equatable {
        let kind: Kind
        let text: String
        let detail: String?
        let icon: XmbIcon
        /// A player's picture, framed as the console frames its user's, in place of the icon.
        let picture: UIImage?

        var id: Int { kind.rawValue }
    }

    private(set) var shown: [Notice] = []
    @ObservationIgnored private var clocks: [Kind: Task<Void, Never>] = [:]

    private static let defaultAvatar = XmbIcon.firmware(rco: XmbRco.friend, name: "tex_Avatar_Default", symbol: "person.crop.square")
    private static let toldRequestsKey = "rpcn.requests.told"

    func post(_ text: String, icon: XmbIcon = .badge, picture: UIImage? = nil, detail: String? = nil, kind: Kind = .note) {
        clocks[kind]?.cancel()
        shown.removeAll { $0.kind == kind }
        shown.append(Notice(kind: kind, text: text, detail: detail, icon: icon, picture: picture))
        shown.sort { $0.kind.rawValue < $1.kind.rawValue }
        AccessibilityNotification.Announcement(text).post()
        clocks[kind] = Task { [weak self] in
            try? await Task.sleep(for: .seconds(kind.seconds))
            guard !Task.isCancelled else { return }
            self?.shown.removeAll { $0.kind == kind }
        }
    }

    /// The greeting on waking, with the player's avatar as the console's sign-in notice shows its user's, else ARMSX3's mark.
    func welcomeBack(avatar: UIImage?) {
        post(String(localized: "Welcome Back!"), picture: avatar)
    }

    /// An invite for the running game that has just arrived.
    func invited(by name: String) {
        post(String(localized: "\(name) invited you to play"),
             icon: .firmware(rco: XmbRco.friend, name: "item_tex_Messages", symbol: "envelope"),
             detail: String(localized: "Join it from RPCN in the XMB."), kind: .invite)
    }

    /// While a game runs in XMB mode, the requests waiting on its account, read beside its invites as Android's game loop reads them.
    func readRequests() async {
        let (status, json) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.rpcnFriends { continuation.resume(returning: ($0, $1)) }
        }
        guard status == 0, let json else { return }
        let rows = RpcnState.friendRows(json)
        // The same list as before and after, so only the requests are told and nobody is said to come online.
        friendsRead(rows, rows)
    }

    /// The friend list after a read: a friend who came online since the last one, and a request not told before.
    func friendsRead(_ old: [RpcnState.FriendRow], _ new: [RpcnState.FriendRow]) {
        // A closed session empties the list, which says nothing about who is waiting.
        guard !new.isEmpty else { return }
        let arrived = new.last { row in
            row.kind == .online && old.contains { $0.name == row.name && $0.kind == .offline }
        }
        if let arrived {
            post(String(localized: "\(arrived.name) is now online"), icon: Self.defaultAvatar, kind: .friendOnline)
        }
        // Each request is told once. A name is dropped once its request is answered, so one sent again is told again.
        let received = new.filter { $0.kind == .received }.map(\.name)
        let told = Set(UserDefaults.standard.stringArray(forKey: Self.toldRequestsKey) ?? [])
        let fresh = received.filter { !told.contains($0) }
        if let last = fresh.last {
            post(String(localized: "\(last) sent you a friend request"),
                 icon: .firmware(rco: XmbRco.friend, name: "item_tex_NewFriend", symbol: "person.badge.plus"), kind: .request)
        }
        let keep = told.intersection(received).union(fresh)
        if keep != told { UserDefaults.standard.set(keep.sorted(), forKey: Self.toldRequestsKey) }
    }
}

/// The stack in the top right, under the clock's bar (50 to 87), its right edge in from the screen's.
struct XmbNoticeStack: View {
    let firmware: XmbFirmware
    let m: XmbMetrics

    var body: some View {
        let shown = XmbNotices.shared.shown
        VStack(alignment: .trailing, spacing: 8 * m.u) {
            ForEach(shown) { notice in
                box(notice)
                    .transition(.asymmetric(insertion: .opacity, removal: .opacity.animation(.xmb(0.45))))
            }
        }
        // A box is as wide as its words, up to this.
        .frame(maxWidth: 560 * m.u, alignment: .trailing)
        .padding(.top, 100 * m.u)
        .padding(.trailing, 48 * m.u)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
        .animation(.xmb(0.25), value: shown.map(\.id))
        .allowsHitTesting(false)
        // Each notice is spoken as it is posted.
        .accessibilityHidden(true)
    }

    private func box(_ notice: XmbNotices.Notice) -> some View {
        let shape = RoundedRectangle(cornerRadius: 6 * m.u)
        return HStack(spacing: 14 * m.u) {
            Group {
                if let picture = notice.picture {
                    let frame = RoundedRectangle(cornerRadius: 4 * m.u)
                    Image(uiImage: picture)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 40 * m.u, height: 40 * m.u)
                        .clipShape(frame)
                        .overlay(frame.strokeBorder(.white.opacity(0.6), lineWidth: 1))
                } else {
                    XmbIconView(icon: notice.icon, firmware: firmware)
                }
            }
            .frame(width: 40 * m.u, height: 40 * m.u)
            VStack(alignment: .leading, spacing: 0) {
                Text(verbatim: notice.text)
                    .font(firmware.font(size: 21 * m.u))
                    .lineLimit(notice.kind == .note ? 2 : 1)
                if let detail = notice.detail {
                    Text(verbatim: detail)
                        .font(firmware.font(size: 15.225 * m.u))
                        .opacity(0.8)
                        .lineLimit(1)
                }
            }
            .foregroundStyle(.white)
            .xmbShadow()
        }
        .padding(.horizontal, 14 * m.u)
        .padding(.vertical, 10 * m.u)
        .background(LinearGradient(colors: [Color(red: 0.188, green: 0.188, blue: 0.212, opacity: 0.88),
                                            Color(red: 0.078, green: 0.078, blue: 0.094, opacity: 0.88)],
                                   startPoint: .top, endPoint: .bottom), in: shape)
        .overlay(shape.strokeBorder(.white.opacity(0.3), lineWidth: 1))
    }
}
