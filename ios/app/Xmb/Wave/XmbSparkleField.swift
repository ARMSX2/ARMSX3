// SPDX-License-Identifier: GPL-2.0-only
// The XMB's sparkles stepped on the CPU, with their wind and motion response, ported from Android's XmbParticles.kt, XmbGlView.kt and XmbSparkleMotion.kt.

import Foundation

/// Kotlin's xorshift bit for bit, 0 to 1, so one seed gives the same field on both. A seed of 0 never leaves 0.
struct XmbSparkleRandom {
    var state: UInt64

    init(seed: UInt64 = 0x2545_F491) {
        state = seed
    }

    mutating func next() -> Float {
        state ^= state << 13
        state ^= state >> 7
        state ^= state << 17
        return Float(state >> 40) / Float(1 << 24)
    }
}

/// Each sparkle is a tiny spinning flake, born on the wave's surface. It drifts off slowly and lives a few seconds,
/// so the field keeps to the wave as the console's does. World space is the console's particle camera: the eye at
/// (0, 0, 2) looking down -z, clip x and y the world's times the projection's scale, clip w = 2 - z.
struct XmbSparkleField {
    /// PARTICLES.mnu, per 60 Hz step, with the firmware's own values for when it is not there.
    struct Settings: Equatable {
        var emitPerFrame: Float = 16.6539
        var emitProb: Float = 0.479115
        var emitVelMin: Float = 0.15064
        var emitVelMul: Float = 0.19
        var emitVelVar: Float = 0.282567
        var emitConeAngle: Float = 51.8695
        var emitNegProb: Float = 0.173899
        var emitVelZScale: Float = 0
        var agingSpeed: Float = 0.00285223
        var agingVariance: Float = 0.493003
        var friction: Float = 0.030551
        var deltaTime: Float = 0.0088883
        var gravity: Float = -6.8e-5
        var brownian: Float = 0.225311
        var spinTimeScale: Float = 2.74

        private static let names: [(String, WritableKeyPath<Settings, Float>)] = [
            ("emit per frame", \.emitPerFrame), ("emit prob", \.emitProb), ("emit vel min", \.emitVelMin),
            ("emit vel mul", \.emitVelMul), ("emit vel var", \.emitVelVar), ("emit cone angle", \.emitConeAngle),
            ("emit neg prob", \.emitNegProb), ("emit vel zscale", \.emitVelZScale), ("aging speed", \.agingSpeed),
            ("aging variance", \.agingVariance), ("friction", \.friction), ("delta time", \.deltaTime),
            ("gravity", \.gravity), ("brownian scale", \.brownian), ("spin time scale", \.spinTimeScale),
        ]

        init() {}

        init(mnu: [UInt8]) {
            let values = Self.parse(mnu)
            for (name, path) in Self.names {
                if let value = values[name], value.isFinite { self[keyPath: path] = value }
            }
        }

        /// "name:float:value" or "name:int:value" a line; anything else is passed over.
        private static func parse(_ bytes: [UInt8]) -> [String: Float] {
            var values: [String: Float] = [:]
            for line in (String(bytes: bytes, encoding: .isoLatin1) ?? "").split(whereSeparator: \.isNewline) {
                let parts = line.trimmingCharacters(in: .whitespaces).split(separator: ":", omittingEmptySubsequences: false)
                guard parts.count == 3, parts[1] == "float" || parts[1] == "int", let value = Float(parts[2]) else { continue }
                values[String(parts[0])] = value
            }
            return values
        }
    }

    private struct Flake {
        var position = SIMD3<Float>()
        var velocity = SIMD3<Float>()
        var attitude = SIMD4<Float>()
        var spin = SIMD3<Float>()
        var age: Float = 0
        var ageRate: Float = 0
    }

    /// The console's field: 2,040 in one capture, 2,027 in another.
    static let capacity = 2048
    static let floatsPerFlake = 8

    private static let netSide = 19
    private static let stepsPerSecond: Float = 60
    private static let maxStepsPerFrame: Float = 6
    private static let warmSteps = 420
    // Thirty times PARTICLES.mnu's gravity in these steps gives the field the console's lean below the wave.
    private static let gravityGain: Float = 30
    private static let scatterKick: Float = 0.6
    private static let waveScaleY: Float = 2.0057

    var settings: Settings
    var count: Int { flakes.count }

