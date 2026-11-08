#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""ThemeScene.swift, run on the Mac host with scripts assembled here, steps a live theme's scene frame by frame as
Android's ThemeScene.kt does, gives scripts System.printPerf and Date, stops a hostile script within its step budget
and import deadline, and maps GL depth into Metal's clip space. It says nothing about real themes or a device."""

import json
import re
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from test_param_sfo import read
from source_helpers import brace_span, without_comments
from theme_fixtures import VsmxAsm

IOS = Path(__file__).resolve().parents[1]
APP = IOS / "app"
BYTES = APP / "Themes" / "ThemeBytes.swift"
P3T = APP / "Themes" / "P3tTheme.swift"
ANIM = APP / "Themes" / "P3tAnimation.swift"
EDGE = APP / "Themes" / "EdgeAnim.swift"
RAF = APP / "Themes" / "RafScene.swift"
VM = APP / "Themes" / "VsmxVm.swift"
SCENE = APP / "Themes" / "ThemeScene.swift"
SOURCES = (BYTES, P3T, ANIM, EDGE, RAF, VM, SCENE)

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
HOST_CLOSURE = re.compile(r"\bVsmx(?:Native|Constructor)\s*\(")
LIGHTS_ARRAY = re.compile(r"\blightStates\s*:\s*\[\s*LightState\s*\]")
LIGHTS_DICT = re.compile(r"\[\s*String\s*:\s*LightState\s*\]")
CAPS = (r"\bFRAME_STEPS\s*=\s*200_000\b", r"\bMAX_TIMERS\s*=\s*64\b", r"\bMAX_CATCH_UP\s*=\s*8\b",
        r"\bMAX_STEP\s*=\s*0\.25\b", r"\bMAX_LIGHTS\s*=\s*16\b", r"\bANIM_SLOTS\s*=\s*8\b",
        r"\bdeadline\s*:\s*Double\s*=\s*10\b")


def script(body):
    a = VsmxAsm()
    body(a)
    a.end()
    return a.assemble()


def stepper(a):
    """`a = new Actor("a"); step = function () { a.position = a.position + [1, 0, 0]; }`"""
    a.assign("a", lambda: (a.global_("Actor"), a.string("a"), a.new(1)))
    a.function("step", 0, 1, lambda: (a.global_("a"), a.global_("a"), a.get("position"), a.int_(1), a.int_(0),
                                      a.int_(0), a.array(3), a.op(0x02), a.set_("position"), a.pop()))


def timer(a, holder, slot, kind, callback, seconds):
    """`<holder>.timer[slot] = new <kind>(<seconds>, <callback>);`"""
    a.global_(holder); a.get("timer"); a.int_(slot)
    a.global_(kind); seconds(); a.global_(callback); a.new(2)
    a.op(0x36); a.pop()


def every_frame(a, slot=0, callback="step"):
    timer(a, "System", slot, "IntervalTimer", callback, lambda: (a.global_("System"), a.get("interval")))


def actor_a(a):
    a.assign("a", lambda: (a.global_("Actor"), a.string("a"), a.new(1)))


def set_position_to(a, interpolation, argc):
    a.global_("a"); a.get_keep("setPosition"); a.int_(10); a.int_(0); a.int_(0); a.array(3); a.float_(1.0)
    if interpolation:
        a.global_(interpolation)
    a.call_method(argc); a.pop()


def p_every(a):
    stepper(a); every_frame(a)


def p_one_shot(a):
    stepper(a); timer(a, "System", 1, "OneShotTimer", "step", lambda: a.float_(0.5))


def p_actor_timer(a):
    stepper(a); timer(a, "a", 1, "OneShotTimer", "step", lambda: a.float_(0.1))


def p_reused(a):
    stepper(a); every_frame(a, 3); every_frame(a, 3)


def p_holder_this(a):
    actor_a(a)
    a.function("move", 0, 1, lambda: (a.op(0x2C), a.get_keep("setPosition"), a.int_(5), a.int_(0), a.int_(0),
                                      a.array(3), a.call_method(1), a.pop()))
    timer(a, "a", 0, "OneShotTimer", "move", lambda: a.float_(0.1))


