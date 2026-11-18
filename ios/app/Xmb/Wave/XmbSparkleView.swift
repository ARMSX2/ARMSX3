// SPDX-License-Identifier: GPL-2.0-only
// XMB mode's sparkles in a clear MTKView laid over the wave, ported from the particle parts of Android's XmbGlView.kt and XmbParticles.kt.

import MetalKit
import QuartzCore
import SwiftUI

struct XmbSparkleView: UIViewRepresentable {
    /// How much of the sparkles shows: the boot's wave level times its reveal, since they come in with the icons. 1 otherwise.
    var level: Float = 1
    var paused = false
    /// The wave's 19x19 control points in clip space (x, y, z, w) as its last frame left them, which the flakes are
    /// born on. Nil while there is no wave.
    var wave: () -> [Float]?

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> MTKView {
        let view = MTKView(frame: .zero, device: MTLCreateSystemDefaultDevice())
        // Not an sRGB format: the light is added as it is, as Android's is.
        view.colorPixelFormat = .bgra8Unorm
        view.clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 0)
        view.framebufferOnly = true
        // Android draws the sparkles in the wave's frame, about 30 a second, and steps them 60 times a second.
        view.preferredFramesPerSecond = 30
        view.enableSetNeedsDisplay = false
        view.isOpaque = false
        view.backgroundColor = .clear
        view.isUserInteractionEnabled = false
        context.coordinator.start(view)
        updateUIView(view, context: context)
        return view
    }

    func updateUIView(_ view: MTKView, context: Context) {
        context.coordinator.level = level
        context.coordinator.wave = wave
        view.isPaused = paused
        // No sensors while nothing is drawn; the next frame switches them back on.
        if paused { context.coordinator.motion.want(false) }
    }

    static func dismantleUIView(_ view: MTKView, coordinator: Coordinator) {
        view.isPaused = true
        view.delegate = nil
        coordinator.stop()
    }

    @MainActor final class Coordinator: NSObject, MTKViewDelegate {
        var level: Float = 1
        var wave: () -> [Float]? = { nil }
        let motion = XmbSparkleMotion()

        // Matches XmbSparkleUniforms in XmbSparkles.metal.
        private struct Uniforms {
            var scale: SIMD2<Float>
            var level: Float
            var tint: Float
            var glare: Float
        }

        private struct Firmware: Sendable {
            var settings = XmbSparkleField.Settings()
            var swirl: [UInt8]?
        }

        private var queue: MTLCommandQueue?
        private var pipeline: MTLRenderPipelineState?
        // The iridescent swirl for the flakes' colours: white, and left out of the light, until the firmware's is read.
        private var swirl: MTLTexture?
        private var tint: Float = 0
        private var field = XmbSparkleField()
        private var wind = XmbSparkleWind()
        private var instances = [Float](repeating: 0, count: XmbSparkleField.capacity * XmbSparkleField.floatsPerFlake)
        private var last: CFTimeInterval = 0
        private var loader: Task<Void, Never>?

        func start(_ view: MTKView) {
            guard let device = view.device, let library = device.makeDefaultLibrary() else { return }
            let descriptor = MTLRenderPipelineDescriptor()
            descriptor.vertexFunction = library.makeFunction(name: "xmbSparkle_vertex")
            descriptor.fragmentFunction = library.makeFunction(name: "xmbSparkle_fragment")
            let color = descriptor.colorAttachments[0]!
            color.pixelFormat = view.colorPixelFormat
            // Added on, as the console blends them (one, one). Alpha stays 0, so the layer adds itself to what is under it.
            color.isBlendingEnabled = true
            color.sourceRGBBlendFactor = .one
            color.destinationRGBBlendFactor = .one
            color.sourceAlphaBlendFactor = .zero
            color.destinationAlphaBlendFactor = .one
            let white = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba8Unorm, width: 1, height: 1, mipmapped: false)
            guard let pipeline = try? device.makeRenderPipelineState(descriptor: descriptor),
                  let queue = device.makeCommandQueue(), let texture = device.makeTexture(descriptor: white) else { return }
            texture.replace(region: MTLRegionMake2D(0, 0, 1, 1), mipmapLevel: 0, withBytes: [0xff, 0xff, 0xff, 0xff] as [UInt8],
                            bytesPerRow: 4)
            self.pipeline = pipeline
            self.queue = queue
            swirl = texture
            view.delegate = self
            NotificationCenter.default.addObserver(self, selector: #selector(didEnterBackground),
                                                   name: UIApplication.didEnterBackgroundNotification, object: nil)

            // shortcut: read once as the view is made, so firmware installed while the XMB is up shows on its next visit.
            loader = Task { [weak self] in
                let firmware = await Task.detached(priority: .utility) { Self.readFirmware() }.value
                guard let self, !Task.isCancelled else { return }
                self.field.settings = firmware.settings
                guard let bytes = firmware.swirl else { return }
                let side = XmbSparkleSwirl.side
                let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba8Unorm, width: side, height: side,
                                                                          mipmapped: false)
                guard let texture = device.makeTexture(descriptor: descriptor) else { return }
                texture.replace(region: MTLRegionMake2D(0, 0, side, side), mipmapLevel: 0, withBytes: bytes, bytesPerRow: side * 4)
                self.swirl = texture
                self.tint = 1
            }
        }

        /// PARTICLES.mnu and the swirl from the player's firmware; the firmware's own numbers and no swirl without it.
        private nonisolated static func readFirmware() -> Firmware {
            var firmware = Firmware()
            let container = XmbFirmware.devFlash.appendingPathComponent("vsh/resource/qgl/lines.qrc")
            guard let files = XmbQrc.open(container) else { return firmware }
            if let mnu = files["PARTICLES.mnu"] { firmware.settings = XmbSparkleField.Settings(mnu: mnu) }
            firmware.swirl = files["particles/proc_iridescent.tga"].flatMap(XmbSparkleSwirl.rgba)
            return firmware
        }

        func stop() {
            loader?.cancel()
            loader = nil
            motion.want(false)
            NotificationCenter.default.removeObserver(self)
        }

        @objc private func didEnterBackground() {
            motion.want(false)
        }

        func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

        func draw(in view: MTKView) {
            guard let queue, let pipeline, let device = view.device else { return }
            motion.want(true)
            let now = CACurrentMediaTime()
            let seconds = last == 0 ? 0 : Float(min(max(now - last, 0), 0.1))
            last = now
            motion.read(orientation: view.window?.windowScene?.effectiveGeometry.interfaceOrientation ?? .portrait, time: now)
            wind.step(seconds, push: motion.push, shake: motion.shake)

            // The wave's projection, which the particles' camera shares.
            let scale = XmbSparkleField.scale(width: Float(view.drawableSize.width), height: Float(view.drawableSize.height))
            if let points = wave() {
                field.setWave(points, scaleX: scale.x, scaleY: scale.y)
                // The wind, from clip-space units a second into world ones at the field's middle depth.
                field.step(seconds, windX: wind.velocity * XmbSparkleWind.fieldDepth / scale.x, windY: 0, scatter: wind.scatter)
            }

            guard let pass = view.currentRenderPassDescriptor, let drawable = view.currentDrawable,
                  let commands = queue.makeCommandBuffer(),
                  let encoder = commands.makeRenderCommandEncoder(descriptor: pass) else { return }
            let count = level > 0 ? instances.withUnsafeMutableBufferPointer { field.writeInstances(to: $0) } : 0
            // A buffer of its own each frame, so the frame before can still be reading the last one.
            if count > 0, let buffer = device.makeBuffer(bytes: instances, length: count * XmbSparkleField.floatsPerFlake
                                                             * MemoryLayout<Float>.stride) {
                encoder.setRenderPipelineState(pipeline)
                encoder.setVertexBuffer(buffer, offset: 0, index: 0)
                encoder.setFragmentTexture(swirl, index: 0)
                // The flakes, then their glare (particles_quads, then particles_second).
                for glare in [0, 1] as [Float] {
                    var uniforms = Uniforms(scale: SIMD2(scale.x, scale.y), level: level, tint: tint, glare: glare)
                    encoder.setVertexBytes(&uniforms, length: MemoryLayout<Uniforms>.stride, index: 1)
                    encoder.setFragmentBytes(&uniforms, length: MemoryLayout<Uniforms>.stride, index: 1)
                    encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4, instanceCount: count)
                }
            }
            encoder.endEncoding()
            commands.present(drawable)
            commands.commit()
        }
    }
}
