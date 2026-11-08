// SPDX-License-Identifier: GPL-2.0-only
// Touch pad hit tests: a finger stays in the cluster it touched down in and presses what lies under it now.

import CoreGraphics
import Foundation

enum PadHitRule {
    case nearest, chord, eightWay
}

struct PadCluster<Key: Hashable> {
    let rule: PadHitRule
    // Each button's drawn art frame, in the touch surface's coordinates.
    var art: [Key: CGRect]
}

enum PadHit {
    static let minimumSide: CGFloat = 44
    static let growth: CGFloat = 1.3
    static let deadZone: CGFloat = 0.25
    static let diagonalReach: CGFloat = 67.5

    static func region(_ art: CGRect) -> CGRect {
        let width = max(art.width * growth, minimumSide)
        let height = max(art.height * growth, minimumSide)
        return CGRect(x: art.midX - width / 2, y: art.midY - height / 2, width: width, height: height)
    }

    static func distance(_ a: CGPoint, _ b: CGPoint) -> CGFloat {
        hypot(a.x - b.x, a.y - b.y)
    }

    static func centre(_ rect: CGRect) -> CGPoint {
        CGPoint(x: rect.midX, y: rect.midY)
    }

    static func bounds<Key>(_ cluster: PadCluster<Key>) -> CGRect {
        cluster.art.values.map(region).reduce(CGRect.null) { $0.union($1) }
    }

    static func covers<Key>(_ cluster: PadCluster<Key>, _ point: CGPoint) -> Bool {
        if cluster.rule == .eightWay { return bounds(cluster).contains(point) }
        return cluster.art.values.contains { region($0).contains(point) }
    }

    static func buttons<Key>(at point: CGPoint, in cluster: PadCluster<Key>) -> Set<Key> {
        switch cluster.rule {
        case .eightWay:
            let box = bounds(cluster)
            guard box.contains(point) else { return [] }
            let middle = centre(box)
            let arm = cluster.art.values.map { distance(centre($0), middle) }.max() ?? 0
            guard distance(point, middle) >= arm * deadZone else { return [] }
            let angle = atan2(point.y - middle.y, point.x - middle.x)
            let reach = diagonalReach * .pi / 180
            return Set(cluster.art.filter { _, art in
                var apart = abs(angle - atan2(art.midY - middle.y, art.midX - middle.x))
                if apart > .pi { apart = 2 * .pi - apart }
                return apart < reach
            }.keys)
        case .nearest, .chord:
            let under = cluster.art.filter { region($0.value).contains(point) }
                .sorted { distance(point, centre($0.value)) < distance(point, centre($1.value)) }
                .map(\.key)
            return Set(under.prefix(cluster.rule == .chord ? 2 : 1))
        }
    }

    // A press for every new button, a release for every gone one.
    static func change<Key>(from before: Set<Key>, to after: Set<Key>) -> PadChange<Key> {
        PadChange(pressed: after.subtracting(before), released: before.subtracting(after))
    }
}

struct PadChange<Key: Hashable>: Equatable {
    var pressed: Set<Key>
    var released: Set<Key>
    var isEmpty: Bool { pressed.isEmpty && released.isEmpty }
}

struct PadTouchTracker<Finger: Hashable, Key: Hashable> {
    var clusters: [PadCluster<Key>] = []
    private var owner: [Finger: Int] = [:]
    private var holding: [Finger: Set<Key>] = [:]

    var held: Set<Key> {
        holding.values.reduce(into: Set<Key>()) { $0.formUnion($1) }
    }

    mutating func down(_ finger: Finger, at point: CGPoint) {
        owner[finger] = claim(point)
        move(finger, to: point)
    }

    mutating func move(_ finger: Finger, to point: CGPoint) {
        guard let index = owner[finger], clusters.indices.contains(index) else {
            holding[finger] = nil
            return
        }
        holding[finger] = PadHit.buttons(at: point, in: clusters[index])
    }

    mutating func lift(_ finger: Finger) {
        owner[finger] = nil
        holding[finger] = nil
    }

    mutating func liftAll() {
        owner.removeAll()
        holding.removeAll()
    }

    // Of the clusters it lands in, the one with the nearest button centre.
    private func claim(_ point: CGPoint) -> Int? {
        func nearest(_ index: Int) -> CGFloat {
            clusters[index].art.values.map { PadHit.distance(point, PadHit.centre($0)) }.min() ?? .infinity
        }
        return clusters.indices.filter { PadHit.covers(clusters[$0], point) }.min { nearest($0) < nearest($1) }
    }
}