def p_failing(a):
    stepper(a)
    a.function("bad", 0, 1, lambda: (a.global_("nothing"), a.call(0), a.pop()))
    every_frame(a, 0, "bad")
    every_frame(a, 1, "step")


def p_missing(a):
    a.assign("b", lambda: (a.global_("Actor"), a.string("missing"), a.new(1)))


def p_linear(a):
    actor_a(a); set_position_to(a, "INTERPOLATION_LINEAR", 3)


def p_bezier(a):
    actor_a(a); set_position_to(a, "INTERPOLATION_BEZIER", 3)


def p_cancel(a):
    actor_a(a); set_position_to(a, None, 2)
    a.global_("a"); a.int_(-3); a.int_(0); a.int_(0); a.array(3); a.set_("position"); a.pop()


def p_hidden(a):
    a.global_("Actor"); a.string("a"); a.new(1); a.op(0x25, 0); a.set_("enable"); a.pop()
    a.global_("Actor"); a.string("b"); a.new(1); a.int_(1); a.int_(1); a.int_(1); a.int_(0); a.array(4)
    a.set_("color"); a.pop()
    a.global_("Actor"); a.string("c"); a.new(1); a.int_(0); a.int_(0); a.int_(0); a.array(3); a.set_("scale"); a.pop()


def p_placed_once(a):
    stepper(a); a.call_global("step")


def p_print_perf(a):
    actor_a(a)
    a.function("step", 0, 1, lambda: (a.global_("a"), a.global_("a"), a.get("position"), a.int_(1), a.int_(0),
                                      a.int_(0), a.array(3), a.op(0x02), a.set_("position"), a.pop(),
                                      a.global_("System"), a.get_keep("printPerf"), a.call_method(0), a.pop()))
    every_frame(a)


def p_date(a):
    actor_a(a)
    a.global_("a")
    for part in ("hours", "minutes", "seconds"):
        a.global_("Date"); a.new(0); a.get(part)
    a.array(3); a.set_("position"); a.pop()
    # A property read is a reference until something reads it, so `d = new Date().day` rather than a bare read.
    a.assign("d", lambda: (a.global_("Date"), a.new(0), a.get("day")))


def spin(a):
    a.function("spin", 0, 1, lambda: a.op(0x39, a.here))


def p_runaway_timer(a):
    stepper(a); spin(a)
    every_frame(a, 0, "spin")
    every_frame(a, 1, "step")


def p_runaway_top(a):
    stepper(a); every_frame(a)
    a.op(0x39, a.here)


def p_dupes(a):
    a.global_("Actor"); a.string("x"); a.new(1); a.int_(5); a.int_(0); a.int_(0); a.array(3); a.set_("position")
    a.pop()


def big(a):
    a.global_("Math"); a.get_keep("pow"); a.int_(10); a.int_(300); a.call_method(2)


def p_huge_slot(a):
    actor_a(a)
    a.global_("a"); a.get_keep("setAnimWeight"); big(a); a.float_(0.5); a.call_method(2); a.pop()
    a.global_("a"); a.get_keep("setAnimTime"); big(a); a.int_(2); a.call_method(2); a.pop()
    a.global_("a"); a.get_keep("getAnimSpeed"); big(a); a.call_method(1); a.pop()
    a.global_("a"); a.get_keep("setAnimWeight"); a.int_(1); a.float_(0.25); a.call_method(2); a.pop()


def p_lights(a):
    a.global_("Light"); a.string("new"); a.new(1); a.pop()
    a.global_("Light"); a.string("alpha"); a.new(1); a.float_(0.5); a.float_(0.5); a.float_(0.5); a.array(3)
    a.set_("color"); a.pop()


PROGRAMS = {
    "every": p_every, "oneShot": p_one_shot, "actorTimer": p_actor_timer, "reused": p_reused,
    "holderThis": p_holder_this, "failing": p_failing, "missing": p_missing, "linear": p_linear,
    "bezier": p_bezier, "cancel": p_cancel, "hidden": p_hidden, "empty": lambda a: None,
    "placedOnce": p_placed_once, "printPerf": p_print_perf, "date": p_date, "runawayTimer": p_runaway_timer,
    "runawayTop": p_runaway_top, "dupes": p_dupes, "hugeSlot": p_huge_slot, "lights": p_lights,
}


