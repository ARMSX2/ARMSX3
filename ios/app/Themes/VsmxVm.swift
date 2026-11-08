// SPDX-License-Identifier: GPL-2.0-only
// Ported from Android's VsmxVm.kt: runs a theme's compiled script, with extra caps because Swift traps where the JVM threw.

import Foundation

/// The Kotlin's `Any?`. Nothing here is recursive, so the enum is not `indirect`.
enum VsmxValue {
    case undefined
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array(JsArray)
    case object(JsObject)
    case function(VsmxFunction)
    case native(VsmxNative)
    case constructor(VsmxConstructor)
    case host(VsmxHostObject)
}

/// Shared by reference, as the Kotlin's ArrayList is: a script writes through the copy it read.
final class JsArray {
    var items: [VsmxValue]
    var props: [String: VsmxValue]?
    init(_ items: [VsmxValue] = []) { self.items = items }
}

/// What it does not have itself is looked up on `proto`, as JavaScript does.
final class JsObject {
    var props: [String: VsmxValue]
    let proto: JsObject?
    init(props: [String: VsmxValue] = [:], proto: JsObject? = nil) {
        self.props = props
        self.proto = proto
    }
}

/// `params` fill local slots 1...params; slot 0 is `arguments`.
final class VsmxFunction {
    let start: Int
    let params: Int
    let locals: Int
    var props: [String: VsmxValue] = [:]
    init(start: Int, params: Int, locals: Int) {
        self.start = start
        self.params = params
        self.locals = locals
    }

    /// Created on first use, as a script adds methods to it.
    var prototype: JsObject {
        if props["prototype"] == nil { props["prototype"] = .object(JsObject()) }
        if case .object(let o)? = props["prototype"] { return o }
        return JsObject()
    }
}

/// `this` is the object it was read from, for methods.
final class VsmxNative {
    let name: String
    let call: (VsmxValue, [VsmxValue]) throws -> VsmxValue
    init(name: String, call: @escaping (VsmxValue, [VsmxValue]) throws -> VsmxValue) {
        self.name = name
        self.call = call
    }
}

final class VsmxConstructor {
    let name: String
    let make: ([VsmxValue]) throws -> VsmxValue
    init(name: String, make: @escaping ([VsmxValue]) throws -> VsmxValue) {
        self.name = name
        self.make = make
    }
}

// Swift: get and set may throw, where Kotlin's could throw unchecked; a VsmxError lands in the script's catch.
protocol VsmxHostObject: AnyObject {
    func get(_ name: String) throws -> VsmxValue
    func set(_ name: String, _ value: VsmxValue) throws
}

protocol VsmxHost: AnyObject {
    func global(_ name: String) -> VsmxValue?
    func unsupported(_ what: String)
}

extension VsmxHost {
    func unsupported(_ what: String) {}
}

struct VsmxError: Error {
    let message: String
    init(_ message: String) { self.message = message }
}

final class VsmxVm {

    // Swift: nested, as the Kotlin's is, so the module gains no top-level `Program`.
    struct Program {
        let op: [Int32]
        let arg: [Int32]
        let names: [String]
        let props: [String]
        let text: [String]

        private static let VSMX = 0x584D5356
        private static let HEADER_BYTES = 52
        private static let MAX_OPS = 1 << 16
        private static let MAX_STRINGS = 1 << 12

