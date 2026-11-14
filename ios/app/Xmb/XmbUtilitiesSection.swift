// SPDX-License-Identifier: GPL-2.0-only
// The XMB's Utilities column and its PKG/Data Manager, ported from XmbHome.kt, XmbLibraryTools.kt, PackageInstallerScreen.kt, GameUpdatesTab.kt and DlcTab.kt.

import SwiftUI

extension XmbHost {
    static let toolsPackageIcon = XmbIcon.firmware(rco: XmbRco.game, name: "item_tex_disc_install", symbol: "shippingbox")
    private static let toolsFolderIcon = XmbIcon.firmware(rco: XmbRco.game, name: "item_tex_plain_folder", symbol: "folder")
    private static let toolsLicenceIcon = XmbIcon.firmware(rco: XmbRco.game, name: "tex_lock_icon", symbol: "key")
    private static let toolsThemeIcon = XmbIcon.firmware(rco: XmbRco.sysconf, name: "tex_thema", symbol: "paintpalette")
    private static let toolsDriveIcon = XmbIcon.firmware(rco: XmbRco.photo, name: "item_tex_usb_connection", symbol: "externaldrive")
    private static let toolsUpdateIcon = XmbIcon.firmware(rco: XmbRco.sysconf, name: "tex_update", symbol: "arrow.triangle.2.circlepath")
    static let toolsUtilIcon = XmbIcon.firmware(rco: XmbRco.game, name: "item_tex_ps3util", symbol: "externaldrive")

    /// Android's Utilities column: the PKG/Data Manager, Trophy Collection, Load / Manage Saves, then the patches where
    /// Android has Mods. Launch Game and Boot XMB are left out: the iOS app can neither boot a loose file nor the console's VSH.
    func toolsSection() -> XmbSection {
        var section = XmbSection(column: XmbColumn(
            id: "tools", title: String(localized: "Utilities"),
            icon: .firmware(rco: XmbRco.game, name: "item_tex_ps3util", symbol: "wrench.and.screwdriver"), items: []))
        section.column.items = [toolsPackages(&section), toolsTrophies(), toolsSaves(&section), toolsPatches(&section)]
        return section
    }

    /// A question whose first button does `then`; the second changes nothing and has the focus, as Android's focusNo.
    func toolsConfirm(_ question: String, _ confirm: String, then: @escaping @MainActor () -> Void) {
        ask(question, buttons: [confirm, String(localized: "Cancel")], focus: 1) { button in
            if button == 0 { then() }
        }
    }

    /// What a closed folder holds, so it still opens; its rows are built once it is open.
    func toolsClosed(_ id: String, _ title: String = "") -> XmbNode {
        XmbNode(id: id + ".closed", title: title, subtitle: nil, icon: .none, kind: .info)
    }

    // MARK: - PKG/Data Manager

    private func toolsPackages(_ section: inout XmbSection) -> XmbNode {
        let store = XmbToolsStore.shared
        if store.justOpened("tools.install", isOpen("tools.install")) { Task { await store.loadLicences() } }
        var items: [XmbNode] = []
        // While something installs: what, and how far along.
        if installs.busy {
            let row = installs.rows.first { $0.progress != nil }
            let installing = String(localized: "Installing. Large packages can take a few minutes.")
            items.append(XmbNode(id: "tools.install.progress", title: row?.name ?? installing,
                                 subtitle: row?.progress.map { "\(Int(($0 * 100).rounded()))%" } ?? installing, icon: Self.toolsPackageIcon, kind: .info))
        }
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        // iOS lets an app walk only its own folder; anywhere else is reached through the Files picker.
        let device = toolsFileFolder(&section, id: "tools.install.device",
                                     title: String(localized: "On My \(UIDevice.current.localizedModel)"), dir: documents, icon: Self.toolsDriveIcon)
        let picker = section.action(XmbNode(id: "tools.install.external", title: String(localized: "Choose from Files"), subtitle: nil,
                                            icon: Self.toolsDriveIcon, kind: .action)) { [weak self] in self?.pick(.tools(.packages)) }
        items.append(XmbNode(id: "tools.install.files", title: String(localized: "Install Package Files"),
                             subtitle: String(localized: "Packages and licences on this device's storage"),
                             icon: Self.toolsPackageIcon, kind: .folder([device, picker])))
        if let installed = toolsInstalled(&section) { items.append(installed) }
        if let licences = toolsLicences(&section) { items.append(licences) }
        items.append(toolsUpdates(&section))
        items.append(toolsAddOns(&section))
        return XmbNode(id: "tools.install", title: String(localized: "PKG/Data Manager"),
                       subtitle: String(localized: "Install packages and licences, game updates and DLC"),
                       icon: Self.toolsPackageIcon, kind: .folder(items))
    }