DRIVER = r"""import Foundation

let dir = URL(fileURLWithPath: @@TMP@@)
func load(_ name: String) -> [UInt8] { [UInt8](try! Data(contentsOf: dir.appendingPathComponent(name))) }

let quad = RafScene.Mesh(positions: [-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0], uvs: [0, 1, 1, 1, 1, 0, 0, 0],
                         indices: [0, 1, 2, 0, 2, 3])
let texture = P3tAnimation.Texture(format: 0x86, width: 4, height: 4, pitch: 8, offset: 0, size: 8)
let camera = RafScene.Camera(position: [0, 0, 10], direction: [0, 0, -1], up: [0, 1, 0], yfov: 0.9273, znear: 0.1,
                             zfar: 100)
let clock13 = Calendar.current.date(from: DateComponents(year: 2026, month: 10, day: 25, hour: 13, minute: 5,
                                                         second: 9))!
var reported: [String] = []

func actor(_ name: String, mesh: RafScene.Mesh? = quad) -> RafScene.Actor {
    RafScene.Actor(name: name, mesh: mesh, material: RafScene.Material(effect: "pure_texture", texture: texture),
                   position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1], color: [1, 1, 1, 1],
                   uvOffset: [0, 0], uvScale: [1, 1])
}

func scene(_ key: String, _ actors: [RafScene.Actor]? = nil, lights: [RafScene.Light] = []) -> RafScene.Scene {
    RafScene.Scene(actors: actors ?? [actor("a")], camera: camera, script: load(key), lights: lights)
}

func play(_ key: String, _ actors: [RafScene.Actor]? = nil, lights: [RafScene.Light] = []) -> ThemeScene {
    let p = ThemeScene(scene: scene(key, actors, lights: lights), random: { 0.5 }, now: { clock13 })
    p.unsupported = { reported.append($0) }
    return p
}

func x(_ p: ThemeScene, _ name: String = "a") -> Float { p.actors.first { $0.source.name == name }!.position.v[0] }
func run(_ p: ThemeScene, _ frames: Int, _ seconds: Double = ThemeScene.tick) {
    for _ in 0 ..< frames { p.advance(seconds) }
}
func err(_ p: ThemeScene) -> String { p.scriptError ?? "nil" }
func floats(_ v: [Float]) -> String { v.map { "\($0)" }.joined(separator: ",") }
func line(_ key: String, _ parts: [Any]) { print("CASE \(key)|" + parts.map { "\($0)" }.joined(separator: "|")) }

do { let p = play("every"); run(p, 60); line("every", [x(p), err(p)]) }
do {
    let p = play("every")
    for _ in 0 ..< 30 { p.advance(ThemeScene.tick * 0.9); p.advance(ThemeScene.tick * 1.1) }
    line("earlyLate", [x(p)])
}
do { let p = play("every"); run(p, 120, 1.0 / 120); line("faster", [x(p)]) }
do { let p = play("every"); p.advance(5); line("stall", [x(p)]) }
do {
    let p = play("oneShot")
    run(p, 29); let a = x(p)
    run(p, 1); let b = x(p)
    run(p, 120)
    line("oneShot", [a, b, x(p)])
}
do { let p = play("actorTimer"); run(p, 60); line("actorTimer", [x(p)]) }
do { let p = play("reused"); run(p, 60); line("reused", [x(p)]) }
do { let p = play("holderThis"); run(p, 12); line("holderThis", [err(p), x(p)]) }
do { let p = play("failing"); run(p, 60); line("failing", [x(p), err(p)]) }
do { let p = play("missing"); p.start(); line("missing", [err(p)]) }
do {
    let p = play("linear")
    run(p, 30); let a = x(p)
    run(p, 30); let b = x(p)
    run(p, 30)
    line("linear", [a, b, x(p)])
}
do { let p = play("bezier"); run(p, 15); let a = x(p); run(p, 15); line("bezier", [a, x(p)]) }
do { let p = play("cancel"); run(p, 60); line("cancel", [x(p)]) }
do {
    let p = play("hidden", [actor("a"), actor("b"), actor("c"), actor("d", mesh: nil)])
    p.start()
    line("hidden", p.actors.map { $0.shown } + [p.actors[3].drawable])
}

let skinnedQuad = RafScene.Mesh(positions: quad.positions, uvs: quad.uvs, indices: quad.indices,
                                joints: [Int](repeating: 0, count: 16), weights: (0 ..< 16).map { $0 % 4 == 0 ? 1 : 0 },
                                segments: [RafScene.Segment(firstIndex: 0, indexCount: 6, palette: [1])])
let twoJoints = EdgeAnim.Skeleton(parents: [-1, 0], rest: [0, 0, 0, 1, 0, 0, 0, 1, 1, 1, 0, 0, 0, 1, 0, 0, 0, 1, 1, 1])
let identities: [Float] = (0 ..< 24).map { [0, 5, 10].contains($0 % 12) ? 1 : 0 }
let slide = EdgeAnim.Clip(duration: 1, frameRate: 60, frames: 61, channels: [
    EdgeAnim.Channel(joint: 1, kind: EdgeAnim.TRANSLATION, frames: [0, 60], values: [0, 0, 0, 4, 0, 0]),
])

func rigged(_ clip: EdgeAnim.Clip?, speed: Float = 1) -> RafScene.Actor {
    RafScene.Actor(name: "a", mesh: skinnedQuad, material: RafScene.Material(effect: "basic_lighting", texture: texture),
                   position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1], color: [1, 1, 1, 1], uvOffset: [0, 0],
                   uvScale: [1, 1], rig: RafScene.Rig(skeleton: twoJoints, inverseBinds: identities, clips: [clip]),
                   animWeights: [1], animSpeeds: [speed], animTimes: [0])
}

line("skinnedNeed", [play("empty", [actor("a", mesh: skinnedQuad)]).actors[0].drawable,
                     play("empty", [rigged(slide)]).actors[0].drawable])
do {
    let p = play("empty", [rigged(slide)])
    run(p, 30); let a = p.actors[0].skinMatrices()![12 + 3]
    run(p, 60)
    line("skinnedPlay", [a, p.actors[0].skinMatrices()![12 + 3]])
}
do { let p = play("empty", [rigged(slide, speed: 0.5)]); run(p, 30); line("speed", [p.actors[0].skinMatrices()![12 + 3]]) }

line("animates", [ThemeScene.animates(scene("every")), ThemeScene.animates(scene("placedOnce"), seconds: 2),
                  ThemeScene.animates(RafScene.Scene(actors: [actor("a")], camera: camera, script: nil))])

do {
    reported.removeAll()
    let p = play("printPerf")
    run(p, 60)
    line("printPerf", [x(p), err(p), reported.joined(separator: ",")])
}
do {
    reported.removeAll()
    let p = play("date")
    p.start()
    line("date", [floats(p.actors[0].position.v), err(p), reported.joined(separator: ",")])
}

do {
    let p = play("runawayTimer")
    p.advance(ThemeScene.tick)
    let first = [p.exhausted ? "true" : "false", "\(x(p))", err(p)]
    run(p, 30)
    line("runawayTimer", first + [p.exhausted ? "true" : "false", "\(x(p))"])
}
var ticks = 0.0
let late = ThemeScene.animates(scene("every"), clock: { ticks += 1; return ticks })
let standing = ThemeScene.animates(scene("every"), clock: { 0 })
line("budgetAnimates", [ThemeScene.animates(scene("runawayTop")), late, standing])

do {
    let p = play("dupes", [actor("x"), actor("x")])
    p.start()
    line("dupes", [p.actors[0].position.v[0], p.actors[1].position.v[0], err(p)])
}
do {
    let p = play("hugeSlot", [rigged(slide)])
    p.start()
    let a = p.actors[0]
    line("hugeSlot", [err(p), floats(a.animWeights.v), floats(a.animTimes.v), floats(a.animSpeeds.v)])
}
do {
    func light(_ name: String, _ type: Int, _ c: Float) -> RafScene.Light {
        RafScene.Light(name: name, type: type, color: [c, c, c], position: [0, 0, 0], direction: [0, 0, -1])
    }
    let lights = [light("zeta", 0, 0.1), light("alpha", 1, 0.2), light("mid", 2, 0.3), light("alpha", 0, 0.4)]
    func order(_ p: ThemeScene) -> String { p.lightStates.map { "\($0.type):\($0.color.v[0])" }.joined(separator: ",") }
    let a = play("lights", lights: lights); a.start()
    let b = play("lights", lights: lights); b.start(); run(b, 3)
    line("lights", [order(a), order(b), err(a)])
}
do {
    let p = play("every")
    p.advance(ThemeScene.tick)
    let a = p.actors[0]
    line("vecBox", [ThemeScene.modelMatrix(a.position.v, a.rotation.v, a.scale.v)[12]])
}

func apply(_ m: [Float], _ x: Float, _ y: Float, _ z: Float) -> [Float] {
    (0 ..< 4).map { r in m[r] * x + m[4 + r] * y + m[8 + r] * z + m[12 + r] }
}
do {
    let half = Float(Double.pi / 2)
    let v = apply(ThemeScene.modelMatrix([0, 0, 0], [half, half, 0], [1, 1, 1]), 0, 1, 0)
    let w = apply(ThemeScene.modelMatrix([1, 2, 3], [0, 0, half], [2, 2, 2]), 1, 0, 0)
    line("rotations", [floats(Array(v[0 ..< 3])), floats(Array(w[0 ..< 3]))])
}
do {
    let o = apply(ThemeScene.viewMatrix([0, 0, 10], [0, 0, -1], [0, 1, 0]), 0, 0, 0)
    let q = apply(ThemeScene.viewMatrix([0, -229.5, 3], [0, 1, 0], [0, 0, 1]), 1, 0, 4)
    line("view", [o[2], floats(Array(q[0 ..< 3]))])
}
do {
    let fov: Float = 0.9273
    let t = Float(tan(Double(fov) / 2))
    func ndc(_ aspect: Float, _ x: Float, _ y: Float) -> [Float] {
        let c = apply(ThemeScene.projection(fov, aspect, 0.1, 100), x, y, -1)
        return [c[0] / c[3], c[1] / c[3]]
    }
    line("crop", [ndc(16 / 9, t * 16 / 9, t)[0], ndc(16 / 9, t * 16 / 9, t)[1], ndc(2, t * 16 / 9, 0)[0],
                  ndc(2, 0, t)[1], ndc(4 / 3, 0, t)[1], ndc(4 / 3, t * 16 / 9, 0)[0]])
}
do {
    let gl = ThemeScene.projection(0.9273, 16 / 9, 0.1, 100)
    let metal = ThemeScene.metalClip(gl)
    func depth(_ m: [Float], _ z: Float) -> Float { let c = apply(m, 0, 0, z); return c[2] / c[3] }
    line("metal", [depth(metal, -0.1), depth(metal, -100), depth(gl, -0.1), depth(gl, -100)])
}
print("DONE")
"""


