// SPDX-License-Identifier: GPL-2.0-only
// Ported from Android's RafScene.kt: a dynamic theme's whole RAF scene and its Edge meshes. A theme is hostile input.

import Foundation

enum RafScene {
    /// `palette` nil means not skinned.
    final class Segment {
        let firstIndex: Int
        let indexCount: Int
        let palette: [Int]?

        init(firstIndex: Int, indexCount: Int, palette: [Int]?) {
            self.firstIndex = firstIndex
            self.indexCount = indexCount
            self.palette = palette
        }
    }

    final class Mesh {
        let positions: [Float]
        let uvs: [Float]
        let indices: [Int]
        /// 3 a vertex, when the mesh has them; lit materials need them.
        let normals: [Float]?
        /// 4 a vertex: indexes into the vertex's segment's palette.
        let joints: [Int]?
        /// 4 a vertex, summing to 1.
        let weights: [Float]?
        let segments: [Segment]

        init(positions: [Float], uvs: [Float], indices: [Int], normals: [Float]? = nil, joints: [Int]? = nil,
             weights: [Float]? = nil, segments: [Segment]? = nil) {
            self.positions = positions
            self.uvs = uvs
            self.indices = indices
            self.normals = normals
            self.joints = joints
            self.weights = weights
            self.segments = segments ?? [Segment(firstIndex: 0, indexCount: indices.count, palette: nil)]
        }

        var vertexCount: Int { positions.count / 3 }

        var skinned: Bool { joints != nil && segments.contains { $0.palette != nil } }

        /// Zero for a mesh a theme's author collapsed to hide it.
        func area() -> Double {
            var sum = 0.0
            var t = 0
            // Swift: stops at the last whole triangle and skips a bad vertex where Kotlin would throw.
            while t + 2 < indices.count {
                let a = indices[t] * 3, b2 = indices[t + 1] * 3, c = indices[t + 2] * 3
                t += 3
                guard min(a, b2, c) >= 0, max(a, b2, c) + 2 < positions.count else { continue }
                let ux = Double(positions[b2] - positions[a]), uy = Double(positions[b2 + 1] - positions[a + 1])
                let uz = Double(positions[b2 + 2] - positions[a + 2])
                let vx = Double(positions[c] - positions[a]), vy = Double(positions[c + 1] - positions[a + 1])
                let vz = Double(positions[c + 2] - positions[a + 2])
                let cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx
                sum += 0.5 * (cx * cx + cy * cy + cz * cz).squareRoot()
            }
            return sum
        }
    }

    /// alpha_0 is drawn solid: the animated themes' DXT1 frames store pure black as DXT1's see-through colour.
    struct Material {
        let effect: String
        let texture: P3tAnimation.Texture?

        // RafScene.kt 79-95, verbatim.
        var opaque: Bool { (effect.hasPrefix("basic_lighting") && !effect.contains("alpha")) || effect.contains("_alpha_0") }
        var additive: Bool { effect.contains("alpha_add") }
        var depthTest: Bool { !effect.contains("depth_0") }
        var lit: Bool { effect.contains("lighting") }
        /// Two passes, so a model hides its own far side and a sprite keeps its soft edge.
        var cutout: Bool { effect == "pure_texture" }
    }

    /// The animations' ids are how scripts find a slot.
    final class Rig {
        let skeleton: EdgeAnim.Skeleton
        let inverseBinds: [Float]
        let clips: [EdgeAnim.Clip?]
        let names: [String?]

        init(skeleton: EdgeAnim.Skeleton, inverseBinds: [Float], clips: [EdgeAnim.Clip?], names: [String?] = []) {
            self.skeleton = skeleton
            self.inverseBinds = inverseBinds
            self.clips = clips
            self.names = names
        }
    }