    private var flakes: [Flake] = []
    private var emitCarry: Float = 0
    private var stepCarry: Float = 0
    private var warmed = false
    private var random: XmbSparkleRandom
    private var net = [SIMD3<Float>](repeating: SIMD3(), count: netSide * netSide)
    private var haveNet = false

    init(settings: Settings = Settings(), seed: UInt64 = 0x2545_F491) {
        self.settings = settings
        random = XmbSparkleRandom(seed: seed)
        flakes.reserveCapacity(Self.capacity)
    }

    /// The wave model's projection for a drawable of this size, which the particles' camera shares.
    static func scale(width: Float, height: Float) -> (x: Float, y: Float) {
        let aspect = width / max(height, 1)
        let x = waveScaleY / max(aspect, 16 / 9)
        return (x, x * aspect)
    }

    /// Takes the wave's control points (19x19 clip-space x, y, z, w as the wave model writes them) back into world space.
    mutating func setWave(_ controlPoints: [Float], scaleX: Float, scaleY: Float) {
        // Points not written yet are all zero, which would put every flake in the eye.
        guard controlPoints.count >= net.count * 4, controlPoints[3] > 0 else { return }
        for i in net.indices {
            net[i] = SIMD3(controlPoints[i * 4] / scaleX, controlPoints[i * 4 + 1] / scaleY, 2 - controlPoints[i * 4 + 3])
        }
        haveNet = true
    }

    /// `windX` and `windY` (world units a second) carry the whole field; `scatter` (0 to 1) jolts every flake.
    mutating func step(_ seconds: Float, windX: Float, windY: Float, scatter: Float) {
        guard haveNet else { return }
        if !warmed {
            // Start full, as the console's field has long been running by the time it is seen.
            warmed = true
            for _ in 0 ..< Self.warmSteps { stepOnce(windX: 0, windY: 0, scatter: 0) }
        }
        stepCarry = min(stepCarry + seconds * Self.stepsPerSecond, Self.maxStepsPerFrame)
        while stepCarry >= 1 {
            stepCarry -= 1
            stepOnce(windX: windX / Self.stepsPerSecond, windY: windY / Self.stepsPerSecond, scatter: scatter)
        }
    }

    private mutating func stepOnce(windX: Float, windY: Float, scatter: Float) {
        // Births: PARTICLES.mnu emits 16.65 a frame with probability 0.479. The cap bounds a damaged file's loop.
        emitCarry = min(emitCarry + settings.emitPerFrame * settings.emitProb, Float(Self.capacity))
        while emitCarry >= 1 {
            emitCarry -= 1
            if flakes.count < Self.capacity { flakes.append(spawn()) }
        }
        let keep = 1 - settings.friction
        let kick = settings.brownian + scatter * Self.scatterKick
        let gravity = settings.gravity * Self.gravityGain
        var i = 0
        while i < flakes.count {
            var flake = flakes[i]
            flake.age += flake.ageRate
            if flake.age >= 1 {
                flakes[i] = flakes[flakes.count - 1]
                flakes.removeLast()
                continue
            }
            flake.velocity.x = (flake.velocity.x + kick * (random.next() - 0.5)) * keep
            flake.velocity.y = (flake.velocity.y + kick * (random.next() - 0.5) + gravity) * keep
            flake.velocity.z = (flake.velocity.z + kick * (random.next() - 0.5)) * keep
            flake.position.x += flake.velocity.x * settings.deltaTime + windX
            flake.position.y += flake.velocity.y * settings.deltaTime + windY
            flake.position.z += flake.velocity.z * settings.deltaTime
            flake.attitude = Self.turned(flake.attitude, by: flake.spin)
            flakes[i] = flake
            i += 1
        }
    }