def closure_spans(code):
    """(opening text, body) of every closure handed to a VsmxNative or VsmxConstructor."""
    out = []
    for match in HOST_CLOSURE.finditer(code):
        open_at, close_at = brace_span(code, match.end())
        out.append(code[open_at + 1:close_at - 1])
    return out


def scene_problems(code_raw):
    """code_raw: ThemeScene.swift as read."""
    problems = []
    code = without_comments(code_raw)
    if IMPORT.findall(code) != ["Foundation"]:
        problems.append("host: ThemeScene.swift imports {0}; Foundation only".format(IMPORT.findall(code)))
    for body in closure_spans(code):
        if re.search(r"\bself\b", body) and not re.match(r"\s*\[\s*unowned\s+self\s*\]", body):
            problems.append("unowned: a host closure names self without [unowned self]: {0}".format(
                " ".join(body.split())[:80]))
    if not LIGHTS_ARRAY.search(code):
        problems.append("lights: ThemeScene.swift does not declare `lightStates: [LightState]`")
    if LIGHTS_DICT.search(code):
        problems.append("lights: ThemeScene.swift keeps lights in a `[String: LightState]` Dictionary")
    for cap in CAPS:
        if not re.search(cap, code):
            problems.append("caps: ThemeScene.swift does not keep `{0}`".format(cap))
    return problems


