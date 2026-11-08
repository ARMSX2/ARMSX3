#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""VsmxVm.swift, run on the Mac host with scripts assembled here, runs a live theme's VSMX script as Android's VsmxVm.kt
does, and turns every hostile script (deep recursion, NaN indexes, bad casts, runaway loops, allocation and string
growth, truncated or flipped bytes) into an error string within its caps instead of a trap."""

import json
import re
import struct
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run
from test_param_sfo import read
from source_helpers import without_comments
from theme_fixtures import VsmxAsm, vsmx_sample

IOS = Path(__file__).resolve().parents[1]
BYTES = IOS / "app" / "Themes" / "ThemeBytes.swift"
VM = IOS / "app" / "Themes" / "VsmxVm.swift"

IMPORT = re.compile(r"^\s*import\s+(\w+)", re.M)
LOGGING = re.compile(r"\b(print|NSLog|os_log|debugPrint)\s*\(|\bLogger\b")
INT_OF_NUM = re.compile(r"\bInt\s*\(\s*(?:Self\.|VsmxVm\.)?num\s*\(")
FINAL = ("JsArray", "JsObject", "VsmxFunction", "Frame")
CAPS = (r"\bMAX_STEPS\s*=\s*200_000\b", r"\bMAX_STACK\s*=\s*4096\b", r"\bMAX_DEPTH\s*=\s*64\b",
        r"\bMAX_ARRAY\s*=\s*1\s*<<\s*16\b", r"\bLOCALS\s*=\s*16\b", r"\bMAX_ALLOCATION\s*=\s*1\s*<<\s*22\b",
        r"\bMAX_STRING\s*=\s*1\s*<<\s*16\b")
NAN_BITS = 0x7FC00000


def script(body, end=True):
    a = VsmxAsm()
    body(a)
    if end:
        a.end()
    return a.assemble()


def counted_loop(a, counter, times, body):
    """`counter = 0; while (counter < times) { body; counter++; }` over a global counter."""
    a.assign(counter, lambda: a.int_(0))
    top = a.here
    a.global_(counter)
    a.int_(times)
    a.op(0x12)
    done = a.op(0x3B)
    body()
    a.global_(counter)
    a.op(0x0C)
    a.pop()
    a.op(0x39, top)
    a.patch(done, a.here)


def p_arith(a):
    a.call_global("report", lambda: (a.int_(2), a.int_(3), a.int_(4), a.op(0x04), a.op(0x02)))
    a.call_global("report", lambda: (a.int_(7), a.int_(2), a.op(0x06)))
    a.call_global("report", lambda: (a.float_(1.5), a.op(0x08)))
    a.call_global("report", lambda: (a.int_(3), a.int_(5), a.op(0x12)))


def p_vectors(a):
    a.call_global("report", lambda: (a.int_(1), a.int_(2), a.int_(3), a.array(3),
                                     a.int_(10), a.int_(20), a.int_(30), a.array(3), a.op(0x02)))
    a.call_global("report", lambda: (a.int_(1), a.int_(2), a.array(2), a.int_(2), a.op(0x04)))
    a.call_global("report", lambda: (a.string("a"), a.int_(1), a.op(0x02)))


def p_rmw(a):
    a.assign("o", lambda: a.op(0x29))
    a.global_("o"); a.int_(1); a.int_(2); a.int_(3); a.array(3); a.set_("p"); a.pop()
    a.global_("o"); a.get_keep("p"); a.element_get(0); a.int_(5); a.op(0x02); a.element_set("p", 0); a.pop()
    a.global_("o"); a.int_(5); a.set_("n"); a.pop()
    a.global_("o"); a.global_("o"); a.get("n"); a.int_(1); a.op(0x02); a.set_("n"); a.pop()
    a.call_global("report", lambda: (a.global_("o"), a.get("p")))
    a.call_global("report", lambda: (a.global_("o"), a.get("n")))


def p_params(a):
    a.function("add", 2, 3, lambda: (a.local(1), a.local(2), a.op(0x02), a.op(0x3F)))
    a.call_global("report", lambda: (a.global_("add"), a.int_(2), a.int_(5), a.call(2)))


def p_methods(a):
    a.global_("report"); a.global_("obj"); a.get_keep("twice"); a.call_method(0); a.call(1); a.pop()


def p_catch(a):
    try_at = a.op(0x41)
    a.global_("nothing"); a.call(0); a.pop()
    a.op(0x42)
    skip = a.op(0x43)
    a.patch(try_at, skip)
    a.global_("e"); a.op(0x21); a.op(0x01); a.pop()
    a.call_global("report", lambda: a.global_("e"))
    a.patch(skip, a.op(0x44))


def p_ctor(a):
    a.function("Foo", 1, 1, lambda: (a.op(0x2C), a.local(1), a.set_("x"), a.pop()))
    a.global_("Foo"); a.get("prototype")
    fn = a.op(0x2A | (1 << 24))
    skip = a.op(0x39)
    a.patch(fn, a.here)
    a.op(0x2C); a.get("x"); a.op(0x3F)
    a.patch(skip, a.here)
    a.set_("get"); a.pop()
    a.assign("o", lambda: (a.global_("Foo"), a.int_(5), a.new(1)))
    a.call_global("report", lambda: (a.global_("o"), a.get_keep("get"), a.call_method(0)))
    a.call_global("report", lambda: (a.global_("o"), a.get("x")))


def p_arguments(a):
    a.function("f", 0, 1, lambda: (a.local(0), a.get("length"), a.op(0x3F)))
    a.call_global("report", lambda: (a.global_("f"), a.int_(1), a.int_(2), a.int_(3), a.call(3)))


def p_incr(a):
    a.assign("o", lambda: a.op(0x29))
    a.global_("o"); a.int_(1); a.set_("n"); a.pop()
    a.global_("o"); a.get("n"); a.op(0x0C); a.pop()
    a.assign("a", lambda: (a.int_(5), a.array(1)))
    a.global_("a"); a.int_(0); a.op(0x34); a.op(0x0C); a.pop()
    a.call_global("report", lambda: (a.global_("o"), a.get("n")))
    a.call_global("report", lambda: (a.global_("a"), a.int_(0), a.op(0x34)))


def p_named(a):
    a.assign("logo", lambda: a.op(0x2B))
    a.global_("logo")
    fn = a.op(0x2A | (1 << 24))
    skip = a.op(0x39)
    a.patch(fn, a.here)
    a.int_(7); a.op(0x3F)
    a.patch(skip, a.here)
    a.set_("init"); a.pop()
    a.call_global("report", lambda: (a.global_("logo"), a.get_keep("init"), a.call_method(0)))


def p_runaway(a):
    a.op(0x39, 0)


def p_unknown(a):
    a.call_global("report", lambda: a.int_(1))
    a.op(0x7F)
    a.call_global("report", lambda: a.int_(2))


def p_calls(a):
    a.function("bad", 0, 1, lambda: (a.global_("nothing"), a.call(0), a.pop()))
    a.function("good", 0, 1, lambda: a.call_global("report", lambda: a.int_(1)))
    a.call_global("keep", lambda: a.global_("bad"), lambda: a.global_("good"))


def cyclic(a):
    a.assign("a", lambda: a.op(0x2B))
    a.global_("a"); a.get_keep("push"); a.global_("a"); a.call_method(1); a.pop()


def p_cyc_eq(a):
    cyclic(a)
    a.call_global("report", lambda: (a.global_("a"), a.global_("a"), a.op(0x0E)))


def p_cyc_str(a):
    cyclic(a)
    a.call_global("report", lambda: (a.string(""), a.global_("a"), a.op(0x02)))


def three(a):
    a.assign("a", lambda: (a.int_(1), a.int_(2), a.int_(3), a.array(3)))


def p_index_read(a):
    three(a)
    for index in (lambda: a.float_bits(NAN_BITS), lambda: a.global_("Infinity"), lambda: a.global_("big"),
                  lambda: a.int_(-1), lambda: a.int_(1)):
        a.call_global("report", lambda: (a.global_("a"), index(), a.op(0x34)))


def index_write(push_index):
    def body(a):
        three(a)
        a.call_global("report", lambda: a.int_(0))
        a.global_("a"); push_index(a); a.int_(9); a.op(0x36); a.pop()
        a.call_global("report", lambda: a.int_(1))
    return body


def p_push_this(a):
    a.assign("a", lambda: a.op(0x2B))
    a.call_global("report", lambda: (a.global_("a"), a.get_keep("push"), a.int_(4), a.call_method(1)))
    a.assign("p", lambda: (a.global_("a"), a.get("push")))
    a.call_global("p", lambda: a.int_(1))
    a.call_global("report", lambda: a.int_(2))


def p_alloc(a):
    counted_loop(a, "i", 100, lambda: a.assign("x", lambda: (a.global_("Array"), a.int_(65536), a.new(1))))
    a.call_global("report", lambda: a.int_(1))


def p_string_cap(a):
    a.assign("s", lambda: a.string("ab"))
    counted_loop(a, "i", 40, lambda: a.assign("s", lambda: (a.global_("s"), a.global_("s"), a.op(0x02))))
    a.call_global("report", lambda: a.int_(1))


def p_local(a):
    a.local(1); a.int_(1); a.op(0x01); a.pop()
    a.call_global("report", lambda: a.local(1))
    a.function("f", 0, 2, lambda: (a.local(1), a.int_(5), a.op(0x01), a.pop(), a.local(1), a.op(0x3F)))
    a.call_global("report", lambda: (a.global_("f"), a.call(0)))


def p_utf16(a):
    a.assign("s", lambda: a.string("a\U0001F600b"))
    a.call_global("report", lambda: (a.global_("s"), a.get("length")))
    a.call_global("report", lambda: (a.global_("s"), a.int_(3), a.op(0x34)))
    a.call_global("report", lambda: (a.global_("s"), a.int_(0), a.op(0x34)))
    a.call_global("report", lambda: (a.global_("s"), a.int_(4), a.op(0x34)))


def p_str_numbers(a):
    for value in (lambda: a.global_("NaN"), lambda: a.global_("Infinity"),
                  lambda: (a.global_("Infinity"), a.op(0x08)), lambda: a.int_(14), lambda: a.float_(1.5)):
        a.call_global("report", lambda: (a.string(""), value(), a.op(0x02)))


def p_budget(a):
    # About 600 steps: 85 turns of a 7-step loop, plus the call and the return.
    def spin():
        a.local(1); a.int_(0); a.op(0x01); a.pop()
        top = a.here
        a.local(1); a.op(0x0C); a.pop()
        a.local(1); a.int_(85); a.op(0x12); a.op(0x3A, top)
    a.function("spin", 0, 2, spin)
    a.call_global("keep", lambda: a.global_("spin"))


PROGRAMS = {
    "arith": p_arith, "vectors": p_vectors, "rmw": p_rmw, "params": p_params, "methods": p_methods,
    "catch": p_catch, "ctor": p_ctor, "arguments": p_arguments, "incr": p_incr, "named": p_named,
    "runaway": p_runaway, "unknown": p_unknown, "calls": p_calls,
    "cycEq": p_cyc_eq, "cycStr": p_cyc_str, "indexRead": p_index_read,
    "writeNaN": index_write(lambda a: a.float_bits(NAN_BITS)),
    "writeInf": index_write(lambda a: a.global_("Infinity")),
    "writeBig": index_write(lambda a: a.global_("big")),
    "writeNeg": index_write(lambda a: a.int_(-1)),
    "pushThis": p_push_this, "alloc": p_alloc, "stringCap": p_string_cap, "local": p_local, "utf16": p_utf16,
    "strNumbers": p_str_numbers, "budget": p_budget,
}


def inputs():
    out = {key: script(body) for key, body in PROGRAMS.items()}
    good = script(lambda a: None)
    bad = bytearray(good)
    struct.pack_into("<i", bad, 12, 1 << 20)
    out.update({"zeros10": bytes(10), "zeros64": bytes(64), "good": good, "bad": bytes(bad), "sample": vsmx_sample()})
    return out


DRIVER = r"""import Foundation