    /// Rotation is in radians, applied X then Y then Z; textures wrap.
    final class Actor {
        let name: String
        let mesh: Mesh?
        let material: Material?
        let position: [Float]
        let rotation: [Float]
        let scale: [Float]
        let color: [Float]
        let uvOffset: [Float]
        let uvScale: [Float]
        let rig: Rig?
        let animWeights: [Float]
        let animSpeeds: [Float]
        let animTimes: [Float]

        init(name: String, mesh: Mesh?, material: Material?, position: [Float], rotation: [Float], scale: [Float],
             color: [Float], uvOffset: [Float], uvScale: [Float], rig: Rig? = nil, animWeights: [Float] = [],
             animSpeeds: [Float] = [], animTimes: [Float] = []) {
            self.name = name
            self.mesh = mesh
            self.material = material
            self.position = position
            self.rotation = rotation
            self.scale = scale
            self.color = color
            self.uvOffset = uvOffset
            self.uvScale = uvScale
            self.rig = rig
            self.animWeights = animWeights
            self.animSpeeds = animSpeeds
            self.animTimes = animTimes
        }
    }

    /// yfov is vertical, in radians.
    struct Camera {
        let position: [Float]
        let direction: [Float]
        let up: [Float]
        let yfov: Float
        let znear: Float
        let zfar: Float
    }

    struct Light {
        let name: String
        let type: Int
        let color: [Float]
        let position: [Float]
        let direction: [Float]

        static let POINT = 0
        static let DIRECTIONAL = 1
        static let AMBIENT = 2
    }

    final class Scene {
        let actors: [Actor]
        let camera: Camera
        let script: [UInt8]?
        let lights: [Light]

        init(actors: [Actor], camera: Camera, script: [UInt8]?, lights: [Light] = []) {
            self.actors = actors
            self.camera = camera
            self.script = script
            self.lights = lights
        }
    }

    // RafScene.kt 132-153.
    private static let RAFO = 0x5241464F // "RAFO"
    private static let HEADER_BYTES = 56
    private static let ELEMENT_BYTES = 28
    private static let ATTRIBUTE_BYTES = 16
    private static let TYPE_INT = 1
    private static let TYPE_FLOAT = 2
    private static let TYPE_STRING = 3
    private static let TYPE_VECTOR = 5
    private static let TYPE_FILE = 6
    private static let TYPE_ID = 7
    private static let TYPE_REF = 8
    private static let MAX_TABLE_BYTES = 16 << 20
    static let MAX_MESH_BYTES = 4 << 20
    private static let MAX_RIG_BYTES = 4 << 20
    private static let MAX_SCRIPT_BYTES = 1 << 20
    private static let MAX_NAME_BYTES = 256
    static let MAX_VECTOR = 16
    static let MAX_ACTORS = 512
    private static let MAX_LIGHTS = 16
    private static let MAX_SEGMENTS = 64
    private static let SEGMENT_BYTES = 0x80
    private static let MATRIX_BYTES = 48