class TheScenePlays(unittest.TestCase):
    """The ported Kotlin cases, the host objects, the step budget and deadline, the Swift-only cases and the Metal depth range."""

    @classmethod
    def setUpClass(cls):
        for source in SOURCES:
            read(source)
        with tempfile.TemporaryDirectory() as tmp:
            for key, body in PROGRAMS.items():
                (Path(tmp) / key).write_bytes(script(body))
            out = compile_and_run(list(SOURCES), DRIVER.replace("@@TMP@@", json.dumps(tmp)), tmp)
        cls.done = "DONE" in out.splitlines()
        cls.lines = {}
        for text in out.splitlines():
            if text.startswith("CASE "):
                key, _, rest = text[5:].partition("|")
                cls.lines[key] = rest.split("|")

    def case(self, key):
        self.assertTrue(self.done, "the driver did not finish")
        got = self.lines.get(key)
        self.assertIsNotNone(got, key)
        return got

    def numbers(self, key, at=None):
        got = self.case(key)
        return [float(v) for v in (got if at is None else [got[at]])]

    def near(self, expected, got, places=3):
        self.assertEqual(len(expected), len(got), got)
        for e, g in zip(expected, got):
            self.assertAlmostEqual(e, g, places=places, msg=got)

    # ---- timers ----

    def test_a_timer_every_frame_fires_once_per_frame(self):
        self.assertEqual(["60.0", "nil"], self.case("every"))

    def test_frames_arriving_early_and_late_still_fire_once(self):
        self.assertEqual(["60.0"], self.case("earlyLate"))

    def test_a_faster_panel_keeps_the_scripts_pace(self):
        self.assertEqual(["60.0"], self.case("faster"))

    def test_a_stall_does_not_replay_its_backlog(self):
        x = self.numbers("stall")[0]
        self.assertTrue(1 <= x <= 8, x)

    def test_a_one_shot_fires_once(self):
        self.assertEqual(["0.0", "1.0", "1.0"], self.case("oneShot"))

    def test_actors_hold_timers_too(self):
        self.assertEqual(["1.0"], self.case("actorTimer"))

    def test_a_timer_keeps_running_when_its_slot_is_reused(self):
        self.assertEqual(["120.0"], self.case("reused"))

    def test_a_timer_runs_with_its_holder_as_this(self):
        self.assertEqual(["nil", "5.0"], self.case("holderThis"))

    def test_a_failing_callback_stops_only_its_timer(self):
        got = self.case("failing")
        self.assertEqual("60.0", got[0])
        self.assertIn("not a function", got[1])

    def test_a_missing_actor_stops_the_setup(self):
        self.assertIn("no actor", self.case("missing")[0])

    # ---- moves ----

    def test_moves_take_their_time(self):
        self.near([5, 10, 10], self.numbers("linear"))

    def test_bezier_moves_ease_in_and_out(self):
        self.near([10 * 0.15625, 5], self.numbers("bezier"))

    def test_setting_a_property_cancels_its_move(self):
        self.assertEqual(["-3.0"], self.case("cancel"))

    def test_hidden_actors_are_not_shown(self):
        self.assertEqual(["false"] * 5, self.case("hidden"))

    # ---- skinned actors ----

    def test_skinned_meshes_need_a_skeleton(self):
        self.assertEqual(["false", "true"], self.case("skinnedNeed"))

    def test_skinned_actors_play_their_clip(self):
        # Joint 1's skin matrix carries the clip's translation, halfway through the second; clips loop.
        self.near([2, 2], self.numbers("skinnedPlay"))

    def test_animation_speed_scales_time(self):
        self.near([1], self.numbers("speed"))

    def test_animates_tells_moving_scenes_from_still_ones(self):
        self.assertEqual(["true", "false", "false"], self.case("animates"))

    # The host objects.

    def test_print_perf_keeps_its_timer_running(self):
        self.assertEqual(["60.0", "nil", ""], self.case("printPerf"))

    def test_date_answers_hours_minutes_and_seconds_and_reports_the_rest(self):
        self.assertEqual(["13.0,5.0,9.0", "nil", "Date.day"], self.case("date"))

    # The step budget and import deadline.

    def test_budget_a_runaway_callback_exhausts_its_advance(self):
        got = self.case("runawayTimer")
        self.assertEqual(["true", "0.0"], got[0:2], got)
        self.assertTrue(got[2].startswith("step budget"), got)
        # Later advances run no script: the every-frame step timer never moves the actor.
        self.assertEqual(["true", "0.0"], got[3:5], got)

    def test_budget_animates_gives_up_on_a_runaway_or_at_the_deadline(self):
        self.assertEqual(["false", "false", "true"], self.case("budgetAnimates"))

    # ---- Swift-only ----

    def test_swift_duplicate_actor_names_keep_the_last(self):
        self.assertEqual(["0.0", "5.0", "nil"], self.case("dupes"))

    def test_swift_a_huge_slot_is_ignored(self):
        got = self.case("hugeSlot")
        self.assertEqual("nil", got[0], got)
        self.assertEqual("1.0,0.25," + ",".join(["0.0"] * 6), got[1], got)
        self.assertEqual(",".join(["0.0"] * 8), got[2], got)
        self.assertEqual(",".join(["1.0"] * 8), got[3], got)

    def test_swift_lights_keep_scene_order(self):
        self.assertEqual(["0:0.1,0:0.5,2:0.3,0:1.0", "0:0.1,0:0.5,2:0.3,0:1.0", "nil"], self.case("lights"))

    def test_swift_a_scripted_vector_reaches_the_matrix(self):
        self.assertEqual(["1.0"], self.case("vecBox"))

    # ---- the camera ----

    def test_rotations_apply_x_then_y_then_z(self):
        got = self.case("rotations")
        # X first turns y into z; Y then turns z into x. The other order would leave z.
        self.near([1, 0, 0], [float(v) for v in got[0].split(",")], places=5)
        self.near([1, 4, 3], [float(v) for v in got[1].split(",")], places=5)

    def test_the_view_looks_along_the_camera_direction(self):
        got = self.case("view")
        self.near([-10], [float(got[0])], places=5)
        # Fallout NV's camera looks along +y with z up: +x stays to the right, +z up.
        self.near([1, 1, -229.5], [float(v) for v in got[1].split(",")], places=3)

    def test_other_screen_shapes_crop_the_picture(self):
        n = self.numbers("crop")
        # 16:9: the picture's corners are the screen's.
        self.near([1, 1], n[0:2], places=4)
        # Wider: the sides still meet, the top and bottom are cropped.
        self.near([1], n[2:3], places=4)
        self.assertGreater(n[3], 1)
        # Narrower: the top and bottom meet, the sides are cropped.
        self.near([1], n[4:5], places=4)
        self.assertGreater(n[5], 1)

    def test_metal_clip_maps_near_to_zero_and_far_to_one(self):
        metal_near, metal_far, gl_near, gl_far = self.numbers("metal")
        self.assertLessEqual(abs(metal_near - 0), 1e-5)
        self.assertLessEqual(abs(metal_far - 1), 1e-5)
        self.assertLessEqual(abs(gl_near + 1), 1e-5)
        self.assertLessEqual(abs(gl_far - 1), 1e-5)