        /// Nil when `b` is not a VSMX script or its sections do not fit it.
        static func parse(_ b: [UInt8]) -> Program? {
            let r = ByteReader(b)
            do {
                if r.count < HEADER_BYTES { return nil }
                func word(_ i: Int) throws -> Int { try r.le32(4 * i) }
                if try word(0) != VSMX { return nil }
                let codeAt = try word(2)
                let codeSize = try word(3)
                if codeAt > r.count || codeSize > r.count - codeAt || codeSize / 8 > MAX_OPS { return nil }
                func strings(_ at: Int, _ size: Int, _ count: Int, wide: Bool) throws -> [String]? {
                    if at > r.count || size > r.count - at || count > MAX_STRINGS { return nil }
                    // Swift: nulSplit keeps empty strings as Kotlin's split does, so no later index shifts.
                    return nulSplit(wide ? try r.utf16le(at: at, count: size) : try r.latin1(at: at, count: size),
                                    count: count)
                }
                guard let text = try strings(word(4), word(5), word(6), wide: true),
                      let props = try strings(word(7), word(8), word(9), wide: true),
                      let names = try strings(word(10), word(11), word(12), wide: false) else { return nil }
                let count = codeSize / 8
                // Swift: the Kotlin's le32 is a signed Int; Int32(truncatingIfNeeded:) keeps an operand's sign.
                let op = try (0 ..< count).map { Int32(truncatingIfNeeded: try r.le32(codeAt + 8 * $0)) }
                let arg = try (0 ..< count).map { Int32(truncatingIfNeeded: try r.le32(codeAt + 8 * $0 + 4)) }
                return Program(op: op, arg: arg, names: names, props: props, text: text)
            } catch {
                // Swift: the Kotlin read past its checks only through le32's own bounds; one catch covers them all.
                return nil
            }
        }
    }

    // Swift: a class, because a copied Swift array would lose `x = 1` where Kotlin's array was a reference.
    private final class Frame {
        var slots: [VsmxValue]
        init(count: Int) { slots = Array(repeating: .undefined, count: count) }
    }

    // Swift: the stack holds Refs, and `.value` is a plain value, as the Kotlin stack held Any? with Refs among them.
    private enum Ref {
        case value(VsmxValue)
        case global(String)
        case local(Frame, Int)
        case property(VsmxValue, String)
        case element(VsmxValue, VsmxValue)
    }

    static let MAX_STEPS = 200_000
    static let MAX_STACK = 4096
    static let MAX_DEPTH = 64
    static let MAX_ARRAY = 1 << 16
    static let LOCALS = 16
    /// Swift-only: array slots made per budget window.
    static let MAX_ALLOCATION = 1 << 22
    /// Swift-only: UTF-16 units of string made per budget window, and the longest `str` result.
    static let MAX_STRING = 1 << 16
    /// Swift-only: a longer string is not a number, since Double's parser reads the whole string every time.
    static let MAX_NUMBER_TEXT = 1 << 10

    private let program: Program
    // Swift: weak, because the host (the scene) owns this VM; a strong reference back would be a retain cycle.
    private weak var host: VsmxHost?
    private var globals: [String: VsmxValue] = [:]
    private var budget = 0
    private var depth = 0
    private var shared: Int?
    private var allocated = 0
    private var stringUnits = 0

    /// Timers it started before that still run.
    private(set) var failed: String?

    /// Swift-only: when set, every call takes its steps from this one budget until it is set again.
    var stepBudget: Int? {
        get { shared }
        set {
            shared = newValue
            allocated = 0
            stringUnits = 0
        }
    }

    init(program: Program, host: VsmxHost) {
        self.program = program
        self.host = host
    }

    // shortcut: this breaks ARC cycles the globals reach, but a dropped cycle leaks; use a weak arena if a theme shows it.
    deinit {
        var seen = Set<ObjectIdentifier>()
        var held: [AnyObject] = []
        var work = Array(globals.values)
        while let v = work.popLast() {
            switch v {
            case .array(let a) where seen.insert(ObjectIdentifier(a)).inserted:
                held.append(a)
                work += a.items
                work += a.props.map { Array($0.values) } ?? []
            case .object(let o) where seen.insert(ObjectIdentifier(o)).inserted:
                held.append(o)
                work += o.props.values
                if let p = o.proto { work.append(.object(p)) }
            case .function(let f) where seen.insert(ObjectIdentifier(f)).inserted:
                held.append(f)
                work += f.props.values
            default:
                break
            }
        }
        for x in held {
            if let a = x as? JsArray {
                a.items.removeAll()
                a.props = nil
            } else if let o = x as? JsObject {
                o.props.removeAll()
            } else if let f = x as? VsmxFunction {
                f.props.removeAll()
            }
        }
    }