    /// Nil when the file is not a RAF scene or has no camera.
    static func read(_ raf: ThemeByteSource) -> Scene? {
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

    private struct ActorRefs {
        let id: Int, material: Int?, model: Int?, attrs: [String: Attr]
    }

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

        // Swift: nothing here depends on dictionary order; the actor and light lists stay arrays, so scene order is kept.
        var actors: [ActorRefs] = []
        var lightAttrs: [[String: Attr]] = []
        var camera: [String: Attr]?
        var script: Int?
        var materialEffect: [Int: String] = [:]
        var materialAt: [Int: Int] = [:]
        var materialTexture: [Int: Int] = [:]
        var modelAt: [Int: Int] = [:]
        var modelGeometry: [Int: Int] = [:]
        var modelSkeleton: [Int: Int] = [:]
        var modelInverseBind: [Int: Int] = [:]
        var modelAnimations: [Int: [Int]] = [:]
        var modelAnimationNames: [Int: [String?]] = [:]
        var textureFile: [Int: Int] = [:]
        var fileSpan: [Int: Attr] = [:]

        var pos = 0
        while pos + ELEMENT_BYTES <= elements.count {
            let name = try cString(names, elements.be32(pos))
            let count = try elements.be32(pos + 4)
            let parent = try elements.be32(pos + 8)
            if count > (elements.count - pos - ELEMENT_BYTES) / ATTRIBUTE_BYTES { throw ThemeDamaged() }
            var attrs: [String: Attr] = [:]
            for i in 0 ..< count {
                let at = pos + ELEMENT_BYTES + i * ATTRIBUTE_BYTES
                attrs[try cString(names, elements.be32(at))] =
                    Attr(type: try elements.be32(at + 4), a: try elements.be32(at + 8), b: try elements.be32(at + 12))
            }
            func ref(_ key: String) -> Int? {
                guard let attr = attrs[key], attr.type == TYPE_ID || attr.type == TYPE_REF else { return nil }
                return attr.a
            }
            switch name {
            case "actor":
                if let id = ref("id"), actors.count < MAX_ACTORS {
                    actors.append(ActorRefs(id: id, material: ref("material"), model: ref("model"), attrs: attrs))
                }
            case "camera":
                if camera == nil { camera = attrs }
            case "light":
                if lightAttrs.count < MAX_LIGHTS { lightAttrs.append(attrs) }
            case "script":
                if script == nil { script = ref("fileref") }
            case "material":
                if let id = ref("id") {
                    materialAt[pos] = id
                    if let effect = attrs["effect"], effect.type == TYPE_STRING {
                        // Swift: Kotlin's trim() is trimmingCharacters(in: .whitespacesAndNewlines).
                        materialEffect[id] = try cString(names, effect.a).trimmingCharacters(in: .whitespacesAndNewlines)
                    }
                }
            // Lit materials list their colour map first and a specular map after it; the first is the one to draw.
            case "_texture":
                if let m = materialAt[parent], let t = ref("texref"), materialTexture[m] == nil { materialTexture[m] = t }
            case "model":
                if let id = ref("id") { modelAt[pos] = id }
            case "geometry":
                if let m = modelAt[parent], let f = ref("fileref") { modelGeometry[m] = f }
            case "skeleton":
                if let m = modelAt[parent], let f = ref("fileref") { modelSkeleton[m] = f }
            case "inv-bind":
                if let m = modelAt[parent], let f = ref("fileref") { modelInverseBind[m] = f }
            case "animation":
                if let m = modelAt[parent], let f = ref("fileref") {
                    modelAnimations[m, default: []].append(f)
                    modelAnimationNames[m, default: []].append(try ref("id").map { try cString(ids, $0 + 4) })
                }
            case "texture":
                if let id = ref("id"), let f = ref("fileref") { textureFile[id] = f }
            case "file":
                if let id = ref("id"), let src = attrs["src"], src.type == TYPE_FILE { fileSpan[id] = src }
            default:
                break
            }
            pos += ELEMENT_BYTES + count * ATTRIBUTE_BYTES
        }

        func file(_ id: Int) -> (offset: Int, size: Int)? {
            guard let span = fileSpan[id] else { return nil }
            if span.a > filesSize || span.b > filesSize - span.a { return nil }
            return (filesAt + span.a, span.b)
        }
        func fileBytes(_ id: Int?, _ max: Int) throws -> [UInt8]? {
            guard let id = id, let span = file(id), (1 ... max).contains(span.size) else { return nil }
            return try raf.read(span.offset, span.size)
        }
        // Swift: every vector value stays a Float, NaN and infinity included; nothing here converts one to Int.
        func floatsOf(_ attrs: [String: Attr], _ key: String) throws -> [Float]? {
            guard let attr = attrs[key], attr.type == TYPE_VECTOR, (1 ... MAX_VECTOR).contains(attr.b) else { return nil }
            if attr.a > floats.count / 4 || attr.b > floats.count / 4 - attr.a { return nil }
            return try (0 ..< attr.b).map { Float(bitPattern: UInt32(truncatingIfNeeded: try floats.be32((attr.a + $0) * 4))) }
        }
        func vector(_ attrs: [String: Attr], _ key: String, _ fallback: [Float]) throws -> [Float] {
            guard let v = try floatsOf(attrs, key) else { return fallback }
            if v.count >= fallback.count { return Array(v.prefix(fallback.count)) }
            return v + fallback.suffix(fallback.count - v.count)
        }
        func scalar(_ attrs: [String: Attr], _ key: String, _ fallback: Float) throws -> Float {
            guard let attr = attrs[key] else { return fallback }
            switch attr.type {
            case TYPE_FLOAT: return Float(bitPattern: UInt32(truncatingIfNeeded: attr.a))
            case TYPE_VECTOR: return try vector(attrs, key, [fallback])[0]
            default: return fallback
            }
        }

        // Swift: actors that share a model share one Mesh and Rig; a nil result is cached too, where Kotlin recomputed it.
        var meshes: [Int: Mesh?] = [:]
        var rigs: [Int: Rig?] = [:]
        var textures: [Int: P3tAnimation.Texture?] = [:]
        func buildRig(_ model: Int, _ mesh: Mesh) throws -> Rig? {
            if !mesh.skinned { return nil }
            guard let skeleton = try fileBytes(modelSkeleton[model], MAX_RIG_BYTES).flatMap(EdgeAnim.skeleton),
                  let inverse = try fileBytes(modelInverseBind[model], MAX_RIG_BYTES)
                      .flatMap({ EdgeAnim.inverseBinds($0, joints: skeleton.joints) })
            else { return nil }
            let palette = mesh.segments.compactMap { $0.palette }.flatMap { $0 }
            if palette.contains(where: { $0 >= skeleton.joints }) { return nil }
            let clips = try (modelAnimations[model] ?? []).map { f in
                try fileBytes(f, MAX_RIG_BYTES).flatMap { EdgeAnim.clip($0, joints: skeleton.joints) }
            }
            return Rig(skeleton: skeleton, inverseBinds: inverse, clips: clips, names: modelAnimationNames[model] ?? [])
        }

        func rigFor(_ model: Int, _ mesh: Mesh) throws -> Rig? {
            if let cached = rigs[model] { return cached }
            let rig = try buildRig(model, mesh)
            rigs[model] = .some(rig)
            return rig
        }
        var built: [Actor] = []
        for a in actors {
            var mesh: Mesh?
            if let model = a.model {
                if let cached = meshes[model] {
                    mesh = cached
                } else {
                    mesh = try fileBytes(modelGeometry[model], MAX_MESH_BYTES).flatMap(edgeMesh)
                    meshes[model] = .some(mesh)
                }
            }
            var material: Material?
            if let m = a.material {
                var texture: P3tAnimation.Texture?
                if let t = materialTexture[m] {
                    if let cached = textures[t] {
                        texture = cached
                    } else {
                        texture = textureFile[t].flatMap(file).flatMap { P3tAnimation.texture(raf, offset: $0.offset, size: $0.size) }
                        textures[t] = .some(texture)
                    }
                }
                material = Material(effect: materialEffect[m] ?? "", texture: texture)
            }
            var rig: Rig?
            if let mesh = mesh, let model = a.model { rig = try rigFor(model, mesh) }
            built.append(Actor(
                name: try cString(ids, a.id + 4),
                mesh: mesh,
                material: material,
                position: try vector(a.attrs, "position", [0, 0, 0]),
                rotation: try vector(a.attrs, "rotation", [0, 0, 0]),
                scale: try vector(a.attrs, "scale", [1, 1, 1]),
                color: try vector(a.attrs, "color", [1, 1, 1, 1]),
                uvOffset: try vector(a.attrs, "uv_offset", [0, 0]),
                uvScale: try vector(a.attrs, "uv_scale", [1, 1]),
                rig: rig,
                animWeights: try floatsOf(a.attrs, "anim_weight") ?? [],
                animSpeeds: try floatsOf(a.attrs, "anim_speed") ?? [],
                animTimes: try floatsOf(a.attrs, "anim_time") ?? []
            ))
        }
        guard let cam = camera else { return nil }
        // Swift: Kotlin's takeIf and coerceAtLeast written out; a NaN yfov takes the fallback, as there.
        let yfov = try scalar(cam, "yfov", 0.9273)
        let znear = try scalar(cam, "znear", 0.1)
        let zfar = try scalar(cam, "zfar", 5000)
        let sceneCamera = Camera(
            position: try vector(cam, "position", [0, 0, 0]),
            direction: try vector(cam, "direction", [0, 0, -1]),
            up: try vector(cam, "up", [0, 1, 0]),
            yfov: yfov > 0.01 && yfov < 3.1 ? yfov : 0.9273,
            znear: znear < 1e-4 ? 1e-4 : znear,
            zfar: zfar < 1 ? 1 : zfar
        )
        var lights: [Light] = []
        for l in lightAttrs {
            guard let idAttr = l["id"], idAttr.type == TYPE_ID || idAttr.type == TYPE_REF else { continue }
            let id = try cString(ids, idAttr.a + 4)
            var type = Light.POINT
            // Swift: the Kotlin's Long.toInt() wrap is Int32(truncatingIfNeeded:).
            if let t = l["type"], t.type == TYPE_INT { type = Int(Int32(truncatingIfNeeded: t.a)) }
            lights.append(Light(
                name: id,
                type: type,
                color: try vector(l, "color", [1, 1, 1]),
                position: try vector(l, "position", [0, 0, 0]),
                direction: try vector(l, "direction", [0, 0, -1])
            ))
        }
        let code = try fileBytes(script, MAX_SCRIPT_BYTES)
        return Scene(actors: built, camera: sceneCamera, script: code, lights: lights)
    }

