// SPDX-License-Identifier: GPL-2.0-only
// Draws a live theme's scene with Metal, written fresh. Runs on the thread that owns the scene.

import Foundation
import ImageIO
import Metal
import MetalKit
import simd

/// Mirrors ThemeScene.metal's ThemeSceneDraw: 192 bytes in both.
private struct ThemeSceneDraw {
    var mvp = matrix_identity_float4x4
    var model = matrix_identity_float4x4
    var uv = SIMD4<Float>(1, 1, 0, 0)
    var color = SIMD4<Float>(1, 1, 1, 1)
    var skinned: Float = 0
    var opaque: Float = 0
    var lit: Float = 0
    var alphaCut: Float = 0
    var straight: Float = 0
    var block: Int32 = 0
    var size = SIMD2<Int32>(0, 0)
}

/// Mirrors ThemeScene.metal's ThemeSceneLights: 160 bytes in both.
private struct ThemeSceneLights {
    var ambient = SIMD4<Float>(repeating: 0)
    var position = (SIMD4<Float>(repeating: 0), SIMD4<Float>(repeating: 0), SIMD4<Float>(repeating: 0),
                    SIMD4<Float>(repeating: 0))
    var color = (SIMD4<Float>(repeating: 0), SIMD4<Float>(repeating: 0), SIMD4<Float>(repeating: 0),
                 SIMD4<Float>(repeating: 0))
    var count: Int32 = 0
}

final class ThemeSceneRenderer {
    /// Long enough for the script to place everything once.
    static let STILL_TICKS = 6
    private static let STRIDE = 40
    private static let MAX_BONES = 72
    private static let MAX_LIGHTS = 4
    private static let CUTOUT_ALPHA: Float = 0.5
    private static let MAX_TEXTURE_SIDE = 16384
    private static let MAX_PICTURE_SIDE = 4096

    /// For the smoke scenes' release witness.
    @MainActor static var liveCount = 0
    @MainActor static var framesDrawn = 0

    private struct GpuMesh {
        let vertices: MTLBuffer
        let indices: MTLBuffer
        let indexCount: Int
    }

    private struct GpuTexture {
        let texture: MTLTexture
        /// BC data is straight alpha; the shader premultiplies it.
        let straight: Bool
        /// 1, 3 or 5: `texture` is that DXT's blocks, one texel a block, decoded in the shader; 0 otherwise.
        var block: Int32 = 0
        var size = SIMD2<Int32>(0, 0)
    }

    private enum Pass { case opaque, premultiplied, additive, depthOnly }

    /// Command buffers come from here, so the mipmap blits made at init run before any draw.
    let queue: MTLCommandQueue
    private(set) var texturePath: ThemeTexturePath

    private let scene: ThemeScene
    private let raf: ThemeByteSource
    private let pipelines: [Pass: MTLRenderPipelineState]
    private let depthWrite: MTLDepthStencilState
    private let depthTestOnly: MTLDepthStencilState
    private let depthOff: MTLDepthStencilState
    private var meshes: [ObjectIdentifier: GpuMesh] = [:]
    private var textures: [Int: GpuTexture] = [:]
    private var bones = [Float](repeating: 0, count: 12 * ThemeSceneRenderer.MAX_BONES)
    /// Bound into whichever fragment texture slot a draw does not use, so both are bound.
    private let blankColor: MTLTexture
    private let blankBlocks: MTLTexture

