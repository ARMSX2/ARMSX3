// SPDX-License-Identifier: GPL-2.0-only
// Ported from Android's ThemeScene.kt: a live theme's script stepped once a frame, with bounded work on the main thread.

import Foundation

final class ThemeScene: VsmxHost {

    // MARK: - ThemeScene.kt's companion, verbatim

    /// A frame of the theme engine's, as scripts read it from System.interval.
    static let tick = 1.0 / 60
    /// The shape of the PS3's picture, which scenes are composed for.
    static let frameAspect: Float = 16.0 / 9.0
    private static let LINEAR = 0.0
    private static let BEZIER = 1.0
    private static let MAX_STEP = 0.25
    private static let MAX_CATCH_UP = 8
    private static let SLACK = 0.25
    private static let MIN_INTERVAL = 1.0 / 240
    private static let MAX_TIMERS = 64
    private static let MAX_LIGHTS = 16
    private static let MAX_ARRAY = 1 << 16
    private static let MIN_AREA = 1e-6
    private static let MIN_FOV: Float = 0.01
    private static let MAX_FOV: Float = 3.1
    static let ANIM_SLOTS = 8
    /// Swift-only: the steps one `advance` or `start` shares across all its script calls.
    static let FRAME_STEPS = 200_000

    // Swift: Kotlin handed out the actor's own FloatArray, and a Swift [Float] would be a copy every write lost.
    final class Vec {
        var v: [Float]
        init(_ v: [Float]) { self.v = v }
    }

    fileprivate final class Tween {
        let target: Vec
        let from: [Float]
        let to: [Float]
        let duration: Double
        let eased: Bool
        var elapsed = 0.0

        init(target: Vec, to: [Float], duration: Double, eased: Bool) {
            self.target = target
            self.from = target.v
            self.to = to
            self.duration = duration
            self.eased = eased
        }
    }

    class Movable: VsmxHostObject {
        unowned let play: ThemeScene
        fileprivate var tweens: [String: Tween] = [:]
        fileprivate lazy var timers = TimerSlots(holder: self)

        init(play: ThemeScene) { self.play = play }

        func vector(_ name: String) -> Vec? { nil }
        func mover(_ name: String) -> String? { nil }
        var kind: String { "" }

        func get(_ name: String) throws -> VsmxValue {
            if let v = vector(name) { return ThemeScene.toArray(v.v) }
            if name == "timer" { return .host(timers) }
            guard let target = mover(name) else {
                play.report("\(kind).\(name)")
                return .undefined
            }
            return .native(VsmxNative(name: name) { [unowned self] _, args in
                self.move(target, args)
                return .undefined
            })
        }

        func set(_ name: String, _ value: VsmxValue) throws {
            guard let v = vector(name) else { return play.report("\(kind).\(name) =") }
            tweens[name] = nil
            _ = ThemeScene.assign(&v.v, value)
        }

        private func move(_ name: String, _ args: [VsmxValue]) {
            guard let v = vector(name) else { return }
            var to = v.v
            if !ThemeScene.assign(&to, args.first ?? .undefined) { return }
            moveVector(name, to, VsmxVm.num(arg(args, 1)), eased: VsmxVm.num(arg(args, 2)) == ThemeScene.BEZIER)
        }

        func moveVector(_ name: String, _ to: [Float], _ seconds: Double, eased: Bool = false) {
            guard let v = vector(name) else { return }
            if seconds.isNaN || seconds <= 0 {
                tweens[name] = nil
                v.v = to
            } else {
                tweens[name] = Tween(target: v, to: to, duration: seconds, eased: eased)
            }
        }

        fileprivate func step(_ dt: Double) {
            if tweens.isEmpty { return }
            for (name, t) in tweens {
                t.elapsed += dt
                let x = min(max(t.elapsed / t.duration, 0), 1)
                let k = t.eased ? x * x * (3 - 2 * x) : x
                // Swift: bounded by all three counts; they are equal unless a hand-built vector is ragged.
                for i in 0 ..< min(t.target.v.count, t.from.count, t.to.count) {
                    t.target.v[i] = Float(Double(t.from[i]) + Double(t.to[i] - t.from[i]) * k)
                }
                if x >= 1 { tweens[name] = nil }
            }
        }
    }