    func run() {
        failed = guarded { _ = try execute(0, Frame(count: Self.LOCALS), .undefined) }
    }

    /// Nil when it ran to the end, else why it stopped.
    func call(_ fn: VsmxValue, this: VsmxValue = .undefined, args: [VsmxValue] = []) -> String? {
        guarded { _ = try invoke(fn, this, args) }
    }

    // Swift: one catch stands for the Kotlin's two; everything stops at MAX_DEPTH, so no stack overflow is left.
    private func guarded(_ block: () throws -> Void) -> String? {
        let start = min(Self.MAX_STEPS, max(shared ?? Self.MAX_STEPS, 0))
        budget = start
        depth = 0
        if shared == nil {
            allocated = 0
            stringUnits = 0
        }
        defer {
            if let s = shared { shared = s - (start - max(budget, 0)) }
        }
        do {
            try block()
            return nil
        } catch let e as VsmxError {
            return e.message
        } catch {
            return "\(error)"
        }
    }

    private func invoke(_ fn: VsmxValue, _ this: VsmxValue, _ args: [VsmxValue]) throws -> VsmxValue {
        switch fn {
        case .native(let n):
            return try n.call(this, args)
        case .function(let f):
            // Swift: the decrement is deferred before the check, so a refused call does not leave depth raised.
            depth += 1
            defer { depth -= 1 }
            if depth > Self.MAX_DEPTH { throw VsmxError("call depth") }
            let frame = Frame(count: max(f.locals, f.params + 1, 1) + 1)
            frame.slots[0] = .array(JsArray(args))
            for i in 0 ..< min(f.params, args.count) { frame.slots[1 + i] = args[i] }
            return try execute(f.start, frame, this)
        default:
            throw VsmxError("not a function: \(Self.describe(fn))")
        }
    }