    /// A mesh or texture that cannot be read is left out, and its actors are not drawn.
    init?(device: MTLDevice, scene: ThemeScene, raf: ThemeByteSource, forcedPath: ThemeTexturePath?) {
        guard let queue = device.makeCommandQueue(),
              let library = device.makeDefaultLibrary(),
              let vertex = library.makeFunction(name: "themeScene_vertex"),
              let fragment = library.makeFunction(name: "themeScene_fragment") else { return nil }
        var pipelines: [Pass: MTLRenderPipelineState] = [:]
        for pass in [Pass.opaque, .premultiplied, .additive, .depthOnly] {
            guard let state = Self.pipeline(device, vertex, fragment, pass) else { return nil }
            pipelines[pass] = state
        }
        guard let depthWrite = Self.depth(device, .lessEqual, write: true),
              let depthTestOnly = Self.depth(device, .lessEqual, write: false),
              let depthOff = Self.depth(device, .always, write: false),
              let blankColor = Self.blank(device, .bgra8Unorm),
              let blankBlocks = Self.blank(device, .rg32Uint) else { return nil }
        self.queue = queue
        self.scene = scene
        self.raf = raf
        self.pipelines = pipelines
        self.depthWrite = depthWrite
        self.depthTestOnly = depthTestOnly
        self.depthOff = depthOff
        self.blankColor = blankColor
        self.blankBlocks = blankBlocks

        let drawn = scene.actors.filter { $0.drawable }
        let unique = drawn.compactMap { $0.source.material?.texture }
        let plan = ThemeTexturePlan.make(textures: unique, supportsBC: device.supportsBCTextureCompression,
                                         maxTextureSide: Self.MAX_TEXTURE_SIDE, forced: forcedPath)
        texturePath = plan.dxtPath

        var mipmapped: [MTLTexture] = []
        for actor in drawn {
            guard let mesh = actor.source.mesh else { continue }
            let id = ObjectIdentifier(mesh)
            if meshes[id] == nil, let gpu = Self.upload(device, mesh) { meshes[id] = gpu }
            guard let texture = actor.source.material?.texture, textures[texture.offset] == nil else { continue }
            switch plan.paths[texture.offset] ?? .rgba {
            case .bc:
                if let gpu = uploadBC(device, texture) { textures[texture.offset] = gpu }
            case .blocks:
                if let gpu = uploadBlocks(device, texture) { textures[texture.offset] = gpu }
            case .rgba:
                if let gpu = uploadRGBA(device, texture, side: plan.side) {
                    textures[texture.offset] = gpu
                    mipmapped.append(gpu.texture)
                }
            }
        }
        if !mipmapped.isEmpty, let commands = queue.makeCommandBuffer(), let blit = commands.makeBlitCommandEncoder() {
            for texture in mipmapped { blit.generateMipmaps(for: texture) }
            blit.endEncoding()
            commands.commit()
        }
    }

    // MARK: - Drawing

