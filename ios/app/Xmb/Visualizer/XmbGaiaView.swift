// SPDX-License-Identifier: GPL-2.0-only
// Gaia on screen in an MTKView, the Earth in light with its own exposure and glare; ported from Android's XmbGaia.kt (XmbGaiaView).

import MetalKit
import QuartzCore
import SwiftUI

struct XmbGaiaView: UIViewRepresentable {
    var paused = false

    final class Coordinator {
        var renderer: XmbGaiaRenderer?
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> MTKView {
        let view = MTKView(frame: .zero, device: MTLCreateSystemDefaultDevice())
        view.colorPixelFormat = .bgra8Unorm
        view.clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
        view.framebufferOnly = true
        view.preferredFramesPerSecond = 60
        view.enableSetNeedsDisplay = false
        view.isOpaque = true
        view.backgroundColor = .black
        view.isUserInteractionEnabled = false
        if let device = view.device {
            context.coordinator.renderer = XmbGaiaRenderer(device: device, format: view.colorPixelFormat)
        }
        view.delegate = context.coordinator.renderer
        updateUIView(view, context: context)
        return view
    }

    func updateUIView(_ view: MTKView, context: Context) {
        if paused { context.coordinator.renderer?.rest() }
        view.isPaused = paused
    }

    static func dismantleUIView(_ view: MTKView, coordinator: Coordinator) {
        view.isPaused = true
        view.delegate = nil
    }
}

/// The planet as the firmware's CubeEarthLayer draws it: the sea taken to its dark blue, clouds lifted off the ground by
/// their thickness and casting shadows away from the sun, the sun's glint off the water, all under the sunlight that
/// comes through the air, scattered along each line of sight in the console's own colours. Then the sun with its glow
/// and spikes, the stars, and the console's exposure, glare and tone. Black until the firmware's pictures are read.
@MainActor final class XmbGaiaRenderer: NSObject, MTKViewDelegate {
    private static let glareBase = 256
    private static let lumWidth = 64
    private static let lumHeight = 32
    private static let lumLevels = 7
    /// The air's Rayleigh and Mie scattering, as the space shader has them.
    private static let kr: Float = 0.0025
    private static let km: Float = 0.0010
    /// The firmware's own ATTN EARTH and ATTN ATM., which the look is made at; presets go from there.
    private static let attnEarth: Float = 0.038
    private static let attnAtm: Float = 2.176

    // These match the structs of the same names in XmbGaia.metal.
    private struct SpaceUniforms {
        var uInvViewProj: simd_float4x4
        var uEye: SIMD3<Float>
        var uSun: SIMD3<Float>
        var uCloud: SIMD4<Float>
        var uShadow: SIMD2<Float>
        var uSea: SIMD4<Float>
        var uInvWave4: SIMD3<Float>
        var uSunColour: SIMD3<Float>
        var uSunIntensity: Float
        var uAttn: SIMD2<Float>
        var uStars: SIMD3<Float>
        var uFade: Float
    }

    private struct Sight {
        var uInvViewProj: simd_float4x4
        var uEye: SIMD3<Float>
        var uViewport: SIMD2<Float>
    }

    private struct SunUniforms {
        var sight: Sight
        var uBright: Float
        var uBeta: SIMD3<Float>
    }

    private struct SpikeUniforms {
        var sight: Sight
        var uFalloff: SIMD2<Float>
        var uColour: SIMD4<Float>
    }

    private struct SpikeShape {
        var uSpike: SIMD4<Float>
        var uSize: SIMD2<Float>
    }

    private struct SourceUniforms {
        var uFoot: SIMD2<Float>
        var uThreshold: Float
        var uExposure: Float
        var uWhiteSqrRcp: Float
        var uAuto: Float
    }

    private struct LumUniforms {
        var uFoot: SIMD2<Float>
        var uWhite: Float
    }

    private struct ExposureUniforms {
        var uTop: Float
        var uKey: Float
        var uRange: SIMD2<Float>
        var uSpeed: Float
    }

    private struct OutUniforms {
        var uExposure: Float
        var uWhiteSqrRcp: Float
        var uAuto: Float
        var uGamma: Float
        var uDither: Float
        var uSeed: Float
    }

