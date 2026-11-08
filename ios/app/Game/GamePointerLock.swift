// SPDX-License-Identifier: GPL-2.0-only
// iPad pointer lock for the game screen. SwiftUI's covers never answer UIKit's lock lookup, so the getter is routed.

import SwiftUI
import UIKit

extension UIViewController {
    // Each controller above the game screen, mapped to the game screen's lock controller. Only the game screen writes it.
    fileprivate static let armsx3PointerLockChildren = NSMapTable<UIViewController, UIViewController>.weakToWeakObjects()

    // It never calls the original, which returns nil, and never maps the lock controller to itself, so recursion ends.
    @objc fileprivate var armsx3ChildViewControllerForPointerLock: UIViewController? {
        UIViewController.armsx3PointerLockChildren.object(forKey: self)
    }

    // Exchanged once, on first use; false when either method is missing, and then nothing is exchanged.
    fileprivate static let armsx3PointerLockRouting: Bool = {
        guard let base = class_getInstanceMethod(UIViewController.self,
                                                 #selector(getter: UIViewController.childViewControllerForPointerLock)),
              let routed = class_getInstanceMethod(UIViewController.self,
                                                   #selector(getter: UIViewController.armsx3ChildViewControllerForPointerLock))
        else { return false }
        method_exchangeImplementations(base, routed)
        return true
    }()

    fileprivate static func armsx3SetPointerLockChild(_ child: UIViewController?, of controller: UIViewController) {
        if let child {
            armsx3PointerLockChildren.setObject(child, forKey: controller)
        } else {
            armsx3PointerLockChildren.removeObject(forKey: controller)
        }
    }
}

final class GamePointerLockController: UIViewController {
    static weak var current: GamePointerLockController?

    override var prefersPointerLocked: Bool { KeyboardMouseFeed.wantsPointerLock }

    // True while anything is presented over the game screen, so a mouse user keeps a pointer.
    var covered: Bool {
        var top: UIViewController = self
        while let up = top.parent { top = up }
        return top.presentedViewController != nil
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.isUserInteractionEnabled = false
        view.backgroundColor = .clear
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        Self.current = self
        route(true)
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        route(false)
        if Self.current === self { Self.current = nil }
    }

    private func route(_ on: Bool) {
        guard UIDevice.current.userInterfaceIdiom == .pad else { return }
        guard UIViewController.armsx3PointerLockRouting else { return }
        var targets: [UIViewController] = []
        var up = parent
        while let next = up {
            targets.append(next)
            up = next.parent
        }
        var down = view.window?.rootViewController
        while let next = down {
            targets.append(next)
            down = next.presentedViewController
        }
        for target in targets where target !== self {
            if on {
                UIViewController.armsx3SetPointerLockChild(self, of: target)
            } else if UIViewController.armsx3PointerLockChildren.object(forKey: target) === self {
                UIViewController.armsx3SetPointerLockChild(nil, of: target)
            }
        }
        self.setNeedsUpdateOfPrefersPointerLocked()
    }
}

// shortcut: if SwiftUI starts overriding childViewControllerForPointerLock the lock silently stays off; re-measure then.
struct GamePointerLockAnchor: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> GamePointerLockController {
        GamePointerLockController()
    }

    func updateUIViewController(_ controller: GamePointerLockController, context: Context) {}
}