    /// A folder as the console lists a USB drive: an item for all of a split package's parts, the packages and licences
    /// with their sizes, then the folders, each in natural order. With `pick`, Cross hands it the file in place of installing it.
    func toolsFileFolder(_ section: inout XmbSection, id: String, title: String, dir: URL, icon: XmbIcon,
                         extensions: Set<String> = ["pkg", "rap", "edat", "pup"], pick: (@MainActor (URL) -> Void)? = nil) -> XmbNode {
        guard isOpen(id) else { return XmbNode(id: id, title: title, subtitle: nil, icon: icon, kind: .folder([toolsClosed(id)])) }
        let entries = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.isDirectoryKey, .fileSizeKey],
                                                                     options: .skipsHiddenFiles)) ?? []
        let natural: (URL, URL) -> Bool = { $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending }
        let isFolder: (URL) -> Bool = { (try? $0.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true }
        // The installer tells a firmware update by its bytes, so a PS3UPDAT.PUP is offered here too.
        let files = entries.filter { !isFolder($0) && extensions.contains($0.pathExtension.lowercased()) }.sorted(by: natural)
        let fileIcon: (URL) -> XmbIcon = { file in
            return switch file.pathExtension.lowercased() {
            // A picked picture shows itself; a picked theme shows the theme icon.
            case _ where pick != nil && file.pathExtension.lowercased() != "p3t": .game(path: file.path)
            case "p3t": Self.toolsThemeIcon
            case "pkg": Self.toolsPackageIcon
            case "pup": Self.toolsUpdateIcon
            default: Self.toolsLicenceIcon
            }
        }
        var rows: [XmbNode] = []
        let parts = files.filter { $0.pathExtension.lowercased() == "pkg" }
        if parts.count > 1, pick == nil {
            let all = String(localized: "All \(parts.count) Packages Here")
            rows.append(section.action(XmbNode(id: id + "/*", title: all, subtitle: nil, icon: Self.toolsPackageIcon, kind: .action)) { [weak self] in
                self?.toolsConfirm(String(localized: "Install \(all)?"), String(localized: "Install")) { self?.installPackages(parts) }
            })
        }
        for file in files {
            let size = (try? file.resourceValues(forKeys: [.fileSizeKey]))?.fileSize.map { XmbFileSize.text(Int64($0)) }
            rows.append(section.action(XmbNode(id: id + "/" + file.lastPathComponent, title: file.lastPathComponent, subtitle: size,
                                               icon: fileIcon(file), kind: .action)) { [weak self] in
                if let pick { return pick(file) }
                self?.toolsConfirm(String(localized: "Install \(file.lastPathComponent)?"), String(localized: "Install")) {
                    self?.installPackages([file])
                }
            })
        }
        for folder in entries.filter(isFolder).sorted(by: natural) {
            rows.append(toolsFileFolder(&section, id: id + "/" + folder.lastPathComponent, title: folder.lastPathComponent,
                                        dir: folder, icon: Self.toolsFolderIcon, extensions: extensions, pick: pick))
        }
        if rows.isEmpty { rows = [XmbNode(id: id + ".empty", title: String(localized: "Empty"), subtitle: nil, icon: icon, kind: .info)] }
        return XmbNode(id: id, title: title, subtitle: nil, icon: icon, kind: .folder(rows))
    }

    /// Installs what was picked, then says how each file went, offering to delete the packages as the Install screen does.
    func installPackages(_ urls: [URL]) {
        guard !installs.busy, !InstallModel.installInFlight else {
            return notice(String(localized: "Another install is running. Wait for it to finish."), error: true)
        }
        Task {
            let installedAny = await installs.install(urls: urls)
            if installedAny { await installs.refreshAfterContentChange { await self.library.refresh() } }
            // A package can bring avatars, which the RPCN column lists.
            if installedAny {
                await rpcn.loadAvatars()
                // A firmware or a licence may have gone in while System Update or PKG/Data Manager stays open.
                XmbConsoleSettingsStore.shared.firmwareVersion = nil
                await XmbToolsStore.shared.loadLicences()
            }
            var lines = installs.rows.compactMap { row in row.result.map { "\(row.name): \($0)" } }
            if let message = installs.message { lines.append(message) }
            let report = lines.joined(separator: "\n")
            guard let prompt = installs.deletePrompt else {
                if !report.isEmpty { notice(report, error: false) }
                return
            }
            installs.deletePrompt = nil
            ask([report, prompt.title].filter { !$0.isEmpty }.joined(separator: "\n"),
                buttons: [String(localized: "Delete"), String(localized: "Keep")], focus: 1) { button in
                guard button == 0 else { return }
                self.installs.deleteSources(prompt)
                if let message = self.installs.message { self.notice(message, error: false) }
            }
        }
    }

    private func toolsInstalled(_ section: inout XmbSection) -> XmbNode? {
        guard !installs.installed.isEmpty else { return nil }
        let rows = installs.installed.map { title -> XmbNode in
            let id = "tools.installed." + title.serial
            section.options[id] = { [weak self] _ in self?.toolsUninstall(title) }
            let node = XmbNode(id: id, title: title.title, subtitle: [title.serial, title.detail].filter { !$0.isEmpty }.joined(separator: "   "),
                               icon: .game(path: title.iconURL?.path), kind: .action,
                               options: [.init(id: "uninstall", title: String(localized: "Uninstall"))])
            return section.action(node) { [weak self] in self?.toolsUninstall(title) }
        }
        return XmbNode(id: "tools.installed", title: String(localized: "Installed titles"), subtitle: nil, icon: Self.toolsFolderIcon,
                       kind: .folder(rows))
    }

    private func toolsUninstall(_ title: InstalledTitle) {
        let name = title.title == title.serial ? title.serial : "\(title.title) (\(title.serial))"
        var lines = [String(localized: "This deletes \(name) and everything installed with it. Save data stored separately is not touched. This cannot be undone.")]
        if title.category == "GD" {
            if let version = title.appVersion, !version.isEmpty {
                let shown = InstalledTitle.shortVersion(version)
                lines.append(String(localized: "This also removes the game's updates (version \(shown)). They would have to be downloaded again."))
            } else {
                lines.append(String(localized: "This also removes the game's updates. They would have to be downloaded again."))
            }
        }
        toolsConfirm(lines.joined(separator: "\n"), String(localized: "Uninstall")) { [weak self] in
            guard let self else { return }
            notice(String(localized: "Uninstalling \(title.title). A large game can take a few minutes."), error: false)
            Task {
                let result = await UninstallSheet.uninstall(serial: title.serial, alsoSaves: false)
                if result.status == 0 {
                    // Only once the game is gone: a cache dropped for an installed game costs a recompile.
                    _ = await Rpcs3Bridge.clearGameCache(withSerial: title.serial)
                    await self.installs.refreshAfterContentChange { await self.library.refresh() }
                    await XmbToolsStore.shared.loadLicences()
                }
                self.notice(result.status == 0 ? String(localized: "Uninstalled.") : UninstallSheet.describe(result.status), error: result.status != 0)
            }
        }
    }

    private func toolsLicences(_ section: inout XmbSection) -> XmbNode? {
        let files = XmbToolsStore.shared.licences
        guard !files.isEmpty else { return nil }
        let id = "tools.licences"
        guard isOpen(id) else {
            return XmbNode(id: id, title: String(localized: "Installed licences"), subtitle: nil, icon: Self.toolsLicenceIcon, kind: .folder([toolsClosed(id)]))
        }
        func label(_ titleID: String?) -> String {
            guard let titleID else { return String(localized: "Unattributed") }
            return installs.installed.first { $0.serial == titleID && $0.title != titleID }?.title ?? titleID
        }
        let groups = Dictionary(grouping: files, by: XmbToolsStore.licenceTitleID).sorted {
            ($0.key == nil ? 1 : 0, label($0.key)) < ($1.key == nil ? 1 : 0, label($1.key))
        }
        let folders = groups.map { entry -> XmbNode in
            let (titleID, group) = (entry.key, entry.value)
            let rows = group.map { file -> XmbNode in
                let row = "tools.licence." + file.lastPathComponent
                section.options[row] = { [weak self] _ in self?.toolsRemoveLicence(file, titleID: titleID) }
                return section.action(XmbNode(id: row, title: file.deletingPathExtension().lastPathComponent,
                                              subtitle: file.pathExtension.uppercased(), icon: Self.toolsLicenceIcon, kind: .action,
                                              options: [.init(id: "remove", title: String(localized: "Remove"))])) { [weak self] in
                    self?.toolsRemoveLicence(file, titleID: titleID)
                }
            }
            return XmbNode(id: "tools.licences." + (titleID ?? "none"), title: label(titleID),
                           subtitle: String(localized: "\(group.count) licences"), icon: Self.toolsLicenceIcon, kind: .folder(rows))
        }
        return XmbNode(id: id, title: String(localized: "Installed licences"), subtitle: nil, icon: Self.toolsLicenceIcon, kind: .folder(folders))
    }

    private func toolsRemoveLicence(_ file: URL, titleID: String?) {
        let question = String(localized: "\(file.lastPathComponent) will be deleted. Content it unlocks will stop working until the licence is installed again.")
        toolsConfirm(question, String(localized: "Remove")) { [weak self] in
            guard let self else { return }
            let removed = (try? FileManager.default.removeItem(at: file)) != nil
            notice(removed ? String(localized: "Licence removed.") : String(localized: "Could not remove the licence file."), error: !removed)
            Task {
                await XmbToolsStore.shared.loadLicences()
                if removed, let titleID { await self.library.reprobe(serial: titleID) }
            }
        }
    }

    // MARK: - Game updates

    /// Each library game with what it has and what is published. Cross brings it up to date; Triangle offers any one
    /// version, the update off, or the download stopped. Looked for only once opened: it asks Sony about every game.
    private func toolsUpdates(_ section: inout XmbSection) -> XmbNode {
        let id = "tools.install.updates"
        let title = String(localized: "Game updates")
        let subtitle = String(localized: "Updates published for the games in your library")
        if XmbToolsStore.shared.justOpened(id, isOpen(id)) {
            Task { [updates] in
                updates.failureSeen()
                await updates.check(fresh: false)
            }
        }
        guard isOpen(id) else {
            return XmbNode(id: id, title: title, subtitle: subtitle, icon: Self.toolsUpdateIcon, kind: .folder([toolsClosed(id, String(localized: "Checking…"))]))
        }
        var rows: [XmbNode] = []
        if let pill = updates.pillText {
            rows.append(XmbNode(id: "tools.updates.progress", title: pill, subtitle: nil, icon: Self.toolsUpdateIcon, kind: .info))
        }
        let checking = updates.checking ? String(localized: "Checking \(min(updates.checked + 1, updates.total)) of \(updates.total)…") : nil
        rows.append(section.action(XmbNode(id: "tools.updates.refresh", title: String(localized: "Refresh"), subtitle: checking,
                                           icon: Self.toolsUpdateIcon, kind: .action)) { [weak self] in
            guard let self, !updates.checking, updates.network.online else { return }
            Task { await self.updates.check(fresh: true) }
        })
        let icons = Dictionary(library.entries.map { ($0.serial, $0.iconPath) }, uniquingKeysWith: { a, _ in a })
        let games = updates.listed().sorted { a, b in
            let availableA = toolsIsAvailable(a.serial), availableB = toolsIsAvailable(b.serial)
            return availableA != availableB ? availableA : a.name.lowercased() < b.name.lowercased()
        }
        for game in games { rows.append(toolsUpdateRow(&section, serial: game.serial, name: game.name, icon: icons[game.serial] ?? nil)) }
        rows.append(section.action(XmbNode(id: "tools.updates.manual", title: String(localized: "Check another title"),
                                           subtitle: String(localized: "Title ID"), icon: Self.toolsUpdateIcon, kind: .action)) { [weak self] in
            self?.type(String(localized: "Title ID")) { typed in
                guard let self, let titleID = GameUpdates.titleID(typed.trimmingCharacters(in: .whitespaces).uppercased()) else { return }
                Task {
                    await self.updates.checkOne(titleID)
                    self.presented = .library(.updateVersions(serial: titleID, title: titleID))
                }
            }
        })
        return XmbNode(id: id, title: title, subtitle: subtitle, icon: Self.toolsUpdateIcon, kind: .folder(rows))
    }

    private func toolsIsAvailable(_ serial: String) -> Bool {
        if case .available = updates.rowState(serial) { return true }
        return false
    }

    private func toolsUpdateRow(_ section: inout XmbSection, serial: String, name: String, icon: String?) -> XmbNode {
        let state = updates.rowState(serial)
        let installed = updates.installed[serial]
        let job = updates.jobs[serial]
        let free = (job?.isFinished ?? true) && !installs.busy
        let id = "tools.updates.row." + serial
        var published: [TitlePackage] = []
        if free, case .found(let patch) = updates.lookups[serial] {
            let have = installed?.installedVersion
            published = patch.packages.reversed().filter { package in
                guard let have else { return true }
                let order = GameUpdates.compare(package.version, have)
                // A PSN game lists nothing older than what it has.
                return order != 0 && !(installed?.isPSN == true && order < 0)
            }
        }
        let versions = published
        var options: [XmbNode.Option] = versions.map { .init(id: "version." + $0.version, title: String(localized: "Version \(InstalledTitle.shortVersion($0.version))  \(XmbFileSize.text($0.size))")) }
        if free, let installed, InstalledContent.canRemoveUpdate(installed, serial: serial) {
            options.append(.init(id: "remove", title: String(localized: "Remove update")))
        }
        if let job, !job.isFinished, !job.isStopped, job.installing == nil {
            options.append(.init(id: "cancel", title: String(localized: "Cancel")))
        }
        section.options[id] = { [weak self] option in
            guard let self else { return }
            switch option {
            case "remove":
                toolsConfirm(String(localized: "Remove this game's update and go back to the disc version?"), String(localized: "Remove update")) {
                    Task {
                        let status = await self.updates.removeUpdate(serial)
                        if status != 0 { self.notice(UninstallSheet.describe(status), error: true) }
                    }
                }
            case "cancel":
                updates.cancel(serial)
            default:
                guard let package = versions.first(where: { "version." + $0.version == option }) else { return }
                toolsAskUpdate(serial: serial, name: name, target: package.version, installed: installed?.installedVersion,
                               word: String(localized: "Install"))
            }
        }
        let node = XmbNode(id: id, title: name, subtitle: serial + "   " + UpdateText.line(state), icon: icon.map { .game(path: $0) } ?? Self.toolsUpdateIcon,
                           kind: .action, options: options)
        return section.action(node) { [weak self] in
            guard let self, free || job?.isStopped == true else { return }
            switch state {
            case .available(_, let to, _, _):
                toolsAskUpdate(serial: serial, name: name, target: to, installed: installed?.installedVersion, word: String(localized: "Install"))
            case .upToDate:
                guard case .found(let patch) = updates.lookups[serial], let newest = patch.packages.last else { return }
                toolsAskUpdate(serial: serial, name: name, target: newest.version, installed: nil, word: String(localized: "Reinstall"))
            case .stopped:
                guard let job else { return }
                if EmulatorSession.shared.browsing { return notice(EmulatorSession.browsingRefusal, error: false) }
                if UpdateRow.mobileRetry(job, name: name) != nil {
                    toolsConfirm(UpdateText.mobileData, String(localized: "Download on Mobile Data")) {
                        self.updates.retry(serial, allowsCellular: true)
                    }
                } else {
                    updates.retry(serial)
                }
            default:
                break
            }
        }
    }

    /// Android's "Install Version X?", with what the Install screen's confirm also says: mobile data, an update taken off
    /// first, the DLC that goes with it, or not enough space.
    private func toolsAskUpdate(serial: String, name: String, target: String, installed: String?, word: String) {
        if EmulatorSession.shared.browsing { return notice(EmulatorSession.browsingRefusal, error: false) }
        let state = updates.installed[serial]
        guard case .found(let patch) = updates.lookups[serial],
              let chain = GameUpdates.chain(published: patch.packages, installed: installed, base: state?.discVersion, target: target) else { return }
        let sizes = chain.packages.map(\.size)
        let short = FreeSpace.shortfall(available: updates.availableBytes(), sizes: sizes)
        guard short == 0 else { return notice(UpdateText.shortfall(short), error: true) }
        let mobile = updates.network.expensive || updates.network.constrained
        let version = String(localized: "Version \(InstalledTitle.shortVersion(target))")
        var lines = [String(localized: "Install \(version)?")]
        if chain.removesFirst, let installed { lines.append(UpdateText.removedFirst(installed)) }
        if chain.removesFirst, (state?.dlcCount ?? 0) > 0 { lines.append(UpdateText.dlcWarning) }
        if mobile { lines.append(UpdateText.mobileData) }
        toolsConfirm(lines.joined(separator: "\n"), word) { [weak self] in
            self?.updates.start(serial: serial, name: name, chain: chain, installedBefore: installed, allowsCellular: mobile)
        }
    }

    // MARK: - DLC

    private func toolsAddOns(_ section: inout XmbSection) -> XmbNode {
        let id = "tools.install.dlc"
        let store = XmbToolsStore.shared
        let icon = XmbIcon.firmware(rco: XmbRco.game, name: "item_tex_ps3util", symbol: "puzzlepiece")
        if store.justOpened(id, isOpen(id)) { Task { await store.loadAddOns() } }
        var rows: [XmbNode]
        switch isOpen(id) ? store.addOns : nil {
        case nil:
            rows = [toolsClosed(id, "…")]
        case let groups? where groups.isEmpty:
            rows = [XmbNode(id: "tools.dlc.none", title: String(localized: "No add-ons installed."), subtitle: nil, icon: icon, kind: .info)]
        case let groups?:
            rows = groups.map { group in
                let items = group.items.map { item -> XmbNode in
                    let row = "tools.dlc." + item.url.path
                    section.options[row] = { [weak self] _ in self?.toolsRemoveAddOn(item) }
                    let kind = item.isLicence ? String(localized: "Licence file") : String(localized: "Content folder")
                    return section.action(XmbNode(id: row, title: item.name, subtitle: kind, icon: icon, kind: .action,
                                                  options: [.init(id: "remove", title: String(localized: "Remove"))])) { [weak self] in
                        self?.toolsRemoveAddOn(item)
                    }
                }
                return XmbNode(id: "tools.dlc.game." + group.id, title: group.name, subtitle: String(localized: "\(group.items.count) installed"),
                               icon: icon, kind: .folder(items))
            }
        }
        return XmbNode(id: id, title: String(localized: "DLC"), subtitle: String(localized: "Add-ons installed for your games"),
                       icon: icon, kind: .folder(rows))
    }

    private func toolsRemoveAddOn(_ item: XmbAddOn) {
        toolsConfirm(String(localized: "Remove \(item.name)?"), String(localized: "Remove")) {
            Task {
                // A licence alone leaves the files it unlocked in the game's folder; the add-on just stops loading.
                let removed = await Task.detached(priority: .utility) { (try? FileManager.default.removeItem(at: item.url)) != nil }.value
                await XmbToolsStore.shared.loadAddOns()
                await XmbToolsStore.shared.loadLicences()
                self.notice(removed ? String(localized: "Removed.") : String(localized: "Couldn't remove that add-on."), error: !removed)
            }
        }
    }
}