    /// The Earth's textures, made on the loading task and then only used on the main actor.
    private final class Earth: @unchecked Sendable {
        let presets: [XmbGaiaFirmware.Preset]
        let ground: MTLTexture
        let clouds: MTLTexture
        let specular: MTLTexture
        let disk: MTLTexture
        let stars: MTLTexture
        let opticalDepth: MTLTexture

        init(presets: [XmbGaiaFirmware.Preset], ground: MTLTexture, clouds: MTLTexture, specular: MTLTexture, disk: MTLTexture,
             stars: MTLTexture, opticalDepth: MTLTexture) {
            self.presets = presets
            self.ground = ground
            self.clouds = clouds
            self.specular = specular
            self.disk = disk
            self.stars = stars
            self.opticalDepth = opticalDepth
        }
    }

    private let device: MTLDevice
    private let queue: MTLCommandQueue
    private let space: MTLRenderPipelineState
    private let sun: MTLRenderPipelineState
    private let spike: MTLRenderPipelineState
    private let source: MTLRenderPipelineState
    private let copy: MTLRenderPipelineState
    private let add: MTLRenderPipelineState
    private let blur: MTLRenderPipelineState
    private let lum: MTLRenderPipelineState
    private let exposure: MTLRenderPipelineState
    private let out: MTLRenderPipelineState

    /// The faces are large (about 30 MB): the last Earth is kept for a quick return while memory allows, as Android does.
    private static let kept = NSCache<NSString, Earth>()

    /// A game counts the textures against its own memory, so they go when the XMB gives up the sound.
    static func forgetEarth() {
        forgets += 1
        kept.removeAllObjects()
    }
    /// Raised by each forget, so a load still running then is not cached.
    private static var forgets = 0

    private var earth: Earth?
    private var tour: XmbGaiaTour?
    private var seenPreset = 0
    private var last: CFTimeInterval = -1

    private var scene: MTLTexture?
    private var levels: [MTLTexture] = []
    private var blurred: [MTLTexture] = []
    private var glare: MTLTexture?
    // SimpleAutoExp: the scene's average light at 64x32 down to one texel, and the exposure kept in a texel of its own,
    // a pair to read the last one while writing the next.
    private var lumTexture: MTLTexture?
    private var exposureTargets: [MTLTexture] = []
    private var exposurePing = 0
    private var exposureReset = true

