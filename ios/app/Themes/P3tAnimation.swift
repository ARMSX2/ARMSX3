// SPDX-License-Identifier: GPL-2.0-only
// Ported from Android's P3tAnimation.kt: a dynamic theme's RAF scene read as a slideshow. A theme is hostile input.

import Foundation

enum P3tAnimation {
    /// With `format` `picture` it is a whole JPEG or PNG file instead of a GTF, which the platform decodes.
    struct Texture: Equatable {
        let format: Int
        let width: Int
        let height: Int
        let pitch: Int
        let offset: Int
        let size: Int
    }

    static let picture = 0x1000

    // P3tAnimation.kt 56-87.
    private static let RAF_HEADER_BYTES = 8
    private static let RAFO = 0x5241464F // "RAFO"
    private static let HEADER_BYTES = 56
    private static let ELEMENT_BYTES = 28
    private static let ATTRIBUTE_BYTES = 16
    private static let TYPE_VECTOR = 5
    private static let TYPE_FILE = 6
    private static let TYPE_ID = 7
    private static let TYPE_REF = 8
    private static let GTF_HEADER_BYTES = 48

    // GCM texture formats, with the linear (0x20) and unnormalised (0x40) flags taken off.
    private static let FORMAT_FLAGS = 0x60
    private static let DXT1 = 0x86
    private static let DXT3 = 0x87
    private static let DXT5 = 0x88

    private static let MAX_TABLE_BYTES = 16 << 20
    private static let MAX_TEXTURE_BYTES = 16 << 20
    private static let MAX_SCRIPT_BYTES = 1 << 20
    private static let MAX_MESH_BYTES = 4 << 20
    private static let MAX_SIDE = 4096
    private static let MAX_NAME_BYTES = 256
    private static let MAX_VECTOR = 16
    // Swift: kept as the Kotlin's input step; ThemeZlib.chunkBytes, the same 256 KiB, is the one unpack reads by.
    private static let CHUNK_BYTES = 256 << 10

    // A full-screen layer: at least this wide, and between 3:2 and 2:1.
    private static let MIN_SCREEN_WIDTH = 960
    private static let MIN_ASPECT: Float = 1.5
    private static let MAX_ASPECT: Float = 2.0

    /// False when it is not a RAF, is damaged, would unpack past `cap`, or unpacks to nothing.
    static func unpack(_ src: ThemeByteSource, offset: Int, size: Int, cap: Int,
                       sink: ([UInt8]) throws -> Void) -> Bool {
        if offset < 0 || size < RAF_HEADER_BYTES || offset > src.size || size > src.size - offset { return false }
        guard let magic = try? src.read(offset, 4), magic == Array("_RAF".utf8) else { return false }
        // Swift: ThemeZlib stands for java.util.zip.Inflater; any throw, the sink's included, is false here.
        var written = 0
        do {
            try ThemeZlib.inflate(src, offset: offset + RAF_HEADER_BYTES, size: size - RAF_HEADER_BYTES, cap: cap) {
                written += $0.count
                try sink($0)
            }
        } catch {
            return false
        }
        return written > 0
    }

    static func texture(_ raf: ThemeByteSource, offset: Int, size: Int) -> Texture? {
        // Swift: a read the bounds checks refuse is no texture, where the Kotlin let the IOException out.
        (try? gtf(raf, offset: offset, size: size)) ?? (try? pictureTexture(raf, offset: offset, size: size)) ?? nil
    }

    static func isPicture(_ t: Texture) -> Bool { t.format == picture }

    private static let PICTURE_PROBE_BYTES = 64 << 10

    private static func pictureTexture(_ raf: ThemeByteSource, offset: Int, size: Int) throws -> Texture? {
        if size < 24 || size > MAX_TEXTURE_BYTES { return nil }
        let head = ByteReader(try raf.read(offset, min(size, PICTURE_PROBE_BYTES)))
        let png = try head.u8(0) == 0x89 && head.latin1(at: 1, count: 3) == "PNG"
        let dims: (Int, Int)
        if png {
            dims = (try head.be32(16), try head.be32(20))
        } else if let found = P3tTheme.jpegSize(head.bytes) {
            dims = (found.width, found.height)
        } else if size > head.count, let found = P3tTheme.jpegSize(try raf.read(offset, size)) {
            dims = (found.width, found.height)
        } else {
            return nil
        }
        if !(1 ... MAX_SIDE).contains(dims.0) || !(1 ... MAX_SIDE).contains(dims.1) { return nil }
        return Texture(format: picture, width: dims.0, height: dims.1, pitch: 0, offset: offset, size: size)
    }

