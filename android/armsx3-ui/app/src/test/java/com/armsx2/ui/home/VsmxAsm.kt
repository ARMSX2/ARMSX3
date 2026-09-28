package com.armsx2.ui.home

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Writes VSMX scripts for tests: instructions, with the name, property and text tables built as used. */
internal class VsmxAsm {
    private val names = ArrayList<String>()
    private val props = ArrayList<String>()
    private val text = ArrayList<String>()
    private val code = ArrayList<IntArray>()

    /** Where the next instruction goes, for jumps. */
    val here: Int get() = code.size

    fun op(op: Int, arg: Int = 0): Int {
        code += intArrayOf(op, arg)
        return code.size - 1
    }

    /** Point the jump or function at [at] to [target]. */
    fun patch(at: Int, target: Int) {
        code[at][1] = target
    }

    fun global(name: String) = op(0x2E, index(names, name))
    fun local(slot: Int) = op(0x2D, slot)
    fun int(v: Int) = op(0x26, v)
    fun float(v: Float) = op(0x27, v.toRawBits())
    fun string(s: String) = op(0x28, index(text, s))
    fun get(prop: String) = op(0x2F, index(props, prop))
    fun getKeep(prop: String) = op(0x30, index(props, prop))
    fun set(prop: String) = op(0x31, index(props, prop))
    fun literalSet(prop: String) = op(0x33, index(props, prop))
    fun elementGet(i: Int) = op(0x4A or (i shl 8))
    fun elementSet(prop: String, i: Int) = op(0x4D or (i shl 8), index(props, prop))
    fun call(argc: Int) = op(0x3C, argc)
    fun callMethod(argc: Int) = op(0x3D, argc)
    fun new(argc: Int) = op(0x3E, argc)
    fun array(n: Int) = op(0x49, n)
    fun pop() = op(0x22)
    fun end() = op(0x45)

    /** `name = <value>;` with [value] pushing the value. */
    fun assign(name: String, value: VsmxAsm.() -> Unit) {
        global(name)
        value()
        op(0x01)
        pop()
    }

    /** `name(args...);` */
    fun callGlobal(name: String, vararg args: VsmxAsm.() -> Unit) {
        global(name)
        args.forEach { it() }
        call(args.size)
        pop()
    }

    /**
     * `name = function (params) { body }`: the function is skipped over where it is defined, as the
     * compiler lays it out. Locals 1..params hold the arguments.
     */
    fun function(name: String, params: Int, locals: Int, body: VsmxAsm.() -> Unit) {
        global(name)
        val fn = op(0x2A or (params shl 8) or (locals shl 24))
        op(0x01)
        pop()
        val skip = op(0x39)
        patch(fn, here)
        body()
        op(0x23)
        op(0x3F)
        patch(skip, here)
    }

    fun bytes(): ByteArray {
        val textBytes = text.joinToString("") { it + "\u0000" }.toByteArray(Charsets.UTF_16LE)
        val propBytes = props.joinToString("") { it + "\u0000" }.toByteArray(Charsets.UTF_16LE)
        val nameBytes = names.joinToString("") { it + "\u0000" }.toByteArray(Charsets.ISO_8859_1)
        val codeAt = 52
        val textAt = codeAt + code.size * 8
        val propAt = textAt + textBytes.size
        val nameAt = propAt + propBytes.size
        val b = ByteBuffer.allocate(nameAt + nameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("VSMX".toByteArray()).putInt(0x20000).putInt(codeAt).putInt(code.size * 8)
        b.putInt(textAt).putInt(textBytes.size).putInt(text.size)
        b.putInt(propAt).putInt(propBytes.size).putInt(props.size)
        b.putInt(nameAt).putInt(nameBytes.size).putInt(names.size)
        code.forEach { b.putInt(it[0]).putInt(it[1]) }
        b.put(textBytes).put(propBytes).put(nameBytes)
        return b.array()
    }

    private fun index(table: ArrayList<String>, s: String): Int {
        val i = table.indexOf(s)
        if (i >= 0) return i
        table += s
        return table.size - 1
    }
}