    init?(device: MTLDevice, format: MTLPixelFormat) {
        guard let queue = device.makeCommandQueue(), let library = device.makeDefaultLibrary() else { return nil }
        func pipeline(_ vertex: String, _ fragment: String, _ format: MTLPixelFormat, added: Bool = false) -> MTLRenderPipelineState? {
            guard let v = library.makeFunction(name: vertex), let f = library.makeFunction(name: fragment) else { return nil }
            let descriptor = MTLRenderPipelineDescriptor()
            descriptor.vertexFunction = v
            descriptor.fragmentFunction = f
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
        let quad = "xmbGaia_quadVertex"
        guard let space = pipeline(quad, "xmbGaia_spaceFragment", .rgba16Float),
              let sun = pipeline("xmbGaia_sunVertex", "xmbGaia_sunFragment", .rgba16Float, added: true),
              let spike = pipeline("xmbGaia_spikeVertex", "xmbGaia_spikeFragment", .rgba16Float, added: true),
              let source = pipeline(quad, "xmbGaia_sourceFragment", .rgba16Float),
              let copy = pipeline(quad, "xmbGaia_copyFragment", .rgba16Float),
              let add = pipeline(quad, "xmbGaia_copyFragment", .rgba16Float, added: true),
              let blur = pipeline(quad, "xmbGaia_blurFragment", .rgba16Float),
              let lum = pipeline(quad, "xmbGaia_lumFragment", .rgba16Float),
              let exposure = pipeline(quad, "xmbGaia_exposureFragment", .rgba16Float),
              let out = pipeline(quad, "xmbGaia_outFragment", format) else { return nil }
        self.device = device
        self.queue = queue
        self.space = space
        self.sun = sun
        self.spike = spike
        self.source = source
        self.copy = copy
        self.add = add
        self.blur = blur
        self.lum = lum
        self.exposure = exposure
        self.out = out
        super.init()

        Task { [weak self] in
            var earth = Self.kept.object(forKey: "earth").flatMap { $0.ground.device === device ? $0 : nil }
            if earth == nil {
                let turn = Self.forgets
                earth = await Task.detached(priority: .userInitiated) { Self.load(device) }.value
                if let earth, turn == Self.forgets { Self.kept.setObject(earth, forKey: "earth") }
            }
            guard let self, let earth else { return }
            self.earth = earth
            let seed = Int32(truncatingIfNeeded: DispatchTime.now().uptimeNanoseconds & 0xffff)
            self.tour = XmbGaiaTour(presets: earth.presets, index: XmbGaiaPresets.preset, seed: seed)
        }
    }

    /// The firmware's Earth, the stars and the air's table; nil when earth.qrc is missing or does not read whole.
    private nonisolated static func load(_ device: MTLDevice) -> Earth? {
        guard let files = XmbQrc.open(XmbFirmware.devFlash.appendingPathComponent(XmbGaiaFirmware.file)),
              let pack = XmbGaiaFirmware.pack(files: files),
              let ground = cube(device, pack.ground, .rgba8Unorm, mipmapped: true),
              let clouds = cube(device, pack.clouds, .r8Unorm, mipmapped: true),
              let specular = cube(device, pack.specular, .r8Unorm, mipmapped: true),
              let stars = cube(device, XmbGaiaFirmware.Cube(size: 512, faces: XmbGaiaFirmware.stars(), bytes: 4), .rgba8Unorm, mipmapped: false)
        else { return nil }
        let picture = pack.disk ?? XmbGaiaFirmware.fallbackDisk()
        let diskDescriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba8Unorm, width: picture.width, height: picture.height,
                                                                      mipmapped: true)
        let w = XmbGaiaFirmware.opticalDepthWidth, h = XmbGaiaFirmware.opticalDepthHeight
        let depthDescriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .r16Float, width: w, height: h, mipmapped: false)
        guard let disk = device.makeTexture(descriptor: diskDescriptor),
              let opticalDepth = device.makeTexture(descriptor: depthDescriptor) else { return nil }
        disk.replace(region: MTLRegionMake2D(0, 0, picture.width, picture.height), mipmapLevel: 0, withBytes: picture.rgba,
                     bytesPerRow: picture.width * 4)
        let halves = XmbGaiaFirmware.opticalDepth().map { Float16($0) }
        opticalDepth.replace(region: MTLRegionMake2D(0, 0, w, h), mipmapLevel: 0, withBytes: halves, bytesPerRow: w * 2)
        guard let queue = device.makeCommandQueue(), let commands = queue.makeCommandBuffer(),
              let blit = commands.makeBlitCommandEncoder() else { return nil }
        for texture in [ground, clouds, specular, disk] { blit.generateMipmaps(for: texture) }
        blit.endEncoding()
        commands.commit()
        commands.waitUntilCompleted()
        return Earth(presets: pack.presets, ground: ground, clouds: clouds, specular: specular, disk: disk, stars: stars,
                     opticalDepth: opticalDepth)
    }

    private nonisolated static func cube(_ device: MTLDevice, _ cube: XmbGaiaFirmware.Cube, _ format: MTLPixelFormat,
                                         mipmapped: Bool) -> MTLTexture? {
        let descriptor = MTLTextureDescriptor.textureCubeDescriptor(pixelFormat: format, size: cube.size, mipmapped: mipmapped)
        guard let texture = device.makeTexture(descriptor: descriptor) else { return nil }
        for (f, face) in cube.faces.enumerated() {
            texture.replace(region: MTLRegionMake2D(0, 0, cube.size, cube.size), mipmapLevel: 0, slice: f, withBytes: face,
                            bytesPerRow: cube.size * cube.bytes, bytesPerImage: cube.size * cube.size * cube.bytes)
        }
        return texture
    }

    /// Paused: the next frame starts the clock over.
    func rest() { last = -1 }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

    func draw(in view: MTKView) {
        let now = CACurrentMediaTime()
        let dt = last < 0 ? 0 : min(max(Float(now - last), 0), 0.1)
        last = now
        if let tour {
            let flick = XmbGaiaPresets.flicks
            XmbGaiaPresets.flicks = false
            tour.tick(dt, flick: flick)
            XmbGaiaPresets.preset = tour.index
            if tour.started != seenPreset {
                seenPreset = tour.started
                exposureReset = true
            }
        }
        guard let pass = view.currentRenderPassDescriptor, let drawable = view.currentDrawable,
              let commands = queue.makeCommandBuffer() else { return }
        encode(commands, pass: pass, width: Int(view.drawableSize.width), height: Int(view.drawableSize.height))
        commands.present(drawable)
        commands.commit()
    }