    private func execute(_ start: Int, _ frame: Frame, _ this: VsmxValue) throws -> VsmxValue {
        let op = program.op
        let arg = program.arg
        var stack: [Ref] = []
        stack.reserveCapacity(32)
        var handlers: [Int] = []
        func push(_ r: Ref) throws {
            if stack.count > Self.MAX_STACK { throw VsmxError("stack") }
            stack.append(r)
        }
        func value(_ v: VsmxValue) throws { try push(.value(v)) }
        func pop() throws -> Ref {
            guard let r = stack.popLast() else { throw VsmxError("stack underflow") }
            return r
        }
        func popValue() throws -> VsmxValue { try deref(pop()) }
        func peek() throws -> Ref {
            guard let r = stack.last else { throw VsmxError("stack underflow") }
            return r
        }
        func prop(_ i: Int32) throws -> String {
            guard i >= 0, Int(i) < program.props.count else { throw VsmxError("property \(i)") }
            return program.props[Int(i)]
        }
        func popArgs(_ n: Int32) throws -> [VsmxValue] {
            guard n >= 0, Int(n) <= stack.count else { throw VsmxError("arguments") }
            let a = try stack.suffix(Int(n)).map { try deref($0) }
            stack.removeLast(Int(n))
            return a
        }

        var pc = start
        while true {
            guard pc >= 0, pc < op.count else { throw VsmxError("pc \(pc)") }
            budget -= 1
            if budget < 0 { throw VsmxError("step budget") }
            let code = op[pc]
            let a = arg[pc]
            // Swift: Kotlin's `code ushr 8` is a logical shift; on the UInt32 bit pattern `>>` is one too.
            let u = UInt32(bitPattern: code)
            pc += 1
            do {
                switch code & 0xFF {
                case 0x01: let v = try popValue(); try assign(pop(), v); try value(v)
                case 0x02: let b = try popValue(); try value(add(popValue(), b))
                case 0x03: let b = try popValue(); try value(arith(popValue(), b) { $0 - $1 })
                case 0x04: let b = try popValue(); try value(arith(popValue(), b) { $0 * $1 })
                case 0x05: let b = try popValue(); try value(arith(popValue(), b) { $0 / $1 })
                // Swift: Kotlin's Double `%` is truncatingRemainder.
                case 0x06: let b = try popValue(); try value(arith(popValue(), b) { $0.truncatingRemainder(dividingBy: $1) })
                case 0x07: try value(.number(Self.num(popValue())))
                case 0x08: try value(arith(popValue(), .number(-1)) { $0 * $1 })
                case 0x09: try value(.bool(!Self.truthy(popValue())))
                case 0x0a, 0x0b:
                    let r = try pop()
                    let v = Self.num(try deref(r)) + (code & 0xFF == 0x0a ? 1 : -1)
                    try assign(r, .number(v))
                    try value(.number(v))
                case 0x0c, 0x0d:
                    let r = try pop()
                    let old = Self.num(try deref(r))
                    try assign(r, .number(old + (code & 0xFF == 0x0c ? 1 : -1)))
                    try value(.number(old))
                case 0x0e: let b = try popValue(); try value(.bool(equals(popValue(), b)))
                case 0x0f: let b = try popValue(); try value(.bool(!equals(popValue(), b)))
                case 0x10: let b = try popValue(); try value(.bool(Self.strictEqual(popValue(), b)))
                case 0x11: let b = try popValue(); try value(.bool(!Self.strictEqual(popValue(), b)))
                case 0x12: let b = try popValue(); try value(.bool(Self.num(popValue()) < Self.num(b)))
                case 0x13: let b = try popValue(); try value(.bool(Self.num(popValue()) <= Self.num(b)))
                case 0x14: let b = try popValue(); try value(.bool(Self.num(popValue()) >= Self.num(b)))
                case 0x15: let b = try popValue(); try value(.bool(Self.num(popValue()) > Self.num(b)))
                case 0x20: try push(peek())
                case 0x21: let b = try pop(); let c = try pop(); try push(b); try push(c)
                case 0x22: _ = try pop()
                case 0x23: try value(.undefined)
                case 0x24: try value(.null)
                case 0x25: try value(.bool(a != 0))
                case 0x26: try value(.number(Double(a)))
                // Swift: Float.fromBits is Float(bitPattern:) over the operand's unsigned bit pattern.
                case 0x27: try value(.number(Double(Float(bitPattern: UInt32(bitPattern: a)))))
                case 0x28:
                    guard a >= 0, Int(a) < program.text.count else { throw VsmxError("text \(a)") }
                    try value(.string(program.text[Int(a)]))
                case 0x29: try value(.object(JsObject()))
                case 0x2a: try value(.function(VsmxFunction(start: Int(a), params: Int(u >> 8 & 0xFF), locals: Int(u >> 24 & 0xFF))))
                case 0x2b: try value(.array(JsArray()))
                case 0x2c: try value(this)
                case 0x2d:
                    guard a >= 0, Int(a) < frame.slots.count else { throw VsmxError("local \(a)") }
                    try push(.local(frame, Int(a)))
                case 0x2e:
                    guard a >= 0, Int(a) < program.names.count else { throw VsmxError("name \(a)") }
                    try push(.global(program.names[Int(a)]))
                case 0x2f: let o = try popValue(); try push(.property(o, prop(a)))
                case 0x30: let o = try popValue(); try value(o); try value(getProperty(o, prop(a)))
                case 0x31: let v = try popValue(); try setProperty(popValue(), prop(a), v); try value(v)
                case 0x33: let v = try popValue(); try setProperty(deref(peek()), prop(a), v)
                case 0x34: let i = try popValue(); try push(.element(popValue(), i))
                case 0x36: let v = try popValue(); let i = try popValue(); try setIndex(popValue(), i, v); try value(v)
                case 0x38:
                    let v = try popValue()
                    guard case .array(let target) = try deref(peek()) else { throw VsmxError("push to non-array") }
                    try append(target, [v])
                case 0x39: pc = Int(a)
                case 0x3a: if Self.truthy(try popValue()) { pc = Int(a) }
                case 0x3b: if !Self.truthy(try popValue()) { pc = Int(a) }
                case 0x3c: let args = try popArgs(a); try value(invoke(popValue(), .undefined, args))
                case 0x3d: let args = try popArgs(a); let fn = try popValue(); try value(invoke(fn, popValue(), args))
                case 0x3e: let args = try popArgs(a); try value(construct(popValue(), args))
                case 0x3f: return try popValue()
                case 0x41: handlers.append(Int(a))
                case 0x42: _ = handlers.popLast()
                case 0x43: pc = Int(a)
                case 0x44: break
                case 0x45: return .undefined
                case 0x49: try value(.array(JsArray(popArgs(a))))
                case 0x4a: let o = try popValue(); try push(.element(o, .number(Double(u >> 8))))
                case 0x4d:
                    let v = try popValue()
                    let o = try popValue()
                    let name = try prop(a)
                    guard case .array(let vec) = try getProperty(o, name) else { throw VsmxError("\(name) is not a vector") }
                    try charge(slots: vec.items.count)
                    let copy = JsArray(vec.items)
                    try store(copy, Int(u >> 8), v)
                    try setProperty(o, name, .array(copy))
                    try value(v)
                default:
                    throw VsmxError(String(format: "opcode 0x%02lx at %ld", Int(code & 0xFF), pc - 1))
                }
            } catch let e as VsmxError {
                // The catch block starts just after the jump that skips it; otherwise the error goes up with where it failed.
                guard let handler = handlers.popLast() else { throw VsmxError("\(e.message) @\(pc - 1)") }
                pc = handler + 1
                stack.removeAll()
                try value(.string(e.message))
            }
        }
    }

