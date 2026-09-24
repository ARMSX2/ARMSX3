package com.armsx2.ui.home

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A dynamic PS3 theme in motion: the scene's actors and camera as its script moves them. Pure
 * Kotlin, driven by whoever draws it ([ThemeSceneRenderer]) through [advance].
 *
 * What the script is given, as the PS3's theme engine gives it (worked out from the real scripts
 * described in [VsmxVm]):
 *  - `new Actor(name)`, `new Camera(name)`, `new Light(name)`: the scene's own objects, with
 *    vector properties (an actor's position, rotation, scale, color, uv_offset, uv_scale and
 *    enable; the camera's position, direction and up) and methods that move one there over a
 *    time: `setPosition(v)`, `setColor(v, seconds)`, `setPosition(v, seconds, INTERPOLATION_BEZIER)`.
 *    Script vectors are the scene's own coordinates.
 *  - `new IntervalTimer(seconds, fn)` and `new OneShotTimer(seconds, fn)`. A timer runs from when it
 *    is made; the `timer` arrays on System and on each actor (`install.timer[1] = ...`) only hold
 *    them. JUJU's theme stores both of its timers in System.timer[3], and replacing the first
 *    would stop its slideshow after one picture.
 *  - `System.interval`, one frame's time; `Math`; `Array`; `writeln`; `INTERPOLATION_LINEAR` and
 *    `INTERPOLATION_BEZIER`.
 * A callback that fails stops its own timer; the rest of the scene keeps going. [random] is what
 * Math.random returns, for tests.
 */
class ThemeScene(val scene: RafScene.Scene, private val random: () -> Double = { Math.random() }) {

    /** A property on its way from [from] to [to] over [duration] seconds. */
    private class Tween(val target: FloatArray, val from: FloatArray, val to: FloatArray, val duration: Double, val eased: Boolean) {
        var elapsed = 0.0
    }

    /** An object whose vectors a script can set at once or move over time. */
    abstract inner class Movable : VsmxVm.HostObject {
        private val tweens = HashMap<String, Tween>(4)
        private val timers = TimerSlots()

        /** The vector property [name], or null when there is none by that name. */
        protected abstract fun vector(name: String): FloatArray?

        /** The property a method such as "setPosition" moves, when it is one. */
        protected abstract fun mover(name: String): String?

        override fun get(name: String): Any? {
            vector(name)?.let { return toArray(it) }
            if (name == "timer") return timers
            val target = mover(name) ?: return VsmxVm.Undefined
            return VsmxVm.Native(name) { _, args -> move(target, args); VsmxVm.Undefined }
        }

        override fun set(name: String, value: Any?) {
            val v = vector(name) ?: return
            tweens.remove(name)
            assign(v, value)
        }

        /** `setX(to, seconds, interpolation)`: at once without a time, otherwise over it. */
        private fun move(name: String, args: List<Any?>) {
            val v = vector(name) ?: return
            val to = v.copyOf()
            if (!assign(to, args.getOrNull(0))) return
            val seconds = VsmxVm.num(args.getOrNull(1))
            if (seconds.isNaN() || seconds <= 0.0) {
                tweens.remove(name)
                to.copyInto(v)
            } else {
                tweens[name] = Tween(v, v.copyOf(), to, seconds, VsmxVm.num(args.getOrNull(2)) == BEZIER)
            }
        }

        internal fun step(dt: Double) {
            if (tweens.isEmpty()) return
            val it = tweens.values.iterator()
            while (it.hasNext()) {
                val t = it.next()
                t.elapsed += dt
                val x = (t.elapsed / t.duration).coerceIn(0.0, 1.0)
                val k = if (t.eased) x * x * (3 - 2 * x) else x
                for (i in t.target.indices) t.target[i] = (t.from[i] + (t.to[i] - t.from[i]) * k).toFloat()
                if (x >= 1.0) it.remove()
            }
        }
    }

    inner class ActorState(val source: RafScene.Actor) : Movable() {
        val position = source.position.copyOf()
        val rotation = source.rotation.copyOf()
        val scale = source.scale.copyOf()
        val color = source.color.copyOf()
        val uvOffset = source.uvOffset.copyOf()
        val uvScale = source.uvScale.copyOf()
        var enabled = true
            private set

        /**
         * Whether there is anything to draw. Not a mesh bent by a skeleton (skeletal animation is
         * not decoded, and the bind pose is a T-pose), and not one its author collapsed to hide it.
         */
        val drawable: Boolean = source.mesh != null && !source.mesh.skinned &&
            source.material?.texture != null && source.mesh.area() > MIN_AREA

        /** Drawn this frame: [drawable], enabled, and neither see-through nor scaled to nothing. */
        val shown: Boolean
            get() = drawable && enabled && color[3] > 0f && (scale[0] != 0f || scale[1] != 0f || scale[2] != 0f)

        override fun vector(name: String): FloatArray? = when (name) {
            "position" -> position
            "rotation" -> rotation
            "scale" -> scale
            "color" -> color
            "uv_offset" -> uvOffset
            "uv_scale" -> uvScale
            else -> null
        }

        override fun mover(name: String): String? = when (name) {
            "setPosition" -> "position"
            "setRotation" -> "rotation"
            "setScale" -> "scale"
            "setColor" -> "color"
            else -> null
        }

        override fun get(name: String): Any? = when (name) {
            "enable" -> enabled
            // Fallout NV's template turns two actors toward a point every frame. What that does to
            // an actor is not known, and both are hidden in every theme seen, so it does nothing.
            "setDirection" -> VsmxVm.Native(name) { _, _ -> VsmxVm.Undefined }
            else -> super.get(name)
        }

        override fun set(name: String, value: Any?) {
            if (name == "enable") enabled = VsmxVm.truthy(value) else super.set(name, value)
        }
    }

    inner class CameraState : Movable() {
        val position = scene.camera.position.copyOf()
        val direction = scene.camera.direction.copyOf()
        val up = scene.camera.up.copyOf()
        var yfov = scene.camera.yfov
            private set

        override fun vector(name: String): FloatArray? = when (name) {
            "position" -> position
            "direction" -> direction
            "up" -> up
            else -> null
        }

        override fun mover(name: String): String? = when (name) {
            "setPosition" -> "position"
            "setDirection" -> "direction"
            "setUp" -> "up"
            else -> null
        }

        override fun get(name: String): Any? = if (name == "yfov") yfov.toDouble() else super.get(name)

        override fun set(name: String, value: Any?) {
            if (name == "yfov") {
                val v = VsmxVm.num(value).toFloat()
                if (v > MIN_FOV && v < MAX_FOV) yfov = v
            } else {
                super.set(name, value)
            }
        }
    }

    /** Lights are kept for the scripts that read and move them; drawing is unlit. */
    inner class LightState : Movable() {
        private val position = FloatArray(3)
        private val direction = floatArrayOf(0f, 0f, -1f)
        private val color = floatArrayOf(1f, 1f, 1f, 1f)

        override fun vector(name: String): FloatArray? = when (name) {
            "position" -> position
            "direction" -> direction
            "color" -> color
            else -> null
        }

        override fun mover(name: String): String? = when (name) {
            "setPosition" -> "position"
            "setDirection" -> "direction"
            "setColor" -> "color"
            else -> null
        }
    }

    private class Timer(val interval: Double, val fn: Any?, val repeat: Boolean) : VsmxVm.HostObject {
        var due = interval
        var dead = false
        override fun get(name: String): Any? = if (name == "interval") interval else VsmxVm.Undefined
        override fun set(name: String, value: Any?) {}
    }

    /** A `timer` array: where a script keeps its timers. */
    private class TimerSlots : VsmxVm.HostObject {
        private val slots = HashMap<String, Any?>()
        override fun get(name: String): Any? = slots[name] ?: VsmxVm.Undefined
        override fun set(name: String, value: Any?) {
            if (slots.size < MAX_TIMERS || name in slots) slots[name] = value
        }
    }

    val actors: List<ActorState> = scene.actors.map { ActorState(it) }
    val camera = CameraState()
    private val lights = HashMap<String, LightState>()
    private val timers = ArrayList<Timer>()
    private val byName = actors.associateBy { it.source.name }

    private val system = object : VsmxVm.HostObject {
        private val slots = TimerSlots()
        override fun get(name: String): Any? = when (name) {
            "timer" -> slots
            "interval" -> TICK
            else -> VsmxVm.Undefined
        }
        override fun set(name: String, value: Any?) {}
    }

    private val math = object : VsmxVm.HostObject {
        private fun f(name: String, op: (Double, Double) -> Double) =
            VsmxVm.Native(name) { _, args -> op(VsmxVm.num(args.getOrElse(0) { Double.NaN }), VsmxVm.num(args.getOrElse(1) { Double.NaN })) }
        override fun get(name: String): Any? = when (name) {
            "PI" -> PI
            "E" -> Math.E
            "floor" -> f(name) { x, _ -> Math.floor(x) }
            "ceil" -> f(name) { x, _ -> Math.ceil(x) }
            "round" -> f(name) { x, _ -> Math.floor(x + 0.5) }
            "abs" -> f(name) { x, _ -> Math.abs(x) }
            "sin" -> f(name) { x, _ -> sin(x) }
            "cos" -> f(name) { x, _ -> cos(x) }
            "tan" -> f(name) { x, _ -> tan(x) }
            "asin" -> f(name) { x, _ -> Math.asin(x) }
            "acos" -> f(name) { x, _ -> Math.acos(x) }
            "atan" -> f(name) { x, _ -> Math.atan(x) }
            "atan2" -> f(name) { y, x -> Math.atan2(y, x) }
            "sqrt" -> f(name) { x, _ -> sqrt(x) }
            "pow" -> f(name) { x, y -> Math.pow(x, y) }
            "exp" -> f(name) { x, _ -> Math.exp(x) }
            "log" -> f(name) { x, _ -> Math.log(x) }
            "min" -> VsmxVm.Native(name) { _, args -> args.minOfOrNull { VsmxVm.num(it) } ?: Double.POSITIVE_INFINITY }
            "max" -> VsmxVm.Native(name) { _, args -> args.maxOfOrNull { VsmxVm.num(it) } ?: Double.NEGATIVE_INFINITY }
            "random" -> VsmxVm.Native(name) { _, _ -> random() }
            else -> VsmxVm.Undefined
        }
        override fun set(name: String, value: Any?) {}
    }

    private fun timer(args: List<Any?>, repeat: Boolean): Timer {
        if (timers.size >= MAX_TIMERS) throw VsmxVm.VsmxError("too many timers")
        val seconds = VsmxVm.num(args.getOrNull(0)).takeIf { !it.isNaN() } ?: 0.0
        return Timer(if (repeat) seconds.coerceAtLeast(MIN_INTERVAL) else seconds.coerceAtLeast(0.0), args.getOrNull(1), repeat)
            .also { timers += it }
    }

    private val host = object : VsmxVm.Host {
        override fun global(name: String): Any? = when (name) {
            "Actor" -> VsmxVm.Constructor(name) { args ->
                val id = VsmxVm.str(args.getOrNull(0))
                byName[id] ?: throw VsmxVm.VsmxError("no actor $id")
            }
            "Camera" -> VsmxVm.Constructor(name) { camera }
            "Light" -> VsmxVm.Constructor(name) { args ->
                val id = VsmxVm.str(args.getOrNull(0))
                lights[id] ?: if (lights.size < MAX_LIGHTS) LightState().also { lights[id] = it } else throw VsmxVm.VsmxError("too many lights")
            }
            "Array" -> VsmxVm.Constructor(name) { args ->
                val size = args.singleOrNull() as? Double
                if (size != null) {
                    if (size < 0 || size > MAX_ARRAY) throw VsmxVm.VsmxError("array size")
                    VsmxVm.JsArray(ArrayList<Any?>(List(size.toInt()) { VsmxVm.Undefined }))
                } else {
                    VsmxVm.JsArray(ArrayList(args))
                }
            }
            "IntervalTimer" -> VsmxVm.Constructor(name) { args -> timer(args, repeat = true) }
            "OneShotTimer" -> VsmxVm.Constructor(name) { args -> timer(args, repeat = false) }
            "System" -> system
            "Math" -> math
            "writeln" -> VsmxVm.Native(name) { _, _ -> VsmxVm.Undefined }
            "INTERPOLATION_LINEAR" -> LINEAR
            "INTERPOLATION_BEZIER" -> BEZIER
            else -> null
        }
    }

    private val vm: VsmxVm? = scene.script?.let(VsmxVm.Program::parse)?.let { VsmxVm(it, host) }

    /** Whether there is a script to run at all. */
    val scripted: Boolean get() = vm != null

    /** The last reason a part of the script stopped, for the log. */
    var scriptError: String? = null
        private set

    private var started = false

    /** Run the script's top level, once: it places things and makes its timers. */
    fun start() {
        if (started) return
        started = true
        vm?.run()
        scriptError = vm?.failed
    }

    /** Move time on by [seconds]: moves in progress go on, then due timers fire. */
    fun advance(seconds: Double) {
        start()
        val dt = if (seconds.isNaN()) 0.0 else seconds.coerceIn(0.0, MAX_STEP)
        for (a in actors) a.step(dt)
        camera.step(dt)
        for (l in lights.values) l.step(dt)
        // A snapshot: timers made by these callbacks start counting next time.
        for (timer in timers.toTypedArray()) {
            if (timer.dead) continue
            timer.due -= dt
            // Up to a quarter frame early, so a timer firing every frame stays locked to frames
            // that arrive a little early or late instead of skipping one and doubling the next.
            val slack = SLACK * minOf(timer.interval, TICK)
            var fired = 0
            while (!timer.dead && timer.due <= slack) {
                if (fired == MAX_CATCH_UP) {
                    timer.due = timer.interval // after a stall: drop the backlog, keep the rhythm
                    break
                }
                fired++
                if (timer.repeat) timer.due += timer.interval else timer.dead = true
                vm?.call(timer.fn)?.let {
                    timer.dead = true
                    scriptError = it
                }
            }
        }
        timers.removeAll { it.dead }
    }

    /** Everything that decides what a frame looks like, to tell whether the script moved it. */
    private fun snapshot(): FloatArray {
        val out = ArrayList<Float>()
        for (a in actors) {
            if (!a.drawable) continue
            out += if (a.enabled) 1f else 0f
            for (v in arrayOf(a.position, a.rotation, a.scale, a.color, a.uvOffset, a.uvScale)) v.forEach { out += it }
        }
        for (v in arrayOf(camera.position, camera.direction, camera.up)) v.forEach { out += it }
        out += camera.yfov
        return out.toFloatArray()
    }

    companion object {
        /** A frame of the theme engine's, as scripts read it from System.interval. */
        const val TICK = 1.0 / 60

        /** The shape of the PS3's picture, which scenes are composed for. */
        const val FRAME_ASPECT = 16f / 9f

        private const val LINEAR = 0.0
        private const val BEZIER = 1.0
        private const val MAX_STEP = 0.25
        private const val MAX_CATCH_UP = 8
        private const val SLACK = 0.25
        private const val MIN_INTERVAL = 1.0 / 240
        private const val MAX_TIMERS = 64
        private const val MAX_LIGHTS = 16
        private const val MAX_ARRAY = 1 shl 16
        private const val MIN_AREA = 1e-6
        private const val MIN_FOV = 0.01f
        private const val MAX_FOV = 3.1f

        /**
         * Whether [scene]'s script moves the camera or anything drawn within [seconds]. A theme whose
         * script only sets things up once is as good as a picture.
         */
        fun animates(scene: RafScene.Scene, seconds: Double = 30.0, random: () -> Double = { Math.random() }): Boolean {
            val play = ThemeScene(scene, random)
            if (!play.scripted || play.actors.none { it.drawable }) return false
            play.start()
            val first = play.snapshot()
            var t = 0.0
            var ticks = 0
            while (t < seconds) {
                play.advance(TICK)
                t += TICK
                if (++ticks % 15 == 0 && !play.snapshot().contentEquals(first)) return true
            }
            return !play.snapshot().contentEquals(first)
        }

        fun toArray(v: FloatArray) = VsmxVm.JsArray(ArrayList<Any?>(v.map { it.toDouble() }))

        /**
         * Copy a script array's numbers into [target], as many as both have: a shorter array leaves
         * the rest as it was. False, changing nothing, when [value] is not an array of numbers.
         */
        fun assign(target: FloatArray, value: Any?): Boolean {
            val items = (value as? VsmxVm.JsArray)?.items ?: return false
            val n = minOf(target.size, items.size)
            if (n == 0) return false
            val v = FloatArray(n) { VsmxVm.num(items[it]).toFloat() }
            if (v.any { !it.isFinite() }) return false
            v.copyInto(target)
            return true
        }

        /**
         * T * Rz * Ry * Rx * S, column-major for GL: rotations apply X, then Y, then Z. Checked
         * against PS4 On PS3, whose slide (rotation pi/2, 0, -pi, negative scale) only comes out
         * upright and unmirrored in this order.
         */
        fun modelMatrix(p: FloatArray, r: FloatArray, s: FloatArray): FloatArray {
            val cx = cos(r[0].toDouble()); val sx = sin(r[0].toDouble())
            val cy = cos(r[1].toDouble()); val sy = sin(r[1].toDouble())
            val cz = cos(r[2].toDouble()); val sz = sin(r[2].toDouble())
            val r00 = cz * cy; val r01 = cz * sy * sx - sz * cx; val r02 = cz * sy * cx + sz * sx
            val r10 = sz * cy; val r11 = sz * sy * sx + cz * cx; val r12 = sz * sy * cx - cz * sx
            val r20 = -sy; val r21 = cy * sx; val r22 = cy * cx
            return floatArrayOf(
                (r00 * s[0]).toFloat(), (r10 * s[0]).toFloat(), (r20 * s[0]).toFloat(), 0f,
                (r01 * s[1]).toFloat(), (r11 * s[1]).toFloat(), (r21 * s[1]).toFloat(), 0f,
                (r02 * s[2]).toFloat(), (r12 * s[2]).toFloat(), (r22 * s[2]).toFloat(), 0f,
                p[0], p[1], p[2], 1f,
            )
        }

        /** Looking from [eye] along [direction], as gluLookAt; column-major. */
        fun viewMatrix(eye: FloatArray, direction: FloatArray, up: FloatArray): FloatArray {
            val f = normalized(direction) ?: floatArrayOf(0f, 0f, -1f)
            val s = normalized(cross(f, up)) ?: normalized(cross(f, floatArrayOf(0f, 0f, 1f)))
                ?: normalized(cross(f, floatArrayOf(0f, 1f, 0f)))!!
            val u = cross(s, f)
            return floatArrayOf(
                s[0], u[0], -f[0], 0f,
                s[1], u[1], -f[1], 0f,
                s[2], u[2], -f[2], 0f,
                -dot(s, eye), -dot(u, eye), dot(f, eye), 1f,
            )
        }

        /**
         * Perspective for a view [aspect] wide. The scene is composed for a 16:9 picture with
         * vertical field [yfov]; other shapes get that picture cropped to fill them, as a photo is,
         * never the edges of the set beyond it.
         */
        fun projection(yfov: Float, aspect: Float, near: Float, far: Float): FloatArray {
            val tanHalf = tan(yfov / 2.0) * minOf(1.0, FRAME_ASPECT / aspect.toDouble())
            val f = (1 / tanHalf).toFloat()
            return floatArrayOf(
                f / aspect, 0f, 0f, 0f,
                0f, f, 0f, 0f,
                0f, 0f, (far + near) / (near - far), -1f,
                0f, 0f, 2 * far * near / (near - far), 0f,
            )
        }

        /** a * b, both column-major 4x4. */
        fun multiply(a: FloatArray, b: FloatArray): FloatArray {
            val out = FloatArray(16)
            for (c in 0 until 4) for (r in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[k * 4 + r] * b[c * 4 + k]
                out[c * 4 + r] = sum
            }
            return out
        }

        private fun cross(a: FloatArray, b: FloatArray) =
            floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

        private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

        private fun normalized(v: FloatArray): FloatArray? {
            val length = sqrt(dot(v, v))
            if (!(length > 1e-6f) || !length.isFinite()) return null
            return floatArrayOf(v[0] / length, v[1] / length, v[2] / length)
        }
    }
}
