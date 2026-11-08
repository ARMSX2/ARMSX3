// SPDX-License-Identifier: GPL-2.0-only
// Devices a port can present, by the core's product ordinal. The generated block is upstream's; regenerate, never edit.

import Foundation

enum DeviceTables {
    struct Device {
        let ordinal: Int
        let className: String
        let name: String
        let isGun: Bool
    }

    // The DualShock 3's name for an ordinal the table does not hold.
    static func name(_ ordinal: Int) -> String {
        devices.indices.contains(ordinal) ? devices[ordinal].name : devices[0].name
    }

    // Portals are always attached and get no row.
    struct USBDevice {
        let key: String
        let label: String
        let path: String
    }

    static let usbDevices = [
        USBDevice(key: "ghltar", label: "Guitar Hero Live Guitar", path: "Input/Output@@GHLtar emulated controller"),
        USBDevice(key: "turntable", label: "DJ Hero Turntable", path: "Input/Output@@Turntable emulated controller"),
        USBDevice(key: "buzz", label: "Buzz Buzzers", path: "Input/Output@@Buzz emulated controller"),
    ]
    // The setting's values in the core's words, and the words the card shows for them.
    static let usbCounts = ["Null", "1 controller", "2 controllers"]
    static let usbCountWords = ["Off", "1", "2"]

    // The uDraw tablet names no buttons.
    static let gunLegendKeys = [11: "guncon3", 12: "topshotelite", 13: "topshotfearmaster"]

    // No phone has a mouse button, so the card names the pad buttons that press them; the generated block keeps upstream's.
    static let aimButtons = ["mouse_button_1": "R2", "mouse_button_2": "L2"]
    static let tabletLegend: [(ps3: String, does: String)] = [(ps3: "Touch the picture", does: "Pen down")]

    // Empty when nothing differs.
    static func legend(_ ordinal: Int) -> [(ps3: String, does: String)] {
        if let rows = padLegends[ordinal] { return rows }
        if ordinal == 14 { return tabletLegend }
        guard let key = gunLegendKeys[ordinal] else { return [] }
        return usbLegend(key).map { (ps3: aimButtons[$0.ps3] ?? $0.ps3, does: $0.does) }
    }

    static func usbLegend(_ key: String) -> [(ps3: String, does: String)] {
        (usbLegends[key] ?? []).map { (ps3: $0.ps3, does: $0.does) }
    }

    static func buttonName(_ raw: String) -> String { buttonNames[raw] ?? raw }