    private mutating func spawn() -> Flake {
        var flake = Flake()
        let u = random.next() * Float(Self.netSide - 3)
        let v = random.next() * Float(Self.netSide - 3)
        let birthplace = surface(u, v)
        flake.position = birthplace.point
        // Within the cone about the surface's normal, now and then the other way.
        var n = birthplace.normal
        if random.next() < settings.emitNegProb { n = -n }
        // Through Double where Kotlin's sin goes through it, so the two round alike.
        let spread = Float(sin(Double(settings.emitConeAngle * 0.5 * (Float(Double.pi) / 180))))
        n.x += (random.next() * 2 - 1) * spread
        n.y += (random.next() * 2 - 1) * spread
        n.z += (random.next() * 2 - 1) * spread
        n.z *= settings.emitVelZScale
        let length = max((n.x * n.x + n.y * n.y + n.z * n.z).squareRoot(), 1e-6)
        let speed = (settings.emitVelMin + settings.emitVelMul * random.next())
            * (1 + settings.emitVelVar * (random.next() * 2 - 1))
        flake.velocity = n / length * speed
        let q = SIMD4(random.next() * 2 - 1, random.next() * 2 - 1, random.next() * 2 - 1, random.next() * 2 - 1)
        flake.attitude = q / max((q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w).squareRoot(), 1e-6)
        let axis = SIMD3(random.next() * 2 - 1, random.next() * 2 - 1, random.next() * 2 - 1)
        let axisLength = max((axis.x * axis.x + axis.y * axis.y + axis.z * axis.z).squareRoot(), 1e-6)
        let rate = settings.spinTimeScale * (0.3 + 0.7 * random.next()) / Self.stepsPerSecond
        flake.spin = axis / axisLength * rate
        flake.ageRate = settings.agingSpeed * (1 + settings.agingVariance * (random.next() * 2 - 1))
        return flake
    }

    /// The attitude one step of spin later (q += 0.5 * w * q, renormalised).
    private static func turned(_ q: SIMD4<Float>, by spin: SIMD3<Float>) -> SIMD4<Float> {
        let h = spin * 0.5
        let next = SIMD4(q.x + (h.x * q.w + h.y * q.z - h.z * q.y),
                         q.y + (h.y * q.w + h.z * q.x - h.x * q.z),
                         q.z + (h.z * q.w + h.x * q.y - h.y * q.x),
                         q.w - (h.x * q.x + h.y * q.y + h.z * q.z))
        return next * (1 / (next.x * next.x + next.y * next.y + next.z * next.z + next.w * next.w).squareRoot())
    }

    /// The wave's surface at (u, v), each 0 to 16 across its 16 cubic B-spline patches: the point and its unit normal.
    private func surface(_ u: Float, _ v: Float) -> (point: SIMD3<Float>, normal: SIMD3<Float>) {
        let iu = min(max(Int(u), 0), Self.netSide - 4)
        let iv = min(max(Int(v), 0), Self.netSide - 4)
        let (bu, dbu) = Self.basis(u - Float(iu))
        let (bv, dbv) = Self.basis(v - Float(iv))
        var point = SIMD3<Float>()
        var du = SIMD3<Float>()
        var dv = SIMD3<Float>()
        for a in 0 ..< 4 {
            for b in 0 ..< 4 {
                let c = net[(iu + a) * Self.netSide + iv + b]
                point += bu[a] * bv[b] * c
                du += dbu[a] * bv[b] * c
                dv += bu[a] * dbv[b] * c
            }
        }
        var normal = SIMD3(du.y * dv.z - du.z * dv.y, du.z * dv.x - du.x * dv.z, du.x * dv.y - du.y * dv.x)
        let length = (normal.x * normal.x + normal.y * normal.y + normal.z * normal.z).squareRoot()
        normal = length > 1e-9 ? normal / length : SIMD3(0, 1, 0)
        return (point, normal)
    }

    private static func basis(_ t: Float) -> (SIMD4<Float>, SIMD4<Float>) {
        let t2 = t * t
        let t3 = t2 * t
        let m = 1 - t
        return (SIMD4(m * m * m / 6, (3 * t3 - 6 * t2 + 4) / 6, (-3 * t3 + 3 * t2 + 3 * t + 1) / 6, t3 / 6),
                SIMD4(-m * m / 2, (3 * t2 - 4 * t) / 2, (-3 * t2 + 2 * t + 1) / 2, t2 / 2))
    }

    /// Writes what the shader draws each flake from and answers how many it wrote, never more than `out` holds.
    func writeInstances(to out: UnsafeMutableBufferPointer<Float>) -> Int {
        let written = min(flakes.count, out.count / Self.floatsPerFlake)
        for i in 0 ..< written {
            let flake = flakes[i]
            // Up over the first 3% of a life and down over the last 12%.
            let life = min(1, flake.age / 0.03) * min(1, (1 - flake.age) / 0.12)
            let at = i * Self.floatsPerFlake
            out[at] = flake.position.x
            out[at + 1] = flake.position.y
            out[at + 2] = flake.position.z
            out[at + 3] = life
            out[at + 4] = flake.attitude.x
            out[at + 5] = flake.attitude.y
            out[at + 6] = flake.attitude.z
            out[at + 7] = flake.attitude.w
        }
        return written
    }
}