    /// Throws for an offset outside the table.
    private static func cString(_ table: ByteReader, _ offset: Int) throws -> String {
        try table.cString(at: offset, limit: MAX_NAME_BYTES)
    }

    /// Nil when it does not decode cleanly.
    static func edgeMesh(_ b: [UInt8]) -> Mesh? {
        // Swift: one do/catch for ThemeDamaged stands where the Kotlin caught Damaged and RuntimeException.
        do {
            return try decodeMesh(ByteReader(b))
        } catch {
            return nil
        }
    }

    /// No area, or every index zero: the two ways theme authors hide an actor they do not want.
    static func collapsed(_ b: [UInt8]) -> Bool {
        if let mesh = edgeMesh(b) { return mesh.area() < 1e-6 }
        let r = ByteReader(b)
        do {
            guard let table = try segmentTable(r) else { return false }
            let (count, at) = table
            for i in 0 ..< count {
                let s = at + SEGMENT_BYTES * i
                let index = try r.be32(s + 0x10)
                let size = try r.be16(s + 0x14) + r.be16(s + 0x16)
                guard size >= 8, themeRangeOK(index, size, size: r.count) else { return false }
                // Swift: the run is taken out through ByteReader rather than indexed byte by byte.
                guard try r.slice(index, size).allSatisfy({ $0 == 0 }) else { return false }
            }
            return true
        } catch {
            return false
        }
    }

