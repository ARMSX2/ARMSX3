// SPDX-License-Identifier: GPL-2.0-only
// Who is player 1-4. A key's ordinal is kept while connected, so a twin leaving never re-keys it. Foundation only.

import Foundation

struct ControllerKey: Hashable {
    let category: String
    let vendor: String
    let ordinal: Int
}

struct PlayerSlots: Equatable {
    static let count = 4

    // holders[n] is player n + 1's controller, nil while that slot is free.
    private(set) var holders: [ControllerKey?] = Array(repeating: nil, count: PlayerSlots.count)
    // reserved[n] is the key that lost slot n this session, nil when none waits for it.
    private(set) var reserved: [ControllerKey?] = Array(repeating: nil, count: PlayerSlots.count)
    private(set) var pins: [ControllerKey: Int] = [:]

    init(storedPins: [String: Int] = [:]) {
        loadPins(storedPins)
    }

    static func key(category: String, vendor: String, live: [ControllerKey]) -> ControllerKey {
        let taken = Set(live.filter { $0.category == category && $0.vendor == vendor }.map(\.ordinal))
        var ordinal = 0
        while taken.contains(ordinal) { ordinal += 1 }
        return ControllerKey(category: category, vendor: vendor, ordinal: ordinal)
    }

    func slot(of key: ControllerKey) -> Int? {
        holders.firstIndex(of: key)
    }

    // Its own slot, else the one reserved for it, else the lowest free one; never at or above `limit`.
    mutating func claim(_ key: ControllerKey, limit: Int = PlayerSlots.count) -> Int? {
        if let held = slot(of: key) { return held }
        if let back = reserved.firstIndex(of: key), back < limit {
            reserved[back] = nil
            holders[back] = key
            return back
        }
        if let pinned = pins[key], pinned < limit, holders[pinned] == nil, reserved[pinned] == nil {
            holders[pinned] = key
            return pinned
        }
        guard let free = holders.indices.first(where: { $0 < limit && holders[$0] == nil && reserved[$0] == nil }) else { return nil }
        holders[free] = key
        return free
    }

    // Its slot is reserved for it; nil when it held none (then nothing changes).
    mutating func lose(_ key: ControllerKey) -> Int? {
        guard let held = slot(of: key) else { return nil }
        holders[held] = nil
        reserved[held] = key
        return held
    }

    // The holders keep their slots.
    mutating func endSession() {
        reserved = Array(repeating: nil, count: Self.count)
    }

    // A held slot is never reserved, so the swap keeps both tables consistent.
    mutating func pin(_ key: ControllerKey, to slot: Int) -> Bool {
        guard holders.indices.contains(slot) else { return false }
        pins = pins.filter { $0.value != slot }
        pins[key] = slot
        if let from = self.slot(of: key), from != slot {
            holders.swapAt(from, slot)
            reserved.swapAt(from, slot)
        }
        return true
    }

    mutating func unpin(_ key: ControllerKey) {
        pins[key] = nil
    }

    // `category|vendor|ordinal` to slot.
    var storedPins: [String: Int] {
        Dictionary(uniqueKeysWithValues: pins.map { ("\($0.key.category)|\($0.key.vendor)|\($0.key.ordinal)", $0.value) })
    }

    // shortcut: a category or vendor containing `|` does not load back (its pin is dropped, never misread).
    mutating func loadPins(_ stored: [String: Int]) {
        pins = [:]
        for (text, slot) in stored where holders.indices.contains(slot) {
            let parts = text.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
            guard parts.count == 3, let ordinal = Int(parts[2]) else { continue }
            pins[ControllerKey(category: parts[0], vendor: parts[1], ordinal: ordinal)] = slot
        }
    }

    // The slot it held, now free; nil when it held none.
    mutating func release(_ key: ControllerKey) -> Int? {
        guard let held = slot(of: key) else { return nil }
        holders[held] = nil
        return held
    }

    var playerOneHasController: Bool { holders[0] != nil }
}