    /// Alpha stays 1 whatever is blended; Fill crops the 16:9 composition, Fit letterboxes it, Stretch covers.
    func encode(into pass: MTLRenderPassDescriptor, commandBuffer: MTLCommandBuffer, drawableSize: CGSize,
                fitMode: BackgroundFitMode) {
        pass.colorAttachments[0].loadAction = .clear
        pass.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
        pass.colorAttachments[0].storeAction = .store
        pass.depthAttachment.loadAction = .clear
        pass.depthAttachment.clearDepth = 1
        pass.depthAttachment.storeAction = .dontCare
        // The pipelines carry a depth format; without a depth texture there is nothing valid to encode.
        guard pass.depthAttachment.texture != nil,
              let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else { return }
        defer { encoder.endEncoding() }
        let width = Double(drawableSize.width), height = Double(drawableSize.height)
        guard width >= 1, height >= 1 else { return }

        let frame = Double(ThemeScene.frameAspect)
        var viewport = MTLViewport(originX: 0, originY: 0, width: width, height: height, znear: 0, zfar: 1)
        var aspect = Float(width / height)
        switch fitMode {
        case .fill:
            break
        case .fit:
            if width / height > frame {
                viewport.width = height * frame
                viewport.originX = (width - viewport.width) / 2
            } else {
                viewport.height = width / frame
                viewport.originY = (height - viewport.height) / 2
            }
            aspect = ThemeScene.frameAspect
        case .stretch:
            aspect = ThemeScene.frameAspect
        }
        encoder.setViewport(viewport)
        encoder.setCullMode(.none)

        let camera = scene.camera
        let lens = scene.scene.camera
        let viewProjection = ThemeScene.multiply(
            ThemeScene.metalClip(ThemeScene.projection(camera.yfov, aspect, lens.znear, lens.zfar)),
            ThemeScene.viewMatrix(camera.position.v, camera.direction.v, camera.up.v))
        let (uniforms, anyLight) = lightUniforms()
        var lights = uniforms
        encoder.setFragmentBytes(&lights, length: MemoryLayout<ThemeSceneLights>.stride, index: 2)

        for actor in scene.actors where actor.shown {
            guard let material = actor.source.material, let mesh = actor.source.mesh,
                  let gpu = meshes[ObjectIdentifier(mesh)], let offset = material.texture?.offset,
                  let texture = textures[offset] else { continue }
            let model = ThemeScene.modelMatrix(actor.position.v, actor.rotation.v, actor.scale.v)
            let color = actor.color.v, uvScale = actor.uvScale.v, uvOffset = actor.uvOffset.v
            var draw = ThemeSceneDraw()
            draw.mvp = Self.matrix(ThemeScene.multiply(viewProjection, model))
            draw.model = Self.matrix(model)
            draw.uv = SIMD4(at(uvScale, 0, 1), at(uvScale, 1, 1), at(uvOffset, 0, 0), at(uvOffset, 1, 0))
            draw.color = SIMD4(at(color, 0, 1), at(color, 1, 1), at(color, 2, 1), at(color, 3, 1))
            draw.opaque = material.opaque ? 1 : 0
            // Without a light in the scene or normals in the mesh, lit reads as unlit rather than black.
            draw.lit = material.lit && anyLight && mesh.normals != nil ? 1 : 0
            draw.straight = texture.straight ? 1 : 0
            draw.block = texture.block
            draw.size = texture.size
            encoder.setVertexBuffer(gpu.vertices, offset: 0, index: 0)
            encoder.setFragmentTexture(texture.block == 0 ? texture.texture : blankColor, index: 0)
            encoder.setFragmentTexture(texture.block == 0 ? blankBlocks : texture.texture, index: 1)
            let skin = mesh.skinned ? actor.skinMatrices() : nil

            // A cut-out material's depth first, where it is solid.
            let passes = material.cutout && material.depthTest ? 2 : 1
            for pass in 0 ..< passes {
                if passes == 2 && pass == 0 {
                    encoder.setRenderPipelineState(pipelines[.depthOnly]!)
                    encoder.setDepthStencilState(depthWrite)
                    draw.alphaCut = Self.CUTOUT_ALPHA
                } else {
                    let blend: Pass = material.opaque ? .opaque : (material.additive ? .additive : .premultiplied)
                    encoder.setRenderPipelineState(pipelines[blend]!)
                    encoder.setDepthStencilState(
                        !material.depthTest ? depthOff : (material.opaque ? depthWrite : depthTestOnly))
                    draw.alphaCut = 0
                }
                for segment in mesh.segments {
                    guard segment.indexCount > 0, segment.firstIndex >= 0,
                          segment.firstIndex <= gpu.indexCount - segment.indexCount else { continue }
                    draw.skinned = 0
                    if let skin = skin, let palette = segment.palette {
                        if palette.count > Self.MAX_BONES { continue }
                        for (k, joint) in palette.enumerated() {
                            if joint >= 0, joint <= skin.count / 12 - 1 {
                                bones.replaceSubrange(12 * k ..< 12 * k + 12, with: skin[12 * joint ..< 12 * joint + 12])
                            } else {
                                bones.replaceSubrange(12 * k ..< 12 * k + 12, with: [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0])
                            }
                        }
                        draw.skinned = 1
                    }
                    encoder.setVertexBytes(&draw, length: MemoryLayout<ThemeSceneDraw>.stride, index: 1)
                    encoder.setFragmentBytes(&draw, length: MemoryLayout<ThemeSceneDraw>.stride, index: 1)
                    // Bound for every draw, skinned or not, so the vertex function's buffer is never missing.
                    bones.withUnsafeBytes { encoder.setVertexBytes($0.baseAddress!, length: $0.count, index: 2) }
                    encoder.drawIndexedPrimitives(type: .triangle, indexCount: segment.indexCount, indexType: .uint16,
                                                  indexBuffer: gpu.indices, indexBufferOffset: 2 * segment.firstIndex)
                }
            }
        }
    }