    // BEGIN GENERATED TABLE
    static let devices: [Device] = [
        Device(ordinal: 0, className: "Standard (Pad)", name: "Standard (Pad)", isGun: false),
        Device(ordinal: 1, className: "Guitar", name: "Guitar (Guitar Hero)", isGun: false),
        Device(ordinal: 2, className: "Drum", name: "Drum (Guitar Hero)", isGun: false),
        Device(ordinal: 3, className: "Dance Mat", name: "Dance Mat", isGun: false),
        Device(ordinal: 4, className: "DJ", name: "DJ", isGun: false),
        Device(ordinal: 5, className: "Guitar", name: "Guitar (Rock Band)", isGun: false),
        Device(ordinal: 6, className: "Drum", name: "Drum (Rock Band)", isGun: false),
        Device(ordinal: 7, className: "Drum", name: "Drum (Rock Band Pro)", isGun: false),
        Device(ordinal: 8, className: "Drum", name: "Drum (Rock Revolution)", isGun: false),
        Device(ordinal: 9, className: "PS Move Navigation", name: "PS Move Navigation", isGun: false),
        Device(ordinal: 10, className: "Skateboard", name: "Skateboard", isGun: false),
        Device(ordinal: 11, className: "GunCon 3", name: "GunCon 3", isGun: true),
        Device(ordinal: 12, className: "Top Shot Elite", name: "Top Shot Elite", isGun: true),
        Device(ordinal: 13, className: "Top Shot Fearmaster", name: "Top Shot Fearmaster", isGun: true),
        Device(ordinal: 14, className: "uDraw GameTablet", name: "uDraw GameTablet", isGun: true),
    ]
    static let padLegends: [Int: [(ps3: String, does: String)]] = [
        1: [
            ("cross", "Green Fret"),
            ("circle", "Red Fret"),
            ("square", "Yellow Fret"),
            ("triangle", "Blue Fret"),
            ("dpad_up", "Strum Up"),
            ("dpad_down", "Strum Down"),
            ("rs_right", "Whammy"),
            ("L1", "Orange Fret"),
        ],
        2: [
            ("cross", "Green Pad"),
            ("circle", "Red Pad"),
            ("square", "Blue Pad"),
            ("triangle", "Yellow Pad"),
            ("L1", "Foot Pedal"),
            ("R1", "Orange Pad"),
        ],
        5: [
            ("cross", "Green Fret"),
            ("circle", "Red Fret"),
            ("square", "Blue Fret"),
            ("triangle", "Yellow Fret"),
            ("dpad_up", "Strum Up"),
            ("dpad_down", "Strum Down"),
            ("rs_up", "Pickup Switch Up"),
            ("rs_down", "Pickup Switch Down"),
            ("rs_right", "Whammy"),
            ("L1", "Orange Fret"),
            ("L2", "Solo Modifier"),
            ("R1", "Tilt"),
        ],
        6: [
            ("cross", "Green Pad"),
            ("circle", "Red Pad"),
            ("square", "Blue Pad"),
            ("triangle", "Yellow Pad"),
            ("L1", "Foot Pedal"),
        ],
        7: [
            ("cross", "Green Pad"),
            ("circle", "Red Pad"),
            ("square", "Blue Pad"),
            ("triangle", "Yellow Pad"),
            ("L1", "Foot Pedal"),
            ("L3", "Pad Modifier"),
            ("R1", "Double Bass Pedal"),
            ("R3", "Cymbal Modifier"),
        ],
    ]
    static let usbLegends: [String: [(does: String, ps3: String)]] = [
        "ghltar": [
            ("W1", "square"),
            ("W2", "L1"),
            ("W3", "R1"),
            ("B1", "cross"),
            ("B2", "circle"),
            ("B3", "triangle"),
            ("Start", "start"),
            ("Hero Power", "select"),
            ("GHTV", "L3"),
            ("Strum Down", "dpad_down"),
            ("Strum Up", "dpad_up"),
            ("D-Pad Left", "dpad_left"),
            ("D-Pad Right", "dpad_right"),
            ("Whammy", "rs_y"),
            ("tilt", "rs_x"),
        ],
        "turntable": [
            ("Blue", "square"),
            ("Green", "cross"),
            ("Red", "circle"),
            ("D-Pad Up", "dpad_up"),
            ("D-Pad Down", "dpad_down"),
            ("D-Pad Left", "dpad_left"),
            ("D-Pad Right", "dpad_right"),
            ("Start", "start"),
            ("Select", "select"),
            ("Square", "R2"),
            ("Circle", "L1"),
            ("Cross", "R1"),
            ("Triangle", "triangle"),
            ("Right Turntable", "ls_y"),
            ("Crossfader", "rs_y"),
            ("Effects Dial", "rs_x"),
        ],
        "buzz": [
            ("Red", "R1"),
            ("Yellow", "cross"),
            ("Green", "circle"),
            ("Orange", "square"),
            ("Blue", "triangle"),
        ],
        "guncon3": [
            ("Trigger", "cross"),
            ("A1", "L1"),
            ("A2", "L2"),
            ("A3", "L3"),
            ("B1", "R1"),
            ("B2", "R2"),
            ("B3", "R3"),
            ("C1", "select"),
            ("C2", "start"),
            ("A-stick X-Axis", "ls_x"),
            ("A-stick Y-Axis", "ls_y"),
            ("B-stick X-Axis", "rs_x"),
            ("B-stick Y-Axis", "rs_y"),
        ],
        "topshotelite": [
            ("Trigger", "mouse_button_1"),
            ("Reload", "mouse_button_2"),
            ("Square", "square"),
            ("Cross", "cross"),
            ("Circle", "circle"),
            ("Triangle", "triangle"),
            ("Select", "select"),
            ("Start", "start"),
            ("L3", "L3"),
            ("R3", "R3"),
            ("PS", "ps"),
            ("D-Pad Up", "dpad_up"),
            ("D-Pad Down", "dpad_down"),
            ("D-Pad Left", "dpad_left"),
            ("D-Pad Right", "dpad_right"),
            ("Left Stick X-Axis", "ls_x"),
            ("Left Stick Y-Axis", "ls_y"),
            ("Right Stick X-Axis", "rs_x"),
            ("Right Stick Y-Axis", "rs_y"),
        ],
        "topshotfearmaster": [
            ("Trigger", "mouse_button_1"),
            ("Heartrate", "mouse_button_2"),
            ("Square", "square"),
            ("Cross", "cross"),
            ("Circle", "circle"),
            ("Triangle", "triangle"),
            ("Select", "select"),
            ("Start", "start"),
            ("L3", "L3"),
            ("PS", "ps"),
            ("D-Pad Up", "dpad_up"),
            ("D-Pad Down", "dpad_down"),
            ("D-Pad Left", "dpad_left"),
            ("D-Pad Right", "dpad_right"),
            ("Left Stick X-Axis", "ls_x"),
            ("Left Stick Y-Axis", "ls_y"),
        ],
        "usio": [
            ("Test", "select"),
            ("Coin", "L2"),
            ("Service", "R2"),
            ("Enter/Start", "start"),
            ("Up", "dpad_up"),
            ("Down", "dpad_down"),
            ("Left", "dpad_left"),
            ("Right", "dpad_right"),
            ("Taiko Hit Side Left", "square"),
            ("Taiko Hit Side Right", "circle"),
            ("Taiko Hit Center Left", "triangle"),
            ("Taiko Hit Center Right", "cross"),
            ("Tekken Button 1", "square"),
            ("Tekken Button 2", "triangle"),
            ("Tekken Button 3", "cross"),
            ("Tekken Button 4", "circle"),
            ("Tekken Button 5", "R1"),
            ("Card Tapping", "L1"),
            ("Gun", "mouse"),
        ],
    ]
    static let buttonNames: [String: String] = [
        "dpad_up": "D-Pad Up",
        "dpad_down": "D-Pad Down",
        "dpad_left": "D-Pad Left",
        "dpad_right": "D-Pad Right",
        "select": "Select",
        "start": "Start",
        "ps": "PS",
        "triangle": "Triangle",
        "circle": "Circle",
        "square": "Square",
        "cross": "Cross",
        "L1": "L1",
        "R1": "R1",
        "L2": "L2",
        "R2": "R2",
        "L3": "L3",
        "R3": "R3",
        "ls_up": "Left Stick Up",
        "ls_down": "Left Stick Down",
        "ls_left": "Left Stick Left",
        "ls_right": "Left Stick Right",
        "ls_x": "Left Stick X-Axis",
        "ls_y": "Left Stick Y-Axis",
        "rs_up": "Right Stick Up",
        "rs_down": "Right Stick Down",
        "rs_left": "Right Stick Left",
        "rs_right": "Right Stick Right",
        "rs_x": "Right Stick X-Axis",
        "rs_y": "Right Stick Y-Axis",
        "motion_x": "Motion X",
        "motion_y": "Motion Y",
        "motion_z": "Motion Z",
        "motion_g": "Motion G",
        "mouse_button_1": "Mouse Button 1",
        "mouse_button_2": "Mouse Button 2",
        "mouse_button_3": "Mouse Button 3",
        "mouse_button_4": "Mouse Button 4",
        "mouse_button_5": "Mouse Button 5",
        "mouse_button_6": "Mouse Button 6",
        "mouse_button_7": "Mouse Button 7",
        "mouse_button_8": "Mouse Button 8",
        "mouse": "Mouse",
        "left_stick": "Left Stick",
        "right_stick": "Right Stick",
    ]
    // END GENERATED TABLE
}
