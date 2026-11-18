// SPDX-License-Identifier: GPL-2.0-only
// The RPCN store the screens share. The typed password is never kept: only the secret the server accepted reaches the Keychain.

import Foundation
import Observation
import UIKit

@MainActor @Observable final class RpcnModel {
    static let shared = RpcnModel()

    private(set) var config: RpcnState.Config?
    /// The pane shows its unreadable state.
    private(set) var configUnreadable = false
    private(set) var status: RpcnState.Status?
    private(set) var hasSecret = false
    var notice: String?
    private(set) var busy = false
    /// The password never lives here.
    var username = ""
    private(set) var secretHandedOver = false

    /// The core's `Net › PSN status` is RPCN.
    private(set) var onlinePlay = false
    /// The switch turns it on with PSN status when it is not.
    private(set) var internetConnected = false
    /// 0 none, 1 opening, 2 signed in, 3 failed.
    private(set) var session = 0
    /// Every open of the counted friends session is paired with exactly one close.
    private(set) var sessionHeld = false
    /// The session is held only while the pane shows, Online Play is on and a login is saved.
    private(set) var paneVisible = false

    /// False whenever the answer is unknown.
    private(set) var localNetworkDenied = false

    /// Empty while no signed-in session is held.
    private(set) var friends: [RpcnState.FriendRow] = []
    var friendsNotice: String?
    var friendPrompt: RpcnState.FriendRow?
    /// The next row, else the previous, else Add Friend; the pane hands it to the navigation as its preferred focus.
    private(set) var focusAfterRemoval: String?

    var serversNotice: String?
    /// Opening or leaving it empties the notice line, as `form` does.
    var addingServer = false {
        didSet { if addingServer != oldValue { notice = nil } }
    }

    var serverPrompt: RpcnState.Host?
    var confirmingServerReset = false
    var confirmingTrophyDelete = false
    private(set) var trophiesNotice: String?

    var confirmingSignOut = false
    /// So form B shows the ordinary sign-in caption, not the missing-secret one.
    private(set) var signedOut = false

    /// Opening or leaving one empties the notice line, so a screen's refusal never shows on the pane.
    var form: RpcnForm? {
        didSet { if form != oldValue { notice = nil } }
    }

    var accountName: String { config?.npid ?? "" }

    /// A reinstall or a new signature hides the Keychain item. Not an error: form B asks again.
    var missingSecret: Bool { !accountName.isEmpty && !hasSecret && !signedOut }

    var accountState: RpcnState.AccountState {
        RpcnState.accountState(onlinePlay: onlinePlay, configured: !accountName.isEmpty, hasSecret: hasSecret, session: session)
    }

    func refresh() async {
        await readConfig()
        hasSecret = RpcnKeychain.load(npid: accountName) != nil
        if username.isEmpty { username = accountName }
        await readSettings()
        await readStatus()
    }