    private func deref(_ r: Ref) throws -> VsmxValue {
        switch r {
        case .value(let v): return v
        case .global(let name): return globals[name] ?? host?.global(name) ?? .undefined
        // Swift: a stored null reads back as null where Kotlin made it undefined; they differ only to `===` and `str`.
        case .local(let f, let slot): return f.slots[slot]
        case .property(let target, let name): return try getProperty(target, name)
        case .element(let target, let i): return try index(target, i)
        }
    }

    private func assign(_ target: Ref, _ v: VsmxValue) throws {
        switch target {
        case .global(let name): globals[name] = v
        case .local(let f, let slot): f.slots[slot] = v
        case .property(let t, let name): try setProperty(t, name, v)
        case .element(let t, let i): try setIndex(t, i, v)
        case .value(let x): throw VsmxError("assignment to \(Self.describe(x))")
        }
    }

    private func construct(_ ctor: VsmxValue, _ args: [VsmxValue]) throws -> VsmxValue {
        switch ctor {
        case .constructor(let c):
            let made = try c.make(args)
            // Swift: `new Array(65536)` is one step, so what a host constructor makes counts against the window.
            if case .array(let a) = made { try charge(slots: a.items.count) }
            return made
        case .function(let f):
            // Unless the constructor returns an object of its own, as one may.
            let made = JsObject(proto: f.prototype)
            let result = try invoke(ctor, .object(made), args)
            switch result {
            case .object, .array, .host, .function: return result
            default: return .object(made)
            }
        default:
            throw VsmxError("not a constructor: \(Self.describe(ctor))")
        }
    }

