// SPDX-License-Identifier: GPL-2.0-only
// The toy portals as XMB lists with the portal drawn beside them, ported from Android's XmbPortal.kt and the XMB art in ToyPortalPanel.kt.

import SwiftUI

/// What stands on each portal and the figure files beside it, read again each time the Portal list opens.
@MainActor @Observable final class XmbPortals {
    static let shared = XmbPortals()

    /// One per portal, in PortalStore's order: what stands where, and the loads and lifts.
    let models: [PortalSheetModel] = PortalStore.portals.map { portal in
        let model = PortalSheetModel()
        model.portalID = portal.id
        return model
    }
    /// The figure files in each portal's folder, by portal id.
    private(set) var files: [Int: [URL]] = [:]
    /// The file put in each place from these lists, by portal id and place, so that one file stands in one place.
    // shortcut: a figure put on from the Portal sheet is not known here; track the core's paths once portalList returns them.
    private(set) var placedFiles: [Int: [Int: URL]] = [:]

    func setPlaced(_ file: URL?, portal: Int, slot: Int) { placedFiles[portal, default: [:]][slot] = file }

    func read() async {
        for model in models {
            await model.refresh()
            // A place emptied anywhere else no longer holds its file.
            placedFiles[model.portalID] = placedFiles[model.portalID]?.filter { model.names.indices.contains($0.key) && model.names[$0.key] != nil }
        }
        let documents = models[0].documents
        files = await Task.detached(priority: .userInitiated) {
            Dictionary(uniqueKeysWithValues: PortalStore.portals.map { portal in
                let folder = PortalStore.folder(portal, documents: documents)
                let all = (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: [.fileSizeKey])) ?? []
                let figures = all.filter { (try? $0.resourceValues(forKeys: [.fileSizeKey]).fileSize) == portal.fileBytes }
                return (portal.id, figures.sorted { $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending })
            })
        }.value
    }

    /// Android's create: a new figure file in the portal's folder, to be placed like any other.
    func create(_ portal: PortalStore.Portal, _ choice: (name: String, figure: Int, variant: Int)) async -> Bool {
        guard let file = PortalStore.freeFile(choice.name, ext: "bin", portal: portal, documents: models[0].documents) else { return false }
        let status = await withCheckedContinuation { continuation in
            Rpcs3Bridge.portalCreate(portal.id, figure: choice.figure, variant: choice.variant, path: file.path) { continuation.resume(returning: $0) }
        }
        await read()
        return status == 0
    }

    /// Picked files copied into the portal's folder; what was not a figure, or could not be copied, is named.
    func add(_ urls: [URL], to portal: PortalStore.Portal) async -> String {
        let documents = models[0].documents
        let (count, notFigures, failed) = await Task.detached(priority: .userInitiated) { () -> (Int, [String], [String]) in
            var count = 0
            var notFigures: [String] = []
            var failed: [String] = []
            for url in urls {
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                if (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) != portal.fileBytes {
                    notFigures.append(url.lastPathComponent)
                } else if PortalStore.copyIn(url, portal: portal, documents: documents) == nil {
                    failed.append(url.lastPathComponent)
                } else {
                    count += 1
                }
            }
            return (count, notFigures, failed)
        }.value
        await read()
        var lines: [String] = []
        if count > 0 { lines.append(String(localized: "Figure files added: \(count)")) }
        if !notFigures.isEmpty { lines.append(String(localized: "Not figure files, so not added: \(notFigures.joined(separator: ", "))")) }
        if !failed.isEmpty { lines.append(String(localized: "Could not copy: \(failed.joined(separator: ", "))")) }
        return lines.joined(separator: "\n")
    }
}

extension XmbHost {

    /// Portal: each portal with what stands on it, the figure files to put there, Create figure and Add figure files.
    func portalNode(_ section: inout XmbSection) -> XmbNode {
        let store = XmbPortals.shared
        let id = "ingame.portal"
        if XmbToolsStore.shared.justOpened(id, isOpen(id)) { Task { await store.read() } }
        let icon = XmbIcon.firmware(rco: XmbRco.game, name: "item_tex_ps3util", symbol: "circle.hexagongrid")
        let rows = isOpen(id) ? PortalStore.portals.map { portal in
            portal.id == 1 || portal.id == 2 ? positionalPortal(&section, portal) : standingPortal(&section, portal)
        } : [XmbNode(id: id + ".closed", title: "", subtitle: nil, icon: .none, kind: .info)]
        return XmbNode(id: id, title: String(localized: "Portal"), subtitle: PortalStore.portals.map(\.name).joined(separator: ", "),
                       icon: icon, kind: .folder(rows))
    }

