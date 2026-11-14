// SPDX-License-Identifier: GPL-2.0-only
// The wave drawn in a lines.qrc mood for the Waves visualizer: its own sky, exposure and the console's glare; ported from Android's XmbFirmwareWave.kt (drawMood).

import Foundation
import Metal
import simd

/// With a mood (lines.qrc override/<name>, the music player's "music_1") the wave's picture is the mood's whole
/// screen: its own sky (lib/moyou/back_colours1 under BACKGROUND colours 1 and 2), the lines in colours 3 and 4, the
/// HDR.mnu exposure, and the console's glare: the bright part taken down to 128x64 and five smaller sizes, each
/// blurred by a gaussian per colour and added back up by GLARE SUM POW (glutils GlareSource, Gaussian, AccGlare and
/// ToneApplyDisplay). XmbWaveRenderer hands it the lines' light it has added up.
@MainActor final class XmbWaveMoodPass {
    /// The same light as the XMB's line gain before a mood's own exposure: the XMB's look is after its exposure, which
    /// (HDR.mnu 1.05, white 0.899) brightens the light's range by about a tenth.
    private static let lineLight: Float = 1.6 / 1.1
    private static let glareWidth = 128
    private static let glareHeight = 64
    private static let glareLevels = 6

    // These match the structs of the same names in XmbWaveMood.metal.
    private struct SceneUniforms {
        var uCol1: SIMD3<Float>
        var uCol2: SIMD3<Float>
        var uCol3: SIMD3<Float>
        var uCol4: SIMD3<Float>
        var uSky: Float
        var uDayCos: Float
        var uGain: Float
        var uExposure: Float
        var uWhiteSqrRcp: Float
    }

    private struct SourceUniforms {
        var uFoot: SIMD2<Float>
        var uThreshold: Float
    }

    private let device: MTLDevice
    private let scenePipeline: MTLRenderPipelineState
    private let sourcePipeline: MTLRenderPipelineState
    private let copyPipeline: MTLRenderPipelineState
    private let addPipeline: MTLRenderPipelineState
    private let blurPipeline: MTLRenderPipelineState
    private let outPipeline: MTLRenderPipelineState
    private var scene: MTLTexture?
    private var levels: [MTLTexture] = []
    private var blurred: [MTLTexture] = []
    private var glare: MTLTexture?

    init?(device: MTLDevice, library: MTLLibrary, format: MTLPixelFormat) {
        func pipeline(_ fragment: String, _ format: MTLPixelFormat, added: Bool = false) -> MTLRenderPipelineState? {
            guard let vertex = library.makeFunction(name: "xmbMood_quadVertex"),
                  let fragment = library.makeFunction(name: fragment) else { return nil }
            let descriptor = MTLRenderPipelineDescriptor()
            descriptor.vertexFunction = vertex
            descriptor.fragmentFunction = fragment
            let colour: MTLRenderPipelineColorAttachmentDescriptor = descriptor.colorAttachments[0]
            colour.pixelFormat = format
            if added {
                colour.isBlendingEnabled = true
                colour.sourceRGBBlendFactor = .one
                colour.destinationRGBBlendFactor = .one
                colour.sourceAlphaBlendFactor = .one
                colour.destinationAlphaBlendFactor = .one
            }
            return try? device.makeRenderPipelineState(descriptor: descriptor)
        }
        guard let scene = pipeline("xmbMood_sceneFragment", .rgba16Float),
              let source = pipeline("xmbMood_sourceFragment", .rgba16Float),
              let copy = pipeline("xmbMood_copyFragment", .rgba16Float),
              let add = pipeline("xmbMood_copyFragment", .rgba16Float, added: true),
              let blur = pipeline("xmbMood_blurFragment", .rgba16Float),
              let out = pipeline("xmbMood_outFragment", format) else { return nil }
        self.device = device
        scenePipeline = scene
        sourcePipeline = source
        copyPipeline = copy
        addPipeline = add
        blurPipeline = blur
        outPipeline = out
    }