    private func encode(_ commands: MTLCommandBuffer, pass: MTLRenderPassDescriptor, width w: Int, height h: Int) {
        guard let earth, let tour, let p = tour.preset, w > 0, h > 0, let sc = target(scene, w, h) else {
            commands.makeRenderCommandEncoder(descriptor: pass)?.endEncoding()
            return
        }
        scene = sc
        let aspect = Float(w) / Float(max(h, 1))
        // Held upright, as much across as the console's 16:9 shows, and more up and down.
        let camera = XmbGaiaTour.camera(p.path, tour.pathTime)
        let fovy = xmbFovy(camera.fovy, aspect: aspect)
        let eye = camera.eye
        let viewProj = XmbVisualizerMatrix.perspective(fovy: fovy, aspect: aspect, near: 0.01, far: 1000)
            * XmbVisualizerMatrix.lookAt(eye, camera.center, camera.up)
        let invViewProj = viewProj.inverse

        // The sun: SUN ANG Y from the north pole, SUN ANG Z round from +Z toward +X.
        let ay = Double(p.f("SUN.mnu", "SUN ANG Y", 90)) * Double.pi / 180
        let az = Double(p.f("SUN.mnu", "SUN ANG Z", 0)) * Double.pi / 180
        let sunDir = SIMD3<Float>(Float(sin(ay) * sin(az)), Float(cos(ay)), Float(sin(ay) * cos(az)))

        // Gaia's glare is finer than the XMB's: 256 high and twice that across on a wide screen, in eight sizes (its
        // HDR takes TEX SIZE 9 and 8 levels).
        let gh = Self.glareBase
        let gw = aspect > 1.5 ? Self.glareBase * 2 : Self.glareBase
        for n in 0 ..< XmbGaiaTour.glareLevels {
            guard let l = target(levels.indices.contains(n) ? levels[n] : nil, max(1, gw >> n), max(1, gh >> n)),
                  let b = target(blurred.indices.contains(n) ? blurred[n] : nil, max(1, gw >> n), max(1, gh >> n)) else { return }
            if levels.indices.contains(n) { levels[n] = l } else { levels.append(l) }
            if blurred.indices.contains(n) { blurred[n] = b } else { blurred.append(b) }
        }
        guard let sum = target(glare, gw, gh) else { return }
        glare = sum

        // Space: the stars, the Earth and its air, in light (HDR).
        let wl = SIMD3<Float>(p.f("ATM.mnu", "R WAVELENGTH", 0.65), p.f("ATM.mnu", "G WAVELENGTH", 0.57), p.f("ATM.mnu", "B WAVELENGTH", 0.475))
        let invWave4 = SIMD3<Float>(1 / pow(wl.x, 4), 1 / pow(wl.y, 4), 1 / pow(wl.z, 4))
        var spaceUniforms = SpaceUniforms(
            uInvViewProj: invViewProj, uEye: eye, uSun: sunDir,
            uCloud: SIMD4(p.f("CLOUDS.mnu", "PARALLAX", 0.15), p.f("CLOUDS.mnu", "PARALLAX BIAS", 0),
                          p.f("CLOUDS.mnu", "BRIGHTNESS", 0.3) * 3, p.f("CLOUDS.mnu", "BLEND", 0.35) * 2.5),
            uShadow: SIMD2(p.f("CLOUDS.mnu", "SHADOW BIAS", 0.8), p.f("CLOUDS.mnu", "SHADOW STREN", 1)),
            uSea: SIMD4(p.f("EARTH.mnu", "SEA ALBEDO", 0.06), p.f("EARTH.mnu", "SEA POW", 10), p.f("EARTH.mnu", "LAND ALBEDO", 0.036),
                        p.f("EARTH.mnu", "LAND POW", 20)),
            uInvWave4: invWave4,
            uSunColour: SIMD3(p.f("ATM.mnu", "SUN R", 1), p.f("ATM.mnu", "SUN G", 1), p.f("ATM.mnu", "SUN B", 1)),
            uSunIntensity: p.f("ATM.mnu", "SUN INTENSITY", 16),
            uAttn: SIMD2(p.f("ATM.mnu", "ATTN EARTH", Self.attnEarth) / Self.attnEarth, p.f("ATM.mnu", "ATTN ATM.", Self.attnAtm) / Self.attnAtm),
            uStars: SIMD3(p.f("EARTH.mnu", "STARS FACTOR", 0.0002), p.f("EARTH.mnu", "STARS POWSCALE", 2.15), p.f("EARTH.mnu", "STARS WHITENESS", 0.22)),
            uFade: tour.fade)
        let scenePass = MTLRenderPassDescriptor()
        scenePass.colorAttachments[0].texture = sc
        scenePass.colorAttachments[0].loadAction = .dontCare
        scenePass.colorAttachments[0].storeAction = .store
        guard let encoder = commands.makeRenderCommandEncoder(descriptor: scenePass) else { return }
        encoder.setRenderPipelineState(space)
        encoder.setFragmentBytes(&spaceUniforms, length: MemoryLayout<SpaceUniforms>.stride, index: 0)
        encoder.setFragmentTexture(earth.ground, index: 0)
        encoder.setFragmentTexture(earth.clouds, index: 1)
        encoder.setFragmentTexture(earth.specular, index: 2)
        encoder.setFragmentTexture(earth.stars, index: 3)
        encoder.setFragmentTexture(earth.opticalDepth, index: 4)
        encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)