    /// Only when the header describes a plausible table.
    private static func segmentTable(_ r: ByteReader) throws -> (Int, Int)? {
        if r.count < 0x10 { return nil }
        let count = try r.be32(0)
        let at = try r.be32(4)
        if !(1 ... MAX_SEGMENTS).contains(count) || at < 0x10 || at + SEGMENT_BYTES * count > r.count { return nil }
        return (count, at)
    }

    private struct Part {
        let positions: [Float], uvs: [Float], indices: [Int], normals: [Float]?
        let joints: [Int]?, weights: [Float]?, palette: [Int]?
    }

    private static func decodeMesh(_ r: ByteReader) throws -> Mesh? {
        guard let table = try segmentTable(r) else { return nil }
        let (count, at) = table
        var parts: [Part] = []
        for i in 0 ..< count {
            guard let part = try decodeSegment(r, at + SEGMENT_BYTES * i) else { return nil }
            parts.append(part)
        }
        let vertices = parts.reduce(0) { $0 + $1.positions.count / 3 }
        if vertices > 0xFFFF { return nil }
        var positions: [Float] = [], uvs: [Float] = [], indices: [Int] = []
        positions.reserveCapacity(3 * vertices)
        uvs.reserveCapacity(2 * vertices)
        let hasNormals = parts.allSatisfy { $0.normals != nil }
        let skinned = parts.contains { $0.joints != nil }
        var normals: [Float]? = hasNormals ? [] : nil
        var joints: [Int]? = skinned ? [] : nil
        var weights: [Float]? = skinned ? [] : nil
        var segments: [Segment] = []
        var v = 0
        for p in parts {
            let pv = p.positions.count / 3
            positions += p.positions
            uvs += p.uvs
            if hasNormals, let n = p.normals { normals?.append(contentsOf: n) }
            // Swift: an unskinned part of a skinned mesh keeps zeros, as the Kotlin's fresh IntArray/FloatArray does.
            if skinned {
                joints?.append(contentsOf: p.joints ?? [Int](repeating: 0, count: 4 * pv))
                weights?.append(contentsOf: p.weights ?? [Float](repeating: 0, count: 4 * pv))
            }
            segments.append(Segment(firstIndex: indices.count, indexCount: p.indices.count, palette: p.palette))
            indices += p.indices.map { $0 + v }
            v += pv
        }
        return Mesh(positions: positions, uvs: uvs, indices: indices, normals: normals, joints: joints,
                    weights: weights, segments: segments)
    }

