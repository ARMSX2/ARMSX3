// SPDX-License-Identifier: GPL-2.0-only
// Valley on screen in an MTKView, sixty frames a second, drawn as the firmware draws its canyon; ported from Android's XmbValley.kt (XmbValleyView).

import MetalKit
import QuartzCore
import SwiftUI

struct XmbValleyView: UIViewRepresentable {
    let pack: XmbValleyFirmware.Pack
    var paused = false
    /// Fills a frame of the console's spectrum (`XmbVisualizerLevels.columns` points), zeros while nothing plays.
    var source: (inout [Float]) -> Void

    final class Coordinator {
        var renderer: XmbValleyRenderer?
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> MTKView {
        let view = MTKView(frame: .zero, device: MTLCreateSystemDefaultDevice())
        view.colorPixelFormat = .bgra8Unorm
        view.clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
        view.framebufferOnly = true
        // The firmware moves on a frame at a time; so does this, sixty a second.
        view.preferredFramesPerSecond = 60
        view.enableSetNeedsDisplay = false
        view.isOpaque = true
        view.backgroundColor = .black
        view.isUserInteractionEnabled = false
        if let device = view.device {
            context.coordinator.renderer = XmbValleyRenderer(device: device, format: view.colorPixelFormat, pack: pack)
        }
        view.delegate = context.coordinator.renderer
        updateUIView(view, context: context)
        return view
    }

    func updateUIView(_ view: MTKView, context: Context) {
        context.coordinator.renderer?.source = source
        if paused { context.coordinator.renderer?.rest() }
        view.isPaused = paused
    }

    static func dismantleUIView(_ view: MTKView, coordinator: Coordinator) {
        view.isPaused = true
        view.delegate = nil
    }
}

/// The scene's ring of points from XmbValleyScene; then how blurred each point is, the ground on its fog-coloured sky,
/// the depth of field laid over the last frame (the feedback), and the console's last soft blur onto the screen.
@MainActor final class XmbValleyRenderer: NSObject, MTKViewDelegate {
    var source: (inout [Float]) -> Void = { _ in }

    private static let step: Float = 1 / 60
    private static let buffers = 3

    // These match the structs of the same names in XmbValley.metal.
    private struct TerrainUniforms {
        var uMvp: simd_float4x4
        var uEye: SIMD3<Float>
        var uLine: SIMD4<Float>
        var uTime: SIMD3<Float>
        var uTexScale: Float
        var uFogMin: Float
        var uFogRange: Float
        var uScale: SIMD3<Float>
    }

    private struct ColourUniforms {
        var uFogColour: SIMD3<Float>
        var uLineColour: SIMD3<Float>
        var uBumpScale: Float
    }

    private struct FocusUniforms {
        var uFocalDepth: Float
        var uFocus: Float
    }

    private struct LayerUniforms {
        var uLod: Float
        var uFeedback: Float
        var uResRecip: SIMD2<Float>
        var uNoiseUV: SIMD3<Float>
        var uFbScale: SIMD2<Float>
        var uFbRot: SIMD2<Float>
    }

    private let device: MTLDevice
    private let queue: MTLCommandQueue
    private let terrainPipeline: MTLRenderPipelineState
    private let focusPipeline: MTLRenderPipelineState
    private let layerPipeline: MTLRenderPipelineState
    private let outPipeline: MTLRenderPipelineState
    private let depthState: MTLDepthStencilState
    private let indices: MTLBuffer
    private let vertexBuffers: [MTLBuffer]
    private let inFlight = DispatchSemaphore(value: buffers)
    private var bufferIndex = 0
    private let normalTexture: MTLTexture
    private let noise1: MTLTexture
    private let noise2: MTLTexture
    private var lut: MTLTexture?

    private let tour: XmbValleyTour
    private var input = [Float](repeating: 0, count: XmbVisualizerLevels.columns)
    private var seenPreset = 0