let dir = URL(fileURLWithPath: @@TMP@@)
func load(_ name: String) -> [UInt8] { [UInt8](try! Data(contentsOf: dir.appendingPathComponent(name))) }

func show(_ v: VsmxValue) -> String {
    switch v {
    case .undefined: return "u"
    case .null: return "null"
    case .bool(let b): return "b:\(b)"
    case .number(let d): return "n:\(d)"
    case .string(let s): return "s:" + s
    case .array(let a): return "[" + a.items.map(show).joined(separator: ",") + "]"
    default: return "other"
    }
}

final class Obj: VsmxHostObject {
    func get(_ name: String) throws -> VsmxValue {
        switch name {
        case "v": return .number(4)
        case "twice":
            return .native(VsmxNative(name: name) { this, _ in
                guard case .host(let o) = this else { throw VsmxError("not obj") }
                return .number(VsmxVm.num(try o.get("v")) * 2)
            })
        default: return .undefined
        }
    }
    func set(_ name: String, _ value: VsmxValue) throws {}
}

final class Host: VsmxHost {
    var reported: [VsmxValue] = []
    var kept: [VsmxValue] = []
    let obj = Obj()
    func global(_ name: String) -> VsmxValue? {
        switch name {
        case "report":
            return .native(VsmxNative(name: name) { [unowned self] _, args in
                self.reported.append(args.first ?? .null)
                return .undefined
            })
        case "keep":
            return .native(VsmxNative(name: name) { [unowned self] _, args in
                self.kept = args
                return .undefined
            })
        case "obj": return .host(obj)
        case "NaN": return .number(.nan)
        case "Infinity": return .number(.infinity)
        case "big": return .number(1e300)
        case "Array":
            // ThemeScene.kt's Array constructor, with its size range-checked before the conversion.
            return .constructor(VsmxConstructor(name: name) { args in
                if args.count == 1, case .number(let size) = args[0] {
                    if !(size >= 0 && size <= 65536) { throw VsmxError("array size") }
                    return .array(JsArray(Array(repeating: .undefined, count: Int(size))))
                }
                return .array(JsArray(args))
            })
        default: return nil
        }
    }
}