    /// Only a plain 2D texture in a format decoded here.
    static func gtf(_ raf: ThemeByteSource, offset: Int, size: Int) throws -> Texture? {
        if size < GTF_HEADER_BYTES { return nil }
        let h = ByteReader(try raf.read(offset, GTF_HEADER_BYTES))
        if try h.be32(8) < 1 { return nil }
        let data = try h.be32(16)
        let length = try h.be32(20)
        let format = try h.u8(24)
        let dimension = try h.u8(26)
        let cube = try h.u8(27)
        let width = try h.be16(32)
        let height = try h.be16(34)
        let pitch = try h.be32(40)
        if dimension != 2 || cube != 0 || !(1 ... MAX_SIDE).contains(width) || !(1 ... MAX_SIDE).contains(height) {
            return nil
        }
        guard let block = blockBytes(format) else { return nil }
        let row = (width + 3) / 4 * block
        let rowPitch = pitch >= row ? pitch : row
        let needed = rowPitch * ((height + 3) / 4 - 1) + row
        if data > size || length > size - data || needed > length || needed > MAX_TEXTURE_BYTES { return nil }
        // Swift: rowPitch stays 64-bit, where Kotlin wrapped a pitch near 2^32 to a negative one.
        return Texture(format: format, width: width, height: height, pitch: rowPitch, offset: offset + data,
                       size: needed)
    }

    /// 1, 3 or 5, or 0 for a format that is not block compressed.
    static func dxt(_ t: Texture) -> Int {
        switch t.format & ~FORMAT_FLAGS {
        case DXT1: return 1
        case DXT3: return 3
        case DXT5: return 5
        default: return 0
        }
    }

    private static func blockBytes(_ format: Int) -> Int? {
        switch format & ~FORMAT_FLAGS {
        case DXT1: return 8
        case DXT3, DXT5: return 16
        default: return nil
        }
    }

    /// ARGB with alpha in the top byte; nil when the pixels cannot be read.
    static func decode(_ raf: ThemeByteSource, _ t: Texture) -> [UInt32]? {
        guard let bytes = try? raf.read(t.offset, t.size) else { return nil }
        return decodeBlocks(bytes, t)
    }

    static func decodeBlocks(_ data: [UInt8], _ t: Texture) -> [UInt32]? {
        if isPicture(t) { return nil } // the platform's to decode
        let kind = t.format & ~FORMAT_FLAGS
        guard let block = blockBytes(t.format) else { return nil }
        // Swift: a Texture built anywhere but gtf() is checked too, so the pixel count and sums can neither trap nor overflow.
        guard (1 ... MAX_SIDE).contains(t.width), (1 ... MAX_SIDE).contains(t.height),
              t.pitch >= 0, t.pitch <= Int(UInt32.max) else { return nil }
        let wide = (t.width + 3) / 4
        let high = (t.height + 3) / 4
        if data.count < t.pitch * (high - 1) + wide * block { return nil }
        let r = ByteReader(data)
        var out = [UInt32](repeating: 0, count: t.width * t.height)
        var palette = [UInt32](repeating: 0, count: 4)
        var alpha = [UInt32](repeating: 0, count: 16)
        let colorAt = kind == DXT1 ? 0 : 8
        do {
            for by in 0 ..< high {
                for bx in 0 ..< wide {
                    let at = by * t.pitch + bx * block
                    switch kind {
                    case DXT3:
                        for k in 0 ..< 16 { alpha[k] = UInt32((try r.u8(at + k / 2) >> ((k & 1) * 4)) & 0xF) * 17 }
                    case DXT5:
                        let a0 = try r.u8(at)
                        let a1 = try r.u8(at + 1)
                        var bits = 0
                        for i in 0 ..< 6 { bits |= try r.u8(at + 2 + i) << (8 * i) }
                        for k in 0 ..< 16 {
                            let i = (bits >> (3 * k)) & 7
                            let value: Int
                            if i == 0 { value = a0 }
                            else if i == 1 { value = a1 }
                            else if a0 > a1 { value = ((8 - i) * a0 + (i - 1) * a1) / 7 }
                            else if i == 6 { value = 0 }
                            else if i == 7 { value = 255 }
                            else { value = ((6 - i) * a0 + (i - 1) * a1) / 5 }
                            alpha[k] = UInt32(value)
                        }
                    default:
                        break
                    }
                    try colors(r, at + colorAt, kind == DXT1, &palette)
                    let indices = try r.le32(at + colorAt + 4)
                    for k in 0 ..< 16 {
                        let x = bx * 4 + (k & 3)
                        let y = by * 4 + (k >> 2)
                        if x >= t.width || y >= t.height { continue }
                        let c = palette[(indices >> (2 * k)) & 3]
                        out[y * t.width + x] = kind == DXT1 ? c : (c & 0xFFFFFF) | (alpha[k] << 24)
                    }
                }
            }
        } catch {
            // Swift: the Kotlin's pre-check above already keeps every read inside; this is the backstop.
            return nil
        }
        return out
    }