    // Render targets: the scene (with mip levels, for the blur), how blurred each point is, the feedback pair, and the
    // depth they share.
    private var front: MTLTexture?
    private var focus: MTLTexture?
    private var feedback: [MTLTexture] = []
    private var depth: MTLTexture?
    private var ping = 0
    private var clearFeedback = true

    private var last: CFTimeInterval = -1
    private var carry: Float = 0

    init?(device: MTLDevice, format: MTLPixelFormat, pack: XmbValleyFirmware.Pack) {
        guard let queue = device.makeCommandQueue(), let library = device.makeDefaultLibrary() else { return nil }
        func pipeline(_ vertex: String, _ fragment: String, _ format: MTLPixelFormat, depth: Bool) -> MTLRenderPipelineState? {
            guard let v = library.makeFunction(name: vertex), let f = library.makeFunction(name: fragment) else { return nil }
            let descriptor = MTLRenderPipelineDescriptor()
            descriptor.vertexFunction = v
            descriptor.fragmentFunction = f
            descriptor.colorAttachments[0].pixelFormat = format
            if depth { descriptor.depthAttachmentPixelFormat = .depth32Float }
            return try? device.makeRenderPipelineState(descriptor: descriptor)
        }
        let depthDescriptor = MTLDepthStencilDescriptor()
        depthDescriptor.depthCompareFunction = .lessEqual
        depthDescriptor.isDepthWriteEnabled = true
        guard let terrain = pipeline("xmbValley_terrainVertex", "xmbValley_terrainFragment", .rgba8Unorm, depth: true),
              let focus = pipeline("xmbValley_terrainVertex", "xmbValley_focusFragment", .rgba8Unorm, depth: true),
              let layer = pipeline("xmbValley_quadVertex", "xmbValley_layerFragment", .rgba8Unorm, depth: false),
              let out = pipeline("xmbValley_quadVertex", "xmbValley_outFragment", format, depth: false),
              let depthState = device.makeDepthStencilState(descriptor: depthDescriptor) else { return nil }

        // The strips between rows: two triangles a cell.
        let rows = XmbValleyScene.rows, cols = XmbValleyScene.cols
        var idx: [UInt16] = []
        idx.reserveCapacity(rows * (cols - 1) * 6)
        for s in 0 ..< rows {
            let r0 = s * cols
            let r1 = ((s + 1) % rows) * cols
            for c in 0 ..< cols - 1 {
                idx += [UInt16(r0 + c), UInt16(r0 + c + 1), UInt16(r1 + c), UInt16(r0 + c + 1), UInt16(r1 + c + 1), UInt16(r1 + c)]
            }
        }
        let vertexBytes = rows * cols * XmbValleyScene.stride * MemoryLayout<Float>.stride
        guard let indices = device.makeBuffer(bytes: idx, length: idx.count * MemoryLayout<UInt16>.stride) else { return nil }
        let vertexBuffers = (0 ..< Self.buffers).compactMap { _ in device.makeBuffer(length: vertexBytes, options: .storageModeShared) }
        guard vertexBuffers.count == Self.buffers else { return nil }

        // The firmware's noise, made as Android makes it so the dither matches.
        func noise(_ seed: Int64) -> MTLTexture? {
            var random = XmbJavaRandom(seed)
            let bytes = (0 ..< 64 * 64 * 4).map { _ in UInt8(truncatingIfNeeded: random.nextInt(256)) }
            return Self.texture(device, .rgba8Unorm, 64, 64, bytes, bytesPerRow: 64 * 4)
        }
        // Flat, facing up: with no normal map the ground is lit by its shape alone.
        let levels = pack.normalMap.isEmpty ? [XmbValleyFirmware.Level(width: 1, height: 1, rgba: [127, 127, 255, 255])] : pack.normalMap
        let normalDescriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba8Unorm, width: levels[0].width,
                                                                        height: levels[0].height, mipmapped: false)
        normalDescriptor.mipmapLevelCount = levels.count
        guard let noise1 = noise(1), let noise2 = noise(2), let normal = device.makeTexture(descriptor: normalDescriptor) else { return nil }
        for (i, level) in levels.enumerated() {
            normal.replace(region: MTLRegionMake2D(0, 0, level.width, level.height), mipmapLevel: i, withBytes: level.rgba,
                           bytesPerRow: level.width * 4)
        }