    /// The mood's picture from the lines' light in `lines` into `pass`, `width` x `height` pixels, at `level`.
    func encode(_ commands: MTLCommandBuffer, pass: MTLRenderPassDescriptor, lines: MTLTexture, mood: XmbWaveFirmware.Mood,
                width: Int, height: Int, level: Float, date: Date) {
        guard let scene = target(scene, width, height) else { return }
        self.scene = scene
        let gw = Self.glareWidth, gh = Self.glareHeight
        for n in 0 ..< Self.glareLevels {
            let w = max(1, gw >> n), h = max(1, gh >> n)
            guard let l = target(levels.indices.contains(n) ? levels[n] : nil, w, h),
                  let b = target(blurred.indices.contains(n) ? blurred[n] : nil, w, h) else { return }
            if levels.indices.contains(n) { levels[n] = l } else { levels.append(l) }
            if blurred.indices.contains(n) { blurred[n] = b } else { blurred.append(b) }
        }
        guard let sum = target(glare, gw, gh) else { return }
        glare = sum

        // The sky (back_colours1) under colours 1 at the bottom to 2 at the top, the lines in colours 3 at the left to
        // 4 at the right, and the exposure (glutils ToneMap).
        let c = mood.colours
        var sceneUniforms = SceneUniforms(uCol1: c[0], uCol2: c[1], uCol3: c[2], uCol4: c[3], uSky: mood.colourShader == 1 ? 1 : 0,
                                          uDayCos: Self.dayCos(date), uGain: Self.lineLight * level, uExposure: mood.hdr.exposure,
                                          uWhiteSqrRcp: 1 / (mood.hdr.white * mood.hdr.white))
        draw(commands, into: scene, scenePipeline) { encoder in
            encoder.setFragmentTexture(lines, index: 0)
            encoder.setFragmentBytes(&sceneUniforms, length: MemoryLayout<SceneUniforms>.stride, index: 0)
        }

        // What is brighter than GLARE THRESH, at 128x64 (glutils GlareSourceMRT), then five smaller sizes, each half
        // the last.
        var source = SourceUniforms(uFoot: SIMD2(1 / Float(gw), 1 / Float(gh)), uThreshold: mood.hdr.glareThreshold)
        draw(commands, into: levels[0], sourcePipeline) { encoder in
            encoder.setFragmentTexture(scene, index: 0)
            encoder.setFragmentBytes(&source, length: MemoryLayout<SourceUniforms>.stride, index: 0)
        }
        var one: Float = 1
        for n in 1 ..< Self.glareLevels {
            draw(commands, into: levels[n], copyPipeline) { encoder in
                encoder.setFragmentTexture(self.levels[n - 1], index: 0)
                encoder.setFragmentBytes(&one, length: MemoryLayout<Float>.stride, index: 0)
            }
        }

        // Each size blurred across and then down, a gaussian of GAUSSIAN RAD texels for each colour.
        var weights = XmbGlare.gaussian(mood.hdr.radius)
        for n in 0 ..< Self.glareLevels {
            let l = levels[n], b = blurred[n]
            var across = SIMD2<Float>(1 / Float(l.width), 0)
            draw(commands, into: b, blurPipeline) { encoder in
                encoder.setFragmentTexture(l, index: 0)
                encoder.setFragmentBytes(&across, length: MemoryLayout<SIMD2<Float>>.stride, index: 0)
                encoder.setFragmentBytes(&weights, length: MemoryLayout<SIMD3<Float>>.stride * weights.count, index: 1)
            }
            var down = SIMD2<Float>(0, 1 / Float(l.height))
            draw(commands, into: l, blurPipeline) { encoder in
                encoder.setFragmentTexture(b, index: 0)
                encoder.setFragmentBytes(&down, length: MemoryLayout<SIMD2<Float>>.stride, index: 0)
                encoder.setFragmentBytes(&weights, length: MemoryLayout<SIMD3<Float>>.stride * weights.count, index: 1)
            }
        }

        // Added up at 128x64, the largest the most (glutils AccGlare).
        let shares = XmbGlare.levelWeights(sumPow: mood.hdr.glareSumPow, level: mood.hdr.glareLevel, count: Self.glareLevels)
        let accumulate = MTLRenderPassDescriptor()
        accumulate.colorAttachments[0].texture = sum
        accumulate.colorAttachments[0].loadAction = .clear
        accumulate.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 0)
        accumulate.colorAttachments[0].storeAction = .store
        if let encoder = commands.makeRenderCommandEncoder(descriptor: accumulate) {
            encoder.setRenderPipelineState(addPipeline)
            for n in stride(from: Self.glareLevels - 1, through: 0, by: -1) {
                var weight = shares[n]
                encoder.setFragmentTexture(levels[n], index: 0)
                encoder.setFragmentBytes(&weight, length: MemoryLayout<Float>.stride, index: 0)
                encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
            }
            encoder.endEncoding()
        }

        // The scene and its glare, over the whole screen (glutils ToneApplyDisplay).
        guard let encoder = commands.makeRenderCommandEncoder(descriptor: pass) else { return }
        encoder.setRenderPipelineState(outPipeline)
        encoder.setFragmentTexture(scene, index: 0)
        encoder.setFragmentTexture(sum, index: 1)
        encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        encoder.endEncoding()
    }

    private func draw(_ commands: MTLCommandBuffer, into texture: MTLTexture, _ pipeline: MTLRenderPipelineState,
                      _ bind: (MTLRenderCommandEncoder) -> Void) {
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = texture
        pass.colorAttachments[0].loadAction = .dontCare
        pass.colorAttachments[0].storeAction = .store
        guard let encoder = commands.makeRenderCommandEncoder(descriptor: pass) else { return }
        encoder.setRenderPipelineState(pipeline)
        bind(encoder)
        encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        encoder.endEncoding()
    }

    private func target(_ old: MTLTexture?, _ width: Int, _ height: Int) -> MTLTexture? {
        if let old, old.width == width, old.height == height { return old }
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba16Float, width: width, height: height, mipmapped: false)
        descriptor.usage = [.renderTarget, .shaderRead]
        descriptor.storageMode = .private
        return device.makeTexture(descriptor: descriptor)
    }

    /// back_colours1's _DayTime: the cosine of the hour of the day times 100.
    static func dayCos(_ date: Date) -> Float {
        let local = date.timeIntervalSince1970 + Double(TimeZone.current.secondsFromGMT(for: date))
        let hours = local.truncatingRemainder(dividingBy: 86_400).advanced(by: local < 0 ? 86_400 : 0) / 3_600
        return Float(cos(hours * 100))
    }
}