    private var portalIcon: XmbIcon { .firmware(rco: XmbRco.game, name: "item_tex_ps3util", symbol: "circle.hexagongrid") }
    private var figureIcon: XmbIcon { .firmware(rco: XmbRco.game, name: "item_tex_ps3game_folder", symbol: "figure.stand") }

    private static func figureName(_ file: URL) -> String { file.deletingPathExtension().lastPathComponent }

    private func noFiguresRow(_ key: String, _ portal: PortalStore.Portal) -> XmbNode {
        XmbNode(id: key + ".none",
                title: String(localized: "No figures yet. Create one or add figure files above, or put them in Files › ARMSX3 › Figures › \(portal.folderName) and open this again."),
                subtitle: nil, icon: portalIcon, kind: .info)
    }

    /// Skylanders and Kamen Rider: the figures standing on it, then Add a figure, which takes the first free place.
    private func standingPortal(_ section: inout XmbSection, _ portal: PortalStore.Portal) -> XmbNode {
        let store = XmbPortals.shared
        let model = store.models[portal.id]
        let key = "ingame.portal.\(portal.id)"
        let lift = String(localized: "Lift off")
        var rows: [XmbNode] = []
        for (slot, name) in model.names.enumerated() {
            guard let name else { continue }
            let id = "\(key).placed.\(slot)"
            section.options[id] = { [weak self] _ in self?.liftFigure(model: model, slot: slot) }
            rows.append(section.action(XmbNode(id: id, title: name, subtitle: String(localized: "On the portal: \(lift)"), icon: figureIcon,
                                               kind: .action, options: [.init(id: "lift", title: lift)])) { [weak self] in
                self?.liftFigure(model: model, slot: slot)
            })
        }
        let standing = Set((store.placedFiles[portal.id] ?? [:]).values)
        let figures = (store.files[portal.id] ?? []).filter { !standing.contains($0) }
        let place = figures.isEmpty ? [noFiguresRow(key, portal)] : figures.map { file in
            section.action(XmbNode(id: "\(key).file.\(file.lastPathComponent)", title: Self.figureName(file), subtitle: nil, icon: figureIcon,
                                   kind: .action)) { [weak self] in
                guard let free = model.names.firstIndex(where: { $0 == nil }) else {
                    self?.notice(PortalSheetModel.message(for: -9))
                    return
                }
                self?.placeFigure(file, model: model, slot: free)
            }
        }
        rows.append(XmbNode(id: key + ".place", title: String(localized: "Add a figure"), subtitle: nil, icon: portalIcon, kind: .folder(place)))
        rows.append(figureCreator(&section, key, portal))
        rows.append(figureAdder(&section, key, portal))
        let art = XmbPortalArt.path(portal: portal.id, placed: Set(model.names.indices.filter { model.names[$0] != nil }), focus: nil)
        return XmbNode(id: key, title: portal.name, subtitle: nil, icon: portalIcon, kind: .folder(rows), aside: art.map { .game(path: $0) })
    }