    /// DXT1 with c0 <= c1 has three colours and a see-through fourth.
    private static func colors(_ r: ByteReader, _ at: Int, _ dxt1: Bool, _ palette: inout [UInt32]) throws {
        let c0 = try r.le16(at)
        let c1 = try r.le16(at + 2)
        let r0 = expand5(c0 >> 11), g0 = expand6((c0 >> 5) & 63), b0 = expand5(c0 & 31)
        let r1 = expand5(c1 >> 11), g1 = expand6((c1 >> 5) & 63), b1 = expand5(c1 & 31)
        palette[0] = argb(r0, g0, b0)
        palette[1] = argb(r1, g1, b1)
        if !dxt1 || c0 > c1 {
            palette[2] = argb((2 * r0 + r1) / 3, (2 * g0 + g1) / 3, (2 * b0 + b1) / 3)
            palette[3] = argb((r0 + 2 * r1) / 3, (g0 + 2 * g1) / 3, (b0 + 2 * b1) / 3)
        } else {
            palette[2] = argb((r0 + r1) / 2, (g0 + g1) / 2, (b0 + b1) / 2)
            palette[3] = 0
        }
    }

    private static func expand5(_ v: Int) -> Int { (v << 3) | (v >> 2) }
    private static func expand6(_ v: Int) -> Int { (v << 2) | (v >> 4) }
    // Swift: ARGB is UInt32, where the Kotlin's Int went negative with alpha set.
    private static func argb(_ r: Int, _ g: Int, _ b: Int) -> UInt32 { 0xFF00_0000 | UInt32(r << 16 | g << 8 | b) }

    /// DXT1 always is: its one-bit alpha is how the templates' encoder stores pure black.
    static func isSlide(_ texture: Texture, _ argb: [UInt32]) -> Bool {
        (texture.format & ~FORMAT_FLAGS) == DXT1 || clearShare(argb) <= MAX_SLIDE_CLEAR_SHARE
    }

    private static let MAX_SLIDE_CLEAR_SHARE: Float = 0.01

    /// Alpha under 250 counts as see-through.
    static func clearShare(_ argb: [UInt32]) -> Float {
        if argb.isEmpty { return 1 }
        var clear = 0
        for p in argb where (p >> 24) < 250 { clear += 1 }
        return Float(clear) / Float(argb.count)
    }

    // MARK: - The scene

    /// `z` is its depth, for the zoom, when it has one.
    struct Layer {
        let name: String
        let texture: Texture
        let z: Float?
    }

    /// `interval` includes the `fade`; with `zoom` above zero the picture scales between 1 and 1 + `zoom`.
    struct Timing: Equatable {
        let interval: Float
        let fade: Float
        let zoom: Float
        let zoomInterval: Float
        let zoomMove: Float