    final class ActorState: Movable {
        let source: RafScene.Actor
        // Swift: each vector is fitted to RafScene.read's length, so a short one cannot index past its end.
        let position: Vec
        let rotation: Vec
        let scale: Vec
        let color: Vec
        let uvOffset: Vec
        let uvScale: Vec
        let animWeights: Vec
        let animSpeeds: Vec
        let animTimes: Vec
        private(set) var enabled = true

        /// A skinned mesh also needs the skeleton to bend it with.
        let drawable: Bool

        init(_ source: RafScene.Actor, play: ThemeScene) {
            self.source = source
            position = Vec(fitted(source.position, [0, 0, 0]))
            rotation = Vec(fitted(source.rotation, [0, 0, 0]))
            scale = Vec(fitted(source.scale, [1, 1, 1]))
            color = Vec(fitted(source.color, [1, 1, 1, 1]))
            uvOffset = Vec(fitted(source.uvOffset, [0, 0]))
            uvScale = Vec(fitted(source.uvScale, [1, 1]))
            let slots = ThemeScene.ANIM_SLOTS
            var weights = fitted(source.animWeights, [Float](repeating: 0, count: slots))
            if source.animWeights.isEmpty { weights[0] = 1 }
            animWeights = Vec(weights)
            animSpeeds = Vec(fitted(source.animSpeeds, [Float](repeating: 1, count: slots)))
            animTimes = Vec(fitted(source.animTimes, [Float](repeating: 0, count: slots)))
            if let mesh = source.mesh {
                drawable = (!mesh.skinned || source.rig != nil) && source.material?.texture != nil &&
                    mesh.area() > ThemeScene.MIN_AREA
            } else {
                drawable = false
            }
            super.init(play: play)
        }

        override var kind: String { "Actor" }

        // Swift: returns new arrays, where the Kotlin reused three scratch arrays.
        func skinMatrices() -> [Float]? {
            guard let rig = source.rig else { return nil }
            var pose = rig.skeleton.rest
            var total: Float = 0
            for slot in 0 ..< min(ThemeScene.ANIM_SLOTS, rig.clips.count) {
                let w = animWeights.v[slot]
                guard let clip = rig.clips[slot], w > 0, clip.duration > 0 else { continue }
                total += w
                let t = play.time * Double(animSpeeds.v[slot]) + Double(animTimes.v[slot])
                let d = Double(clip.duration)
                var local = t.truncatingRemainder(dividingBy: d)
                if local < 0 { local += d }
                // Blending in turn by weight / weight so far is the weighted average.
                EdgeAnim.sample(clip, frame: Float(local * Double(clip.frameRate)), pose: &pose, weight: w / total)
            }
            return EdgeAnim.skinMatrices(EdgeAnim.worlds(rig.skeleton, pose: pose), rig.inverseBinds)
        }

        var playsClip: Bool {
            guard let rig = source.rig else { return false }
            for (slot, clip) in rig.clips.enumerated() where slot < ThemeScene.ANIM_SLOTS {
                guard let clip = clip, animWeights.v[slot] > 0, animSpeeds.v[slot] != 0 else { continue }
                if clip.channels.contains(where: { $0.frames.count > 1 }) { return true }
            }
            return false
        }

        var shown: Bool {
            drawable && enabled && color.v[3] > 0 && (scale.v[0] != 0 || scale.v[1] != 0 || scale.v[2] != 0)
        }

        override func vector(_ name: String) -> Vec? {
            switch name {
            case "position": return position
            case "rotation": return rotation
            case "scale": return scale
            case "color": return color
            case "uv_offset": return uvOffset
            case "uv_scale": return uvScale
            case "anim_weight": return animWeights
            case "anim_speed": return animSpeeds
            case "anim_time": return animTimes
            default: return nil
            }
        }

        override func mover(_ name: String) -> String? {
            switch name {
            case "setPosition": return "position"
            case "setRotation": return "rotation"
            case "setScale": return "scale"
            case "setColor": return "color"
            default: return nil
            }
        }