    /// Disney Infinity and LEGO Dimensions: each place on the pad a list of its own, with that pad ringed in the picture.
    private func positionalPortal(_ section: inout XmbSection, _ portal: PortalStore.Portal) -> XmbNode {
        let store = XmbPortals.shared
        let model = store.models[portal.id]
        let key = "ingame.portal.\(portal.id)"
        let placed = Set(model.names.indices.filter { model.names[$0] != nil })
        var rows: [XmbNode] = []
        for (slot, label) in Self.positions(portal.id) where model.names.indices.contains(slot) {
            let standing = model.names[slot]
            let folder = "\(key).pos.\(slot)"
            var children: [XmbNode] = []
            if let standing {
                children.append(section.action(XmbNode(id: folder + ".lift", title: String(localized: "Lift off"), subtitle: standing,
                                                       icon: portalIcon, kind: .action)) { [weak self] in
                    self?.liftFigure(model: model, slot: slot)
                    self?.perform(.back)
                })
            }
            let figures = store.files[portal.id] ?? []
            if figures.isEmpty { children.append(noFiguresRow(key, portal)) }
            for file in figures {
                // A figure stands in one place: off any other first, as the panel does.
                let elsewhere = store.placedFiles[portal.id]?.first { $0.value == file && $0.key != slot }?.key
                children.append(section.action(XmbNode(id: "\(folder).\(file.lastPathComponent)", title: Self.figureName(file),
                                                       subtitle: store.placedFiles[portal.id]?[slot] == file ? label : nil, icon: figureIcon, kind: .action)) { [weak self] in
                    self?.placeFigure(file, model: model, slot: slot, liftingFrom: elsewhere)
                })
            }
            let art = XmbPortalArt.path(portal: portal.id, placed: placed, focus: slot)
            rows.append(XmbNode(id: folder, title: label, subtitle: standing ?? String(localized: "Empty"),
                                icon: standing != nil ? figureIcon : portalIcon, kind: .folder(children), aside: art.map { .game(path: $0) }))
        }
        rows.append(figureCreator(&section, key, portal))
        rows.append(figureAdder(&section, key, portal))
        let art = XmbPortalArt.path(portal: portal.id, placed: placed, focus: nil)
        return XmbNode(id: key, title: portal.name, subtitle: nil, icon: portalIcon, kind: .folder(rows), aside: art.map { .game(path: $0) })
    }

    /// The core's places on each pad, in Android's order and words.
    private static func positions(_ portal: Int) -> [(Int, String)] {
        if portal == 1 {
            return [(0, String(localized: "Play set or power disc")), (1, String(localized: "Power disc \(2)")),
                    (2, String(localized: "Power disc \(3)")), (3, String(localized: "Player \(1)")),
                    (4, String(localized: "Player \(1), first ability")), (5, String(localized: "Player \(1), second ability")),
                    (6, String(localized: "Player \(2)")), (7, String(localized: "Player \(2), first ability")),
                    (8, String(localized: "Player \(2), second ability"))]
        }
        return [(1, String(localized: "Centre pad")), (0, String(localized: "Left pad, spot \(1)")), (3, String(localized: "Left pad, spot \(2)")),
                (4, String(localized: "Left pad, spot \(3)")), (2, String(localized: "Right pad, spot \(1)")),
                (5, String(localized: "Right pad, spot \(2)")), (6, String(localized: "Right pad, spot \(3)"))]
    }

    private func placeFigure(_ file: URL, model: PortalSheetModel, slot: Int, liftingFrom elsewhere: Int? = nil) {
        let store = XmbPortals.shared
        Task {
            if let elsewhere {
                _ = await model.remove(slot: elsewhere)
                store.setPlaced(nil, portal: model.portalID, slot: elsewhere)
            }
            let status = await model.load(picked: file, slot: slot)
            guard status == 0 else { return notice(PortalSheetModel.message(for: status)) }
            store.setPlaced(file, portal: model.portalID, slot: slot)
            perform(.back)
        }
    }

    private func liftFigure(model: PortalSheetModel, slot: Int) {
        Task {
            _ = await model.remove(slot: slot)
            XmbPortals.shared.setPlaced(nil, portal: model.portalID, slot: slot)
        }
    }

