package com.armsx2.ui.home

/**
 * Runs a dynamic PS3 theme's script: VSMX, the compiled JavaScript of the PS3's theme engine (and of
 * the PSP's menus before it). A stack machine over 8-byte instructions (opcode, operand).
 *
 * No specification of the PS3 variant was found; the instruction set here was worked out from five
 * real theme scripts (Sony's Ape Escape theme, the Prince of Persia template behind Fallout NV
 * Custom Dynamic, and three slideshow templates) by decompiling them against what they visibly do.
 * An opcode outside that set, a type error, or a runaway loop stops that run of the script -- the
 * top level, or one timer's callback -- rather than guessing; what it already moved stays put.
 *
 * Two details that differ from textbook JavaScript engines, both needed by real scripts:
 *  - 0x30 reads a property but keeps the object under it, for method calls (`o.f(x)`: the object
 *    is `this`) and read-modify-write (`o.p = o.p + [..]`, `o.p[i] += x`).
 *  - Array arithmetic is element-wise: `rotation + [0, Math.PI / 600, 0]`,
 *    `color * [1, 1, 1, 0]`. The theme engine provides it for vectors.
 */
class VsmxVm(private val program: Program, private val host: Host) {

    /** The parsed script: instructions and its three string tables. */
    class Program(val op: IntArray, val arg: IntArray, val names: List<String>, val props: List<String>, val text: List<String>) {
        companion object {
            private const val VSMX = 0x584D5356L
            private const val HEADER_BYTES = 52
            private const val MAX_OPS = 1 shl 16
            private const val MAX_STRINGS = 1 shl 12

            /** Null when [b] is not a VSMX script or its sections do not fit it. */
            fun parse(b: ByteArray): Program? {
                if (b.size < HEADER_BYTES) return null
                fun word(i: Int): Long = le32(b, 4 * i).toLong() and 0xFFFFFFFFL
                if (word(0) != VSMX) return null
                val codeAt = word(2)
                val codeSize = word(3)
                if (codeAt > b.size || codeSize > b.size - codeAt || codeSize / 8 > MAX_OPS) return null
                fun strings(at: Long, size: Long, count: Long, wide: Boolean): List<String>? {
                    if (at > b.size || size > b.size - at || count > MAX_STRINGS) return null
                    val s = String(b, at.toInt(), size.toInt(), if (wide) Charsets.UTF_16LE else Charsets.ISO_8859_1)
                    return s.split('\u0000').take(count.toInt())
                }
                val text = strings(word(4), word(5), word(6), true) ?: return null
                val props = strings(word(7), word(8), word(9), true) ?: return null
                val names = strings(word(10), word(11), word(12), false) ?: return null
                val count = (codeSize / 8).toInt()
                val op = IntArray(count) { le32(b, (codeAt + 8L * it).toInt()) }
                val arg = IntArray(count) { le32(b, (codeAt + 8L * it + 4).toInt()) }
                return Program(op, arg, names, props, text)
            }

            private fun le32(b: ByteArray, at: Int): Int =
                (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
                    ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
        }
    }

    /** What the theme engine provides: global names (Actor, Math, System, ...). */
    interface Host {
        fun global(name: String): Any?
    }

    /** An object the host implements: an actor, the camera, Math, System. */
    interface HostObject {
        fun get(name: String): Any?
        fun set(name: String, value: Any?)
    }

    /** A host function. [self] is the object it was read from, for methods. */
    class Native(val name: String, val call: (self: Any?, args: List<Any?>) -> Any?)

    /** A host constructor, for `new X(...)`. */
    class Constructor(val name: String, val make: (args: List<Any?>) -> Any?)

    /** A script function: its body starts at [start]; [params] fill local slots 1..params. */
    class Function(val start: Int, val params: Int, val locals: Int)

    class JsArray(val items: ArrayList<Any?> = ArrayList()) {
        operator fun get(i: Int): Any? = items.getOrNull(i) ?: Undefined
        operator fun set(i: Int, v: Any?) {
            if (i < 0 || i > MAX_ARRAY) throw VsmxError("array index $i")
            while (items.size <= i) items.add(Undefined)
            items[i] = v
        }
    }

    class JsObject(val props: HashMap<String, Any?> = HashMap())

    object Undefined {
        override fun toString() = "undefined"
    }

    class VsmxError(message: String) : Exception(message) {
        override fun fillInStackTrace(): Throwable = this
    }