        override func get(_ name: String) throws -> VsmxValue {
            switch name {
            case "enable":
                return .bool(enabled)
            case "setDirection":
                // What turning an actor toward a point does is not known, and both such actors are hidden in every theme seen.
                return .native(VsmxNative(name: name) { _, _ in .undefined })
            case "getAnimIndex":
                // Animation slots by their animation's id, as Sony's Afrika theme drives its zebras.
                return .native(VsmxNative(name: name) { [unowned self] _, args in
                    let id = try VsmxVm.str(args.first ?? .undefined)
                    return .number(Double(self.source.rig?.names.firstIndex(of: id) ?? -1))
                })
            case "getAnimSpeed":
                return .native(VsmxNative(name: name) { [unowned self] _, args in
                    guard let s = ActorState.slot(args) else { return .undefined }
                    return .number(Double(self.animSpeeds.v[s]))
                })
            case "setAnimWeight":
                // (slot, weight, seconds): there over that long, as setColor goes.
                return .native(VsmxNative(name: name) { [unowned self] _, args in
                    guard let s = ActorState.slot(args) else { return .undefined }
                    let w = Float(VsmxVm.num(arg(args, 1)))
                    guard w.isFinite else { return .undefined }
                    var to = self.animWeights.v
                    to[s] = w
                    self.moveVector("anim_weight", to, VsmxVm.num(arg(args, 2)))
                    return .undefined
                })
            case "setAnimTime":
                // (slot, seconds): the slot's clip is that far in now.
                return .native(VsmxNative(name: name) { [unowned self] _, args in
                    guard let s = ActorState.slot(args) else { return .undefined }
                    let at = VsmxVm.num(arg(args, 1))
                    if at.isFinite { self.animTimes.v[s] = Float(at - self.play.time * Double(self.animSpeeds.v[s])) }
                    return .undefined
                })
            default:
                return try super.get(name)
            }
        }

        // Swift: the range is checked on the Double before converting; `> -1` keeps Kotlin's truncation of -0.5 to slot 0.
        private static func slot(_ args: [VsmxValue]) -> Int? {
            let d = VsmxVm.num(args.first ?? .undefined)
            guard d > -1, d < Double(ThemeScene.ANIM_SLOTS) else { return nil }
            return Int(d)
        }

        override func set(_ name: String, _ value: VsmxValue) throws {
            if name == "enable" { enabled = VsmxVm.truthy(value) } else { try super.set(name, value) }
        }
    }

    final class CameraState: Movable {
        let position: Vec
        let direction: Vec
        let up: Vec
        private(set) var yfov: Float

        init(_ camera: RafScene.Camera, play: ThemeScene) {
            position = Vec(fitted(camera.position, [0, 0, 0]))
            direction = Vec(fitted(camera.direction, [0, 0, -1]))
            up = Vec(fitted(camera.up, [0, 1, 0]))
            yfov = camera.yfov
            super.init(play: play)
        }

        override var kind: String { "Camera" }

        override func vector(_ name: String) -> Vec? {
            switch name {
            case "position": return position
            case "direction": return direction
            case "up": return up
            default: return nil
            }
        }

        override func mover(_ name: String) -> String? {
            switch name {
            case "setPosition": return "position"
            case "setDirection": return "direction"
            case "setUp": return "up"
            default: return nil
            }
        }

        override func get(_ name: String) throws -> VsmxValue {
            switch name {
            case "yfov": return .number(Double(yfov))
            // The scene is always composed for 16:9, and cropped to fit.
            case "aspect": return .number(Double(ThemeScene.frameAspect))
            default: return try super.get(name)
            }
        }

        override func set(_ name: String, _ value: VsmxValue) throws {
            if name == "yfov" {
                let v = Float(VsmxVm.num(value))
                if v > ThemeScene.MIN_FOV && v < ThemeScene.MAX_FOV { yfov = v }
            } else {
                try super.set(name, value)
            }
        }
    }

    final class LightState: Movable {
        let type: Int
        let position: Vec
        let direction: Vec
        let color: Vec

