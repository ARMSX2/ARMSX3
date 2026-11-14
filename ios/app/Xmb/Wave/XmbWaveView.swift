// SPDX-License-Identifier: GPL-2.0-only
// XMB mode's background in an MTKView, the month's gradient under the console's wave, ported from Android's XmbGlView.kt and XmbFirmwareWave.kt.

import MetalKit
import QuartzCore
import SwiftUI

struct XmbWaveView: UIViewRepresentable {
    /// -1 follows the month, 0...11 keeps that month's colour, 12 goes through them all.
    var themeColor: Int
    /// How much of the wave shows: the boot animation brings it up from 0, on black. 1 otherwise.
    var level: Float = 1
    /// How much of the colour shows: the boot animation raises it from black behind the wave. 1 otherwise.
    var colorLevel: Float = 1
    /// False while a game's picture covers the background, which leaves only the gradient.
    var drawsWave = true
    var paused = false
    /// Where the sparkles find the wave they are born on.
    var link: XmbWaveLink?
    /// A lines.qrc mood to draw the wave in ("music_1" for the Waves visualizer): its own sky, colours and glare.
    var mood: String?
    /// How hard the music pushes the wave now (the Waves visualizer), asked once a frame; none for the XMB's own.
    var drive: (() -> Float)?

    final class Coordinator {
        var renderer: XmbWaveRenderer?
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> MTKView {
        let view = MTKView(frame: .zero, device: MTLCreateSystemDefaultDevice())
        // Not an sRGB format: the colours are blended as they are, as Android's are.
        view.colorPixelFormat = .bgra8Unorm
        view.clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
        view.framebufferOnly = true
        // The wave is slow, so 30 looks the same as 60 for half the work.
        view.preferredFramesPerSecond = 30
        view.enableSetNeedsDisplay = false
        view.isOpaque = true
        view.backgroundColor = .black
        if let device = view.device {
            context.coordinator.renderer = XmbWaveRenderer(device: device, format: view.colorPixelFormat, mood: mood)
        }
        view.delegate = context.coordinator.renderer
        context.coordinator.renderer?.view = view
        link?.renderer = context.coordinator.renderer
        updateUIView(view, context: context)
        return view
    }

    func updateUIView(_ view: MTKView, context: Context) {
        if let renderer = context.coordinator.renderer {
            renderer.themeColor = themeColor
            renderer.level = level
            renderer.colorLevel = colorLevel
            renderer.drawsWave = drawsWave
            renderer.drive = drive
        }
        view.isPaused = paused
        // A paused wave still draws on layout and on each change, so one made paused (Reduce Motion) is not left black.
        view.enableSetNeedsDisplay = paused
        if paused { view.setNeedsDisplay() }
    }

    static func dismantleUIView(_ view: MTKView, coordinator: Coordinator) {
        view.isPaused = true
        view.delegate = nil
    }
}

/// The wave view's renderer, for the sparkle view beside it.
@MainActor final class XmbWaveLink {
    weak var renderer: XmbWaveRenderer?
}

/// The gradient is drawn from the first frame. The wave joins it once the firmware's settings and the springs'
/// starting state are read, off the main thread: its light is added up in a target of its own and then laid over
/// the gradient in the line colour.
@MainActor final class XmbWaveRenderer: NSObject, MTKViewDelegate {
    var themeColor = -1
    var level: Float = 1
    var colorLevel: Float = 1
    var drawsWave = true
    var drive: (() -> Float)?
    /// The view it draws into, so a paused one draws again once the wave has loaded.
    weak var view: MTKView?

    /// The wave's 19x19 control points in clip space (x, y, z, w) as the last frame left them.
    private(set) var controlPoints = [Float](repeating: 0, count: XmbWaveModel.count * 4)

    private static let grid = 128

    /// The line light's strength over the background (the console's exposure and glare).
    private static let gain: Float = 1.6

    // These match the structs of the same names in XmbWave.metal.
    private struct GradientUniforms {
        var uColorStart: SIMD3<Float>
        var uColorEnd: SIMD3<Float>
        var uDir: SIMD2<Float>
        var uTMin: Float
    }

    private struct LineUniforms {
        var uFresnel: Float
        var uBrightness: Float
        var uMipBias: Float
    }

    private struct CompositeUniforms {
        var uCol3: SIMD3<Float>
        var uCol4: SIMD3<Float>
        var uGain: Float
    }

    /// It is made on the loading task and then only used on the main actor.
    private final class Wave: @unchecked Sendable {
        let model: XmbWaveModel
        let firmware: XmbWaveFirmware
        let strands: MTLTexture
        /// The mood asked for, when the firmware has lines.qrc to read it from.
        let mood: XmbWaveFirmware.Mood?

        init(model: XmbWaveModel, firmware: XmbWaveFirmware, strands: MTLTexture, mood: XmbWaveFirmware.Mood?) {
            self.model = model
            self.firmware = firmware
            self.strands = strands
            self.mood = mood
        }
    }