    private sealed class Ref {
        class Global(val name: String) : Ref()
        class Local(val frame: Array<Any?>, val slot: Int) : Ref()
    }

    private val globals = HashMap<String, Any?>()
    private var budget = 0
    private var depth = 0

    /** Why the script's top level stopped early, when it did. Timers it started before that still run. */
    var failed: String? = null
        private set

    /** Run the script's top level: it defines its globals and starts its timers. */
    fun run() {
        failed = guard { execute(0, arrayOfNulls(LOCALS), Undefined) }
    }

    /** Call a script function (a timer's callback). Null when it ran to the end, else why it stopped. */
    fun call(fn: Any?, args: List<Any?> = emptyList()): String? = guard { invoke(fn, Undefined, args) }

    private fun guard(block: () -> Unit): String? {
        budget = MAX_STEPS
        depth = 0
        return try {
            block()
            null
        } catch (e: VsmxError) {
            e.message ?: "error"
        } catch (e: RuntimeException) {
            e.toString()
        } catch (e: StackOverflowError) {
            "stack overflow"
        }
    }

    private fun invoke(fn: Any?, self: Any?, args: List<Any?>): Any? = when (fn) {
        is Native -> fn.call(self, args)
        is Function -> {
            if (++depth > MAX_DEPTH) throw VsmxError("call depth")
            try {
                val frame = arrayOfNulls<Any?>(maxOf(fn.locals, fn.params + 1, 1) + 1)
                for (i in 0 until minOf(fn.params, args.size)) frame[1 + i] = args[i]
                execute(fn.start, frame, self)
            } finally {
                depth--
            }
        }
        else -> throw VsmxError("not a function: ${describe(fn)}")
    }