for key in @@KEYS@@ {
    let host = Host()
    guard let program = VsmxVm.Program.parse(load(key)) else { print("RUN \(key)|unparsed"); continue }
    let vm = VsmxVm(program: program, host: host)
    vm.run()
    print("RUN \(key)|" + ([vm.failed ?? "-"] + host.reported.map(show)).joined(separator: "|"))
}

for key in ["zeros10", "zeros64", "good", "bad"] {
    print("PARSE \(key) " + (VsmxVm.Program.parse(load(key)) == nil ? "nil" : "ok"))
}

do {
    let host = Host()
    let vm = VsmxVm(program: VsmxVm.Program.parse(load("calls"))!, host: host)
    vm.run()
    let bad = vm.call(host.kept[0])
    let good1 = vm.call(host.kept[1])
    let good2 = vm.call(host.kept[1])
    print("CALLS bad=\(bad == nil ? "nil" : "error") good1=\(good1 ?? "nil") good2=\(good2 ?? "nil") failed=\(vm.failed ?? "nil") reported=" + host.reported.map(show).joined(separator: ","))
}

do {
    let host = Host()
    let vm = VsmxVm(program: VsmxVm.Program.parse(load("budget"))!, host: host)
    vm.run()
    let alone = vm.call(host.kept[0])
    vm.stepBudget = 1000
    let first = vm.call(host.kept[0])
    let second = vm.call(host.kept[0])
    vm.stepBudget = 1000
    let reset = vm.call(host.kept[0])
    print("BUDGET alone=\(alone ?? "nil")|first=\(first ?? "nil")|second=\(second ?? "nil")|reset=\(reset ?? "nil")")
}