        // The sun's disc and spikes, added where the sun is. Each pixel of them is hidden where the Earth is in the
        // way, and the disc reddened through the air near the Earth's edge.
        let clip = viewProj * SIMD4(eye + sunDir * 100, 1)
        if clip.w > 0 {
            let sx = clip.x / clip.w, sy = clip.y / clip.w
            if abs(sx) < 3 && abs(sy) < 3 {
                let sight = Sight(uInvViewProj: invViewProj, uEye: eye, uViewport: SIMD2(Float(w), Float(h)))
                let bright = p.f("SUN.mnu", "BRIGHTNESS", 3.4) * tour.fade
                let beta = SIMD3<Float>((invWave4 * Self.kr + Self.km) * 4 * Float(Double.pi))
                // The console's disc: SOLID ANGLE times the sun's own 0.265 degrees, the quad twice that from its centre.
                let radius = Float(0.265 * 2 * Double(p.f("SUN.mnu", "SOLID ANGLE", 1)) * Double.pi / 180)
                let half = tan(radius) / tan(fovy / 2 * Float(Double.pi) / 180)
                var rect = SIMD4<Float>(sx, sy, half / aspect, half)
                var sunUniforms = SunUniforms(sight: sight, uBright: bright, uBeta: beta)
                encoder.setRenderPipelineState(sun)
                encoder.setVertexBytes(&rect, length: MemoryLayout<SIMD4<Float>>.stride, index: 0)
                encoder.setFragmentBytes(&sunUniforms, length: MemoryLayout<SunUniforms>.stride, index: 0)
                encoder.setFragmentTexture(earth.disk, index: 0)
                encoder.setFragmentTexture(earth.opticalDepth, index: 1)
                encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
                // The spikes go as the disc goes behind the Earth.
                let b = simd_dot(eye, sunDir)
                let e2 = simd_dot(eye, eye)
                let shown: Float
                if b >= 0 {
                    shown = 1
                } else {
                    let d = max(0, e2 - b * b).squareRoot()
                    let edge = -b * tan(radius / 2)
                    shown = min(max((d - 1 + edge) / (2 * edge), 0), 1)
                }
                let spikes = shown * bright * 0.12
                var spikeUniforms = SpikeUniforms(
                    sight: sight, uFalloff: SIMD2(p.f("SUN.mnu", "FALLOFF X", 9.4), p.f("SUN.mnu", "FALLOFF Y", 10.1)),
                    uColour: SIMD4(p.f("SUN.mnu", "SPIKE R", 1) * spikes, p.f("SUN.mnu", "SPIKE G", 1) * spikes, p.f("SUN.mnu", "SPIKE B", 1) * spikes, 1))
                let scaleX = p.f("SUN.mnu", "SCALE X", 3) * 0.12
                let scaleY = p.f("SUN.mnu", "SCALE Y", 6) * 0.006
                encoder.setRenderPipelineState(spike)
                encoder.setFragmentBytes(&spikeUniforms, length: MemoryLayout<SpikeUniforms>.stride, index: 0)
                for k in 0 ..< XmbGaiaTour.maxSpikes where tour.spikeLengths[k] > 0 {
                    var shape = SpikeShape(uSpike: SIMD4(sx, sy, tour.spikeAngles[k], aspect), uSize: SIMD2(scaleX * tour.spikeLengths[k], scaleY))
                    encoder.setVertexBytes(&shape, length: MemoryLayout<SpikeShape>.stride, index: 0)
                    encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
                }
            }
        }
        encoder.endEncoding()