    private func lightUniforms() -> (ThemeSceneLights, Bool) {
        var ambient = SIMD4<Float>(repeating: 0)
        var position = [SIMD4<Float>](repeating: SIMD4(repeating: 0), count: Self.MAX_LIGHTS)
        var color = position
        var n = 0
        for light in scene.lightStates {
            let c = light.color.v
            let rgb = SIMD4<Float>(at(c, 0, 0), at(c, 1, 0), at(c, 2, 0), 0)
            if light.type == RafScene.Light.AMBIENT {
                ambient += rgb
            } else if n < Self.MAX_LIGHTS {
                if light.type == RafScene.Light.DIRECTIONAL {
                    let d = light.direction.v
                    position[n] = SIMD4(-at(d, 0, 0), -at(d, 1, 0), -at(d, 2, -1), 0)
                } else {
                    let p = light.position.v
                    position[n] = SIMD4(at(p, 0, 0), at(p, 1, 0), at(p, 2, 0), 1)
                }
                color[n] = rgb
                n += 1
            }
        }
        var out = ThemeSceneLights()
        out.ambient = ambient
        out.position = (position[0], position[1], position[2], position[3])
        out.color = (color[0], color[1], color[2], color[3])
        out.count = Int32(n)
        return (out, !scene.lightStates.isEmpty)
    }

    // MARK: - The still

