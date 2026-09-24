package com.armsx2.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.tan

class ThemeSceneTest {

    private val quad = RafScene.Mesh(
        positions = floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, 1f, 1f, 0f, -1f, 1f, 0f),
        uvs = floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f),
        indices = intArrayOf(0, 1, 2, 0, 2, 3),
    )
    private val texture = P3tAnimation.Texture(0x86, 4, 4, 8, 0, 8)
    private val camera = RafScene.Camera(floatArrayOf(0f, 0f, 10f), floatArrayOf(0f, 0f, -1f), floatArrayOf(0f, 1f, 0f), 0.9273f, 0.1f, 100f)

    private fun actor(name: String, mesh: RafScene.Mesh? = quad) = RafScene.Actor(
        name, mesh, RafScene.Material("pure_texture", texture),
        FloatArray(3), FloatArray(3), floatArrayOf(1f, 1f, 1f), floatArrayOf(1f, 1f, 1f, 1f), FloatArray(2), floatArrayOf(1f, 1f),
    )

    private fun play(actors: List<RafScene.Actor> = listOf(actor("a")), script: VsmxAsm.() -> Unit): ThemeScene {
        val code = VsmxAsm().apply(script).apply { end() }.bytes()
        return ThemeScene(RafScene.Scene(actors, camera, code))
    }

    /** `a = new Actor("a"); step = function () { a.position = a.position + [1, 0, 0]; }` */
    private fun VsmxAsm.stepper() {
        assign("a") { global("Actor"); string("a"); new(1) }
        function("step", 0, 1) {
            global("a"); global("a"); get("position"); int(1); int(0); int(0); array(3); op(0x02); set("position"); pop()
        }
    }

    /** `<holder>.timer[slot] = new <kind>(<seconds>, <callback>);` */
    private fun VsmxAsm.timer(holder: String, slot: Int, kind: String, callback: String, seconds: VsmxAsm.() -> Unit) {
        global(holder); get("timer"); int(slot)
        global(kind); seconds(); global(callback); new(2)
        op(0x36); pop()
    }

    private fun VsmxAsm.everyFrame(slot: Int = 0, callback: String = "step") =
        timer("System", slot, "IntervalTimer", callback) { global("System"); get("interval") }

    private fun ThemeScene.x(name: String = "a") = actors.first { it.source.name == name }.position[0]

    private fun ThemeScene.run(frames: Int, seconds: Double = ThemeScene.TICK) = repeat(frames) { advance(seconds) }

    // ---- timers ----

    @Test
    fun aTimerEveryFrameFiresOncePerFrame() {
        val p = play { stepper(); everyFrame() }
        p.run(60)
        assertEquals(60f, p.x())
        assertNull(p.scriptError)
    }

    @Test
    fun framesArrivingEarlyAndLateStillFireOnce() {
        val p = play { stepper(); everyFrame() }
        repeat(30) {
            p.advance(ThemeScene.TICK * 0.9)
            p.advance(ThemeScene.TICK * 1.1)
        }
        assertEquals(60f, p.x())
    }

    @Test
    fun aFasterPanelKeepsTheScriptsPace() {
        val p = play { stepper(); everyFrame() }
        p.run(120, 1.0 / 120)
        assertEquals(60f, p.x())
    }

    @Test
    fun aStallDoesNotReplayItsBacklog() {
        val p = play { stepper(); everyFrame() }
        p.advance(5.0)
        assertTrue(p.x() in 1f..8f)
    }

    @Test
    fun aOneShotFiresOnce() {
        val p = play { stepper(); timer("System", 1, "OneShotTimer", "step") { float(0.5f) } }
        p.run(29)
        assertEquals(0f, p.x())
        p.run(1)
        assertEquals(1f, p.x())
        p.run(120)
        assertEquals(1f, p.x())
    }

    @Test
    fun actorsHoldTimersToo() {
        // PS4 On PS3: install.timer[1] = new OneShotTimer(install_duration, install_position)
        val p = play { stepper(); timer("a", 1, "OneShotTimer", "step") { float(0.1f) } }
        p.run(60)
        assertEquals(1f, p.x())
    }

    @Test
    fun aTimerKeepsRunningWhenItsSlotIsReused() {
        // JUJU7U stores both of its timers in System.timer[3].
        val p = play { stepper(); everyFrame(3); everyFrame(3) }
        p.run(60)
        assertEquals(120f, p.x())
    }

    @Test
    fun aFailingCallbackStopsOnlyItsTimer() {
        val p = play {
            stepper()
            function("bad", 0, 1) { global("nothing"); call(0); pop() }
            everyFrame(0, "bad")
            everyFrame(1, "step")
        }
        p.run(60)
        assertEquals(60f, p.x())
        assertTrue(p.scriptError!!, p.scriptError!!.contains("not a function"))
    }

    @Test
    fun aMissingActorStopsTheSetup() {
        val p = play { assign("b") { global("Actor"); string("missing"); new(1) } }
        p.start()
        assertTrue(p.scriptError!!.contains("no actor"))
    }

    // ---- moves ----

    @Test
    fun movesTakeTheirTime() {
        val p = play {
            assign("a") { global("Actor"); string("a"); new(1) }
            global("a"); getKeep("setPosition"); int(10); int(0); int(0); array(3); float(1f); global("INTERPOLATION_LINEAR"); callMethod(3); pop()
        }
        p.run(30)
        assertEquals(5f, p.x(), 1e-3f)
        p.run(30)
        assertEquals(10f, p.x(), 1e-3f)
        p.run(30)
        assertEquals(10f, p.x(), 1e-3f)
    }

    @Test
    fun bezierMovesEaseInAndOut() {
        val p = play {
            assign("a") { global("Actor"); string("a"); new(1) }
            global("a"); getKeep("setPosition"); int(10); int(0); int(0); array(3); float(1f); global("INTERPOLATION_BEZIER"); callMethod(3); pop()
        }
        p.run(15)
        assertEquals(10f * 0.15625f, p.x(), 1e-3f)
        p.run(15)
        assertEquals(5f, p.x(), 1e-3f)
    }

    @Test
    fun settingAPropertyCancelsItsMove() {
        val p = play {
            assign("a") { global("Actor"); string("a"); new(1) }
            global("a"); getKeep("setPosition"); int(10); int(0); int(0); array(3); float(1f); callMethod(2); pop()
            global("a"); int(-3); int(0); int(0); array(3); set("position"); pop()
        }
        p.run(60)
        assertEquals(-3f, p.x())
    }

    @Test
    fun hiddenActorsAreNotShown() {
        val p = play(listOf(actor("a"), actor("b"), actor("c"), actor("d", mesh = null))) {
            global("Actor"); string("a"); new(1); op(0x25, 0); set("enable"); pop()
            global("Actor"); string("b"); new(1); int(1); int(1); int(1); int(0); array(4); set("color"); pop()
            global("Actor"); string("c"); new(1); int(0); int(0); int(0); array(3); set("scale"); pop()
        }
        p.start()
        assertEquals(listOf(false, false, false, false), p.actors.map { it.shown })
        assertFalse(p.actors[3].drawable)
    }

    @Test
    fun skinnedMeshesAreNotDrawn() {
        val skinned = RafScene.Mesh(quad.positions, quad.uvs, quad.indices, skinned = true)
        val p = play(listOf(actor("a", skinned))) {}
        assertFalse(p.actors[0].drawable)
    }

    @Test
    fun animatesTellsMovingScenesFromStillOnes() {
        assertTrue(ThemeScene.animates(play { stepper(); everyFrame() }.scene))
        // Placed once and never again.
        assertFalse(ThemeScene.animates(play { stepper(); callGlobal("step") }.scene, seconds = 2.0))
        assertFalse(ThemeScene.animates(RafScene.Scene(listOf(actor("a")), camera, null)))
    }

    // ---- the camera ----

    private fun apply(m: FloatArray, x: Float, y: Float, z: Float): FloatArray =
        FloatArray(4) { r -> m[r] * x + m[4 + r] * y + m[8 + r] * z + m[12 + r] }

    @Test
    fun rotationsApplyXThenYThenZ() {
        val m = ThemeScene.modelMatrix(FloatArray(3), floatArrayOf((PI / 2).toFloat(), (PI / 2).toFloat(), 0f), floatArrayOf(1f, 1f, 1f))
        val v = apply(m, 0f, 1f, 0f)
        // X first turns y into z; Y then turns z into x. The other order would leave z.
        assertEquals(1f, v[0], 1e-5f)
        assertEquals(0f, v[1], 1e-5f)
        assertEquals(0f, v[2], 1e-5f)
        val t = ThemeScene.modelMatrix(floatArrayOf(1f, 2f, 3f), floatArrayOf(0f, 0f, (PI / 2).toFloat()), floatArrayOf(2f, 2f, 2f))
        val w = apply(t, 1f, 0f, 0f)
        assertEquals(1f, w[0], 1e-5f)
        assertEquals(4f, w[1], 1e-5f)
        assertEquals(3f, w[2], 1e-5f)
    }

    @Test
    fun theViewLooksAlongTheCameraDirection() {
        val v = ThemeScene.viewMatrix(floatArrayOf(0f, 0f, 10f), floatArrayOf(0f, 0f, -1f), floatArrayOf(0f, 1f, 0f))
        val o = apply(v, 0f, 0f, 0f)
        assertEquals(-10f, o[2], 1e-5f)
        // Fallout NV's camera looks along +y with z up: +x stays to the right, +z up.
        val f = ThemeScene.viewMatrix(floatArrayOf(0f, -229.5f, 3f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f))
        val p = apply(f, 1f, 0f, 4f)
        assertEquals(1f, p[0], 1e-4f)
        assertEquals(1f, p[1], 1e-4f)
        assertEquals(-229.5f, p[2], 1e-3f)
    }

    @Test
    fun otherScreenShapesCropThePicture() {
        val fov = 0.9273f
        val t = tan(fov / 2.0).toFloat()
        fun ndc(aspect: Float, x: Float, y: Float): Pair<Float, Float> {
            val c = apply(ThemeScene.projection(fov, aspect, 0.1f, 100f), x, y, -1f)
            return c[0] / c[3] to c[1] / c[3]
        }
        // 16:9: the picture's corners are the screen's.
        assertEquals(1f, ndc(16f / 9f, t * 16f / 9f, t).first, 1e-4f)
        assertEquals(1f, ndc(16f / 9f, t * 16f / 9f, t).second, 1e-4f)
        // Wider: the sides still meet, the top and bottom are cropped.
        assertEquals(1f, ndc(2f, t * 16f / 9f, 0f).first, 1e-4f)
        assertTrue(ndc(2f, 0f, t).second > 1f)
        // Narrower: the top and bottom meet, the sides are cropped.
        assertEquals(1f, ndc(4f / 3f, 0f, t).second, 1e-4f)
        assertTrue(ndc(4f / 3f, t * 16f / 9f, 0f).first > 1f)
    }

    // ---- real themes ----

    private fun scene(path: String): RafScene.Scene? {
        val theme = P3tTheme.ArrayBytes(File(path).readBytes())
        val anim = P3tTheme.read(theme).anim ?: return null
        val out = ByteArrayOutputStream()
        if (!P3tAnimation.unpack(theme, anim.offset, anim.size, out, 256L shl 20)) return null
        return RafScene.read(P3tTheme.ArrayBytes(out.toByteArray()))
    }

    /** Real dynamic themes, when given: P3T_DYNAMIC=/path/a.p3t,/path/b.p3t */
    @Test
    fun realThemesPlay() {
        val paths = System.getenv("P3T_DYNAMIC")?.split(',')?.filter { File(it).isFile }.orEmpty()
        assumeTrue(paths.isNotEmpty())
        for (path in paths) {
            val name = File(path).name
            val scene = scene(path)
            assertNotNull(name, scene)
            // Math.random() * 8 comes out 5: see the Persona 5 note below.
            val p = ThemeScene(scene!!) { 0.7 }
            p.start()
            val smoke = p.actors.firstOrNull { it.source.name == "Smoke00" }?.position?.get(0)
            val cameraX = p.camera.position[0]
            p.run(60)
            println("$name: ${p.actors.count { it.drawable }} of ${p.actors.size} actors drawable, camera ${p.camera.position.toList()}, script ${p.scriptError ?: "ok"}")
            assertNull(name, p.scriptError)
            assertTrue(name, ThemeScene.animates(scene, random = { 0.7 }))
            when {
                "Fallout" in name -> {
                    // The smoke sheet drifts 19.3/162 a frame.
                    assertEquals(7, p.actors.count { it.drawable })
                    assertEquals(60 * 19.3f / 162, p.x("Smoke00") - smoke!!, 1e-3f)
                }
                "Persona 5" in name -> {
                    // Its first slide is (random - 1) % 8, which is -1 when Math.random() * 8 comes out
                    // 0: as in JavaScript, slide[-1] is undefined and setting its scale stops the script.
                    // One start in eight; the app plays this theme as a slideshow, not through here.
                    val unlucky = ThemeScene(scene) { 0.05 }
                    unlucky.run(60)
                    assertTrue(unlucky.scriptError ?: "no error", unlucky.scriptError?.contains("scale") == true)
                }
                "SARUTHEME" in name -> {
                    // The camera pans 1000 units in 25 seconds, from x = 500.
                    assertEquals(500f, cameraX, 1e-3f)
                    assertEquals(460f, p.camera.position[0], 1e-2f)
                }
            }
        }
    }
}