        init(type: Int = RafScene.Light.POINT, from: RafScene.Light? = nil, play: ThemeScene) {
            self.type = type
            // Kotlin's copyOf(3): cut or padded with zeros.
            position = Vec(from.map { fitted($0.position, [0, 0, 0]) } ?? [0, 0, 0])
            direction = Vec(from.map { fitted($0.direction, [0, 0, 0]) } ?? [0, 0, -1])
            color = Vec(from.map { fitted($0.color, [0, 0, 0]) } ?? [1, 1, 1])
            super.init(play: play)
        }

        override var kind: String { "Light" }

        override func vector(_ name: String) -> Vec? {
            switch name {
            case "position": return position
            case "direction": return direction
            case "color": return color
            default: return nil
            }
        }

        override func mover(_ name: String) -> String? {
            switch name {
            case "setPosition": return "position"
            case "setDirection": return "direction"
            case "setColor": return "color"
            default: return nil
            }
        }
    }

    private final class Timer: VsmxHostObject {
        unowned let play: ThemeScene
        let interval: Double
        let fn: VsmxValue
        let repeats: Bool
        var due: Double
        var dead = false
        /// Whoever's timer slot holds the timer.
        var owner: VsmxValue = .undefined

        init(play: ThemeScene, interval: Double, fn: VsmxValue, repeats: Bool) {
            self.play = play
            self.interval = interval
            self.fn = fn
            self.repeats = repeats
            due = interval
        }

        func get(_ name: String) throws -> VsmxValue {
            if name == "interval" { return .number(interval) }
            play.report("Timer.\(name)")
            return .undefined
        }

        func set(_ name: String, _ value: VsmxValue) throws { play.report("Timer.\(name) =") }
    }

    /// A timer runs its callback with its holder as `this`, which some themes' callbacks use.
    fileprivate final class TimerSlots: VsmxHostObject {
        // Swift: unowned, because the holder owns these slots; a strong reference back would be a retain cycle.
        unowned let holder: VsmxHostObject
        var slots: [String: VsmxValue] = [:]

        init(holder: VsmxHostObject) { self.holder = holder }

        func get(_ name: String) throws -> VsmxValue { slots[name] ?? .undefined }

        func set(_ name: String, _ value: VsmxValue) throws {
            guard slots.count < ThemeScene.MAX_TIMERS || slots[name] != nil else { return }
            slots[name] = value
            if case .host(let h) = value, let t = h as? Timer { t.owner = .host(holder) }
        }
    }

    private final class SystemObject: VsmxHostObject {
        unowned let play: ThemeScene
        lazy var slots = TimerSlots(holder: self)

        init(play: ThemeScene) { self.play = play }

        func get(_ name: String) throws -> VsmxValue {
            switch name {
            case "timer": return .host(slots)
            case "interval": return .number(ThemeScene.tick)
            // The PS3's output, which themes pick HD or SD pictures by: always HD here.
            case "resolution": return ThemeScene.toArray([1920, 1080])
            // Not in the Kotlin: a theme's timer ends with it, and on Android it threw and stopped that timer.
            case "printPerf": return .native(VsmxNative(name: name) { _, _ in .undefined })
            default:
                play.report("System.\(name)")
                return .undefined
            }
        }

        func set(_ name: String, _ value: VsmxValue) throws { play.report("System.\(name) =") }
    }

    private final class MathObject: VsmxHostObject {
        unowned let play: ThemeScene

        init(play: ThemeScene) { self.play = play }

        private func f(_ name: String, _ op: @escaping (Double, Double) -> Double) -> VsmxValue {
            .native(VsmxNative(name: name) { _, args in
                .number(op(VsmxVm.num(args.count > 0 ? args[0] : .number(.nan)),
                           VsmxVm.num(args.count > 1 ? args[1] : .number(.nan))))
            })
        }

        // Swift: Kotlin's minOfOrNull and maxOfOrNull give NaN when any value is NaN; Swift's min and max do not.
        private func extreme(_ name: String, _ empty: Double, _ pick: @escaping (Double, Double) -> Double) -> VsmxValue {
            .native(VsmxNative(name: name) { _, args in
                .number(args.map(VsmxVm.num).reduce(empty) { $0.isNaN || $1.isNaN ? .nan : pick($0, $1) })
            })
        }