        // Swift: a non-finite field takes defaultTiming's, because Kotlin's coerceIn lets NaN through.
        init(interval: Float, fade: Float, zoom: Float, zoomInterval: Float, zoomMove: Float) {
            self.interval = interval.isFinite ? interval : 10
            self.fade = fade.isFinite ? fade : 1.5
            self.zoom = zoom.isFinite ? zoom : 0
            self.zoomInterval = zoomInterval.isFinite ? zoomInterval : 0
            self.zoomMove = zoomMove.isFinite ? zoomMove : 0
        }
    }

    struct Scene {
        let layers: [Layer]
        let timing: Timing
    }

    /// What the slideshow templates do, for a script with nothing readable in it.
    static let defaultTiming = Timing(interval: 10, fade: 1.5, zoom: 0, zoomInterval: 0, zoomMove: 0)

    /// Nil when there is no slideshow in it.
    static func scene(_ raf: ThemeByteSource) -> Scene? {
        // Swift: one catch stands for the Kotlin's three; a bad actor id still refuses the whole scene, as Kotlin's does.
        do {
            return try parse(raf)
        } catch {
            return nil
        }
    }

    private struct Attr {
        let type: Int, a: Int, b: Int
    }

    private struct SceneActor {
        let id: Int, material: Int, model: Int?, z: Float?
    }

    private static let WANTED: Set<String> = ["actor", "material", "_texture", "texture", "file", "script", "model",
                                              "geometry"]

    private static func parse(_ raf: ThemeByteSource) throws -> Scene? {
        if raf.size < HEADER_BYTES { return nil }
        let header = ByteReader(try raf.read(0, HEADER_BYTES))
        if try header.be32(0) != RAFO { return nil }
        var tables: [(offset: Int, size: Int)] = []
        for i in 0 ..< 6 {
            let offset = try header.be32(8 + i * 8)
            let size = try header.be32(12 + i * 8)
            if offset > raf.size || size > raf.size - offset { throw ThemeDamaged() }
            tables.append((offset, size))
        }
        func table(_ i: Int) throws -> ByteReader {
            let (offset, size) = tables[i]
            if size > MAX_TABLE_BYTES { throw ThemeDamaged() }
            return ByteReader(try raf.read(offset, size))
        }
        let elements = try table(0)
        let ids = try table(1)
        let names = try table(2)
        let floats = try table(4)
        let (filesAt, filesSize) = tables[5]

        // Everything is joined by id, an offset into the id table; nothing here depends on dictionary order.
        var actors: [SceneActor] = []
        var materialAt: [Int: Int] = [:]      // material element offset -> its id
        var materialTexture: [Int: Int] = [:]
        var modelAt: [Int: Int] = [:]         // model element offset -> its id
        var modelGeometry: [Int: Int] = [:]
        var textureFile: [Int: Int] = [:]
        var fileSpan: [Int: Attr] = [:]
        var script: Int?

        var pos = 0
        while pos + ELEMENT_BYTES <= elements.count {
            let name = try cString(names, elements.be32(pos))
            let count = try elements.be32(pos + 4)
            let parent = try elements.be32(pos + 8)
            if count > (elements.count - pos - ELEMENT_BYTES) / ATTRIBUTE_BYTES { throw ThemeDamaged() }
            var attrs: [String: Attr] = [:]
            if WANTED.contains(name) {
                for i in 0 ..< count {
                    let at = pos + ELEMENT_BYTES + i * ATTRIBUTE_BYTES
                    attrs[try cString(names, elements.be32(at))] =
                        Attr(type: try elements.be32(at + 4), a: try elements.be32(at + 8), b: try elements.be32(at + 12))
                }
            }
            func ref(_ key: String) -> Int? {
                guard let attr = attrs[key], attr.type == TYPE_ID || attr.type == TYPE_REF else { return nil }
                return attr.a
            }
            switch name {
            case "actor":
                if let id = ref("id"), let material = ref("material") {
                    let position = try vector(floats, attrs["position"])
                    actors.append(SceneActor(id: id, material: material, model: ref("model"),
                                             z: position.flatMap { $0.count > 2 ? $0[2] : nil }))
                }
            case "material":
                if let id = ref("id") { materialAt[pos] = id }
            case "model":
                if let id = ref("id") { modelAt[pos] = id }
            case "geometry":
                if let model = modelAt[parent], let file = ref("fileref") { modelGeometry[model] = file }
            case "_texture":
                if let material = materialAt[parent], let texture = ref("texref") { materialTexture[material] = texture }
            case "texture":
                if let id = ref("id"), let file = ref("fileref") { textureFile[id] = file }
            case "file":
                if let id = ref("id"), let src = attrs["src"], src.type == TYPE_FILE { fileSpan[id] = src }
            case "script":
                if script == nil { script = ref("fileref") }
            default:
                break
            }
            pos += ELEMENT_BYTES + count * ATTRIBUTE_BYTES
        }

        // Nil when it points outside the file table.
        func file(_ id: Int) -> (offset: Int, size: Int)? {
            guard let span = fileSpan[id] else { return nil }
            if span.a > filesSize || span.b > filesSize - span.a { return nil }
            return (filesAt + span.a, span.b)
        }
        // A mesh that draws nothing is how theme authors hide a layer they do not want.
        func hidden(_ actor: SceneActor) throws -> Bool {
            guard let model = actor.model, let geometry = modelGeometry[model], let span = file(geometry)
            else { return false }
            guard (1 ... MAX_MESH_BYTES).contains(span.size) else { return false }
            return RafScene.collapsed(try raf.read(span.offset, span.size))
        }
        var layers: [Layer] = []
        for actor in actors {
            if try hidden(actor) { continue }
            guard let textureID = materialTexture[actor.material], let fileID = textureFile[textureID],
                  let span = file(fileID), let texture = try gtf(raf, offset: span.offset, size: span.size)
            else { continue }
            let aspect = Float(texture.width) / Float(texture.height)
            if texture.width < MIN_SCREEN_WIDTH || aspect < MIN_ASPECT || aspect > MAX_ASPECT { continue }
            layers.append(Layer(name: try cString(ids, actor.id + 4), texture: texture, z: actor.z))
        }
        guard let firstLayer = layers.first else { return nil }
        var code: [UInt8]?
        if let id = script, let span = file(id), (1 ... MAX_SCRIPT_BYTES).contains(span.size) {
            code = try raf.read(span.offset, span.size)
        }
        return Scene(layers: layers, timing: timing(code, planeZ: firstLayer.z))
    }

