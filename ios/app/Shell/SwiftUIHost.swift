// SPDX-License-Identifier: GPL-2.0-or-later
// Adapted from ARMSX2's iOS app.

import SwiftUI
import UIKit
#if canImport(GameController)
import GameController
#endif

/// Owns the controller delivery boundary for the complete SDL/SwiftUI window.
@MainActor
@objc(ARMSX2ControllerEventHostViewController)
final class ARMSX2ControllerEventHostViewController: GCEventViewController {
    private let contentController: UIViewController

    @objc init(contentController: UIViewController) {
        self.contentController = contentController
        super.init(nibName: nil, bundle: nil)
        controllerUserInteractionEnabled = false
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemGroupedBackground

        if #available(iOS 26.0, *) {
            let controllerEvents = GCEventInteraction()
            controllerEvents.handledEventTypes = .gamepad
            controllerEvents.receivesEventsInView = false
            view.addInteraction(controllerEvents)
        }

        addChild(contentController)
        contentController.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(contentController.view)
        NSLayoutConstraint.activate([
            contentController.view.topAnchor.constraint(equalTo: view.topAnchor),
            contentController.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            contentController.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            contentController.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])
        contentController.didMove(toParent: self)
    }

    override var childForStatusBarHidden: UIViewController? {
        children.last
    }

    override var childForStatusBarStyle: UIViewController? {
        children.last
    }

    override var childForHomeIndicatorAutoHidden: UIViewController? {
        children.last
    }

    override var childForScreenEdgesDeferringSystemGestures: UIViewController? {
        children.last
    }

    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        contentController.supportedInterfaceOrientations
    }

    override var preferredInterfaceOrientationForPresentation: UIInterfaceOrientation {
        contentController.preferredInterfaceOrientationForPresentation
    }

    override func pressesBegan(
        _ presses: Set<UIPress>,
        with event: UIPressesEvent?
    ) {
        if ControllerEventDeliveryCoordinator.shared
            .handleMenuPressesEvent(presses) {
            return
        }
        super.pressesBegan(presses, with: event)
    }

    override func pressesChanged(
        _ presses: Set<UIPress>,
        with event: UIPressesEvent?
    ) {
        if ControllerEventDeliveryCoordinator.shared.shouldConsumeDirectionalPresses,
           presses.contains(where: { $0.type.isDirectional }) {
            return
        }
        super.pressesChanged(presses, with: event)
    }

    override func pressesEnded(
        _ presses: Set<UIPress>,
        with event: UIPressesEvent?
    ) {
        if ControllerEventDeliveryCoordinator.shared.shouldConsumeDirectionalPresses,
           presses.contains(where: { $0.type.isDirectional }) {
            return
        }
        super.pressesEnded(presses, with: event)
    }

    override func pressesCancelled(
        _ presses: Set<UIPress>,
        with event: UIPressesEvent?
    ) {
        if ControllerEventDeliveryCoordinator.shared.shouldConsumeDirectionalPresses,
           presses.contains(where: { $0.type.isDirectional }) {
            return
        }
        super.pressesCancelled(presses, with: event)
    }
}

private extension UIPress.PressType {
    var isDirectional: Bool {
        self == .upArrow || self == .downArrow
            || self == .leftArrow || self == .rightArrow
    }
}

private extension UIKeyboardHIDUsage {
    var menuControllerCommand: MenuControllerCommand? {
        switch rawValue {
        case 82: .up
        case 81: .down
        case 80: .left
        case 79: .right
        default: nil
        }
    }

    /// Return, keypad Enter and Escape: the XMB's Cross and Circle, as Android's library confirms with Enter.
    var xmbCommand: MenuControllerCommand? {
        switch rawValue {
        case 40, 88: .activate
        case 41: .back
        default: nil
        }
    }
}

/// A small bridge between UIKit presses and the SwiftUI-owned
/// menu router, which reads controllers through their GameController profile.
@MainActor
final class ControllerEventDeliveryCoordinator {
    static let shared = ControllerEventDeliveryCoordinator()

    private weak var router: MenuControllerInputRouter?
    private(set) var rightStickScrolling = false
    private var menuActive = false

    private init() {}

    var shouldConsumeDirectionalPresses: Bool {
        menuActive
    }