        func get(_ name: String) throws -> VsmxValue {
            switch name {
            case "PI": return .number(Double.pi)
            case "E": return .number(M_E)
            case "floor": return f(name) { x, _ in x.rounded(.down) }
            case "ceil": return f(name) { x, _ in x.rounded(.up) }
            case "round": return f(name) { x, _ in (x + 0.5).rounded(.down) }
            case "abs": return f(name) { x, _ in Swift.abs(x) }
            case "sin": return f(name) { x, _ in Foundation.sin(x) }
            case "cos": return f(name) { x, _ in Foundation.cos(x) }
            case "tan": return f(name) { x, _ in Foundation.tan(x) }
            case "asin": return f(name) { x, _ in Foundation.asin(x) }
            case "acos": return f(name) { x, _ in Foundation.acos(x) }
            case "atan": return f(name) { x, _ in Foundation.atan(x) }
            case "atan2": return f(name) { y, x in Foundation.atan2(y, x) }
            case "sqrt": return f(name) { x, _ in x.squareRoot() }
            case "pow": return f(name) { x, y in Foundation.pow(x, y) }
            case "exp": return f(name) { x, _ in Foundation.exp(x) }
            case "log": return f(name) { x, _ in Foundation.log(x) }
            case "min": return extreme(name, .infinity) { Swift.min($0, $1) }
            case "max": return extreme(name, -.infinity) { Swift.max($0, $1) }
            case "random": return .native(VsmxNative(name: name) { [unowned self] _, _ in .number(self.play.random()) })
            default:
                play.report("Math.\(name)")
                return .undefined
            }
        }

        func set(_ name: String, _ value: VsmxValue) throws { play.report("Math.\(name) =") }
    }

    /// Only what the measured scripts read; every other name is reported, as an unsupported name is.
    private final class DateObject: VsmxHostObject {
        unowned let play: ThemeScene
        let parts: DateComponents

        init(play: ThemeScene, date: Date) {
            self.play = play
            parts = Calendar.current.dateComponents([.hour, .minute, .second], from: date)
        }

        func get(_ name: String) throws -> VsmxValue {
            switch name {
            case "hours": return .number(Double(parts.hour ?? 0))
            case "minutes": return .number(Double(parts.minute ?? 0))
            case "seconds": return .number(Double(parts.second ?? 0))
            default:
                play.report("Date.\(name)")
                return .undefined
            }
        }

        func set(_ name: String, _ value: VsmxValue) throws { play.report("Date.\(name) =") }
    }


    // MARK: - The scene

    let scene: RafScene.Scene
    fileprivate let random: () -> Double
    private let now: () -> Date

    /// A theme needing more than this player knows. Set before `start`.
    var unsupported: ((String) -> Void)?

    // Swift: lazy, because the nested objects hold the scene and Swift has no inner classes.
    private(set) lazy var actors: [ActorState] = scene.actors.map { ActorState($0, play: self) }
    private(set) lazy var camera = CameraState(scene.camera, play: self)
    // Swift: Kotlin's LinkedHashMap is this array plus `lightIndex`, so a later light of a name replaces the earlier.
    private(set) var lightStates: [LightState] = []
    private var lightIndex: [String: Int] = [:]

    private(set) var time = 0.0
    private var timers: [Timer] = []
    // Swift: the Kotlin's associateBy keeps the last actor of a name; uniquingKeysWith says so instead of trapping.
    private lazy var byName = Dictionary(actors.map { ($0.source.name, $0) }, uniquingKeysWith: { _, last in last })
    // Swift: not lazy, so `deinit` can read it without making one that holds a scene being freed.
    private var systemObject: SystemObject?
    private var system: SystemObject {
        if let s = systemObject { return s }
        let s = SystemObject(play: self)
        systemObject = s
        return s
    }
    private lazy var math = MathObject(play: self)
    private var vm: VsmxVm?

    var scripted: Bool { vm != nil }
    private(set) var scriptError: String?
    /// No script runs after it, and the view shows the still.
    private(set) var exhausted = false
    private var started = false

    init(scene: RafScene.Scene, random: @escaping () -> Double = { Double.random(in: 0 ..< 1) },
         now: @escaping () -> Date = { Date() }) {
        self.scene = scene
        self.random = random
        self.now = now
        _ = actors
        _ = camera
        for l in scene.lights { putLight(l.name, LightState(type: l.type, from: l, play: self)) }
        vm = scene.script.flatMap(VsmxVm.Program.parse).map { VsmxVm(program: $0, host: self) }
    }