var ran = 0
func exercise(_ input: [UInt8]) {
    guard let program = VsmxVm.Program.parse(input) else { return }
    ran += 1
    let host = Host()
    let vm = VsmxVm(program: program, host: host)
    vm.stepBudget = 10_000
    vm.run()
    for fn in host.kept {
        vm.stepBudget = 10_000
        _ = vm.call(fn, this: .undefined, args: [.number(1), .string("x")])
    }
}

var state: UInt64 = 0x16_03_0003
func next() -> Int {
    state = state &* 6364136223846793005 &+ 1442695040888963407
    return Int(truncatingIfNeeded: state >> 33)
}
var truncations = 0
var flips = 0
let whole = load("sample")
for n in 0 ... whole.count {
    exercise(Array(whole[0 ..< n]))
    truncations += 1
}
for _ in 0 ..< 3000 {
    var copy = whole
    let at = next() % copy.count
    copy[at] ^= UInt8(1 + next() % 255)
    exercise(copy)
    flips += 1
}
print("FUZZ truncations=\(truncations) flips=\(flips) ran=\(ran) done=1")
"""


def vsmx_problems(code_raw):
    """code_raw: VsmxVm.swift as read."""
    problems = []
    code = without_comments(code_raw)
    if IMPORT.findall(code) != ["Foundation"]:
        problems.append("host: VsmxVm.swift imports {0}; Foundation only".format(IMPORT.findall(code)))
    if LOGGING.search(code):
        problems.append("log: VsmxVm.swift logs")
    for match in INT_OF_NUM.finditer(code):
        problems.append("int: VsmxVm.swift converts `{0}` with Int(); use saturatingInt".format(match.group(0)))
    for name in FINAL:
        if not re.search(r"\bfinal\s+class\s+{0}\b".format(name), code):
            problems.append("final: VsmxVm.swift does not declare `final class {0}`".format(name))
    for cap in CAPS:
        if not re.search(cap, code):
            problems.append("caps: VsmxVm.swift does not keep the cap `{0}`".format(cap))
    return problems


class TheScriptsRun(unittest.TestCase):
    """The ported Kotlin cases, the Swift-only cases, the budget and the fuzz; one line per case."""

    @classmethod
    def setUpClass(cls):
        read(BYTES)
        read(VM)
        cls.inputs = inputs()
        with tempfile.TemporaryDirectory() as tmp:
            for key, data in cls.inputs.items():
                (Path(tmp) / key).write_bytes(data)
            keys = "[" + ", ".join(json.dumps(k) for k in PROGRAMS) + "]"
            driver = DRIVER.replace("@@TMP@@", json.dumps(tmp)).replace("@@KEYS@@", keys)
            out = compile_and_run([BYTES, VM], driver, tmp)
        cls.lines = {}
        for text in out.splitlines():
            parts = text.split(" ", 2)
            if parts[0] == "RUN" and len(parts) >= 2:
                key, _, rest = text[4:].partition("|")
                cls.lines["RUN " + key] = rest
            elif parts[0] == "PARSE" and len(parts) == 3:
                cls.lines["PARSE " + parts[1]] = parts[2]
            elif parts[0] in ("CALLS", "BUDGET", "FUZZ"):
                cls.lines[parts[0]] = text[len(parts[0]) + 1:]

    def run_of(self, key):
        """(failed, reports) of one script's top level: failed is None when it ran to the end."""
        got = self.lines.get("RUN " + key)
        self.assertIsNotNone(got, key)
        self.assertNotEqual("unparsed", got, key)
        parts = got.split("|")
        return (None if parts[0] == "-" else parts[0]), parts[1:]

    def ok(self, key, reports):
        failed, got = self.run_of(key)
        self.assertIsNone(failed, key)
        self.assertEqual(reports, got, key)

    def stops(self, key, words, reports):
        failed, got = self.run_of(key)
        self.assertIsNotNone(failed, key)
        self.assertIn(words, failed, key)
        self.assertEqual(reports, got, key)

    def test_arithmetic_follows_the_stack(self):
        self.ok("arith", ["n:14.0", "n:1.0", "n:-1.5", "b:true"])

    def test_arrays_are_vectors(self):
        self.ok("vectors", ["[n:11.0,n:22.0,n:33.0]", "[n:2.0,n:4.0]", "s:a1"])

    def test_read_modify_write_keeps_the_object(self):
        self.ok("rmw", ["[n:6.0,n:2.0,n:3.0]", "n:6.0"])

    def test_functions_take_parameters_in_locals(self):
        self.ok("params", ["n:7.0"])

    def test_methods_see_their_object(self):
        self.ok("methods", ["n:8.0"])

    def test_catch_gets_the_error(self):
        failed, got = self.run_of("catch")
        self.assertIsNone(failed)
        self.assertEqual(1, len(got), got)
        self.assertTrue(got[0].startswith("s:") and "not a function" in got[0], got)

    def test_constructors_build_on_their_prototype(self):
        self.ok("ctor", ["n:5.0", "n:5.0"])

    def test_arguments_is_local_slot_zero(self):
        self.ok("arguments", ["n:3.0"])

    def test_properties_and_elements_can_be_incremented(self):
        self.ok("incr", ["n:2.0", "n:6.0"])

    def test_arrays_and_functions_hold_named_properties(self):
        self.ok("named", ["n:7.0"])

    def test_runaway_loops_stop(self):
        failed, _ = self.run_of("runaway")
        self.assertIsNotNone(failed)
        self.assertTrue(failed.startswith("step budget"), failed)

    def test_unknown_opcodes_stop(self):
        self.stops("unknown", "opcode", ["n:1.0"])

    def test_a_failing_call_does_not_stop_the_script(self):
        self.assertEqual("bad=error good1=nil good2=nil failed=nil reported=n:1.0,n:1.0", self.lines.get("CALLS"))

    def test_parse_rejects_what_is_not_a_script(self):
        self.assertEqual("nil", self.lines.get("PARSE zeros10"))
        self.assertEqual("nil", self.lines.get("PARSE zeros64"))
        self.assertEqual("ok", self.lines.get("PARSE good"))
        self.assertEqual("nil", self.lines.get("PARSE bad"))

    def test_swift_cyclic_equality_stops_at_depth(self):
        self.stops("cycEq", "depth", [])

    def test_swift_cyclic_string_stops_at_depth(self):
        self.stops("cycStr", "depth", [])

    def test_swift_bad_indexes_read_undefined(self):
        self.ok("indexRead", ["u", "u", "u", "u", "n:2.0"])

    def test_swift_bad_index_writes_are_refused(self):
        for key in ("writeNaN", "writeInf", "writeBig", "writeNeg"):
            with self.subTest(key):
                self.stops(key, "array index", ["n:0.0"])

    def test_swift_push_with_another_this_is_an_error(self):
        self.stops("pushThis", "push", ["n:1.0"])

    def test_swift_allocation_cap(self):
        self.stops("alloc", "allocation", [])

    def test_swift_string_cap(self):
        self.stops("stringCap", "string length", [])

    def test_swift_a_local_keeps_its_value(self):
        self.ok("local", ["n:1.0", "n:5.0"])

    def test_swift_strings_count_utf16_units(self):
        self.ok("utf16", ["n:4.0", "s:b", "s:a", "u"])

    def test_swift_numbers_print_as_kotlin(self):
        self.ok("strNumbers", ["s:NaN", "s:Infinity", "s:-Infinity", "s:14", "s:1.5"])

    def test_budget_is_shared_until_reset(self):
        got = dict(part.split("=", 1) for part in self.lines.get("BUDGET", "").split("|"))
        self.assertEqual("nil", got.get("alone"), got)
        self.assertEqual("nil", got.get("first"), got)
        self.assertTrue(got.get("second", "").startswith("step budget"), got)
        self.assertEqual("nil", got.get("reset"), got)

    def test_fuzz_truncations_and_flips_never_trap(self):
        got = re.fullmatch(r"truncations=(\d+) flips=(\d+) ran=(\d+) done=1", self.lines.get("FUZZ", ""))
        self.assertIsNotNone(got, self.lines.get("FUZZ"))
        self.assertEqual((str(len(self.inputs["sample"]) + 1), "3000"), got.group(1, 2))
        # A fuzz whose inputs never parse runs nothing: most flips must reach the VM.
        self.assertGreater(int(got.group(3)), 1500, got.group(0))