    /// A controller's UIKit copy of a press must not reach SwiftUI, where a
    /// focused NavigationLink takes a duplicate direction as activation on iPad.
    /// Keyboard arrows are bridged here, for Simulator and hardware keyboards.
    func handleMenuPressesEvent(_ presses: Set<UIPress>) -> Bool {
        guard menuActive else { return false }
        // Other screens keep Return and Escape for their own fields and buttons.
        let xmb = router?.isPadFirstCaptured == true
        func command(_ press: UIPress) -> MenuControllerCommand? {
            guard let code = press.key?.keyCode else { return nil }
            return code.menuControllerCommand ?? (xmb ? code.xmbCommand : nil)
        }
        if let keyboardPress = presses.first(where: { command($0) != nil }) {
            if keyboardPress.phase == .began, let command = command(keyboardPress) {
                router?.handleKeyboardDirectionalPress(command)
            }
            return true
        }

        // The iPad crash report shows a keyless _UIGameControllerEvent being
        // converted into a UIKit presses event. Its L1 shoulder recognizer then
        // moved native focus and selected a SwiftUI NavigationLink while the
        // custom Settings graph was switching tabs. Profile-driven screens own
        // every such keyless controller press, so UIKit must not see the copy.
        return presses.contains(where: { $0.key == nil })
    }

    func install(router: MenuControllerInputRouter) {
        self.router = router
    }

    func setMenuActive(_ active: Bool) {
        menuActive = active
        if !active { rightStickScrolling = false }
    }

    func setRightStickScrolling(_ active: Bool) {
        rightStickScrolling = active
    }
}

/// Custom hosting controller that respects fullScreen state for status bar hiding
class ARMSX2HostingController<Content: View>: UIHostingController<Content> {
    override var prefersStatusBarHidden: Bool {
        AppState.shared.hideStatusBar
    }
    override var prefersHomeIndicatorAutoHidden: Bool {
        AppState.shared.hideStatusBar || AppState.shared.hideHomeIndicator
    }
    override var preferredStatusBarUpdateAnimation: UIStatusBarAnimation {
        .fade
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(systemChromeNeedsUpdate),
            name: AppState.systemChromeNeedsUpdateNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(systemChromeNeedsUpdate),
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )
    }

    deinit {
        NotificationCenter.default.removeObserver(
            self,
            name: AppState.systemChromeNeedsUpdateNotification,
            object: nil
        )
        NotificationCenter.default.removeObserver(
            self,
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        applyNativeContentScale(to: view)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        applyNativeContentScale(to: view)
        // The intro preference is seeded before this child is attached. Ask
        // the complete parent chain again once UIKit has a visible window.
        systemChromeNeedsUpdate()
    }

    /// Force SwiftUI to re-evaluate its layout when the device rotates.
    ///
    /// This hosting controller is a child of SDL's root view controller. While UIKit does forward `viewWillTransition` to child controllers, `UIHostingController`'s internal
    /// layout engine sometimes fails to invalidate its `GeometryReader` contents promptly, especially when the view is pinned via Auto Layout constraints rather than living
    /// directly under the window. Without this nudge, SwiftUI keeps stale geometry after rotation, producing broken layouts (black bars, cropped viewports, misplaced touch
    /// controls). We force a re-evaluation by toggling the `rootView` on the animation coordinator so the re-layout rides the standard rotation animation block.
    override func viewWillTransition(to size: CGSize, with coordinator: any UIViewControllerTransitionCoordinator) {
        super.viewWillTransition(to: size, with: coordinator)
        // Re-assigning rootView forces UIHostingController to invalidate its
        // internal sizing cache and re-measure for the new container size.
        let current = rootView
        coordinator.animate(alongsideTransition: { _ in
            self.rootView = current
            self.view.setNeedsLayout()
            self.view.layoutIfNeeded()
        })
    }

    @objc private func systemChromeNeedsUpdate() {
        // UIKit asks the window's controller hierarchy for system chrome.
        // Updating only this nested hosting controller can leave the SDL and
        // GCEvent parents with their cached answer for the entire intro.
        // Invalidate every owner up to the window root.
        var controller: UIViewController? = self
        while let current = controller {
            current.setNeedsStatusBarAppearanceUpdate()
            current.setNeedsUpdateOfHomeIndicatorAutoHidden()
            controller = current.parent
        }
    }

    private func applyNativeContentScale(to view: UIView) {
        let screen = view.window?.screen ?? UIScreen.main
        let scale = max(screen.nativeScale, screen.scale, 1.0)
        view.contentScaleFactor = scale
        view.layer.contentsScale = scale
        for subview in view.subviews {
            applyNativeContentScale(to: subview)
        }
    }
}


// ARMSX2's @objc entry points are gone: this app is SwiftUI and hosts RootView itself.

extension UIApplication {
    /// The app's own scene; an AirPlay scene can enumerate first and has a key window too.
    var appWindowScene: UIWindowScene? {
        connectedScenes.first { $0.session.role == .windowApplication } as? UIWindowScene
    }
}
