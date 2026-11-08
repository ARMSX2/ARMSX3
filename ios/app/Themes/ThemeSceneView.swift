// SPDX-License-Identifier: GPL-2.0-only
// Plays a live theme in an MTKView. The crash-loop marker is written before loading, because a GPU fault can kill the process.

import MetalKit
import QuartzCore
import SwiftUI

struct ThemeSceneView: UIViewRepresentable {
    let entry: ThemeEntry
    let fitMode: BackgroundFitMode
    let forcedPath: ThemeTexturePath?
    let onFirstFrame: () -> Void
    let onFailure: () -> Void

    /// ThemeSceneView.kt's: a script that fails as it starts gets this many goes with other random numbers.
    nonisolated static let START_TRIES = 3

    /// For the smoke scenes' witness. Nil until one is made.
    @MainActor static private(set) var texturePath: ThemeTexturePath?
    /// The last to go removes it.
    @MainActor private static var armedViews = 0

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> MTKView {
        let view = MTKView(frame: .zero, device: MTLCreateSystemDefaultDevice())
        // The renderer's pipelines are built for exactly these two formats.
        view.colorPixelFormat = .bgra8Unorm
        view.depthStencilPixelFormat = .depth32Float
        view.clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
        view.framebufferOnly = true
        view.preferredFramesPerSecond = 30
        view.enableSetNeedsDisplay = false
        view.isPaused = false
        view.isOpaque = true
        view.backgroundColor = .black
        context.coordinator.start(view)
        return view
    }

    func updateUIView(_ view: MTKView, context: Context) {
        context.coordinator.parent = self
    }

    static func dismantleUIView(_ view: MTKView, coordinator: Coordinator) {
        view.delegate = nil
        view.isPaused = true
        view.enableSetNeedsDisplay = true
        view.releaseDrawables()
        coordinator.stop()
        view.device = nil
    }

    /// It is never touched by both the loading task and the main actor.
    private final class Loaded: @unchecked Sendable {
        let scene: ThemeScene
        let renderer: ThemeSceneRenderer
        init(scene: ThemeScene, renderer: ThemeSceneRenderer) {
            self.scene = scene
            self.renderer = renderer
        }
    }

    @MainActor final class Coordinator: NSObject, MTKViewDelegate {
        var parent: ThemeSceneView
        private weak var view: MTKView?
        private var scene: ThemeScene?
        private var renderer: ThemeSceneRenderer?
        private var loader: Task<Void, Never>?
        private var last: CFTimeInterval = 0
        private var shown = false
        private var done = false
        private var armed = false
        private var counted = false

        init(_ parent: ThemeSceneView) {
            self.parent = parent
        }

        func start(_ view: MTKView) {
            self.view = view
            guard let device = view.device, let name = parent.entry.meta.scene,
                  ThemeLibrary.isPlainName(name) else { fail(); return }
            NotificationCenter.default.addObserver(self, selector: #selector(willDeactivate),
                                                   name: UIScene.willDeactivateNotification, object: nil)
            NotificationCenter.default.addObserver(self, selector: #selector(didActivate),
                                                   name: UIScene.didActivateNotification, object: nil)
            ThemeLibraryModel.shared.library.armLive(parent.entry.folderName)
            ThemeSceneView.armedViews += 1
            armed = true

            let url = parent.entry.folder.appendingPathComponent(name, isDirectory: false)
            let forced = parent.forcedPath
            loader = Task { [weak self] in
                let loaded = await Task.detached(priority: .userInitiated) { () -> Loaded? in
                    Self.load(url, device: device, forcedPath: forced)
                }.value
                guard let self, !self.done else { return }
                guard let loaded else { self.fail(); return }
                self.adopt(loaded)
            }
        }

        private nonisolated static func load(_ url: URL, device: MTLDevice, forcedPath: ThemeTexturePath?) -> Loaded? {
            guard let data = try? Data(contentsOf: url, options: .alwaysMapped) else { return nil }
            let raf = DataByteSource(data)
            guard let read = RafScene.read(raf) else { return nil }
            func started() -> ThemeScene {
                let play = ThemeScene(scene: read)
                play.start()
                return play
            }
            // A script that fails as it starts gets another go: one known theme fails one start in eight.
            var play = started()
            var tries = 1
            while play.scriptError != nil && tries < ThemeSceneView.START_TRIES {
                play = started()
                tries += 1
            }
            guard !play.exhausted,
                  let renderer = ThemeSceneRenderer(device: device, scene: play, raf: raf, forcedPath: forcedPath)
            else { return nil }
            return Loaded(scene: play, renderer: renderer)
        }

        private func adopt(_ loaded: Loaded) {
            scene = loaded.scene
            renderer = loaded.renderer
            ThemeSceneRenderer.liveCount += 1
            counted = true
            ThemeSceneView.texturePath = loaded.renderer.texturePath
            last = CACurrentMediaTime()
            view?.delegate = self
        }

        func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

        func draw(in view: MTKView) {
            guard !done, let scene, let renderer else { return }
            let now = CACurrentMediaTime()
            scene.advance(now - last) // the scene clamps a long gap
            last = now
            if scene.exhausted { fail(); return }
            guard let pass = view.currentRenderPassDescriptor, let drawable = view.currentDrawable,
                  let commands = renderer.queue.makeCommandBuffer() else { return }
            renderer.encode(into: pass, commandBuffer: commands, drawableSize: view.drawableSize,
                            fitMode: parent.fitMode)
            if !shown {
                shown = true
                // Swift: MTLDrawable here has no presented handler, so the first frame counts when its commands complete.
                commands.addCompletedHandler { [weak self] done in
                    let ok = done.status == .completed
                    DispatchQueue.main.async { self?.presented(ok) }
                }
            }
            commands.present(drawable)
            commands.commit()
            ThemeSceneRenderer.framesDrawn += 1
        }

        private func presented(_ ok: Bool) {
            if done { return }
            if ok { parent.onFirstFrame() } else { fail() }
        }

        /// A snapshot may not capture a Metal layer, so with this view clear it shows the still beneath.
        @objc private func willDeactivate() {
            view?.alpha = 0
        }

        @objc private func didActivate() {
            view?.alpha = 1
        }

        private func fail() {
            if done { return }
            stop()
            // Never from inside a SwiftUI update: makeUIView can get here.
            DispatchQueue.main.async { [parent] in parent.onFailure() }
        }

        /// Drops the scene and the renderer, and with them every GPU resource the theme holds.
        func stop() {
            done = true
            view?.isPaused = true
            view?.delegate = nil
            loader?.cancel()
            loader = nil
            NotificationCenter.default.removeObserver(self)
            renderer = nil
            scene = nil
            if counted {
                counted = false
                ThemeSceneRenderer.liveCount -= 1
            }
            if armed {
                armed = false
                ThemeSceneView.armedViews -= 1
                if ThemeSceneView.armedViews == 0 { ThemeLibraryModel.shared.library.disarmLive() }
            }
        }
    }
}
