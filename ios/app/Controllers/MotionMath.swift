// SPDX-License-Identifier: GPL-2.0-only
// Motion as a DS3 Sixaxis in the core's units (pad_types.h). Foundation only, for the host test.

import Foundation

// x right and y down, each -1...1.
struct MovePointer: Equatable {
    var x = 0.0
    var y = 0.0

    // A non-finite input moves nothing.
    mutating func integrate(yawRate: Double, pitchRate: Double, dt: Double) {
        func step(_ v: Double, _ rate: Double) -> Double {
            let d = rate * 0.72 * dt
            return d.isFinite ? min(max(v + d, -1), 1) : v
        }
        x = step(x, yawRate)
        y = step(y, pitchRate)
    }

    mutating func recentre() {
        x = 0
        y = 0
    }

    var stick: (UInt8, UInt8) {
        func byte(_ v: Double) -> UInt8 { UInt8(((min(max(v, -1), 1) + 1) * 127.5).rounded()) }
        return (byte(x), byte(y))
    }
}

struct Sixaxis: Equatable {
    var x: UInt16
    var y: UInt16
    var z: UInt16
    var g: UInt16

    // A flat DS3 at rest (pad_types.h DEFAULT_MOTION_X/Y/Z/G).
    static let rest = Sixaxis(x: 512, y: 399, z: 512, g: 512)

    // An inverted axis is mirrored about 512, kept inside 0...1023.
    func applying(invertX: Bool, invertY: Bool) -> Sixaxis {
        var s = self
        if invertX { s.x = min(1023, 1024 - x) }
        if invertY { s.y = min(1023, 1024 - y) }
        return s
    }
}

// As UIKit names it (landscape-right: the home side on the right).
enum ScreenOrientation {
    case portrait, portraitUpsideDown, landscapeLeft, landscapeRight
}

struct GyroStick: Equatable {
    var target: Int /* 0 left, 1 right */
    var sensitivity: Int /* 0...300 percent */
}

enum MotionMath {
    // Android's aim scale, rate * 0.72 * sensitivity / 100, smoothed by half each frame; nil setting is (0, 0).
    static func gyroOffset(rateX: Double, rateY: Double, setting: GyroStick?, previous: (Double, Double)) -> (Double, Double) {
        guard let setting else { return (0, 0) }
        let gain = 0.72 * Double(min(max(setting.sensitivity, 0), 300)) / 100
        func step(_ rate: Double, _ previous: Double) -> Double {
            let target = rate.isFinite ? rate * gain : 0
            return previous + (target - previous) * 0.5
        }
        return (step(rateX, previous.0), step(rateY, previous.1))
    }

    // Added after the mapping and deadzone, so the gyro is never deadzoned away.
    static func fold(stick: (UInt8, UInt8), offset: (Double, Double)) -> (UInt8, UInt8) {
        func byte(_ b: UInt8, _ o: Double) -> UInt8 {
            let v = (Double(b) - 127.5) / 127.5 + (o.isFinite ? o : 0)
            return UInt8(((min(max(v, -1), 1) + 1) * 127.5).rounded())
        }
        return (byte(stick.0, offset.0), byte(stick.1, offset.1))
    }

    static func steer(stick: (UInt8, UInt8), rateX: Double, rateY: Double, setting: GyroStick?,
                      previous: (Double, Double)) -> (stick: (UInt8, UInt8), state: (Double, Double)) {
        let offset = gyroOffset(rateX: rateX, rateY: rateY, setting: setting, previous: previous)
        return (fold(stick: stick, offset: offset), offset)
    }

    // Rounded and clamped to 0...1023.
    static func sixaxis(ax: Double, ay: Double, az: Double, yaw: Double) -> Sixaxis {
        func value(_ v: Double, rest: Double, scale: Double) -> UInt16 {
            let v = v.isFinite ? v : rest
            return UInt16(max(0, min(1023, (512 + v * scale).rounded())))
        }
        return Sixaxis(x: value(ax, rest: 0, scale: 113), y: value(ay, rest: -1, scale: 113),
                       z: value(az, rest: 0, scale: 113), g: value(yaw, rest: 0, scale: 78.31))
    }

    // Landscape-right turned the device counter-clockwise, so its +x points up and its +y left.
    static func screenFrame(x: Double, y: Double, z: Double, orientation: ScreenOrientation) -> (Double, Double, Double) {
        switch orientation {
        case .portrait: return (x, y, z)
        case .portraitUpsideDown: return (-x, -y, z)
        case .landscapeRight: return (-y, x, z)
        case .landscapeLeft: return (y, -x, z)
        }
    }

    // nil for a non-finite channel (the core's clamp lets NaN through) or black, so the caller shows the slot colour.
    static func sphereColor(r: Float, g: Float, b: Float) -> (r: Float, g: Float, b: Float)? {
        guard r.isFinite, g.isFinite, b.isFinite else { return nil }
        func unit(_ x: Float) -> Float { min(max(x, 0), 1) }
        let c = (r: unit(r), g: unit(g), b: unit(b))
        guard max(c.r, c.g, c.b) >= 1.0 / 255 else { return nil }
        return c
    }
}