        self.device = device
        self.queue = queue
        terrainPipeline = terrain
        focusPipeline = focus
        layerPipeline = layer
        outPipeline = out
        self.depthState = depthState
        self.indices = indices
        self.vertexBuffers = vertexBuffers
        normalTexture = normal
        self.noise1 = noise1
        self.noise2 = noise2
        tour = XmbValleyTour(pack: pack, group: XmbValleyPresets.group, preset: XmbValleyPresets.preset)
        super.init()
        presetChanged()
    }

    private static func texture(_ device: MTLDevice, _ format: MTLPixelFormat, _ width: Int, _ height: Int, _ bytes: [UInt8],
                                bytesPerRow: Int) -> MTLTexture? {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: format, width: width, height: height, mipmapped: false)
        guard let texture = device.makeTexture(descriptor: descriptor) else { return nil }
        texture.replace(region: MTLRegionMake2D(0, 0, width, height), mipmapLevel: 0, withBytes: bytes, bytesPerRow: bytesPerRow)
        return texture
    }

    /// Paused: the next frame starts the clock over, as Android's thread does after it is parked.
    func rest() { last = -1 }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

    func draw(in view: MTKView) {
        let now = CACurrentMediaTime()
        let dt = last < 0 ? 0 : min(max(Float(now - last), 0), 0.25)
        last = now
        carry += dt
        var steps = 0
        while carry >= Self.step && steps < 4 {
            tick()
            carry -= Self.step
            steps += 1
        }
        if steps == 4 { carry = 0 }

        guard let pass = view.currentRenderPassDescriptor, let drawable = view.currentDrawable else { return }
        inFlight.wait()
        guard let commands = queue.makeCommandBuffer() else {
            inFlight.signal()
            return
        }
        let semaphore = inFlight
        commands.addCompletedHandler { _ in semaphore.signal() }
        encode(commands, pass: pass, width: Int(view.drawableSize.width), height: Int(view.drawableSize.height))
        commands.present(drawable)
        commands.commit()
    }

    private func tick() {
        let flick = XmbValleyPresets.flicks
        XmbValleyPresets.flicks = 0
        source(&input)
        tour.tick(input, flick: flick, step: Self.step)
        XmbValleyPresets.group = tour.group
        XmbValleyPresets.preset = tour.preset
        if tour.started != seenPreset { presetChanged() }
    }

    private func presetChanged() {
        seenPreset = tour.started
        if let p = tour.params {
            let data = XmbValleyTour.fresLut(p)
            let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba32Float, width: 512, height: 1, mipmapped: false)
            lut = device.makeTexture(descriptor: descriptor)
            lut?.replace(region: MTLRegionMake2D(0, 0, 512, 1), mipmapLevel: 0, withBytes: data, bytesPerRow: 512 * 16)
        }
        clearFeedback = true
    }

    private func encode(_ commands: MTLCommandBuffer, pass: MTLRenderPassDescriptor, width w: Int, height h: Int) {
        guard let p = tour.params, let lut, w > 0, h > 0, targets(w, h), let front, let focus, let depth else {
            // Nothing to draw yet: black.
            commands.makeRenderCommandEncoder(descriptor: pass)?.endEncoding()
            return
        }
        let scene = tour.scene
        let buffer = vertexBuffers[bufferIndex]
        bufferIndex = (bufferIndex + 1) % Self.buffers
        scene.vertices.withUnsafeBytes { buffer.contents().copyMemory(from: $0.baseAddress!, byteCount: $0.count) }

        // The camera, POS and TARGET from where the terrain has got to.
        let t = scene.travelled
        let view = XmbVisualizerMatrix.lookAt(SIMD3(p.posX, p.posY, p.posZ + t), SIMD3(p.targetX, p.targetY, p.targetZ + t), SIMD3(0, 1, 0))
        // Held upright, as much across as the console's 16:9 shows, and more up and down.
        let aspect = Float(w) / Float(max(h, 1))
        let projection = XmbVisualizerMatrix.perspective(fovy: xmbFovy(p.zoom, aspect: aspect), aspect: aspect, near: 0.1, far: 10000)
        let fade = p.fade * p.fade
        let newest = scene.newest
        var terrain = TerrainUniforms(uMvp: projection * view, uEye: SIMD3(p.posX, p.posY, p.posZ + t),
                                      uLine: SIMD4(p.lineWidth, p.lineHeight, p.lineLength, p.lineBias),
                                      uTime: SIMD3(p.lineAhead + t, p.timeAhead + t, 126.9 + t), uTexScale: p.textureScale,
                                      uFogMin: p.fogMin, uFogRange: p.fogMax - p.fogMin != 0 ? p.fogMax - p.fogMin : 1e-6,
                                      uScale: SIMD3(p.scaleX, 0.3 * p.terrainHeight, 1))

        // How blurred each point is: the sky at SKY BLUR, the ground by its distance from the focus.
        var focusUniforms = FocusUniforms(uFocalDepth: p.focalDepth, uFocus: 1 / (p.focalVariance != 0 ? p.focalVariance : 1e-6))
        let sky = Double(min(max(p.skyBlur, 0), 1))
        drawTerrain(commands, into: focus, depth: depth, clear: MTLClearColor(red: sky, green: 1, blue: 1, alpha: 1), focusPipeline,
                    buffer, &terrain, newest) { encoder in
            encoder.setFragmentBytes(&focusUniforms, length: MemoryLayout<FocusUniforms>.stride, index: 0)
        }

        // The scene: the ground on its fog-coloured sky.
        let fog = p.fog * fade
        let fogClear = MTLClearColor(red: Double(fog.x), green: Double(fog.y), blue: Double(fog.z), alpha: 1)
        var colour = ColourUniforms(uFogColour: fog, uLineColour: p.line * fade, uBumpScale: p.bumpScale)
        drawTerrain(commands, into: front, depth: depth, clear: fogClear, terrainPipeline, buffer, &terrain, newest) { encoder in
            encoder.setFragmentTexture(self.normalTexture, index: 0)
            encoder.setFragmentTexture(lut, index: 1)
            encoder.setFragmentBytes(&colour, length: MemoryLayout<ColourUniforms>.stride, index: 0)
        }
        if let blit = commands.makeBlitCommandEncoder() {
            blit.generateMipmaps(for: front)
            blit.endEncoding()
        }

        // Depth of field and feedback, into one of the pair from the other.
        if clearFeedback {
            for texture in feedback {
                let clear = MTLRenderPassDescriptor()
                clear.colorAttachments[0].texture = texture
                clear.colorAttachments[0].loadAction = .clear
                clear.colorAttachments[0].clearColor = fogClear
                clear.colorAttachments[0].storeAction = .store
                commands.makeRenderCommandEncoder(descriptor: clear)?.endEncoding()
            }
            clearFeedback = false
        }
        let dst = feedback[ping], prev = feedback[1 - ping]
        var layer = LayerUniforms(uLod: p.lod, uFeedback: p.feedback, uResRecip: SIMD2(1 / Float(w), 1 / Float(h)),
                                  uNoiseUV: SIMD3(scene.rand() * 0.5 + 0.5, scene.rand() * 0.5 + 0.5, 1 / 128),
                                  uFbScale: SIMD2(p.feedbackX, p.feedbackY), uFbRot: SIMD2(sin(p.feedbackRot), cos(p.feedbackRot)))
        let layerPass = MTLRenderPassDescriptor()
        layerPass.colorAttachments[0].texture = dst
        layerPass.colorAttachments[0].loadAction = .dontCare
        layerPass.colorAttachments[0].storeAction = .store
        if let encoder = commands.makeRenderCommandEncoder(descriptor: layerPass) {
            encoder.setRenderPipelineState(layerPipeline)
            encoder.setFragmentTexture(focus, index: 0)
            encoder.setFragmentTexture(prev, index: 1)
            encoder.setFragmentTexture(noise1, index: 2)
            encoder.setFragmentTexture(front, index: 3)
            encoder.setFragmentTexture(noise2, index: 4)
            encoder.setFragmentBytes(&layer, length: MemoryLayout<LayerUniforms>.stride, index: 0)
            encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
            encoder.endEncoding()
        }

        // To the screen through the console's last soft blur.
        if let encoder = commands.makeRenderCommandEncoder(descriptor: pass) {
            var step = SIMD2<Float>(1 / Float(w), 1 / Float(h))
            encoder.setRenderPipelineState(outPipeline)
            encoder.setFragmentTexture(dst, index: 0)
            encoder.setFragmentBytes(&step, length: MemoryLayout<SIMD2<Float>>.stride, index: 0)
            encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
            encoder.endEncoding()
        }
        ping = 1 - ping
    }

    // The ring, every strip but the seam from the newest row back round to the oldest.
    private func drawTerrain(_ commands: MTLCommandBuffer, into texture: MTLTexture, depth: MTLTexture, clear: MTLClearColor,
                             _ pipeline: MTLRenderPipelineState, _ buffer: MTLBuffer, _ terrain: inout TerrainUniforms, _ newest: Int,
                             _ bind: (MTLRenderCommandEncoder) -> Void) {
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = texture
        pass.colorAttachments[0].loadAction = .clear
        pass.colorAttachments[0].clearColor = clear
        pass.colorAttachments[0].storeAction = .store
        pass.depthAttachment.texture = depth
        pass.depthAttachment.loadAction = .clear
        pass.depthAttachment.clearDepth = 1
        pass.depthAttachment.storeAction = .dontCare
        guard let encoder = commands.makeRenderCommandEncoder(descriptor: pass) else { return }
        encoder.setRenderPipelineState(pipeline)
        encoder.setDepthStencilState(depthState)
        encoder.setCullMode(.none)
        encoder.setVertexBuffer(buffer, offset: 0, index: 0)
        encoder.setVertexBytes(&terrain, length: MemoryLayout<TerrainUniforms>.stride, index: 1)
        bind(encoder)
        let perStrip = (XmbValleyScene.cols - 1) * 6
        let after = newest + 1
        if after < XmbValleyScene.rows {
            encoder.drawIndexedPrimitives(type: .triangle, indexCount: (XmbValleyScene.rows - after) * perStrip, indexType: .uint16,
                                          indexBuffer: indices, indexBufferOffset: after * perStrip * 2)
        }
        if newest > 0 {
            encoder.drawIndexedPrimitives(type: .triangle, indexCount: newest * perStrip, indexType: .uint16, indexBuffer: indices,
                                          indexBufferOffset: 0)
        }
        encoder.endEncoding()
    }

    /// The targets at the drawable's size, made again when that changes; false when one cannot be made.
    private func targets(_ w: Int, _ h: Int) -> Bool {
        if let front, front.width == w, front.height == h, focus != nil, feedback.count == 2, depth != nil { return true }
        func make(_ format: MTLPixelFormat, mipmapped: Bool = false) -> MTLTexture? {
            let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: format, width: w, height: h, mipmapped: mipmapped)
            descriptor.usage = [.renderTarget, .shaderRead]
            descriptor.storageMode = .private
            return device.makeTexture(descriptor: descriptor)
        }
        front = make(.rgba8Unorm, mipmapped: true)
        focus = make(.rgba8Unorm)
        feedback = [make(.rgba8Unorm), make(.rgba8Unorm)].compactMap { $0 }
        depth = make(.depth32Float)
        clearFeedback = true
        return front != nil && focus != nil && feedback.count == 2 && depth != nil
    }
}