    private let device: MTLDevice
    private let queue: MTLCommandQueue
    private let gradientPipeline: MTLRenderPipelineState
    private let linePipeline: MTLRenderPipelineState
    private let compositePipeline: MTLRenderPipelineState
    private let lineFormat: MTLPixelFormat
    private let gridIndices: MTLBuffer
    private let gridIndexCount: Int
    private var lineTarget: MTLTexture?
    private var wave: Wave?
    private var last: CFTimeInterval = -1
    private let moodName: String?
    private let moodPass: XmbWaveMoodPass?

    init?(device: MTLDevice, format: MTLPixelFormat, mood: String? = nil) {
        guard let queue = device.makeCommandQueue(), let library = device.makeDefaultLibrary(),
              let gradient = Self.pipeline(device, library, "xmbWave_quadVertex", "xmbWave_gradientFragment", format, added: false),
              let composite = Self.pipeline(device, library, "xmbWave_quadVertex", "xmbWave_compositeFragment", format, added: true)
        else { return nil }
        // Only the red of the light is used. Half floats keep it past 1 where lines pile up; 8 bits, as the
        // console has, if the device will not blend into those.
        if let line = Self.pipeline(device, library, "xmbWave_lineVertex", "xmbWave_lineFragment", .r16Float, added: true) {
            linePipeline = line
            lineFormat = .r16Float
        } else if let line = Self.pipeline(device, library, "xmbWave_lineVertex", "xmbWave_lineFragment", .r8Unorm, added: true) {
            linePipeline = line
            lineFormat = .r8Unorm
        } else {
            return nil
        }

        // Strips along each line of the 128x128 samples, with a restart between.
        var indices: [UInt16] = []
        for r in 0 ..< Self.grid - 1 {
            for c in 0 ..< Self.grid {
                indices.append(UInt16(r * Self.grid + c))
                indices.append(UInt16((r + 1) * Self.grid + c))
            }
            indices.append(0xFFFF)
        }
        guard let gridIndices = device.makeBuffer(bytes: indices, length: indices.count * MemoryLayout<UInt16>.stride)
        else { return nil }

        self.device = device
        self.queue = queue
        gradientPipeline = gradient
        compositePipeline = composite
        self.gridIndices = gridIndices
        gridIndexCount = indices.count
        moodName = mood
        moodPass = mood == nil ? nil : XmbWaveMoodPass(device: device, library: library, format: format)
        super.init()

        Task { [weak self] in
            let wave = await Task.detached(priority: .userInitiated) { Self.load(device, mood: mood) }.value
            self?.wave = wave
            if let view = self?.view, view.isPaused { view.setNeedsDisplay() }
        }
    }

    private static func pipeline(_ device: MTLDevice, _ library: MTLLibrary, _ vertex: String, _ fragment: String,
                                 _ format: MTLPixelFormat, added: Bool) -> MTLRenderPipelineState? {
        guard let vertex = library.makeFunction(name: vertex), let fragment = library.makeFunction(name: fragment)
        else { return nil }
        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.vertexFunction = vertex
        descriptor.fragmentFunction = fragment
        let colour: MTLRenderPipelineColorAttachmentDescriptor = descriptor.colorAttachments[0]
        colour.pixelFormat = format
        if added {
            colour.isBlendingEnabled = true
            colour.sourceRGBBlendFactor = .one
            colour.destinationRGBBlendFactor = .one
            // The alpha already there is kept, so the view stays opaque.
            colour.sourceAlphaBlendFactor = .zero
            colour.destinationAlphaBlendFactor = .one
        }
        return try? device.makeRenderPipelineState(descriptor: descriptor)
    }

    /// The firmware's settings and strands when lines.qrc is there, the firmware's defaults otherwise.
    private nonisolated static func load(_ device: MTLDevice, mood moodName: String?) -> Wave? {
        let state = Bundle.main.url(forResource: "wave_state", withExtension: "bin")
            .flatMap { try? Data(contentsOf: $0) }.map { [UInt8]($0) }
        let lines = XmbFirmware.devFlash.appendingPathComponent("vsh/resource/qgl/lines.qrc")
        let files = XmbQrc.open(lines)
        var firmware = files.map(XmbWaveFirmware.init(files:)) ?? XmbWaveFirmware()
        var mood = files.flatMap { files in moodName.map { XmbWaveFirmware.mood($0, files: files) } }
        var model = XmbWaveModel(state: state, line: mood?.line ?? firmware.line)
        // Settings that take the wave out of the numbers count as absent.
        var points = [Float](repeating: 0, count: XmbWaveModel.count * 4)
        model.update(dt: 0, aspect: 16 / 9, into: &points)
        if !points.allSatisfy(\.isFinite) {
            firmware.line = XmbWaveModel.Line()
            mood?.line = firmware.line
            model = XmbWaveModel(state: state, line: firmware.line)
        }

        let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .r8Unorm, width: firmware.strands.count,
                                                                  height: 1, mipmapped: false)
        guard let strands = device.makeTexture(descriptor: descriptor) else { return nil }
        let bytes = firmware.strands.map { UInt8(min(max($0, 0), 1) * 255 + 0.5) }
        strands.replace(region: MTLRegionMake2D(0, 0, bytes.count, 1), mipmapLevel: 0, withBytes: bytes,
                        bytesPerRow: bytes.count)
        return Wave(model: model, firmware: firmware, strands: strands, mood: mood)
    }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