class TheRealFiles(unittest.TestCase):
    def test_real_files_have_no_problems(self):
        self.assertEqual([], scene_problems(read(SCENE)))

    def test_scene_has_host_closures_to_check(self):
        # The unowned check means nothing over a file it finds no closures in.
        self.assertTrue(any(re.search(r"\bself\b", b) for b in closure_spans(without_comments(read(SCENE)))))

    def test_scene_missing_input_raises(self):
        with self.assertRaises(AssertionError):
            read(APP / "NoSuchFile.swift")


class Mutations(unittest.TestCase):
    """Each test makes ONE edit to the real text and shows its check fail."""

    @classmethod
    def setUpClass(cls):
        cls.code = read(SCENE)

    def flagged(self, prefix, old, new):
        self.assertIn(old, self.code)
        problems = scene_problems(self.code.replace(old, new, 1))
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), problems)

    def test_mutation_host_imports_uikit(self):
        self.flagged("host", "import Foundation", "import Foundation\nimport UIKit")

    def test_mutation_unowned(self):
        self.flagged("unowned", "{ [unowned self] args in\n                let id", "{ args in\n                let id")

    def test_mutation_lights(self):
        self.flagged("lights", "private(set) var lightStates: [LightState] = []",
                     "private(set) var lightStates: [String: LightState] = [:]")

    def test_mutation_caps(self):
        self.flagged("caps", "FRAME_STEPS = 200_000", "FRAME_STEPS = 2_000_000")


if __name__ == "__main__":
    unittest.main()
