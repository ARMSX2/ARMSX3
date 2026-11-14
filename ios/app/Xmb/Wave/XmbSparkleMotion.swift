// SPDX-License-Identifier: GPL-2.0-only
// The phone's and the controllers' motion that pushes the XMB's sparkles, ported from Android's XmbSparkleMotion.kt.

import CoreMotion
import GameController
import UIKit

/// Swinging the phone or a controller to the left, or tipping it down on the left, sends the sparkles to the left,
/// and a hard shake scatters them, as on the console. The source moving hardest wins.
@MainActor final class XmbSparkleMotion {
    private(set) var push: Float = 0
    private(set) var shake: Float = 0

    private let phone = CMMotionManager()
    private var phoneSource = XmbSparkleMotionSource()
    private var controllerSources: [ObjectIdentifier: XmbSparkleMotionSource] = [:]
    private var activated: [GCController] = []
    private var on = false

    func want(_ on: Bool) {
        guard on != self.on else { return }
        self.on = on
        if on {
            phone.deviceMotionUpdateInterval = 1.0 / 60
            if phone.isDeviceMotionAvailable { phone.startDeviceMotionUpdates() }
        } else {
            phone.stopDeviceMotionUpdates()
            for controller in activated { controller.motion?.sensorsActive = false }
            activated = []
            phoneSource = XmbSparkleMotionSource()
            controllerSources = [:]
            push = 0
            shake = 0
        }
    }

    /// Core Motion and GameController report the negative of what Android's sensors do, so each reading is turned round.
    func read(orientation: UIInterfaceOrientation, time: TimeInterval) {
        guard on else { return }
        var best: XmbSparkleMotionSource?
        var hardest: Float = 0
        // Core Motion always tells gravity from movement, so the phone needs no raw accelerometer path.
        if let sample = phone.deviceMotion {
            let moved = sample.userAcceleration
            phoneSource.fused(gravityX: -Float(Self.sideways(sample.gravity.x, sample.gravity.y, orientation)),
                              linearX: -Float(Self.sideways(moved.x, moved.y, orientation)),
                              linearMagnitude: Float((moved.x * moved.x + moved.y * moved.y + moved.z * moved.z).squareRoot()))
            best = phoneSource
            hardest = phoneSource.shake
        }
        var sources: [ObjectIdentifier: XmbSparkleMotionSource] = [:]
        for controller in GCController.controllers() {
            guard let sensors = controller.motion else { continue }
            if sensors.sensorsRequireManualActivation, !sensors.sensorsActive {
                sensors.sensorsActive = true
                activated.append(controller)
            }
            let g = sensors.gravity, moved = sensors.userAcceleration
            let a = sensors.hasGravityAndUserAcceleration
                ? GCAcceleration(x: g.x + moved.x, y: g.y + moved.y, z: g.z + moved.z) : sensors.acceleration
            let magnitude = (a.x * a.x + a.y * a.y + a.z * a.z).squareRoot()
            // Sensors still coming on read zero, which the shake test would take for a fall.
            guard magnitude > 0 else { continue }
            let id = ObjectIdentifier(controller)
            var source = controllerSources[id] ?? XmbSparkleMotionSource()
            source.raw(x: -Float(a.x), magnitude: Float(magnitude), time: time)
            sources[id] = source
            if best.map({ abs(source.push) > abs($0.push) }) ?? true { best = source }
            hardest = max(hardest, source.shake)
        }
        controllerSources = sources
        push = best?.push ?? 0
        shake = hardest
    }

    private static func sideways(_ x: Double, _ y: Double, _ orientation: UIInterfaceOrientation) -> Double {
        switch orientation {
        // The phone was turned counter-clockwise, so its +y now points left.
        case .landscapeRight: return -y
        case .portraitUpsideDown: return -x
        case .landscapeLeft: return y
        default: return x
        }
    }
}