    /// A failed read leaves both as they were.
    func readSettings() async {
        let (data, status) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.settings(forSerial: nil) { continuation.resume(returning: ($0, $1)) }
        }
        guard status == 0, let data, let dump = SettingsModel.decode(data) else { return }
        let global = Dictionary(dump.leaves.map { ($0.id, $0.global) }, uniquingKeysWith: { first, _ in first })
        onlinePlay = global[RpcnState.psnStatusPath] == "RPCN"
        internetConnected = global[RpcnState.internetPath] == "Connected"
    }

    /// Never confirmed. The writes stop at the first refusal, and the switch then shows what the core kept.
    func setOnlinePlay(_ on: Bool) async {
        var failed = 0
        for write in RpcnState.onlineSwitchWrites(on: on, internetConnected: internetConnected) {
            let status = await withCheckedContinuation { continuation in
                Rpcs3Bridge.setSetting(write.path, value: write.value, serial: nil) { status, _ in continuation.resume(returning: status) }
            }
            guard status == 0 else { failed = status; break }
            if write.path == RpcnState.internetPath { internetConnected = true }
        }
        if failed == 0 { onlinePlay = on }
        await syncSession()
    }

    /// Only a detected denial shows the row.
    func checkLocalNetwork() async {
        localNetworkDenied = await LocalNetwork.isDenied()
    }

    func setPaneVisible(_ visible: Bool) async {
        paneVisible = visible
        if !visible { focusAfterRemoval = nil }
        await syncSession()
    }

    func syncSession() async {
        let wanted = paneVisible && onlinePlay && hasSecret && !accountName.isEmpty
        if wanted && !sessionHeld {
            await openSession()
        } else if !wanted && sessionHeld {
            await closeSession()
        }
    }

    /// The held open is closed and a new one made, so the count stays paired.
    func retry() async {
        if sessionHeld { await closeSession() }
        await syncSession()
    }

    func openSession() async {
        guard !sessionHeld else { return }
        // Without a prepared core the client logs in with no password, which the server answers as a wrong one.
        if EmulatorSession.shared.browsing {
            session = 3
            notice = EmulatorSession.browsingRefusal
            return
        }
        sessionHeld = true
        session = 1
        notice = nil
        let (_, state, error, raw) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.rpcnFriendsSession(true) { continuation.resume(returning: ($0, $1, $2, $3)) }
        }
        // A close that ran while this open waited has already let the session go.
        guard sessionHeld else { return }
        if state == 0 && error == 0 {
            session = 2
            await readFriends()
        } else {
            session = 3
            notice = RpcnState.signInSentence(state: state, error: error, raw: raw)
        }
    }

    func closeSession() async {
        guard sessionHeld else { return }
        sessionHeld = false
        session = 0
        friends = []
        _ = await withCheckedContinuation { continuation in
            Rpcs3Bridge.rpcnFriendsSession(false) { status, _, _, _ in continuation.resume(returning: status) }
        }
    }

    /// A status with no client keeps the open's own outcome, since a failed open leaves no client behind.
    func poll() async {
        await readStatus()
        if sessionHeld, let reported = status?.session, reported != 0 { session = reported }
        await readFriends()
    }

    /// A peek-only read; a read that fails keeps the rows shown.
    func readFriends() async {
        guard sessionHeld, session == 2 else { friends = []; return }
        let (status, json) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.rpcnFriends { continuation.resume(returning: ($0, $1)) }
        }
        // A close that ran while this read waited has already emptied the rows.
        guard sessionHeld, status == 0, let json else { return }
        friends = RpcnState.friendRows(json)
    }

    /// An empty field sends nothing.
    @discardableResult
    func addFriend(_ typed: String) async -> Bool {
        let name = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { notice = String(localized: "Enter a username."); return false }
        busy = true
        notice = nil
        let (status, state, error, raw) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.rpcnAddFriend(name) { continuation.resume(returning: ($0, $1, $2, $3)) }
        }
        busy = false
        if let refusal = RpcnState.friendOutcome(status: status, state: state, error: error, raw: raw, name: name) {
            notice = refusal
            return false
        }
        friendsNotice = String(localized: "Request sent to \(name).")
        form = nil
        await readFriends()
        return true
    }

    var servers: [RpcnState.Host] { config?.hosts ?? [] }

    var currentServerName: String {
        let host = config?.host ?? ""
        return servers.first { ServersPane.isChecked($0, host: host) }?.desc ?? host
    }

    /// Saved at once and never confirmed; a held session is opened again against the new server.
    func selectServer(_ server: RpcnState.Host) async {
        let status = await withCheckedContinuation { continuation in
            Rpcs3Bridge.rpcnSelectHost(server.host) { continuation.resume(returning: $0) }
        }
        serversNotice = status == 0 ? nil : Self.serverSentence(status, adding: false)
        await readConfig()
        if status == 0 && sessionHeld { await retry() }
    }

    /// The new server is not checked.
    @discardableResult
    func addServer(name typedName: String, address typedAddress: String) async -> Bool {
        let name = typedName.trimmingCharacters(in: .whitespacesAndNewlines)
        let address = typedAddress.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, !address.isEmpty else { notice = String(localized: "Enter a name and an address."); return false }
        busy = true
        notice = nil
        let status = await withCheckedContinuation { continuation in
            Rpcs3Bridge.rpcnAddHost(name, host: address) { continuation.resume(returning: $0) }
        }
        busy = false
        await readConfig()
        guard status == 0 else { notice = Self.serverSentence(status, adding: true); return false }
        serversNotice = String(localized: "Server added.")
        addingServer = false
        return true
    }

    /// The official server never gets here: the bridge refuses it.
    func removeServer(_ server: RpcnState.Host) async {
        let wasChosen = ServersPane.isChecked(server, host: config?.host)
        let status = await withCheckedContinuation { continuation in
            Rpcs3Bridge.rpcnDelHost(server.desc, host: server.host) { continuation.resume(returning: $0) }
        }
        await readConfig()
        serversNotice = status == 0 ? String(localized: "Server removed.") : Self.serverSentence(status, adding: false)
        // Removing the chosen server chooses the official one through the same call a tap makes, so a row stays checked.
        guard status == 0, wasChosen, let official = servers.first(where: ServersPane.isOfficial) else { return }
        await selectServer(official)
        if serversNotice == nil { serversNotice = String(localized: "Server removed. ARMSX3 now uses the official server.") }
    }

    func resetServers() async {
        let status = await withCheckedContinuation { continuation in
            Rpcs3Bridge.rpcnResetHosts { continuation.resume(returning: $0) }
        }
        await readConfig()
        serversNotice = status == 0 ? String(localized: "The server list is back to the official server.")
            : String(localized: "Couldn't reset the server list (code \(status)). Try again.")
    }

    /// The switch then shows what the config holds.
    func setIpv6(_ on: Bool) async {
        _ = await withCheckedContinuation { continuation in
            Rpcs3Bridge.rpcnSetIpv6(on) { continuation.resume(returning: $0) }
        }
        await readConfig()
    }

    func deleteTrophies() async {
        let (status, state, error, raw) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.rpcnDeleteTrophies { continuation.resume(returning: ($0, $1, $2, $3)) }
        }
        let ok = status == 0 && state == 0 && error == 0
        trophiesNotice = ok ? String(localized: "Deleted your trophies on RPCN.")
            : String(localized: "Couldn't delete your trophies on RPCN (code \(status != 0 ? status : raw)). Try again.")
    }

    /// -9 is a duplicate on add and a missing server elsewhere; -7 is a name or address the bridge refuses.
    static func serverSentence(_ status: Int, adding: Bool) -> String {
        if status == -9 {
            return adding ? String(localized: "That server is already in the list.") : String(localized: "That server isn't in the list any more.")
        }
        return String(localized: "That name or address can't be used. Check it and try again.")
    }

    private(set) var avatars: [RpcnState.AvatarItem] = []
    private(set) var avatarUnusable = 0
    /// True until the list says otherwise, so the caption never flashes.
    private(set) var avatarFirmware = true
    private(set) var avatarsLoaded = false
    /// Nil until read; `kind` is `default` when none is chosen.
    private(set) var currentAvatar: RpcnState.AvatarItem?
    var avatarNotice: String?
    /// Bounded and filled on demand, so a library of thousands of avatars is never decoded at once.
    private let avatarImages: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.totalCostLimit = 32 * 1024 * 1024
        return cache
    }()

    /// A failed read keeps what was shown.
    func loadAvatars() async {
        let (status, json) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.avatarList { continuation.resume(returning: ($0, $1)) }
        }
        if status == 0, let json, let list = try? JSONDecoder().decode(RpcnState.AvatarList.self, from: json) {
            avatars = RpcnState.avatarTiles(list.avatars)
            avatarUnusable = list.unusable
            avatarFirmware = list.firmware
        }
        avatarsLoaded = true
        await readCurrentAvatar()
    }

    func readCurrentAvatar() async {
        let (status, json) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.avatarCurrent { continuation.resume(returning: ($0, $1)) }
        }
        if status == 0, let json, let current = try? JSONDecoder().decode(RpcnState.AvatarItem.self, from: json) {
            currentAvatar = current
        }
    }

    /// Never confirmed. A refusal says so and the stroke stays.
    func select(_ item: RpcnState.AvatarItem) async {
        let status = await withCheckedContinuation { continuation in
            Rpcs3Bridge.avatarSelect(item.path) { continuation.resume(returning: $0) }
        }
        avatarNotice = status == 0 ? nil : String(localized: "That avatar couldn't be used. Pick another one.")
        await readCurrentAvatar()
    }

    func avatarImage(_ path: String) async -> UIImage? {
        if let cached = avatarImages.object(forKey: path as NSString) { return cached }
        let (status, png) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.avatarImage(path) { continuation.resume(returning: ($0, $1)) }
        }
        guard status == 0, let png, let image = UIImage(data: png) else { return nil }
        let pixels = image.size.width * image.scale * image.size.height * image.scale
        avatarImages.setObject(image, forKey: path as NSString, cost: Int(pixels) * 4)
        return image
    }

    /// `png` is already cut square and scaled; the bridge checks it again before anything is written.
    func usePicture(_ png: Data?) async {
        let unreadable = String(localized: "That picture couldn't be read. Pick another one.")
        guard let png else { avatarNotice = unreadable; return }
        let status = await withCheckedContinuation { continuation in
            Rpcs3Bridge.avatarUseImage(png) { continuation.resume(returning: $0) }
        }
        avatarNotice = status == 0 ? nil
            : status == -7 ? unreadable : String(localized: "That avatar couldn't be used. Pick another one.")
        await loadAvatars()
    }

    /// -9 is no firmware, so no default.
    func useDefault() async {
        let status = await withCheckedContinuation { continuation in
            Rpcs3Bridge.avatarUseDefault { continuation.resume(returning: $0) }
        }
        avatarNotice = status == 0 ? nil
            : status == -9 ? String(localized: "The default avatar comes with PS3 firmware, which isn't installed yet.")
            : String(localized: "That avatar couldn't be used. Pick another one.")
        await readCurrentAvatar()
    }

    /// A favourite is in ALL AVATARS too.
    var favoriteAvatars: [RpcnState.AvatarItem] {
        let names = Set(SettingsStore.shared.rpcnAvatarFavorites)
        return avatars.filter { names.contains($0.file) }
    }

    func isFavoriteAvatar(_ item: RpcnState.AvatarItem) -> Bool {
        SettingsStore.shared.rpcnAvatarFavorites.contains(item.file)
    }

    /// Kept by file name in SettingsStore, as on Android.
    func toggleFavorite(_ item: RpcnState.AvatarItem) {
        var names = SettingsStore.shared.rpcnAvatarFavorites
        if let at = names.firstIndex(of: item.file) { names.remove(at: at) } else { names.append(item.file) }
        SettingsStore.shared.rpcnAvatarFavorites = names
    }

    /// False on any other target.
    func toggleFavorite(target: String) -> Bool {
        for prefix in [AvatarPane.tilePrefix, AvatarPane.favoritePrefix] where target.hasPrefix(prefix) {
            let file = String(target.dropFirst(prefix.count))
            guard let item = avatars.first(where: { $0.file == file }) else { return false }
            toggleFavorite(item)
            return true
        }
        return false
    }

    func readConfig() async {
        let (status, json) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.rpcnConfig { continuation.resume(returning: ($0, $1)) }
        }
        let decoded = status == 0 ? json.flatMap { try? JSONDecoder().decode(RpcnState.Config.self, from: $0) } : nil
        if let decoded { config = decoded }
        configUnreadable = decoded == nil
    }

    func readStatus() async {
        let (status, json) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.rpcnStatus { continuation.resume(returning: ($0, $1)) }
        }
        if status == 0, let json, let decoded = try? JSONDecoder().decode(RpcnState.Status.self, from: json) {
            self.status = decoded
        }
    }

    /// Missing input is answered before any network call.
    func signIn(username typed: String, password: String) async {
        await signIn(username: typed, password: password, token: nil, screen: .signIn)
    }

    /// `token` nil keeps the Keychain's; Activate Account passes the emailed code.
    @discardableResult
    func signIn(username typed: String, password: String, token typedToken: String?,
                screen: RpcnState.AccountScreen) async -> Bool {
        let name = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        if let missing = RpcnState.missingInput(username: name, password: password) { notice = missing; return false }
        if EmulatorSession.shared.browsing { notice = EmulatorSession.browsingRefusal; return false }
        let previous = accountName
        let token = typedToken ?? RpcnKeychain.load(npid: name)?.token ?? ""
        busy = true
        notice = nil
        // The first sign-in on this device also turns Online Play on.
        let first = previous.isEmpty
        let (status, state, error, raw, secret) = await withCheckedContinuation { continuation in
            Rpcs3Bridge.rpcnSign(in: name, password: password, token: token) {
                continuation.resume(returning: ($0, $1, $2, $3, $4))
            }
        }
        busy = false
        guard status == 0, state == 0, error == 0, let secret, !secret.isEmpty else {
            // -7: the bridge refused the username's shape before the network.
            notice = RpcnState.accountOutcome(status: status, state: state, error: error, raw: raw, screen: screen)
                ?? RpcnState.accountSentence(error: 99, raw: raw, screen: screen)
            return false
        }
        // A refused write leaves this launch signed in; the next launch asks again.
        RpcnKeychain.save(npid: name, secret: secret, token: token)
        if !previous.isEmpty && previous != name { RpcnKeychain.delete(npid: previous) }
        hasSecret = true
        signedOut = false
        username = name
        await readConfig()
        await readStatus()
        if first && !onlinePlay { await setOnlinePlay(true) }
        // iOS is asked only after the first sign-in, from this screen, so its prompt never comes during a game.
        if first {
            notice = (onlinePlay ? String(localized: "Signed in. Online play is on.") : String(localized: "Signed in.")) + "\n" + LocalNetwork.sentence
            LocalNetwork.trigger()
        } else {
            notice = String(localized: "Signed in.")
        }
        await syncSession()
        return true
    }

    enum FriendAction {
        case accept, decline, withdraw, remove
    }

    /// On success the row goes at once and focus moves to the next row, else the previous, else Add Friend.
    func answer(_ row: RpcnState.FriendRow, _ action: FriendAction) async {
        let name = row.name
        let (status, state, error, raw) = await withCheckedContinuation { continuation in
            let resume = { (s: Int, t: Int, e: Int, r: Int) in continuation.resume(returning: (s, t, e, r)) }
            if action == .accept {
                Rpcs3Bridge.rpcnAddFriend(name, completion: resume)
            } else {
                Rpcs3Bridge.rpcnRemoveFriend(name, completion: resume)
            }
        }
        if let refusal = RpcnState.friendOutcome(status: status, state: state, error: error, raw: raw, name: name) {
            friendsNotice = refusal
            return
        }
        // By name: a poll may have changed the row's presence word while the prompt was up.
        if let index = friends.firstIndex(where: { $0.name == name }) {
            let next = friends.indices.contains(index + 1) ? friends[index + 1] : index > 0 ? friends[index - 1] : nil
            focusAfterRemoval = next.map { RpcnFriendsCard.target($0.name) } ?? "settings.rpcn.friends.add"
            friends.remove(at: index)
        }
        switch action {
        case .accept: friendsNotice = String(localized: "You and \(name) are friends now.")
        case .decline: friendsNotice = String(localized: "Declined \(name)'s request.")
        case .withdraw: friendsNotice = String(localized: "Withdrew your request to \(name).")
        case .remove: friendsNotice = String(localized: "Removed \(name).")
        }
    }

    /// Nothing is saved; the screen keeps the details for Activate Account.
    func createAccount(username: String, password: String, email: String) async -> Bool {
        if let missing = RpcnState.missingInput(username: username, password: password, email: email) {
            notice = missing
            return false
        }
        let name = username.trimmingCharacters(in: .whitespacesAndNewlines)
        let mail = email.trimmingCharacters(in: .whitespacesAndNewlines)
        return await account(.create) { Rpcs3Bridge.rpcnCreateAccount(name, password: password, email: mail, completion: $0) }
    }

    @discardableResult
    func activate(username: String, password: String, code: String) async -> Bool {
        if let missing = RpcnState.missingInput(username: username, password: password, code: code) {
            notice = missing
            return false
        }
        let token = code.trimmingCharacters(in: .whitespacesAndNewlines)
        guard await signIn(username: username, password: password, token: token,
                           screen: .activate) else { return false }
        popToPane()
        return true
    }

    /// The server checks the password, so it needs both details.
    func resend(username: String, password: String) async {
        if let missing = RpcnState.missingInput(username: username, password: password) {
            notice = missing
            return
        }
        let name = username.trimmingCharacters(in: .whitespacesAndNewlines)
        if await account(.activate, { Rpcs3Bridge.rpcnResendToken(name, password: password, completion: $0) }) {
            notice = String(localized: "RPCN sent a new code to your email.")
        }
    }

    func askReset(username: String, email: String) async -> Bool {
        if let missing = RpcnState.missingInput(username: username, email: email) {
            notice = missing
            return false
        }
        let name = username.trimmingCharacters(in: .whitespacesAndNewlines)
        let mail = email.trimmingCharacters(in: .whitespacesAndNewlines)
        return await account(.reset) { Rpcs3Bridge.rpcnSendResetToken(name, email: mail, completion: $0) }
    }

    /// The password reaches the Keychain only through that sign-in's accepted secret.
    @discardableResult
    func resetPassword(username: String, code: String, password: String) async -> Bool {
        if let missing = RpcnState.missingInput(username: username, code: code, newPassword: password) {
            notice = missing
            return false
        }
        let name = username.trimmingCharacters(in: .whitespacesAndNewlines)
        let entered = code.trimmingCharacters(in: .whitespacesAndNewlines)
        guard await account(.newPassword, {
            Rpcs3Bridge.rpcnResetPassword(name, code: entered, password: password, completion: $0)
        }) else { return false }
        guard await signIn(username: name, password: password, token: nil,
                           screen: .newPassword) else { return false }
        popToPane()
        return true
    }

    private func account(_ screen: RpcnState.AccountScreen,
                         _ call: (@escaping (Int, Int, Int, Int) -> Void) -> Void) async -> Bool {
        busy = true
        notice = nil
        let (status, state, error, raw) = await withCheckedContinuation { continuation in
            call { continuation.resume(returning: ($0, $1, $2, $3)) }
        }
        busy = false
        let refusal = RpcnState.accountOutcome(status: status, state: state, error: error, raw: raw, screen: screen)
        notice = refusal
        return refusal == nil
    }

    private func popToPane() {
        let sentence = notice
        form = nil
        notice = sentence
    }

    /// The username stays in rpcn.yml and in the Username field, so form B shows with it filled.
    func signOut() async {
        let name = accountName
        await closeSession()
        _ = await withCheckedContinuation { continuation in
            Rpcs3Bridge.rpcnSignOut { continuation.resume(returning: $0) }
        }
        RpcnKeychain.delete(npid: name)
        _ = await withCheckedContinuation { continuation in
            Rpcs3Bridge.rpcnSetSecret("", token: "") { continuation.resume(returning: $0) }
        }
        hasSecret = false
        secretHandedOver = false
        signedOut = true
        session = 0
        notice = nil
        if username.isEmpty { username = name }
    }

    /// Nothing when no account is saved.
    func restoreSecret() async {
        await readConfig()
        guard let login = RpcnKeychain.load(npid: accountName) else { return }
        let status = await withCheckedContinuation { continuation in
            Rpcs3Bridge.rpcnSetSecret(login.secret, token: login.token) { continuation.resume(returning: $0) }
        }
        hasSecret = true
        secretHandedOver = status == 0
    }
}

enum RpcnForm: Hashable {
    case create, activate, reset, addFriend, servers, avatar
}