    deinit {
        // Emptying the timer slots and the moves breaks the cycles a script can make back to their holder.
        let all: [Movable] = actors + [camera] + lightStates
        for m in all {
            m.timers.slots.removeAll()
            m.tweens.removeAll()
        }
        systemObject?.slots.slots.removeAll()
        timers.removeAll()
    }

    fileprivate func report(_ what: String) {
        unsupported?(what)
    }

    private func putLight(_ name: String, _ light: LightState) {
        if let i = lightIndex[name] {
            lightStates[i] = light
        } else {
            lightIndex[name] = lightStates.count
            lightStates.append(light)
        }
    }

    private func timer(_ args: [VsmxValue], repeats: Bool) throws -> VsmxValue {
        if timers.count >= Self.MAX_TIMERS { throw VsmxError("too many timers") }
        var seconds = VsmxVm.num(arg(args, 0))
        if seconds.isNaN { seconds = 0 }
        let t = Timer(play: self, interval: repeats ? max(seconds, Self.MIN_INTERVAL) : max(seconds, 0),
                      fn: arg(args, 1), repeats: repeats)
        timers.append(t)
        return .host(t)
    }

    // MARK: - VsmxHost

    func global(_ name: String) -> VsmxValue? {
        switch name {
        case "Actor":
            return .constructor(VsmxConstructor(name: name) { [unowned self] args in
                let id = try VsmxVm.str(arg(args, 0))
                guard let a = self.byName[id] else { throw VsmxError("no actor \(id)") }
                return .host(a)
            })
        case "Camera":
            return .constructor(VsmxConstructor(name: name) { [unowned self] _ in .host(self.camera) })
        case "Light":
            return .constructor(VsmxConstructor(name: name) { [unowned self] args in
                let id = try VsmxVm.str(arg(args, 0))
                if let i = self.lightIndex[id] { return .host(self.lightStates[i]) }
                if self.lightStates.count >= Self.MAX_LIGHTS { throw VsmxError("too many lights") }
                let made = LightState(play: self)
                self.putLight(id, made)
                return .host(made)
            })
        case "Array":
            return .constructor(VsmxConstructor(name: name) { args in
                if args.count == 1, case .number(let size) = args[0] {
                    // Swift: NaN passes the Kotlin's `size < 0 || size > MAX_ARRAY`, and Int(Double.nan) traps.
                    guard size >= 0, size <= Double(Self.MAX_ARRAY) else { throw VsmxError("array size") }
                    return .array(JsArray(Array(repeating: .undefined, count: Int(size))))
                }
                return .array(JsArray(args))
            })
        case "IntervalTimer":
            return .constructor(VsmxConstructor(name: name) { [unowned self] args in try self.timer(args, repeats: true) })
        case "OneShotTimer":
            return .constructor(VsmxConstructor(name: name) { [unowned self] args in try self.timer(args, repeats: false) })
        case "Date":
            return .constructor(VsmxConstructor(name: name) { [unowned self] _ in
                .host(DateObject(play: self, date: self.now()))
            })
        case "System": return .host(system)
        case "Math": return .host(math)
        case "writeln": return .native(VsmxNative(name: name) { _, _ in .undefined })
        case "INTERPOLATION_LINEAR": return .number(Self.LINEAR)
        case "INTERPOLATION_BEZIER": return .number(Self.BEZIER)
        // JavaScript's own globals, which scripts compare against.
        case "undefined": return .undefined
        case "NaN": return .number(.nan)
        case "Infinity": return .number(.infinity)
        default:
            report(name)
            return nil
        }
    }

    func unsupported(_ what: String) {
        report(what)
    }

    // MARK: - Stepping

    func start() {
        if started { return }
        started = true
        guard let vm = vm else { return }
        vm.stepBudget = Self.FRAME_STEPS
        vm.run()
        scriptError = vm.failed
        if (vm.stepBudget ?? 1) <= 0 { exhausted = true }
    }