    /// Create figure: every figure the portal can make, by letter when there are many. Only an open list builds its rows.
    private func figureCreator(_ section: inout XmbSection, _ key: String, _ portal: PortalStore.Portal) -> XmbNode {
        let id = key + ".create"
        let title = String(localized: "Create figure")
        guard isOpen(id) else {
            return XmbNode(id: id, title: title, subtitle: nil, icon: portalIcon,
                           kind: .folder([XmbNode(id: id + ".closed", title: "", subtitle: nil, icon: .none, kind: .info)]))
        }
        // Disney Infinity names the series a figure came out in: 1.0 cannot use the later ones.
        func label(_ choice: (name: String, figure: Int, variant: Int)) -> String {
            portal.id == 1 ? "\(choice.name) (\(choice.variant).0)" : choice.name
        }
        let all = PortalNames.creatable(portal: portal.id).sorted { label($0).lowercased() < label($1).lowercased() }
        func item(_ choice: (name: String, figure: Int, variant: Int)) -> XmbNode {
            section.action(XmbNode(id: "\(id).\(choice.figure).\(choice.variant).\(choice.name)", title: label(choice), subtitle: nil,
                                   icon: figureIcon, kind: .action)) { [weak self] in
                Task {
                    let made = await XmbPortals.shared.create(portal, choice)
                    self?.notice(made ? String(localized: "Created \(label(choice)).") : String(localized: "Could not create \(label(choice))."),
                                 error: !made)
                }
            }
        }
        guard all.count > 40 else { return XmbNode(id: id, title: title, subtitle: nil, icon: portalIcon, kind: .folder(all.map(item))) }
        var letters: [Character: [(name: String, figure: Int, variant: Int)]] = [:]
        for choice in all {
            let first = label(choice).uppercased().first
            letters[first.flatMap { $0.isLetter ? $0 : nil } ?? "#", default: []].append(choice)
        }
        let groups = letters.keys.sorted().map { letter in
            let group = letters[letter] ?? []
            let folder = "\(id).\(letter)"
            let children = isOpen(folder) ? group.map(item) : [XmbNode(id: folder + ".closed", title: "", subtitle: nil, icon: .none, kind: .info)]
            return XmbNode(id: folder, title: String(letter), subtitle: "\(group.count)", icon: portalIcon, kind: .folder(children))
        }
        return XmbNode(id: id, title: title, subtitle: nil, icon: portalIcon, kind: .folder(groups))
    }

    private func figureAdder(_ section: inout XmbSection, _ key: String, _ portal: PortalStore.Portal) -> XmbNode {
        section.action(XmbNode(id: key + ".add", title: String(localized: "Add figure files"), subtitle: nil, icon: portalIcon, kind: .action)) { [weak self] in
            self?.pick(.figures(portal: portal.id))
        }
    }

    /// The figure files picked for Add figure files, copied into the portal's folder.
    func figuresPicked(_ urls: [URL], portal id: Int) {
        guard let portal = PortalStore.portals.first(where: { $0.id == id }), !urls.isEmpty else { return }
        Task {
            let said = await XmbPortals.shared.add(urls, to: portal)
            if !said.isEmpty { notice(said) }
        }
    }
}

/// A still of each portal for beside its list, as Android draws them: what stands on it, and the focused place's pad ringed.
enum XmbPortalArt {
    private static let body = Color(red: 0.082, green: 0.075, blue: 0.122)
    private static let light = Color(red: 0.388, green: 0.847, blue: 1)
    private static let infinityGlow = Color(red: 0.498, green: 0.816, blue: 1)
    private static let ring = Color(red: 0.169, green: 0.184, blue: 0.212)

    /// A PNG of the portal, drawn once for each arrangement.
    @MainActor static func path(portal: Int, placed: Set<Int>, focus: Int?) -> String? {
        let cache = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("xmb-portals", isDirectory: true)
        let name = "\(portal)-" + placed.sorted().map(String.init).joined(separator: "_") + "-\(focus.map(String.init) ?? "n").png"
        let target = cache.appendingPathComponent(name)
        if FileManager.default.fileExists(atPath: target.path) { return target.path }
        let wide = portal == 1 || portal == 2
        let size = CGSize(width: 480, height: wide ? 480 * 0.74 : 480)
        let renderer = ImageRenderer(content: Canvas { context, size in
            switch portal {
            case 1: infinity(&context, size, placed, focus)
            case 2: toyPad(&context, size, placed, focus)
            default: round(&context, size, placed)
            }
        }.frame(width: size.width, height: size.height))
        renderer.scale = 2
        guard let png = renderer.uiImage?.pngData() else { return nil }
        try? FileManager.default.createDirectory(at: cache, withIntermediateDirectories: true)
        return (try? png.write(to: target, options: .atomic)) != nil ? target.path : nil
    }

