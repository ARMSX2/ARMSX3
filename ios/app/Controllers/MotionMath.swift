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

    // An inverted axis is mirrored about 512, kept inside 0...1023. Tilt Y is forward and back, which a DS3 reads on Z.
    func applying(invertX: Bool, invertY: Bool) -> Sixaxis {
        var s = self
        if invertX { s.x = min(1023, 1024 - x) }
        if invertY { s.z = min(1023, 1024 - z) }
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

    // Hand movement shorter than this, in g, is tremor; a deliberate shake peaks far above it.
    static let tremor = 0.15

    // How far the grip tips the top edge away from upright, in radians, from gravity in the screen frame.
    static func gripPitch(gravity g: (Double, Double, Double)) -> Double {
        let pitch = atan2(-g.2, -g.1)
        return pitch.isFinite ? pitch : 0
    }

    // Gravity and hand movement in the screen frame, in g, as the pad's acceleration: turned so the grip reads
    // flat, tilt scaled by `sensitivity` percent, and movement under `tremor` dropped. Movement is never scaled.
    static func shaped(gravity g: (Double, Double, Double), movement m: (Double, Double, Double),
                       grip: Double, sensitivity: Int, tremor: Double) -> (Double, Double, Double) {
        let shaking = (m.0 * m.0 + m.1 * m.1 + m.2 * m.2).squareRoot() >= tremor
        let tilt = scaled(pitched(g, by: -grip), percent: sensitivity)
        let move = shaking ? pitched(m, by: -grip) : (0, 0, 0)
        return (tilt.0 + move.0, tilt.1 + move.1, tilt.2 + move.2)
    }

    // Turned about the pad's left-right axis; a positive angle tips the top edge away.
    static func pitched(_ v: (Double, Double, Double), by angle: Double) -> (Double, Double, Double) {
        (v.0, v.1 * cos(angle) - v.2 * sin(angle), v.1 * sin(angle) + v.2 * cos(angle))
    }

    // Roll from the horizon and pitch from flat, each multiplied and stopped at 90 degrees; the length is kept.
    static func scaled(_ g: (Double, Double, Double), percent: Int) -> (Double, Double, Double) {
        let k = Double(min(max(percent, 50), 200)) / 100
        let length = (g.0 * g.0 + g.1 * g.1 + g.2 * g.2).squareRoot()
        guard k != 1, length > 0, length.isFinite else { return g }
        let limit = Double.pi / 2
        let roll = min(max(asin(min(max(g.0 / length, -1), 1)) * k, -limit), limit)
        let pitch = min(max(atan2(-g.2, -g.1) * k, -limit), limit)
        return (length * sin(roll), -length * cos(roll) * cos(pitch), -length * cos(roll) * sin(pitch))
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