    private fun execute(start: Int, frame: Array<Any?>, self: Any?): Any? {
        val op = program.op
        val arg = program.arg
        val stack = ArrayList<Any?>(32)
        val handlers = ArrayList<Int>()
        fun push(v: Any?) {
            if (stack.size > MAX_STACK) throw VsmxError("stack")
            stack.add(v)
        }
        fun pop(): Any? = if (stack.isEmpty()) throw VsmxError("stack underflow") else stack.removeAt(stack.size - 1)
        fun popValue(): Any? = deref(pop())
        fun peek(): Any? = if (stack.isEmpty()) throw VsmxError("stack underflow") else stack[stack.size - 1]
        fun prop(i: Int): String = program.props.getOrNull(i) ?: throw VsmxError("property $i")
        fun popArgs(n: Int): List<Any?> {
            if (n < 0 || n > stack.size) throw VsmxError("arguments")
            val a = ArrayList<Any?>(n)
            for (i in stack.size - n until stack.size) a.add(deref(stack[i]))
            repeat(n) { stack.removeAt(stack.size - 1) }
            return a
        }

        var pc = start
        while (true) {
            if (pc !in op.indices) throw VsmxError("pc $pc")
            if (--budget < 0) throw VsmxError("step budget")
            val code = op[pc]
            val a = arg[pc]
            pc++
            try {
                when (code and 0xFF) {
                    0x01 -> { val v = popValue(); assign(pop(), v); push(v) }
                    0x02 -> { val b = popValue(); push(add(popValue(), b)) }
                    0x03 -> { val b = popValue(); push(arith(popValue(), b) { x, y -> x - y }) }
                    0x04 -> { val b = popValue(); push(arith(popValue(), b) { x, y -> x * y }) }
                    0x05 -> { val b = popValue(); push(arith(popValue(), b) { x, y -> x / y }) }
                    0x06 -> { val b = popValue(); push(arith(popValue(), b) { x, y -> x % y }) }
                    0x07 -> push(num(popValue()))
                    0x08 -> push(arith(popValue(), -1.0) { x, y -> x * y })
                    0x09 -> push(!truthy(popValue()))
                    0x0a, 0x0b -> { val r = pop(); val v = num(deref(r)) + (if (code and 0xFF == 0x0a) 1 else -1); assign(r, v); push(v) }
                    0x0c, 0x0d -> { val r = pop(); val old = num(deref(r)); assign(r, old + (if (code and 0xFF == 0x0c) 1 else -1)); push(old) }
                    0x0e -> { val b = popValue(); push(equal(popValue(), b)) }
                    0x0f -> { val b = popValue(); push(!equal(popValue(), b)) }
                    0x10 -> { val b = popValue(); push(strictEqual(popValue(), b)) }
                    0x11 -> { val b = popValue(); push(!strictEqual(popValue(), b)) }
                    0x12 -> { val b = popValue(); push(num(popValue()) < num(b)) }
                    0x13 -> { val b = popValue(); push(num(popValue()) <= num(b)) }
                    0x14 -> { val b = popValue(); push(num(popValue()) >= num(b)) }
                    0x15 -> { val b = popValue(); push(num(popValue()) > num(b)) }
                    0x20 -> push(peek())
                    0x21 -> { val b = pop(); val c = pop(); push(b); push(c) }
                    0x22 -> pop()
                    0x23 -> push(Undefined)
                    0x24 -> push(null)
                    0x25 -> push(a != 0)
                    0x26 -> push(a.toDouble())
                    0x27 -> push(Float.fromBits(a).toDouble())
                    0x28 -> push(program.text.getOrNull(a) ?: throw VsmxError("text $a"))
                    0x29 -> push(JsObject())
                    0x2a -> push(Function(a, (code ushr 8) and 0xFF, (code ushr 24) and 0xFF))
                    0x2b -> push(JsArray())
                    0x2d -> push(Ref.Local(frame, a).also { if (a !in frame.indices) throw VsmxError("local $a") })
                    0x2e -> push(Ref.Global(program.names.getOrNull(a) ?: throw VsmxError("name $a")))
                    0x2f -> push(getProperty(popValue(), prop(a)))
                    0x30 -> { val o = popValue(); push(o); push(getProperty(o, prop(a))) }
                    0x31 -> { val v = popValue(); setProperty(popValue(), prop(a), v); push(v) }
                    0x33 -> { val v = popValue(); setProperty(deref(peek()), prop(a), v) }
                    0x34 -> { val i = popValue(); push(index(popValue(), i)) }
                    0x36 -> { val v = popValue(); val i = popValue(); setIndex(popValue(), i, v); push(v) }
                    0x38 -> { val v = popValue(); (deref(peek()) as? JsArray ?: throw VsmxError("push to non-array")).items.add(v) }
                    0x39 -> pc = a
                    0x3a -> if (truthy(popValue())) pc = a
                    0x3b -> if (!truthy(popValue())) pc = a
                    0x3c -> { val args = popArgs(a); push(invoke(popValue(), Undefined, args)) }
                    0x3d -> { val args = popArgs(a); val fn = popValue(); push(invoke(fn, popValue(), args)) }
                    0x3e -> { val args = popArgs(a); push(construct(popValue(), args)) }
                    0x3f -> return popValue()
                    0x41 -> handlers.add(a)
                    0x42 -> if (handlers.isNotEmpty()) handlers.removeAt(handlers.size - 1)
                    0x43 -> pc = a
                    0x44 -> {}
                    0x45 -> return Undefined
                    0x49 -> push(JsArray(ArrayList(popArgs(a))))
                    0x4a -> push(index(popValue(), (code ushr 8).toDouble()))
                    0x4d -> {
                        val v = popValue()
                        val o = popValue()
                        val name = prop(a)
                        val vec = getProperty(o, name) as? JsArray ?: throw VsmxError("$name is not a vector")
                        val copy = JsArray(ArrayList(vec.items))
                        copy[code ushr 8] = v
                        setProperty(o, name, copy)
                        push(v)
                    }
                    else -> throw VsmxError("opcode 0x%02x at %d".format(code and 0xFF, pc - 1))
                }
            } catch (e: VsmxError) {
                // try { } catch (e) { }: the catch block starts just after the jump that skips it.
                if (handlers.isEmpty()) throw e
                pc = handlers.removeAt(handlers.size - 1) + 1
                stack.clear()
                push(e.message ?: "error")
            }
        }
    }

    private fun deref(v: Any?): Any? = when (v) {
        is Ref.Global -> if (globals.containsKey(v.name)) globals[v.name] else host.global(v.name) ?: Undefined
        is Ref.Local -> v.frame[v.slot] ?: Undefined
        else -> v
    }

    private fun assign(target: Any?, v: Any?) {
        when (target) {
            is Ref.Global -> globals[target.name] = v
            is Ref.Local -> target.frame[target.slot] = v
            else -> throw VsmxError("assignment to ${describe(target)}")
        }
    }