    /// A low nibble other than 0 at +6 means skinned; the offsets below follow Android's reading of Sony's layout.
    private static func decodeSegment(_ r: ByteReader, _ s: Int) throws -> Part? {
        let vertices = try r.be16(s + 0x08)
        let indexCount = try r.be16(s + 0x0A)
        if vertices < 3 || indexCount < 3 || indexCount % 3 != 0 { return nil }
        let indexAt = try r.be32(s + 0x10)
        let indexSize = try r.be16(s + 0x14) + r.be16(s + 0x16)
        let positionsAt = try r.be32(s + 0x18)
        let positionsSize = try r.be16(s + 0x20) + r.be16(s + 0x22) + r.be16(s + 0x24)
        func inside(_ at: Int, _ size: Int) -> Bool { at <= r.count && size <= r.count - at }
        if !inside(indexAt, indexSize) || !inside(positionsAt, positionsSize) { return nil }
        if positionsSize < 12 * vertices { return nil }
        var positions: [Float] = []
        positions.reserveCapacity(vertices * 3)
        for k in 0 ..< vertices * 3 {
            // Swift: Float.fromBits becomes Float(bitPattern:), and be32 is already unsigned.
            let word = try r.be32(positionsAt + 4 * k)
            positions.append(Float(bitPattern: UInt32(truncatingIfNeeded: word)))
        }
        if positions.contains(where: { !$0.isFinite }) { return nil }
        guard let uvs = try attribute(r, s, vertices, ID_TEXCOORD) else { return nil }
        let normals = try attribute(r, s, vertices, ID_NORMAL)
        let indexBlock = try r.slice(indexAt, indexSize)
        guard let indices = edgeIndices(indexBlock, indexCount: indexCount, vertexCount: vertices) else { return nil }
        if try r.u8(s + 6) & 0x0F == 0 {
            return Part(positions: positions, uvs: uvs, indices: indices, normals: normals, joints: nil, weights: nil,
                        palette: nil)
        }

        let runs = try (0 ..< 4).map { try r.be16(s + 0x34 + 2 * $0) } // offset 0, offset 1, size 0, size 1
        let first = runs[2] / MATRIX_BYTES
        let palette = (0 ..< first + runs[3] / MATRIX_BYTES).map { k in
            k < first ? runs[0] / MATRIX_BYTES + k : runs[1] / MATRIX_BYTES + (k - first)
        }
        let skinAt = try r.be32(s + 0x40)
        let skinSize = try r.be16(s + 0x3C) + r.be16(s + 0x3E)
        if palette.isEmpty || !inside(skinAt, skinSize) || skinSize < 8 * vertices { return nil }
        var joints = [Int](repeating: 0, count: 4 * vertices)
        var weights = [Float](repeating: 0, count: 4 * vertices)
        for v in 0 ..< vertices {
            var total: Float = 0
            for k in 0 ..< 4 {
                let at = skinAt + 8 * v + 2 * k
                let w = try Float(r.u8(at)) / 255
                let j = try r.u8(at + 1)
                if w > 0 && j >= palette.count { return nil }
                weights[4 * v + k] = w
                joints[4 * v + k] = w > 0 ? j : 0
                total += w
            }
            if total > 0 { for k in 0 ..< 4 { weights[4 * v + k] /= total } }
        }
        return Part(positions: positions, uvs: uvs, indices: indices, normals: normals, joints: joints,
                    weights: weights, palette: palette)
    }

