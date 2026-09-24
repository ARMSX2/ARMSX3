package com.armsx2.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VsmxVmTest {

    /** Runs [script]'s top level with `report(x)` collecting what it reports. */
    private fun run(script: VsmxAsm.() -> Unit): Pair<VsmxVm, List<Any?>> {
        val reported = ArrayList<Any?>()
        val host = object : VsmxVm.Host {
            override fun global(name: String): Any? = when (name) {
                "report" -> VsmxVm.Native(name) { _, args -> reported += args.firstOrNull(); VsmxVm.Undefined }
                else -> null
            }
        }
        val asm = VsmxAsm().apply(script)
        asm.end()
        val vm = VsmxVm(VsmxVm.Program.parse(asm.bytes())!!, host)
        vm.run()
        return vm to reported
    }

    private fun items(v: Any?) = (v as VsmxVm.JsArray).items.map { VsmxVm.num(it) }

    @Test
    fun arithmeticFollowsTheStack() {
        val (vm, out) = run {
            callGlobal("report", { int(2); int(3); int(4); op(0x04); op(0x02) })          // 2 + 3 * 4
            callGlobal("report", { int(7); int(2); op(0x06) })                             // 7 % 2
            callGlobal("report", { float(1.5f); op(0x08) })                                // -1.5
            callGlobal("report", { int(3); int(5); op(0x12) })                             // 3 < 5
        }
        assertNull(vm.failed)
        assertEquals(listOf(14.0, 1.0, -1.5, true), out)
    }

    @Test
    fun arraysAreVectors() {
        val (vm, out) = run {
            callGlobal("report", { int(1); int(2); int(3); array(3); int(10); int(20); int(30); array(3); op(0x02) })
            callGlobal("report", { int(1); int(2); array(2); int(2); op(0x04) })
            callGlobal("report", { string("a"); int(1); op(0x02) })
        }
        assertNull(vm.failed)
        assertEquals(listOf(11.0, 22.0, 33.0), items(out[0]))
        assertEquals(listOf(2.0, 4.0), items(out[1]))
        assertEquals("a1", out[2])
    }

    @Test
    fun readModifyWriteKeepsTheObject() {
        // o = {}; o.p = [1, 2, 3]; o.p[0] += 5; o.n = 5; o.n = o.n + 1; report(o.p); report(o.n)
        val (vm, out) = run {
            assign("o") { op(0x29) }
            global("o"); int(1); int(2); int(3); array(3); set("p"); pop()
            global("o"); getKeep("p"); elementGet(0); int(5); op(0x02); elementSet("p", 0); pop()
            global("o"); int(5); set("n"); pop()
            global("o"); global("o"); get("n"); int(1); op(0x02); set("n"); pop()
            callGlobal("report", { global("o"); get("p") })
            callGlobal("report", { global("o"); get("n") })
        }
        assertNull(vm.failed)
        assertEquals(listOf(6.0, 2.0, 3.0), items(out[0]))
        assertEquals(6.0, out[1])
    }

    @Test
    fun functionsTakeParametersInLocals() {
        val (vm, out) = run {
            function("add", params = 2, locals = 3) { local(1); local(2); op(0x02); op(0x3F) }
            callGlobal("report", { global("add"); int(2); int(5); call(2) })
        }
        assertNull(vm.failed)
        assertEquals(listOf(7.0), out)
    }

    @Test
    fun methodsSeeTheirObject() {
        // o = { v: 4, twice: function () { return this.v * 2 } } is not how the host does it; a host
        // Native gets the object it was read from.
        val host = object : VsmxVm.Host {
            val reported = ArrayList<Any?>()
            val obj = object : VsmxVm.HostObject {
                override fun get(name: String): Any? = when (name) {
                    "v" -> 4.0
                    "twice" -> VsmxVm.Native(name) { self, _ -> VsmxVm.num((self as VsmxVm.HostObject).get("v")) * 2 }
                    else -> VsmxVm.Undefined
                }
                override fun set(name: String, value: Any?) {}
            }
            override fun global(name: String): Any? = when (name) {
                "obj" -> obj
                "report" -> VsmxVm.Native(name) { _, args -> reported += args.firstOrNull(); VsmxVm.Undefined }
                else -> null
            }
        }
        val asm = VsmxAsm().apply {
            global("report"); global("obj"); getKeep("twice"); callMethod(0); call(1); pop()
            end()
        }
        VsmxVm(VsmxVm.Program.parse(asm.bytes())!!, host).run()
        assertEquals(listOf(8.0), host.reported)
    }

    @Test
    fun catchGetsTheError() {
        // try { nothing(); } catch (e) { report(e); }
        val (vm, out) = run {
            val tryAt = op(0x41)
            global("nothing"); call(0); pop()
            op(0x42)
            val skip = op(0x43)
            patch(tryAt, skip)
            global("e"); op(0x21); op(0x01); pop()
            callGlobal("report", { global("e") })
            patch(skip, op(0x44))
        }
        assertNull(vm.failed)
        assertEquals(1, out.size)
        assertTrue(out[0].toString(), out[0].toString().contains("not a function"))
    }

    @Test
    fun runawayLoopsStop() {
        val (vm, _) = run { op(0x39, 0) }
        assertEquals("step budget", vm.failed)
    }

    @Test
    fun unknownOpcodesStop() {
        val (vm, out) = run {
            callGlobal("report", { int(1) })
            op(0x7F)
            callGlobal("report", { int(2) })
        }
        assertTrue(vm.failed!!.contains("opcode"))
        assertEquals(listOf(1.0), out)
    }

    @Test
    fun aFailingCallDoesNotStopTheScript() {
        val reported = ArrayList<Any?>()
        var bad: Any? = null
        var good: Any? = null
        // `keep(bad, good)` hands the two functions over, the way a timer holds its callback.
        val host = object : VsmxVm.Host {
            override fun global(name: String): Any? = when (name) {
                "keep" -> VsmxVm.Native(name) { _, args -> bad = args[0]; good = args[1]; VsmxVm.Undefined }
                "report" -> VsmxVm.Native(name) { _, args -> reported += args.firstOrNull(); VsmxVm.Undefined }
                else -> null
            }
        }
        val asm = VsmxAsm().apply {
            function("bad", 0, 1) { global("nothing"); call(0); pop() }
            function("good", 0, 1) { callGlobal("report", { int(1) }) }
            callGlobal("keep", { global("bad") }, { global("good") })
            end()
        }
        val vm = VsmxVm(VsmxVm.Program.parse(asm.bytes())!!, host)
        vm.run()
        assertNotNull(vm.call(bad))
        assertNull(vm.call(good))
        assertNull(vm.call(good))
        assertNull(vm.failed)
        assertEquals(listOf(1.0, 1.0), reported)
    }

    @Test
    fun parseRejectsWhatIsNotAScript() {
        assertNull(VsmxVm.Program.parse(ByteArray(10)))
        assertNull(VsmxVm.Program.parse(ByteArray(64)))
        val good = VsmxAsm().apply { end() }.bytes()
        assertNotNull(VsmxVm.Program.parse(good))
        // A code section running past the end.
        val bad = good.copyOf()
        java.nio.ByteBuffer.wrap(bad).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(12, 1 shl 20)
        assertNull(VsmxVm.Program.parse(bad))
    }
}