class TheRealFiles(unittest.TestCase):
    def test_real_files_have_no_problems(self):
        self.assertEqual([], vsmx_problems(read(VM)))

    def test_vsmx_missing_input_raises(self):
        with self.assertRaises(AssertionError):
            read(IOS / "app" / "NoSuchFile.swift")


class Mutations(unittest.TestCase):
    """Each test makes ONE edit to the real text and shows its check fail."""

    @classmethod
    def setUpClass(cls):
        cls.code = read(VM)

    def flagged(self, prefix, old, new):
        self.assertIn(old, self.code)
        problems = vsmx_problems(self.code.replace(old, new, 1))
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), problems)

    def test_mutation_host_imports_uikit(self):
        self.flagged("host", "import Foundation", "import Foundation\nimport UIKit")

    def test_mutation_log(self):
        self.flagged("log", "import Foundation", "import Foundation\nfunc probe(_ s: String) { print(s) }")

    def test_mutation_int_of_num(self):
        self.flagged("int", "import Foundation", "import Foundation\nfunc probe(_ v: VsmxValue) -> Int { Int(num(v)) }")

    def test_mutation_final(self):
        self.flagged("final", "final class JsArray", "class JsArray")

    def test_mutation_caps(self):
        self.flagged("caps", "MAX_STEPS = 200_000", "MAX_STEPS = 2_000_000")


if __name__ == "__main__":
    unittest.main()