    /// Reused values are deltas on the index eight reads earlier: eight running sums, one per 16-bit SPU lane.
    static func edgeIndices(_ block: [UInt8], indexCount: Int, vertexCount: Int) -> [Int]? {
        let r = ByteReader(block)
        // Swift: a negative count is refused here; Kotlin's IntArray(indexCount) would have thrown.
        guard r.count >= 8, indexCount >= 0 else { return nil }
        do {
            let reused = try r.be16(0)
            let bias = try r.be16(2)
            let flagBytes = try r.be16(4)
            let width = try r.u8(6)
            let triangles = indexCount / 3
            if flagBytes < 1 || width > 16 { return nil }
            let reads = flagBytes * 8
            let flagsAt = 8
            let codesAt = flagsAt + flagBytes
            let valuesAt = codesAt + (2 * triangles + 7) / 8
            if valuesAt * 8 + reused * width > r.count * 8 { return nil }

            // Swift: ByteReader checks every byte this reads, so no block can read past its end.
            func bits(_ byteStart: Int, _ bitIndex: Int, _ count: Int) throws -> Int {
                var value = 0
                var p = byteStart * 8 + bitIndex
                for _ in 0 ..< count {
                    let byte = try r.u8(p >> 3)
                    value = (value << 1) | ((byte >> (7 - (p & 7))) & 1)
                    p += 1
                }
                return value
            }

            var lanes = [Int](repeating: 0, count: 8)
            var readIndex = 0
            var reuseIndex = 0
            var next = 0
            func index() throws -> Int {
                if readIndex >= reads { throw ThemeDamaged() }
                let flag = try bits(flagsAt, readIndex, 1)
                readIndex += 1
                if flag == 0 {
                    next += 1
                    return next - 1
                }
                if reuseIndex >= reused { throw ThemeDamaged() }
                let lane = reuseIndex % 8
                let delta = try bits(valuesAt, reuseIndex * width, width)
                lanes[lane] = (lanes[lane] + delta - bias) & 0xFFFF
                reuseIndex += 1
                return lanes[lane]
            }

            var out = [Int](repeating: 0, count: indexCount)
            var p0 = -1, p1 = -1, p2 = -1
            for t in 0 ..< triangles {
                let code = try bits(codesAt, 2 * t, 2)
                let a: Int, c: Int, d: Int
                if code == 3 {
                    a = try index(); c = try index(); d = try index()
                } else {
                    if p0 < 0 { return nil } // an edge to share needs a triangle before it
                    let x = try index()
                    switch code {
                    case 0: a = p0; c = p2; d = x
                    case 1: a = p2; c = p1; d = x
                    default: a = p1; c = p0; d = x
                    }
                }
                out[3 * t] = a; out[3 * t + 1] = c; out[3 * t + 2] = d
                p0 = a; p1 = c; p2 = d
            }
            return out.contains(where: { !(0 ..< max(vertexCount, 0)).contains($0) }) ? nil : out
        } catch {
            // Swift: one catch stands where the Kotlin caught Damaged and IndexOutOfBoundsException.
            return nil
        }
    }