/// One sensor's readings as a push and a shake. In g and in Android's sense: at rest it reads +1 along the axis pointing up.
struct XmbSparkleMotionSource {
    /// Sideways push on the sparkles, in g, positive to the right.
    private(set) var push: Float = 0
    /// How far past the console's shake threshold the last movement went, 0 to 1.
    private(set) var shake: Float = 0

    private var gravityX: Float = 0
    private var lastTime: Double?

    private static let gravitySeconds: Float = 0.25
    private static let swingDeadZone: Float = 0.05
    private static let tipDeadZone: Float = 0.1
    private static let swingGain: Float = 1
    private static let tipGain: Float = 0.5
    /// The console's shake threshold (PARTICLES_UI.mnu "dshake thresh" 0.722145), in g.
    private static let shakeThreshold: Float = 0.72
    private static let shakeRange: Float = 0.8

    /// A reading whose gravity and movement the sensors have already told apart; `linearX` is the way it speeds up.
    mutating func fused(gravityX: Float, linearX: Float, linearMagnitude: Float) {
        self.gravityX = gravityX
        respond(linearX: linearX, linearMagnitude: linearMagnitude)
    }

    mutating func raw(x: Float, magnitude: Float, time: Double) {
        // A quarter-second low-pass keeps the gravity and leaves the movement.
        let dt = lastTime.map { Float(min(max(time - $0, 0), 0.1)) } ?? 0
        lastTime = time
        gravityX += (x - gravityX) * (dt == 0 ? 1 : 1 - Float(exp(Double(-dt / Self.gravitySeconds))))
        let linearX = x - gravityX
        // The reading's length away from 1 g misses a sideways swing, so the sideways part counts in full.
        respond(linearX: linearX, linearMagnitude: max(abs(magnitude - 1), abs(linearX)))
    }

    private mutating func respond(linearX: Float, linearMagnitude: Float) {
        // A swing pushes the way the pad went. A tip pulls towards the low side, and the reading at rest points away from it.
        push = Self.deadZone(linearX, Self.swingDeadZone) * Self.swingGain
            - Self.deadZone(gravityX, Self.tipDeadZone) * Self.tipGain
        shake = min(max((linearMagnitude - Self.shakeThreshold) / Self.shakeRange, 0), 1)
    }

    private static func deadZone(_ value: Float, _ zone: Float) -> Float {
        if value > zone { return value - zone }
        if value < -zone { return value + zone }
        return 0
    }
}

/// The wind on the field from motion: a push speeds it up, friction slows it, and a hard shake's scatter fades out.
struct XmbSparkleWind {
    /// Clip-space units a second across the screen.
    private(set) var velocity: Float = 0
    private(set) var scatter: Float = 0

    private static let pushGain: Float = 1.2
    private static let friction: Float = 0.8
    private static let maxSpeed: Float = 0.2
    private static let scatterFade: Float = 2.5
    /// The field's middle distance from the camera, which turns clip-space speed into the world's.
    static let fieldDepth: Float = 8.6

    mutating func step(_ seconds: Float, push: Float, shake: Float) {
        let slowed = (velocity + push * Self.pushGain * seconds) * Float(exp(Double(-Self.friction * seconds)))
        velocity = min(max(slowed, -Self.maxSpeed), Self.maxSpeed)
        scatter = max(scatter * Float(exp(Double(-Self.scatterFade * seconds))), shake)
    }
}

enum XmbSparkleSwirl {
    static let side = 128

    /// particles/proc_iridescent.tga, the colours the flakes take as they spin, as RGBA; nil unless it is the plain 128x128 picture.
    static func rgba(tga: [UInt8]) -> [UInt8]? {
        guard tga.count >= 18, tga[1] == 0, tga[2] == 2 else { return nil }
        let start = 18 + Int(tga[0])
        let width = Int(tga[12]) | Int(tga[13]) << 8
        let height = Int(tga[14]) | Int(tga[15]) << 8
        let bytesPerTexel = Int(tga[16]) / 8
        guard width == side, height == side, bytesPerTexel == 3 || bytesPerTexel == 4,
              tga.count >= start + width * height * bytesPerTexel else { return nil }
        var out = [UInt8](repeating: 0xff, count: width * height * 4)
        for texel in 0 ..< width * height {
            let bgr = start + texel * bytesPerTexel
            out[texel * 4] = tga[bgr + 2]
            out[texel * 4 + 1] = tga[bgr + 1]
            out[texel * 4 + 2] = tga[bgr]
        }
        return out
    }
}