    func advance(_ seconds: Double) {
        start()
        let dt = seconds.isNaN ? 0 : min(max(seconds, 0), Self.MAX_STEP)
        time += dt
        for a in actors { a.step(dt) }
        camera.step(dt)
        for l in lightStates { l.step(dt) }
        guard let vm = vm, !exhausted else { return }
        vm.stepBudget = Self.FRAME_STEPS
        // A snapshot: timers made by these callbacks start counting next time.
        frame: for timer in timers {
            if timer.dead { continue }
            timer.due -= dt
            // Up to a quarter frame early, so a per-frame timer does not skip one frame and double the next.
            let slack = Self.SLACK * min(timer.interval, Self.tick)
            var fired = 0
            while !timer.dead && timer.due <= slack {
                if fired == Self.MAX_CATCH_UP {
                    timer.due = timer.interval // after a stall: drop the backlog, keep the rhythm
                    break
                }
                fired += 1
                if timer.repeats { timer.due += timer.interval } else { timer.dead = true }
                if let e = vm.call(timer.fn, this: timer.owner) {
                    timer.dead = true
                    scriptError = e
                }
                // Swift: the frame's budget is spent; no more script, this frame or after.
                if (vm.stepBudget ?? 1) <= 0 {
                    exhausted = true
                    break frame
                }
            }
        }
        timers.removeAll { $0.dead }
    }

    // Swift: bit patterns, so NaN equals NaN as in Kotlin's contentEquals; == would call a scene holding a NaN moving forever.
    private func snapshot() -> [UInt32] {
        var out: [Float] = []
        for a in actors where a.drawable {
            out.append(a.enabled ? 1 : 0)
            for v in [a.position, a.rotation, a.scale, a.color, a.uvOffset, a.uvScale] { out += v.v }
        }
        for v in [camera.position, camera.direction, camera.up] { out += v.v }
        out.append(camera.yfov)
        return out.map { $0.bitPattern }
    }

    /// A script that only sets things up once is as good as a picture; a passed deadline or spent budget also reads false.
    static func animates(_ scene: RafScene.Scene, seconds: Double = 30,
                         random: @escaping () -> Double = { Double.random(in: 0 ..< 1) },
                         now: @escaping () -> Date = { Date() }, deadline: Double = 10,
                         clock: () -> Double = { ProcessInfo.processInfo.systemUptime }) -> Bool {
        let play = ThemeScene(scene: scene, random: random, now: now)
        if !play.actors.contains(where: { $0.drawable }) { return false }
        if play.actors.contains(where: { $0.drawable && $0.playsClip }) { return true }
        if !play.scripted { return false }
        let began = clock()
        play.start()
        if play.exhausted { return false }
        let first = play.snapshot()
        var t = 0.0
        var ticks = 0
        while t < seconds {
            if !(clock() - began <= deadline) { return false }
            play.advance(tick)
            t += tick
            if play.exhausted { return false }
            ticks += 1
            if ticks % 15 == 0 && play.snapshot() != first { return true }
        }
        return play.snapshot() != first
    }

    // MARK: - Values and matrices

    static func toArray(_ v: [Float]) -> VsmxValue {
        .array(JsArray(v.map { .number(Double($0)) }))
    }

    /// A shorter array leaves the rest as it was; false, changing nothing, when `value` is not an array of numbers.
    static func assign(_ target: inout [Float], _ value: VsmxValue) -> Bool {
        guard case .array(let a) = value else { return false }
        let n = min(target.count, a.items.count)
        if n == 0 { return false }
        let v = (0 ..< n).map { Float(VsmxVm.num(a.items[$0])) }
        if v.contains(where: { !$0.isFinite }) { return false }
        target.replaceSubrange(0 ..< n, with: v)
        return true
    }