    func draw(in view: MTKView) {
        guard let pass = view.currentRenderPassDescriptor, let drawable = view.currentDrawable,
              let commands = queue.makeCommandBuffer() else { return }
        encode(commands, pass: pass, width: Int(view.drawableSize.width), height: Int(view.drawableSize.height),
               now: CACurrentMediaTime(), date: Date())
        commands.present(drawable)
        commands.commit()
    }

    /// One frame into `pass`, `width` x `height` pixels: `now` moves the wave and `date` picks the colour.
    func encode(_ commands: MTLCommandBuffer, pass: MTLRenderPassDescriptor, width: Int, height: Int,
                now: CFTimeInterval, date: Date) {
        let width = max(width, 1), height = max(height, 1)
        // A song pushing it (the Waves visualizer) also brightens it a little on the beat.
        let push = drive?() ?? 0
        let level = level * (1 + push * 0.35)
        var lines: MTLTexture?
        if drawsWave, let wave, let target = target(width, height) {
            wave.model.drive = push
            // A long gap, after a pause or a game's picture, is cut to a quarter second by the model.
            let dt = last < 0 ? 0 : Float(now - last)
            last = now
            wave.model.update(dt: dt, aspect: Float(width) / Float(height), into: &controlPoints)

            let linesPass = MTLRenderPassDescriptor()
            linesPass.colorAttachments[0].texture = target
            linesPass.colorAttachments[0].loadAction = .clear
            linesPass.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 0)
            linesPass.colorAttachments[0].storeAction = .store
            // A buffer of its own each frame, so the frame before can still be reading the last one.
            if let ctrl = device.makeBuffer(bytes: controlPoints, length: controlPoints.count * MemoryLayout<Float>.stride),
               let encoder = commands.makeRenderCommandEncoder(descriptor: linesPass) {
                var uniforms = LineUniforms(uFresnel: wave.model.line.fresnel, uBrightness: wave.model.line.brightness,
                                            uMipBias: wave.model.line.mipmapBias)
                encoder.setRenderPipelineState(linePipeline)
                encoder.setVertexBuffer(ctrl, offset: 0, index: 0)
                encoder.setVertexBytes(&uniforms, length: MemoryLayout<LineUniforms>.stride, index: 1)
                encoder.setFragmentTexture(wave.strands, index: 0)
                encoder.drawIndexedPrimitives(type: .triangleStrip, indexCount: gridIndexCount, indexType: .uint16,
                                              indexBuffer: gridIndices, indexBufferOffset: 0)
                encoder.endEncoding()
                lines = target
            }
        }

        // A mood brings its own sky; until its settings are read, black.
        if let lines, let wave, let mood = wave.mood, let moodPass {
            moodPass.encode(commands, pass: pass, lines: lines, mood: mood, width: width, height: height, level: level, date: date)
            return
        }
        guard let encoder = commands.makeRenderCommandEncoder(descriptor: pass) else { return }
        if moodName != nil && wave == nil {
            encoder.endEncoding()
            return
        }
        let month = XmbOriginalColors.at(date, themeColor: themeColor)
        let direction = XmbOriginalColors.shaderDirection(angleDeg: month.angleDeg, width: width, height: height)
        var gradient = GradientUniforms(uColorStart: month.start * colorLevel, uColorEnd: month.end * colorLevel,
                                        uDir: direction.dir, uTMin: direction.tMin)
        encoder.setRenderPipelineState(gradientPipeline)
        encoder.setFragmentBytes(&gradient, length: MemoryLayout<GradientUniforms>.stride, index: 0)
        encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        if let lines, let wave {
            var composite = CompositeUniforms(uCol3: wave.firmware.colour3, uCol4: wave.firmware.colour4,
                                              uGain: Self.gain * level)
            encoder.setRenderPipelineState(compositePipeline)
            encoder.setFragmentTexture(lines, index: 0)
            encoder.setFragmentBytes(&composite, length: MemoryLayout<CompositeUniforms>.stride, index: 0)
            encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        }
        encoder.endEncoding()
    }

    /// The lines' target at the drawable's size, made again when that changes.
    private func target(_ width: Int, _ height: Int) -> MTLTexture? {
        if let lineTarget, lineTarget.width == width, lineTarget.height == height { return lineTarget }
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: lineFormat, width: width, height: height,
                                                                  mipmapped: false)
        descriptor.usage = [.renderTarget, .shaderRead]
        descriptor.storageMode = .private
        lineTarget = device.makeTexture(descriptor: descriptor)
        return lineTarget
    }
}