    /// A fresh ThemeScene advanced STILL_TICKS ticks; nil when Metal cannot draw it. Blocking; any thread.
    static func still(scene: RafScene.Scene, raf: ThemeByteSource, width: Int, height: Int) -> CGImage? {
        guard (1 ... MAX_TEXTURE_SIDE).contains(width), (1 ... MAX_TEXTURE_SIDE).contains(height),
              let device = MTLCreateSystemDefaultDevice() else { return nil }
        let play = ThemeScene(scene: scene)
        for _ in 0 ..< STILL_TICKS { play.advance(ThemeScene.tick) }
        guard let renderer = ThemeSceneRenderer(device: device, scene: play, raf: raf, forcedPath: nil) else { return nil }

        let colorDescriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: width,
                                                                       height: height, mipmapped: false)
        colorDescriptor.usage = [.renderTarget, .shaderRead]
        colorDescriptor.storageMode = .shared
        let depthDescriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .depth32Float, width: width,
                                                                       height: height, mipmapped: false)
        depthDescriptor.usage = .renderTarget
        depthDescriptor.storageMode = .private
        guard let color = device.makeTexture(descriptor: colorDescriptor),
              let depth = device.makeTexture(descriptor: depthDescriptor),
              let commands = renderer.queue.makeCommandBuffer() else { return nil }
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = color
        pass.depthAttachment.texture = depth
        renderer.encode(into: pass, commandBuffer: commands, drawableSize: CGSize(width: width, height: height),
                        fitMode: .fill)
        commands.commit()
        commands.waitUntilCompleted()
        guard commands.status == .completed else { return nil }

        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        bytes.withUnsafeMutableBytes {
            color.getBytes($0.baseAddress!, bytesPerRow: width * 4, from: MTLRegionMake2D(0, 0, width, height),
                           mipmapLevel: 0)
        }
        guard let provider = CGDataProvider(data: Data(bytes) as CFData),
              let space = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
        // BGRA in memory: a little-endian 32-bit word with its first (alpha, here always 1) byte skipped.
        let info = CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
        return CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4,
                       space: space, bitmapInfo: info, provider: provider, decode: nil, shouldInterpolate: false,
                       intent: .defaultIntent)
    }

    // MARK: - Building

    private static func pipeline(_ device: MTLDevice, _ vertex: MTLFunction, _ fragment: MTLFunction,
                                 _ pass: Pass) -> MTLRenderPipelineState? {
        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.vertexFunction = vertex
        descriptor.fragmentFunction = fragment
        descriptor.vertexDescriptor = vertexDescriptor()
        descriptor.depthAttachmentPixelFormat = .depth32Float
        let color = descriptor.colorAttachments[0]!
        color.pixelFormat = .bgra8Unorm
        // Alpha stays the 1 it was cleared to, so the view is opaque whatever is blended into it.
        color.writeMask = [.red, .green, .blue]
        switch pass {
        case .opaque:
            break
        case .premultiplied, .additive:
            color.isBlendingEnabled = true
            color.sourceRGBBlendFactor = .one
            color.sourceAlphaBlendFactor = .one
            let destination: MTLBlendFactor = pass == .additive ? .one : .oneMinusSourceAlpha
            color.destinationRGBBlendFactor = destination
            color.destinationAlphaBlendFactor = destination
        case .depthOnly:
            color.writeMask = []
        }
        return try? device.makeRenderPipelineState(descriptor: descriptor)
    }

    private static func vertexDescriptor() -> MTLVertexDescriptor {
        let d = MTLVertexDescriptor()
        let attributes: [(MTLVertexFormat, Int)] = [(.float3, 0), (.float2, 12), (.float3, 20), (.uchar4, 32),
                                                    (.uchar4Normalized, 36)]
        for (i, (format, offset)) in attributes.enumerated() {
            d.attributes[i].format = format
            d.attributes[i].offset = offset
            d.attributes[i].bufferIndex = 0
        }
        d.layouts[0].stride = STRIDE
        return d
    }

    private static func depth(_ device: MTLDevice, _ compare: MTLCompareFunction, write: Bool) -> MTLDepthStencilState? {
        let d = MTLDepthStencilDescriptor()
        d.depthCompareFunction = compare
        d.isDepthWriteEnabled = write
        return device.makeDepthStencilState(descriptor: d)
    }

    /// Nil when the arrays or indices do not fit, so no draw reads past a buffer.
    private static func upload(_ device: MTLDevice, _ mesh: RafScene.Mesh) -> GpuMesh? {
        let n = mesh.vertexCount
        guard n > 0, n <= 0x10000, mesh.uvs.count >= 2 * n, !mesh.indices.isEmpty,
              mesh.indices.allSatisfy({ $0 >= 0 && $0 < n }) else { return nil }
        let normals = mesh.normals.flatMap { $0.count >= 3 * n ? $0 : nil }
        let joints = mesh.joints.flatMap { $0.count >= 4 * n ? $0 : nil }
        let weights = mesh.weights.flatMap { $0.count >= 4 * n ? $0 : nil }
        var bytes = [UInt8](repeating: 0, count: n * STRIDE)
        bytes.withUnsafeMutableBytes { raw in
            for v in 0 ..< n {
                let o = v * STRIDE
                for k in 0 ..< 3 { raw.storeBytes(of: mesh.positions[3 * v + k], toByteOffset: o + 4 * k, as: Float.self) }
                for k in 0 ..< 2 { raw.storeBytes(of: mesh.uvs[2 * v + k], toByteOffset: o + 12 + 4 * k, as: Float.self) }
                if let normals = normals {
                    for k in 0 ..< 3 { raw.storeBytes(of: normals[3 * v + k], toByteOffset: o + 20 + 4 * k, as: Float.self) }
                }
                for k in 0 ..< 4 {
                    raw[o + 32 + k] = UInt8(clamping: joints?[4 * v + k] ?? 0)
                    let w = weights?[4 * v + k] ?? 0
                    raw[o + 36 + k] = w.isNaN ? 0 : UInt8(min(max(w, 0), 1) * 255 + 0.5)
                }
            }
        }
        let indices = mesh.indices.map { UInt16($0) }
        guard let vertexBuffer = device.makeBuffer(bytes: bytes, length: bytes.count),
              let indexBuffer = indices.withUnsafeBytes({ device.makeBuffer(bytes: $0.baseAddress!, length: $0.count) })
        else { return nil }
        return GpuMesh(vertices: vertexBuffer, indices: indexBuffer, indexCount: indices.count)
    }

    /// Rows repacked from the GTF pitch; nil when the data is short.
    private func uploadBC(_ device: MTLDevice, _ t: P3tAnimation.Texture) -> GpuTexture? {
        let format: MTLPixelFormat
        switch P3tAnimation.dxt(t) {
        case 1: format = .bc1_rgba
        case 3: format = .bc2_rgba
        case 5: format = .bc3_rgba
        default: return nil
        }
        let blockBytes = format == .bc1_rgba ? 8 : 16
        guard (1 ... Self.MAX_PICTURE_SIDE).contains(t.width), (1 ... Self.MAX_PICTURE_SIDE).contains(t.height),
              t.pitch >= 0, t.pitch <= Int(UInt32.max) else { return nil }
        let wide = (t.width + 3) / 4, high = (t.height + 3) / 4, row = wide * blockBytes
        guard let data = try? raf.read(t.offset, t.size), data.count >= t.pitch * (high - 1) + row else { return nil }
        var tight = [UInt8](repeating: 0, count: row * high)
        for y in 0 ..< high { tight.replaceSubrange(y * row ..< (y + 1) * row, with: data[y * t.pitch ..< y * t.pitch + row]) }
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: format, width: t.width,
                                                                  height: t.height, mipmapped: false)
        descriptor.usage = .shaderRead
        guard let texture = device.makeTexture(descriptor: descriptor) else { return nil }
        tight.withUnsafeBytes {
            texture.replace(region: MTLRegionMake2D(0, 0, t.width, t.height), mipmapLevel: 0,
                            withBytes: $0.baseAddress!, bytesPerRow: row)
        }
        return GpuTexture(texture: texture, straight: true)
    }

    /// The words are little-endian, as Apple GPUs read them; nil when the data is short.
    private func uploadBlocks(_ device: MTLDevice, _ t: P3tAnimation.Texture) -> GpuTexture? {
        let kind = P3tAnimation.dxt(t)
        guard kind != 0,
              (1 ... Self.MAX_PICTURE_SIDE).contains(t.width), (1 ... Self.MAX_PICTURE_SIDE).contains(t.height),
              t.pitch >= 0, t.pitch <= Int(UInt32.max) else { return nil }
        let format: MTLPixelFormat = kind == 1 ? .rg32Uint : .rgba32Uint
        let blockBytes = kind == 1 ? 8 : 16
        let wide = (t.width + 3) / 4, high = (t.height + 3) / 4, row = wide * blockBytes
        guard let data = try? raf.read(t.offset, t.size), data.count >= t.pitch * (high - 1) + row else { return nil }
        var tight = [UInt8](repeating: 0, count: row * high)
        for y in 0 ..< high { tight.replaceSubrange(y * row ..< (y + 1) * row, with: data[y * t.pitch ..< y * t.pitch + row]) }
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: format, width: wide, height: high,
                                                                  mipmapped: false)
        descriptor.usage = .shaderRead
        guard let texture = device.makeTexture(descriptor: descriptor) else { return nil }
        tight.withUnsafeBytes {
            texture.replace(region: MTLRegionMake2D(0, 0, wide, high), mipmapLevel: 0, withBytes: $0.baseAddress!,
                            bytesPerRow: row)
        }
        return GpuTexture(texture: texture, straight: false, block: Int32(kind),
                          size: SIMD2(Int32(t.width), Int32(t.height)))
    }

    private static func blank(_ device: MTLDevice, _ format: MTLPixelFormat) -> MTLTexture? {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: format, width: 1, height: 1,
                                                                  mipmapped: false)
        descriptor.usage = .shaderRead
        guard let texture = device.makeTexture(descriptor: descriptor) else { return nil }
        let zeros = [UInt8](repeating: 0, count: 8)
        zeros.withUnsafeBytes {
            texture.replace(region: MTLRegionMake2D(0, 0, 1, 1), mipmapLevel: 0, withBytes: $0.baseAddress!,
                            bytesPerRow: 8)
        }
        return texture
    }

    /// Mips are generated at the end of init; nil when it cannot be read.
    private func uploadRGBA(_ device: MTLDevice, _ t: P3tAnimation.Texture, side: Int) -> GpuTexture? {
        var w = t.width, h = t.height
        var argb: [UInt32]
        if P3tAnimation.isPicture(t) {
            guard let picture = decodePicture(t) else { return nil }
            (argb, w, h) = picture
        } else {
            guard let decoded = P3tAnimation.decode(raf, t) else { return nil }
            argb = decoded
            ThemeTextureBudget.premultiply(&argb)
        }
        while w > side || h > side {
            argb = ThemeTextureBudget.halve(argb, width: w, height: h)
            w = max(w / 2, 1)
            h = max(h / 2, 1)
        }
        guard !argb.isEmpty, argb.count >= w * h else { return nil }
        // An ARGB word in little-endian memory is B, G, R, A: BGRA8 takes it without a swizzle.
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: w, height: h,
                                                                  mipmapped: true)
        descriptor.usage = .shaderRead
        guard let texture = device.makeTexture(descriptor: descriptor) else { return nil }
        argb.withUnsafeBytes {
            texture.replace(region: MTLRegionMake2D(0, 0, w, h), mipmapLevel: 0, withBytes: $0.baseAddress!,
                            bytesPerRow: w * 4)
        }
        return GpuTexture(texture: texture, straight: false)
    }

    private func decodePicture(_ t: P3tAnimation.Texture) -> ([UInt32], Int, Int)? {
        guard let bytes = try? raf.read(t.offset, t.size),
              let source = CGImageSourceCreateWithData(Data(bytes) as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCache: false] as CFDictionary)
        else { return nil }
        let w = image.width, h = image.height
        // The header's size was checked when the texture was read; the decoded picture is checked again.
        guard (1 ... Self.MAX_PICTURE_SIDE).contains(w), (1 ... Self.MAX_PICTURE_SIDE).contains(h),
              let space = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
        var argb = [UInt32](repeating: 0, count: w * h)
        let drawn: Bool = argb.withUnsafeMutableBytes { raw in
            guard let context = CGContext(data: raw.baseAddress, width: w, height: h, bitsPerComponent: 8,
                                          bytesPerRow: w * 4, space: space,
                                          bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue |
                                              CGBitmapInfo.byteOrder32Little.rawValue) else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        return drawn ? (argb, w, h) : nil
    }

    private static func matrix(_ m: [Float]) -> simd_float4x4 {
        guard m.count >= 16 else { return matrix_identity_float4x4 }
        return simd_float4x4(columns: (SIMD4(m[0], m[1], m[2], m[3]), SIMD4(m[4], m[5], m[6], m[7]),
                                       SIMD4(m[8], m[9], m[10], m[11]), SIMD4(m[12], m[13], m[14], m[15])))
    }
}

private func at(_ v: [Float], _ i: Int, _ fallback: Float) -> Float { i < v.count ? v[i] : fallback }