    private func getProperty(_ o: VsmxValue, _ name: String) throws -> VsmxValue {
        switch o {
        case .array(let a):
            if name == "length" { return .number(Double(a.items.count)) }
            if name == "push" { return .native(pushNative()) }
            if let v = a.props?[name] { return v }
            host?.unsupported("Array.\(name)")
            return .undefined
        case .object(let x):
            return lookup(x, name)
        case .function(let f):
            if name == "prototype" { return .object(f.prototype) }
            return f.props[name] ?? .undefined
        case .host(let h):
            return try h.get(name)
        case .string(let s):
            // Swift: Kotlin's length counts UTF-16 units, so this does.
            if name == "length" { return .number(Double(s.utf16.count)) }
            host?.unsupported("String.\(name)")
            return .undefined
        case .null, .undefined:
            throw VsmxError("property \(name) of \(Self.describe(o))")
        default:
            return .undefined
        }
    }

    // Swift: a `push` called with another `this` is a VsmxError; the native holds the VM weakly because a script can keep it.
    private func pushNative() -> VsmxNative {
        VsmxNative(name: "push") { [weak self] this, args in
            guard case .array(let a) = this else { throw VsmxError("push on a non-array") }
            guard let vm = self else { throw VsmxError("push after the script ended") }
            try vm.append(a, args)
            return .number(Double(a.items.count))
        }
    }

    private func lookup(_ o: JsObject, _ name: String) -> VsmxValue {
        var at: JsObject? = o
        var hops = 0
        while let x = at, hops < Self.MAX_DEPTH {
            hops += 1
            if let v = x.props[name] { return v }
            at = x.proto
        }
        return .undefined
    }

    private func setProperty(_ o: VsmxValue, _ name: String, _ v: VsmxValue) throws {
        switch o {
        case .object(let x): x.props[name] = v
        case .function(let f): f.props[name] = v
        case .array(let a):
            if a.props == nil { a.props = [:] }
            a.props?[name] = v
        case .host(let h): try h.set(name, v)
        default: throw VsmxError("set \(name) on \(Self.describe(o))")
        }
    }

    // Swift: a NaN, infinite or out-of-range index reads undefined, where Kotlin saturated and Swift's Int(_:) would trap.
    private static func slot(_ d: Double) -> Int? {
        guard d >= 0, d <= Double(MAX_ARRAY) else { return nil }
        return saturatingInt(d)
    }

    private func index(_ o: VsmxValue, _ i: VsmxValue) throws -> VsmxValue {
        switch o {
        case .array(let a):
            if case .string(let s) = i, Self.number(s) == nil { return try getProperty(o, s) }
            guard let k = Self.slot(Self.num(i)), k < a.items.count else { return .undefined }
            return a.items[k]
        case .object(let x):
            return lookup(x, try text(i))
        case .host(let h):
            return try h.get(try text(i))
        case .string(let s):
            // Swift: Kotlin indexes UTF-16 units, so this does; a lone surrogate comes back as U+FFFD.
            let units = s.utf16
            guard let k = Self.slot(Self.num(i)), k < units.count else { return .undefined }
            return .string(String(decoding: [units[units.index(units.startIndex, offsetBy: k)]], as: UTF16.self))
        default:
            throw VsmxError("index of \(Self.describe(o))")
        }
    }

    private func setIndex(_ o: VsmxValue, _ i: VsmxValue, _ v: VsmxValue) throws {
        switch o {
        case .array(let a):
            guard let k = Self.slot(Self.num(i)) else { throw VsmxError("array index") }
            try store(a, k, v)
        case .object(let x): x.props[try text(i)] = v
        case .host(let h): try h.set(try text(i), v)
        default: throw VsmxError("index set on \(Self.describe(o))")
        }
    }

    /// The Kotlin's JsArray.set.
    private func store(_ a: JsArray, _ i: Int, _ v: VsmxValue) throws {
        if i < 0 || i > Self.MAX_ARRAY { throw VsmxError("array index \(i)") }
        if a.items.count <= i {
            let grow = i + 1 - a.items.count
            try charge(slots: grow)
            a.items.append(contentsOf: repeatElement(VsmxValue.undefined, count: grow))
        }
        a.items[i] = v
    }