    private static let ID_NORMAL = 2
    private static let ID_TEXCOORD = 5

    // The RSX-only stream, then the second input stream.
    private static let STREAMS = [(0x74, 0x2C, -1), (0x6C, 0x1C, 0x26)]

    /// Packed normals are 11, 11 and 10 signed bits, x in the low bits.
    private static func attribute(_ r: ByteReader, _ s: Int, _ vertices: Int, _ id: Int) throws -> [Float]? {
        let width = id == ID_TEXCOORD ? 2 : 3
        for (descSlot, streamSlot, sizeSlot) in STREAMS {
            let desc = try r.be32(s + descSlot)
            let streamAt = try r.be32(s + streamSlot)
            let streamSize = try sizeSlot < 0
                ? r.be32(s + 0x30)
                : r.be16(s + sizeSlot) + r.be16(s + sizeSlot + 2) + r.be16(s + sizeSlot + 4)
            if desc == 0 || desc + 8 > r.count { continue }
            let count = try r.u8(desc)
            let stride = try r.u8(desc + 1)
            if stride == 0 || count > 16 || desc + 8 + 8 * count > r.count { continue }
            if streamAt > r.count || streamSize > r.count - streamAt || stride * vertices > streamSize { continue }
            for i in 0 ..< count {
                let a = desc + 8 + 8 * i
                let offset = try r.u8(a)
                let type = try r.u8(a + 1)
                let components = try r.u8(a + 2)
                if try r.u8(a + 3) != id { continue }
                let size: Int
                switch type {
                case 3: size = 2
                case 2, 6: size = 4
                default: continue
                }
                if type == 6 {
                    if id != ID_NORMAL || offset + 4 > stride { continue }
                    var out: [Float] = []
                    out.reserveCapacity(vertices * 3)
                    for k in 0 ..< vertices * 3 {
                        let packed = try r.be32(streamAt + stride * (k / 3) + offset)
                        switch k % 3 {
                        case 0: out.append(Float(signed(packed, 11)) / 1023)
                        case 1: out.append(Float(signed(packed >> 11, 11)) / 1023)
                        default: out.append(Float(signed(packed >> 22, 10)) / 511)
                        }
                    }
                    return out
                }
                if components < width || offset + width * size > stride { continue }
                var out: [Float] = []
                out.reserveCapacity(vertices * width)
                for k in 0 ..< vertices * width {
                    let at = streamAt + stride * (k / width) + offset + size * (k % width)
                    out.append(try type == 3 ? halfToFloat(r.be16(at))
                                             : Float(bitPattern: UInt32(truncatingIfNeeded: r.be32(at))))
                }
                return out
            }
        }
        return nil
    }

    private static func signed(_ v: Int, _ bits: Int) -> Int {
        let m = v & ((1 << bits) - 1)
        return m & (1 << (bits - 1)) != 0 ? m - (1 << bits) : m
    }

    static func halfToFloat(_ h: Int) -> Float {
        let sign: Float = h & 0x8000 != 0 ? -1 : 1
        let exp = (h >> 10) & 0x1F
        let frac = h & 0x3FF
        switch exp {
        case 0: return sign * Float(frac) * (1 / Float(1 << 24))
        case 31: return frac == 0 ? sign * Float.infinity : Float.nan
        default: return sign * (1 + Float(frac) / 1024) * Float(pow(2.0, Double(exp - 15)))
        }
    }
}