    private fun construct(ctor: Any?, args: List<Any?>): Any? = when (ctor) {
        is Constructor -> ctor.make(args)
        else -> throw VsmxError("not a constructor: ${describe(ctor)}")
    }

    private fun getProperty(o: Any?, name: String): Any? = when (o) {
        is JsArray -> when (name) {
            "length" -> o.items.size.toDouble()
            "push" -> Native("push") { self, args -> (self as JsArray).items.addAll(args); self.items.size.toDouble() }
            else -> Undefined
        }
        is JsObject -> if (o.props.containsKey(name)) o.props[name] else Undefined
        is HostObject -> o.get(name)
        is String -> if (name == "length") o.length.toDouble() else Undefined
        null, Undefined -> throw VsmxError("property $name of ${describe(o)}")
        else -> Undefined
    }

    private fun setProperty(o: Any?, name: String, v: Any?) {
        when (o) {
            is JsObject -> o.props[name] = v
            is HostObject -> o.set(name, v)
            else -> throw VsmxError("set $name on ${describe(o)}")
        }
    }

    private fun index(o: Any?, i: Any?): Any? = when (o) {
        is JsArray -> o[num(i).toInt()]
        is JsObject -> o.props[str(i)] ?: Undefined
        is HostObject -> o.get(str(i))
        is String -> o.getOrNull(num(i).toInt())?.toString() ?: Undefined
        else -> throw VsmxError("index of ${describe(o)}")
    }

    private fun setIndex(o: Any?, i: Any?, v: Any?) {
        when (o) {
            is JsArray -> o[num(i).toInt()] = v
            is JsObject -> o.props[str(i)] = v
            is HostObject -> o.set(str(i), v)
            else -> throw VsmxError("index set on ${describe(o)}")
        }
    }

    companion object {
        private const val MAX_STEPS = 200_000
        private const val MAX_STACK = 4096
        private const val MAX_DEPTH = 64
        private const val MAX_ARRAY = 1 shl 16
        private const val LOCALS = 16

        fun num(v: Any?): Double = when (v) {
            is Double -> v
            is Number -> v.toDouble()
            is Boolean -> if (v) 1.0 else 0.0
            null -> 0.0
            is String -> v.trim().toDoubleOrNull() ?: Double.NaN
            else -> Double.NaN
        }

        fun truthy(v: Any?): Boolean = when (v) {
            null, Undefined -> false
            is Boolean -> v
            is Double -> v != 0.0 && !v.isNaN()
            is String -> v.isNotEmpty()
            else -> true
        }

        fun str(v: Any?): String = when (v) {
            is Double -> if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
            null -> "null"
            is JsArray -> v.items.joinToString(",") { str(it) }
            else -> v.toString()
        }

        private fun describe(v: Any?): String = when (v) {
            null -> "null"
            Undefined -> "undefined"
            is Double -> "number"
            is String -> "string"
            is JsArray -> "array"
            is Native -> "function ${v.name}"
            else -> v.javaClass.simpleName
        }

        private fun add(a: Any?, b: Any?): Any? =
            if (a is String || b is String) str(a) + str(b) else arith(a, b) { x, y -> x + y }

        private fun arith(a: Any?, b: Any?, f: (Double, Double) -> Double): Any? = when {
            a is JsArray && b is JsArray -> JsArray(ArrayList(List(minOf(a.items.size, b.items.size)) { f(num(a.items[it]), num(b.items[it])) }))
            a is JsArray -> JsArray(ArrayList(a.items.map { f(num(it), num(b)) }))
            b is JsArray -> JsArray(ArrayList(b.items.map { f(num(a), num(it)) }))
            else -> f(num(a), num(b))
        }

        fun equal(a: Any?, b: Any?): Boolean = when {
            (a == null || a === Undefined) && (b == null || b === Undefined) -> true
            a == null || a === Undefined || b == null || b === Undefined -> false
            a is JsArray && b is JsArray -> a.items.size == b.items.size && a.items.indices.all { equal(a.items[it], b.items[it]) }
            a is String && b is String -> a == b
            a is Double || b is Double || a is Boolean || b is Boolean -> num(a) == num(b)
            else -> a === b
        }

        private fun strictEqual(a: Any?, b: Any?): Boolean = when {
            a is Double && b is Double -> a == b
            a is String && b is String -> a == b
            a is Boolean && b is Boolean -> a == b
            else -> a === b
        }
    }
}