    // Swift: `push` stops an array at the length an element write can reach, so a timer cannot grow one without bound.
    private func append(_ a: JsArray, _ values: [VsmxValue]) throws {
        if a.items.count + values.count > Self.MAX_ARRAY + 1 { throw VsmxError("array size") }
        try charge(slots: values.count)
        a.items.append(contentsOf: values)
    }

    // MARK: - The Swift-only budget window

    private func charge(work n: Int) throws {
        budget -= n >> 6
        if budget < 0 { throw VsmxError("step budget") }
    }

    private func charge(slots n: Int) throws {
        allocated += n
        if allocated > Self.MAX_ALLOCATION { throw VsmxError("allocation") }
        try charge(work: n)
    }

    private func charge(units n: Int) throws {
        stringUnits += n
        if stringUnits > Self.MAX_STRING { throw VsmxError("string length") }
        try charge(work: n)
    }

    private func text(_ v: VsmxValue) throws -> String {
        let s = try Self.str(v)
        if case .array = v { try charge(work: s.utf16.count) }
        return s
    }

    private func equals(_ a: VsmxValue, _ b: VsmxValue) throws -> Bool {
        var visits = 0
        let same = try Self.equal(a, b, depth: 0, visits: &visits)
        try charge(work: visits)
        return same
    }

    private func add(_ a: VsmxValue, _ b: VsmxValue) throws -> VsmxValue {
        guard Self.isString(a) || Self.isString(b) else { return try arith(a, b) { $0 + $1 } }
        let x = try text(a)
        let y = try text(b)
        try charge(units: x.utf16.count + y.utf16.count)
        return .string(x + y)
    }

    private func arith(_ a: VsmxValue, _ b: VsmxValue, _ f: (Double, Double) -> Double) throws -> VsmxValue {
        switch (a, b) {
        case (.array(let x), .array(let y)):
            let n = min(x.items.count, y.items.count)
            try charge(slots: n)
            return .array(JsArray((0 ..< n).map { .number(f(Self.num(x.items[$0]), Self.num(y.items[$0]))) }))
        case (.array(let x), _):
            try charge(slots: x.items.count)
            let k = Self.num(b)
            return .array(JsArray(x.items.map { .number(f(Self.num($0), k)) }))
        case (_, .array(let y)):
            try charge(slots: y.items.count)
            let k = Self.num(a)
            return .array(JsArray(y.items.map { .number(f(k, Self.num($0))) }))
        default:
            return .number(f(Self.num(a), Self.num(b)))
        }
    }

    // MARK: - Values

    // Swift: Double's parser and Kotlin's differ on a few spellings, none of them a number a theme writes.
    private static func number(_ s: String) -> Double? {
        s.utf16.count <= MAX_NUMBER_TEXT ? Double(s) : nil
    }

    static func num(_ v: VsmxValue) -> Double {
        switch v {
        case .number(let d): return d
        case .bool(let b): return b ? 1 : 0
        case .null: return 0
        case .string(let s):
            // Swift: Kotlin's trim() is trimmingCharacters(in: .whitespacesAndNewlines), after the length check.
            guard s.utf16.count <= MAX_NUMBER_TEXT else { return .nan }
            return number(s.trimmingCharacters(in: .whitespacesAndNewlines)) ?? .nan
        default: return .nan
        }
    }

    static func truthy(_ v: VsmxValue) -> Bool {
        switch v {
        case .null, .undefined: return false
        case .bool(let b): return b
        case .number(let d): return d != 0 && !d.isNaN
        case .string(let s): return !s.isEmpty
        default: return true
        }
    }

    /// Throws VsmxError for a cyclic array or a result over MAX_STRING units.
    static func str(_ v: VsmxValue) throws -> String { try str(v, depth: 0) }