    /// Skylanders and Kamen Rider: rings of light on a round top, brighter the more figures stand on it.
    private static func round(_ context: inout GraphicsContext, _ size: CGSize, _ placed: Set<Int>) {
        let r = min(size.width, size.height) / 2
        let centre = CGPoint(x: size.width / 2, y: size.height / 2)
        let level = 0.35 + 0.65 * Double(min(placed.count, 8)) / 8
        context.fill(circle(centre, r), with: .color(body))
        context.stroke(circle(centre, r * 0.97), with: .color(light.opacity(0.55)), lineWidth: r * 0.06)
        context.fill(circle(centre, r * 0.9), with: .radialGradient(Gradient(colors: [light.opacity(0.85 * level), light.opacity(0.3 * level), .clear]),
                                                                     center: centre, startRadius: 0, endRadius: r * 0.9))
        for (radius, count, sweep, colour) in [(r * 0.72, 3, 70.0, light.opacity(0.8 * level)), (r * 0.5, 4, 40.0, Color.white.opacity(0.35 * level))] {
            for i in 0 ..< count {
                let start = Double(i) * 360 / Double(count) + (count == 4 ? 20 : 0)
                var arc = Path()
                arc.addArc(center: centre, radius: radius, startAngle: .degrees(start), endAngle: .degrees(start + sweep), clockwise: false)
                context.stroke(arc, with: .color(colour), style: StrokeStyle(lineWidth: r * (count == 3 ? 0.07 : 0.05), lineCap: .round))
            }
        }
        let n = placed.count
        let tall = n <= 2 ? r * 0.62 : n <= 4 ? r * 0.5 : r * 0.42
        let feet: [CGPoint] = switch n {
        case 0: []
        case 1: [CGPoint(x: centre.x, y: centre.y + r * 0.32)]
        case 2: [CGPoint(x: centre.x - r * 0.3, y: centre.y + r * 0.32), CGPoint(x: centre.x + r * 0.3, y: centre.y + r * 0.32)]
        default: (0 ..< n).map { i in
            let a = Double.pi * 2 * Double(i) / Double(n) - Double.pi / 2
            return CGPoint(x: centre.x + r * 0.42 * cos(a), y: centre.y + r * 0.42 * sin(a) + tall * 0.45)
        }
        }
        for foot in feet.sorted(by: { $0.y < $1.y }) { figure(&context, foot, tall, light) }
    }

    /// The Infinity base: the hexagon at the back and the two round pads in front, each lit while something stands on it.
    private static func infinity(_ context: inout GraphicsContext, _ size: CGSize, _ placed: Set<Int>, _ focus: Int?) {
        let w = size.width, h = size.height
        func polygon(_ points: [(CGFloat, CGFloat)]) -> Path {
            Path { path in path.addLines(points.map { CGPoint(x: w * $0.0, y: h * $0.1) }); path.closeSubpath() }
        }
        let shape = polygon([(0.16, 0.02), (0.84, 0.02), (0.99, 0.45), (0.82, 0.97), (0.18, 0.97), (0.01, 0.45)])
        context.fill(shape, with: .linearGradient(Gradient(colors: [Color(white: 0.945), Color(white: 0.8)]),
                                                  startPoint: .zero, endPoint: CGPoint(x: 0, y: h)))
        context.fill(polygon([(0.16, 0.02), (0.84, 0.02), (0.94, 0.31), (0.68, 0.31), (0.58, 0.52), (0.42, 0.52), (0.32, 0.31), (0.06, 0.31)]),
                     with: .color(Color(white: 0.6)))
        let pad = focus.map { $0 / 3 * 3 }
        let hexCentre = CGPoint(x: w * 0.5, y: h * 0.25)
        func hexagon(_ radius: CGFloat) -> Path {
            Path { path in
                path.addLines((0 ..< 6).map { i in
                    let a = Double(i) * Double.pi / 3
                    return CGPoint(x: hexCentre.x + radius * cos(a), y: hexCentre.y + radius * sin(a))
                })
                path.closeSubpath()
            }
        }
        let lit = [0: placed.contains(0), 3: placed.contains(3), 6: placed.contains(6)]
        context.fill(hexagon(w * 0.14), with: .color(lit[0] == true ? infinityGlow : Color(white: 0.97)))
        context.stroke(hexagon(w * 0.14), with: .color(lit[0] == true ? infinityGlow : Color(white: 0.5)), lineWidth: 5)
        if pad == 0 { context.stroke(hexagon(w * 0.14 + 10), with: .color(ring), lineWidth: 6) }
        for (spot, x) in [(3, 0.29), (6, 0.71)] {
            let centre = CGPoint(x: w * x, y: h * 0.7)
            context.fill(circle(centre, w * 0.15), with: .color(lit[spot] == true ? infinityGlow : Color(white: 0.96)))
            context.stroke(circle(centre, w * 0.15), with: .color(lit[spot] == true ? infinityGlow : Color(white: 0.72)), lineWidth: 5)
            if pad == spot { context.stroke(circle(centre, w * 0.15 + 10), with: .color(ring), lineWidth: 6) }
            if lit[spot] == true { figure(&context, CGPoint(x: w * x, y: h * 0.75), h * 0.2, infinityGlow) }
        }
    }