    private static func vector(_ floats: ByteReader, _ attr: Attr?) throws -> [Float]? {
        guard let attr = attr, attr.type == TYPE_VECTOR, (1 ... MAX_VECTOR).contains(attr.b) else { return nil }
        if attr.a > floats.count / 4 || attr.b > floats.count / 4 - attr.a { return nil }
        return try (0 ..< attr.b).map { Float(bitPattern: UInt32(truncatingIfNeeded: try floats.be32((attr.a + $0) * 4))) }
    }

    /// Throws for an offset outside the table.
    private static func cString(_ table: ByteReader, _ offset: Int) throws -> String {
        try table.cString(at: offset, limit: MAX_NAME_BYTES)
    }

    // MARK: - The script, compiled VSMX of which only the slideshow templates' timer opcodes are recognised

    private static let VSMX = 0x584D5356 // "VSMX", little-endian
    private static let VSMX_HEADER_BYTES = 52
    private static let MAX_OPS = 1 << 16
    private static let MAX_STRINGS = 1 << 12
    private static let OP_ASSIGN = 0x01
    private static let OP_NEGATE = 0x08
    private static let OP_END = 0x22
    private static let OP_INT = 0x26
    private static let OP_FLOAT = 0x27
    private static let OP_FUNCTION = 0x2A // low byte; the rest are flags
    private static let OP_GLOBAL = 0x2E
    private static let OP_METHOD = 0x30
    private static let OP_JUMP = 0x39
    private static let OP_CALL_METHOD = 0x3D
    private static let OP_NEW = 0x3E
    private static let OP_ARRAY = 0x49

    private struct Script {
        let names: [String], props: [String], op: [Int], arg: [Int]
    }