    /// Rotations apply X, then Y, then Z: the only order that draws a known theme's mirrored slide upright.
    static func modelMatrix(_ p: [Float], _ r: [Float], _ s: [Float]) -> [Float] {
        // Swift: short vectors are padded rather than indexed past their end.
        let p = fitted(p, [0, 0, 0]), r = fitted(r, [0, 0, 0]), s = fitted(s, [1, 1, 1])
        let cx = cos(Double(r[0])), sx = sin(Double(r[0]))
        let cy = cos(Double(r[1])), sy = sin(Double(r[1]))
        let cz = cos(Double(r[2])), sz = sin(Double(r[2]))
        let r00 = cz * cy, r01 = cz * sy * sx - sz * cx, r02 = cz * sy * cx + sz * sx
        let r10 = sz * cy, r11 = sz * sy * sx + cz * cx, r12 = sz * sy * cx - cz * sx
        let r20 = -sy, r21 = cy * sx, r22 = cy * cx
        let s0 = Double(s[0]), s1 = Double(s[1]), s2 = Double(s[2])
        return [
            Float(r00 * s0), Float(r10 * s0), Float(r20 * s0), 0,
            Float(r01 * s1), Float(r11 * s1), Float(r21 * s1), 0,
            Float(r02 * s2), Float(r12 * s2), Float(r22 * s2), 0,
            p[0], p[1], p[2], 1,
        ]
    }

    static func viewMatrix(_ eye: [Float], _ direction: [Float], _ up: [Float]) -> [Float] {
        let eye = fitted(eye, [0, 0, 0])
        let f = normalized(fitted(direction, [0, 0, -1])) ?? [0, 0, -1]
        // Swift: Kotlin's `!!` cannot fail here, but a last fallback stands in for it so no input traps.
        let s = normalized(cross(f, fitted(up, [0, 1, 0]))) ?? normalized(cross(f, [0, 0, 1]))
            ?? normalized(cross(f, [0, 1, 0])) ?? [1, 0, 0]
        let u = cross(s, f)
        return [
            s[0], u[0], -f[0], 0,
            s[1], u[1], -f[1], 0,
            s[2], u[2], -f[2], 0,
            -dot(s, eye), -dot(u, eye), dot(f, eye), 1,
        ]
    }

    /// Other shapes get the 16:9 picture cropped to fill them, never the edges of the set beyond it.
    static func projection(_ yfov: Float, _ aspect: Float, _ near: Float, _ far: Float) -> [Float] {
        let tanHalf = tan(Double(yfov) / 2) * min(1, Double(frameAspect) / Double(aspect))
        let f = Float(1 / tanHalf)
        return [
            f / aspect, 0, 0, 0,
            0, f, 0, 0,
            0, 0, (far + near) / (near - far), -1,
            0, 0, 2 * far * near / (near - far), 0,
        ]
    }

    /// Swift-only: Metal's clip-space z runs from 0 to 1 where OpenGL's runs from -1 to 1.
    static func metalClip(_ projection: [Float]) -> [Float] {
        let glToMetal: [Float] = [
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 0.5, 0,
            0, 0, 0.5, 1,
        ]
        return multiply(glToMetal, projection)
    }

    static func multiply(_ a: [Float], _ b: [Float]) -> [Float] {
        // Swift: anything not 4x4 gives the identity rather than indexing past its end.
        guard a.count >= 16, b.count >= 16 else { return [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1] }
        var out = [Float](repeating: 0, count: 16)
        for c in 0 ..< 4 {
            for r in 0 ..< 4 {
                var sum: Float = 0
                for k in 0 ..< 4 { sum += a[k * 4 + r] * b[c * 4 + k] }
                out[c * 4 + r] = sum
            }
        }
        return out
    }

    private static func cross(_ a: [Float], _ b: [Float]) -> [Float] {
        [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]
    }

    private static func dot(_ a: [Float], _ b: [Float]) -> Float { a[0] * b[0] + a[1] * b[1] + a[2] * b[2] }

    private static func normalized(_ v: [Float]) -> [Float]? {
        let length = dot(v, v).squareRoot()
        if !(length > 1e-6) || !length.isFinite { return nil }
        return [v[0] / length, v[1] / length, v[2] / length]
    }
}

private func fitted(_ v: [Float], _ fallback: [Float]) -> [Float] {
    if v.count >= fallback.count { return Array(v.prefix(fallback.count)) }
    return v + fallback.suffix(fallback.count - v.count)
}

/// The Kotlin's getOrNull.
private func arg(_ args: [VsmxValue], _ i: Int) -> VsmxValue {
    i < args.count ? args[i] : .undefined
}