    private static func str(_ v: VsmxValue, depth: Int) throws -> String {
        switch v {
        case .number(let d):
            // Swift: Kotlin prints NaN, Infinity and -Infinity; Swift would print nan and inf.
            if d.isNaN { return "NaN" }
            if d.isInfinite { return d < 0 ? "-Infinity" : "Infinity" }
            if d == d.rounded(.down) && abs(d) < 1e15 { return String(Int64(d)) }
            return "\(d)"
        case .null: return "null"
        case .undefined: return "undefined"
        case .bool(let b): return b ? "true" : "false"
        case .string(let s): return s
        case .array(let a):
            if depth >= MAX_DEPTH { throw VsmxError("depth") }
            var out = ""
            var units = 0
            for (i, item) in a.items.enumerated() {
                let s = try str(item, depth: depth + 1)
                units += s.utf16.count + (i > 0 ? 1 : 0)
                if units > MAX_STRING { throw VsmxError("string length") }
                if i > 0 { out += "," }
                out += s
            }
            return out
        // Swift: Kotlin printed an object's JVM toString(), a class name and a hash; these are fixed words.
        case .object, .host: return "[object Object]"
        case .function, .native, .constructor: return "function"
        }
    }

    private static func describe(_ v: VsmxValue) -> String {
        switch v {
        case .null: return "null"
        case .undefined: return "undefined"
        case .number: return "number"
        case .string: return "string"
        case .array: return "array"
        case .native(let n): return "function \(n.name)"
        case .bool: return "Boolean"
        case .object: return "JsObject"
        case .function: return "Function"
        case .constructor: return "Constructor"
        case .host: return "HostObject"
        }
    }

    private static func isString(_ v: VsmxValue) -> Bool {
        if case .string = v { return true }
        return false
    }

    private static func nullish(_ v: VsmxValue) -> Bool {
        switch v {
        case .null, .undefined: return true
        default: return false
        }
    }

    // Swift: Kotlin compared strings unit by unit; Swift's == is canonical equivalence, so two spellings of one letter match.
    private static func equal(_ a: VsmxValue, _ b: VsmxValue, depth: Int, visits: inout Int) throws -> Bool {
        let an = nullish(a), bn = nullish(b)
        if an && bn { return true }
        if an || bn { return false }
        switch (a, b) {
        case (.array(let x), .array(let y)):
            if depth >= MAX_DEPTH { throw VsmxError("depth") }
            if x.items.count != y.items.count { return false }
            visits += x.items.count
            if visits > MAX_ALLOCATION { throw VsmxError("comparison size") }
            for i in 0 ..< x.items.count {
                if try !equal(x.items[i], y.items[i], depth: depth + 1, visits: &visits) { return false }
            }
            return true
        case (.string(let x), .string(let y)):
            return x == y
        case (.number, _), (_, .number), (.bool, _), (_, .bool):
            return num(a) == num(b)
        default:
            return same(a, b)
        }
    }

    private static func strictEqual(_ a: VsmxValue, _ b: VsmxValue) -> Bool {
        switch (a, b) {
        case (.number(let x), .number(let y)): return x == y
        case (.string(let x), .string(let y)): return x == y
        case (.bool(let x), .bool(let y)): return x == y
        default: return same(a, b)
        }
    }

    /// The Kotlin's `===`.
    private static func same(_ a: VsmxValue, _ b: VsmxValue) -> Bool {
        switch (a, b) {
        case (.undefined, .undefined), (.null, .null): return true
        case (.array(let x), .array(let y)): return x === y
        case (.object(let x), .object(let y)): return x === y
        case (.function(let x), .function(let y)): return x === y
        case (.native(let x), .native(let y)): return x === y
        case (.constructor(let x), .constructor(let y)): return x === y
        case (.host(let x), .host(let y)): return x === y
        default: return false
        }
    }
}