    /// The LEGO toy pad: a dark slab with the round centre pad and the two side pads, lit by how many stand on each.
    private static func toyPad(_ context: inout GraphicsContext, _ size: CGSize, _ placed: Set<Int>, _ focus: Int?) {
        let w = size.width, h = size.height
        let left = [0, 3, 4], right = [2, 5, 6]
        context.fill(Path(roundedRect: CGRect(origin: .zero, size: size), cornerRadius: w * 0.05),
                     with: .linearGradient(Gradient(colors: [Color(white: 0.3), Color(white: 0.17)]), startPoint: .zero, endPoint: CGPoint(x: 0, y: h)))
        context.fill(Path(roundedRect: CGRect(x: w * 0.035, y: h * 0.265, width: w * 0.93, height: h * 0.705), cornerRadius: w * 0.03),
                     with: .color(Color(white: 0.13)))
        let pad = focus.map { left.contains($0) ? 0 : right.contains($0) ? 2 : 1 }
        let frosted = Color(white: 0.9)
        let pads: [(Int, Path, Double)] = [
            (1, circle(CGPoint(x: w * 0.5, y: h * 0.42), w * 0.13), placed.contains(1) ? 1 : 0.25),
            (0, Path(roundedRect: CGRect(x: w * 0.07, y: h * 0.56, width: w * 0.39, height: h * 0.37), cornerRadius: w * 0.03),
             0.25 + 0.75 * Double(left.filter(placed.contains).count) / 3),
            (2, Path(roundedRect: CGRect(x: w * 0.54, y: h * 0.56, width: w * 0.39, height: h * 0.37), cornerRadius: w * 0.03),
             0.25 + 0.75 * Double(right.filter(placed.contains).count) / 3),
        ]
        for (index, shape, glow) in pads {
            context.fill(shape, with: .color(frosted))
            context.fill(shape, with: .color(light.opacity(0.8 * glow)))
            if pad == index { context.stroke(shape, with: .color(.white), lineWidth: 6) }
        }
        let spots: [Int: CGPoint] = [1: CGPoint(x: 0.5, y: 0.47), 0: CGPoint(x: 0.16, y: 0.9), 3: CGPoint(x: 0.27, y: 0.9),
                                     4: CGPoint(x: 0.38, y: 0.9), 2: CGPoint(x: 0.62, y: 0.9), 5: CGPoint(x: 0.73, y: 0.9),
                                     6: CGPoint(x: 0.84, y: 0.9)]
        for (slot, at) in spots.sorted(by: { $0.key < $1.key }) where placed.contains(slot) {
            figure(&context, CGPoint(x: w * at.x, y: h * at.y), h * 0.22, light)
        }
    }

    /// A plain figure on its base, dark and rimmed in the pad's light, the shape of any toy.
    private static func figure(_ context: inout GraphicsContext, _ foot: CGPoint, _ height: CGFloat, _ glow: Color) {
        let w = height * 0.62
        context.fill(Path(ellipseIn: CGRect(x: foot.x - w * 0.62, y: foot.y - height * 0.07, width: w * 1.24, height: height * 0.14)),
                     with: .color(.black.opacity(0.3)))
        let torso = Path(roundedRect: CGRect(x: foot.x - w * 0.35, y: foot.y - height * 0.72, width: w * 0.7, height: height * 0.72),
                         cornerRadius: w * 0.18)
        let head = circle(CGPoint(x: foot.x, y: foot.y - height * 0.84), height * 0.15)
        for part in [torso, head] {
            context.fill(part, with: .color(Color(white: 0.12)))
            context.stroke(part, with: .color(glow), lineWidth: max(2, height * 0.03))
        }
    }

    private static func circle(_ centre: CGPoint, _ radius: CGFloat) -> Path {
        Path(ellipseIn: CGRect(x: centre.x - radius, y: centre.y - radius, width: radius * 2, height: radius * 2))
    }
}