    private static func readScript(_ bytes: [UInt8]) -> Script? {
        let r = ByteReader(bytes)
        do {
            if r.count < VSMX_HEADER_BYTES { return nil }
            if try r.le32(0) != VSMX { return nil }
            func word(_ i: Int) throws -> Int { try r.le32(4 * i) }
            let codeAt = try word(2)
            let codeSize = try word(3)
            if codeAt > r.count || codeSize > r.count - codeAt || codeSize / 8 > MAX_OPS { return nil }
            guard let props = try strings(r, word(7), word(8), word(9), wide: true),
                  let names = try strings(r, word(10), word(11), word(12), wide: false) else { return nil }
            let count = codeSize / 8
            // Swift: the Kotlin's le32 is a signed Int; Int32(truncatingIfNeeded:) keeps an operand's sign.
            let op = try (0 ..< count).map { Int(Int32(truncatingIfNeeded: try r.le32(codeAt + 8 * $0))) }
            let arg = try (0 ..< count).map { Int(Int32(truncatingIfNeeded: try r.le32(codeAt + 8 * $0 + 4))) }
            return Script(names: names, props: props, op: op, arg: arg)
        } catch {
            // Swift: the Kotlin read past its checks only through le32's own bounds; one catch covers them all.
            return nil
        }
    }

    private static func strings(_ r: ByteReader, _ at: Int, _ size: Int, _ count: Int, wide: Bool) throws -> [String]? {
        if at > r.count || size > r.count - at || count > MAX_STRINGS { return nil }
        let text = wide ? try r.utf16le(at: at, count: size) : try r.latin1(at: at, count: size)
        // Swift: nulSplit keeps empty names as Kotlin's split does, so no later index shifts.
        return nulSplit(text, count: count)
    }

    /// Kotlin's getOrNull, which takes a negative index too.
    private static func item<T>(_ list: [T], _ i: Int) -> T? { i >= 0 && i < list.count ? list[i] : nil }

    /// Kotlin's coerceIn without its argument check: NaN passes through, and Timing's initialiser replaces it.
    private static func coerce(_ v: Float, _ lo: Float, _ hi: Float) -> Float { v < lo ? lo : (v > hi ? hi : v) }