        // The exposure (SimpleAutoExp): KEY VALUE over the scene's average light, held between MIN and MAX EXPOSURE and
        // eased there at FILTER SPEED a frame; the preset's EXPOSURE to start.
        let minExp = p.f("SimpleAutoExp.mnu", "MIN EXPOSURE", 0.4)
        let maxExp = max(minExp, p.f("SimpleAutoExp.mnu", "MAX EXPOSURE", 1.16))
        let startExp = min(max(p.f("HDR.mnu", "EXPOSURE", 0.8), minExp), maxExp)
        let white = p.f("HDR.mnu", "WHITE LEVEL", 1.41)
        let expTexture = autoExposure(commands, sc, p, startExp, minExp, maxExp, white)
        let auto: Float = expTexture != nil ? 1 : 0

        // The console's glare: what is past GLARE THRESH in the toned picture, at the largest size and seven smaller
        // ones, each blurred by a gaussian per colour, added back up by GLARE SUM POW.
        var sourceUniforms = SourceUniforms(uFoot: SIMD2(1 / Float(gw), 1 / Float(gh)), uThreshold: p.f("HDR.mnu", "GLARE THRESH", 0.55),
                                            uExposure: startExp, uWhiteSqrRcp: 1 / (white * white), uAuto: auto)
        quad(commands, into: levels[0], source) { encoder in
            encoder.setFragmentBytes(&sourceUniforms, length: MemoryLayout<SourceUniforms>.stride, index: 0)
            encoder.setFragmentTexture(sc, index: 0)
            encoder.setFragmentTexture(expTexture ?? sc, index: 1)
        }
        var one: Float = 1
        for n in 1 ..< XmbGaiaTour.glareLevels {
            quad(commands, into: levels[n], copy) { encoder in
                encoder.setFragmentTexture(self.levels[n - 1], index: 0)
                encoder.setFragmentBytes(&one, length: MemoryLayout<Float>.stride, index: 0)
            }
        }
        var weights = tour.blurWeights
        for n in 0 ..< XmbGaiaTour.glareLevels {
            let l = levels[n], b = blurred[n]
            var across = SIMD2<Float>(1 / Float(l.width), 0)
            quad(commands, into: b, blur) { encoder in
                encoder.setFragmentTexture(l, index: 0)
                encoder.setFragmentBytes(&across, length: MemoryLayout<SIMD2<Float>>.stride, index: 0)
                encoder.setFragmentBytes(&weights, length: MemoryLayout<SIMD3<Float>>.stride * weights.count, index: 1)
            }
            var down = SIMD2<Float>(0, 1 / Float(l.height))
            quad(commands, into: l, blur) { encoder in
                encoder.setFragmentTexture(b, index: 0)
                encoder.setFragmentBytes(&down, length: MemoryLayout<SIMD2<Float>>.stride, index: 0)
                encoder.setFragmentBytes(&weights, length: MemoryLayout<SIMD3<Float>>.stride * weights.count, index: 1)
            }
        }
        let accumulate = MTLRenderPassDescriptor()
        accumulate.colorAttachments[0].texture = sum
        accumulate.colorAttachments[0].loadAction = .clear
        accumulate.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 0)
        accumulate.colorAttachments[0].storeAction = .store
        if let encoder = commands.makeRenderCommandEncoder(descriptor: accumulate) {
            encoder.setRenderPipelineState(add)
            for n in stride(from: XmbGaiaTour.glareLevels - 1, through: 0, by: -1) {
                var weight = tour.levelWeights[n]
                encoder.setFragmentTexture(levels[n], index: 0)
                encoder.setFragmentBytes(&weight, length: MemoryLayout<Float>.stride, index: 0)
                encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
            }
            encoder.endEncoding()
        }

        // Toned, with the glare, the preset's GAMMA, and the console's dither.
        var outUniforms = OutUniforms(uExposure: startExp, uWhiteSqrRcp: 1 / (white * white), uAuto: auto,
                                      uGamma: min(max(p.f("INTERACT.mnu", "GAMMA", 1), 0.5), 3), uDither: p.f("HDR.mnu", "DITHER", 0.004),
                                      uSeed: tour.rand01())
        guard let final = commands.makeRenderCommandEncoder(descriptor: pass) else { return }
        final.setRenderPipelineState(out)
        final.setFragmentBytes(&outUniforms, length: MemoryLayout<OutUniforms>.stride, index: 0)
        final.setFragmentTexture(sc, index: 0)
        final.setFragmentTexture(sum, index: 1)
        final.setFragmentTexture(expTexture ?? sc, index: 2)
        final.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        final.endEncoding()
    }

    /// The scene's average light into one texel, then the exposure eased toward KEY VALUE over it; the texture holding
    /// it, or nil when its targets cannot be made (the preset's EXPOSURE then). The light is read as the console's
    /// toned picture holds it, no brighter than the white level.
    private func autoExposure(_ commands: MTLCommandBuffer, _ sc: MTLTexture, _ p: XmbGaiaFirmware.Preset, _ start: Float,
                              _ minExp: Float, _ maxExp: Float, _ white: Float) -> MTLTexture? {
        if lumTexture == nil {
            let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba16Float, width: Self.lumWidth,
                                                                      height: Self.lumHeight, mipmapped: true)
            descriptor.mipmapLevelCount = Self.lumLevels
            descriptor.usage = [.renderTarget, .shaderRead]
            descriptor.storageMode = .private
            lumTexture = device.makeTexture(descriptor: descriptor)
        }
        for k in 0 ..< 2 {
            guard let t = target(exposureTargets.indices.contains(k) ? exposureTargets[k] : nil, 1, 1) else { return nil }
            if exposureTargets.indices.contains(k) { exposureTargets[k] = t } else { exposureTargets.append(t) }
        }
        guard let lumTexture else { return nil }
        if exposureReset {
            for t in exposureTargets {
                let clear = MTLRenderPassDescriptor()
                clear.colorAttachments[0].texture = t
                clear.colorAttachments[0].loadAction = .clear
                clear.colorAttachments[0].clearColor = MTLClearColor(red: Double(start), green: 0, blue: 0, alpha: 0)
                clear.colorAttachments[0].storeAction = .store
                commands.makeRenderCommandEncoder(descriptor: clear)?.endEncoding()
            }
            exposureReset = false
        }
        let prev = exposureTargets[exposurePing]
        let next = exposureTargets[1 - exposurePing]
        var lumUniforms = LumUniforms(uFoot: SIMD2(1 / Float(Self.lumWidth), 1 / Float(Self.lumHeight)), uWhite: white)
        quad(commands, into: lumTexture, lum) { encoder in
            encoder.setFragmentBytes(&lumUniforms, length: MemoryLayout<LumUniforms>.stride, index: 0)
            encoder.setFragmentTexture(sc, index: 0)
            encoder.setFragmentTexture(prev, index: 1)
        }
        if let blit = commands.makeBlitCommandEncoder() {
            blit.generateMipmaps(for: lumTexture)
            blit.endEncoding()
        }
        var exposureUniforms = ExposureUniforms(uTop: Float(Self.lumLevels - 1), uKey: p.f("SimpleAutoExp.mnu", "KEY VALUE", 1),
                                                uRange: SIMD2(minExp, maxExp),
                                                uSpeed: min(max(p.f("SimpleAutoExp.mnu", "FILTER SPEED", 0.01), 0.001), 1))
        quad(commands, into: next, exposure) { encoder in
            encoder.setFragmentBytes(&exposureUniforms, length: MemoryLayout<ExposureUniforms>.stride, index: 0)
            encoder.setFragmentTexture(lumTexture, index: 0)
            encoder.setFragmentTexture(prev, index: 1)
        }
        exposurePing = 1 - exposurePing
        return next
    }

    private func quad(_ commands: MTLCommandBuffer, into texture: MTLTexture, _ pipeline: MTLRenderPipelineState,
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
}