    /// The interval is the IntervalTimer's and the fade the time given to setColor; anything not found keeps defaultTiming.
    static func timing(_ script: [UInt8]?, planeZ: Float?) -> Timing {
        guard let bytes = script, let s = readScript(bytes) else { return defaultTiming }
        let n = s.op.count
        func op(_ i: Int) -> Int { i >= 0 && i < n ? s.op[i] : -1 }
        func arg(_ i: Int) -> Int { i >= 0 && i < n ? s.arg[i] : -1 }
        func global(_ i: Int) -> Int { op(i) == OP_GLOBAL ? arg(i) : -1 }
        func name(_ i: Int) -> String? { item(s.names, global(i)) }
        // One instruction, or two for a negative number.
        func constant(_ i: Int) -> (Float, Int)? {
            let v: Float
            switch op(i) {
            case OP_INT: v = Float(arg(i))
            case OP_FLOAT: v = Float(bitPattern: UInt32(truncatingIfNeeded: arg(i)))
            default: return nil
            }
            return op(i + 1) == OP_NEGATE ? (-v, 2) : (v, 1)
        }

        var numbers: [Int: Float] = [:]        // global -> the number assigned to it
        var vectors: [Int: [Float]] = [:]      // global -> the array of numbers assigned to it
        var bodies: [Int: Range<Int>] = [:]    // global -> the function assigned to it
        var cameras = Set<Int>()               // globals holding a new Camera(...)
        for i in 0 ..< n {
            let target = global(i)
            if target < 0 { continue }
            if let c = constant(i + 1), op(i + 1 + c.1) == OP_ASSIGN { numbers[target] = c.0 }
            var j = i + 1
            var values: [Float] = []
            while values.count < MAX_VECTOR, let c = constant(j) {
                values.append(c.0)
                j += c.1
            }
            if !values.isEmpty && op(j) == OP_ARRAY && arg(j) == values.count && op(j + 1) == OP_ASSIGN {
                vectors[target] = values
            }
            // The body runs from the function's operand to the jump past it.
            if (op(i + 1) & 0xFF) == OP_FUNCTION && op(i + 2) == OP_ASSIGN && op(i + 3) == OP_END && op(i + 4) == OP_JUMP {
                let start = arg(i + 1)
                let end = arg(i + 4)
                if start >= 0 && start <= end && end <= n { bodies[target] = start ..< end }
            }
            // Swift: an empty range where Kotlin's was empty, since Swift's ... traps on a lower bound above the upper.
            if name(i + 1) == "Camera", i + 2 <= min(i + 6, n - 1) {
                let made = (i + 2 ... min(i + 6, n - 1)).first { op($0) == OP_NEW }
                if let made = made, op(made + 1) == OP_ASSIGN { cameras.insert(target) }
            }
        }

        // The time is the argument before the interpolation, or the last one, since one template passes no interpolation.
        struct Call {
            let method: String, receiver: Int, first: Int, time: Float?
        }
        func number(_ i: Int) -> Float? { constant(i)?.0 ?? numbers[global(i)] }
        func calls(_ body: Range<Int>) -> [Call] {
            var found: [Call] = []
            for k in body {
                if op(k) != OP_CALL_METHOD { continue }
                let time: Float?
                if arg(k) == 3 && (name(k - 1)?.hasPrefix("INTERPOLATION_") ?? false) {
                    time = number(k - 2)
                } else if arg(k) == 2 {
                    time = number(k - 1)
                } else {
                    continue
                }
                var m = k - 2
                while m >= body.lowerBound && op(m) != OP_METHOD { m -= 1 }
                if m < body.lowerBound { continue }
                // Swift: item() stands for getOrNull, so a negative property index in the bytecode is skipped.
                guard let method = item(s.props, arg(m)) else { continue }
                found.append(Call(method: method, receiver: global(m - 1), first: m + 1, time: time))
            }
            return found
        }

        var interval: Float?
        var fade: Float?
        var zoomInterval: Float?
        var zoomMove: Float?
        var distances: [Float] = []
        for i in 0 ..< n {
            if name(i) != "IntervalTimer" || op(i + 3) != OP_NEW || arg(i + 3) != 2 { continue }
            let inPlace = constant(i + 1).flatMap { $0.1 == 1 ? $0.0 : nil }
            guard let every = inPlace ?? numbers[global(i + 1)], let body = bodies[global(i + 2)] else { continue }
            let found = calls(body)
            let fades = found.filter { $0.method == "setColor" }.compactMap { $0.time }
            if interval == nil, let longest = fades.max() {
                interval = every
                fade = longest
            }
            let moves = found.filter { $0.method == "setPosition" && cameras.contains($0.receiver) }
            if zoomInterval == nil && !moves.isEmpty {
                zoomInterval = every
                zoomMove = moves.compactMap { $0.time }.max()
                distances = moves.compactMap { call in
                    guard let z = vectors[global(call.first)].flatMap({ $0.count > 2 ? $0[2] : nil }) else { return nil }
                    return planeZ.map { z - $0 } ?? z
                }
            }
        }

        let slideEvery = coerce(interval ?? defaultTiming.interval, 2, 120)
        let slideFade = coerce(fade ?? defaultTiming.fade, 0, slideEvery)
        let near = distances.min()
        let far = distances.max()
        var zoom: Float = 0
        if zoomInterval != nil, let near = near, let far = far, near > 0 { zoom = coerce(far / near - 1, 0, 0.25) }
        // Swift: a NaN zoom also counts as none, where Kotlin's `zoom < 0.005f` let it through.
        if !(zoom >= 0.005) { return Timing(interval: slideEvery, fade: slideFade, zoom: 0, zoomInterval: 0, zoomMove: 0) }
        let zoomEvery = coerce(zoomInterval ?? 0, 2, 120)
        return Timing(interval: slideEvery, fade: slideFade, zoom: zoom, zoomInterval: zoomEvery,
                      zoomMove: coerce(zoomMove ?? zoomEvery, 0.5, zoomEvery))
    }
}
