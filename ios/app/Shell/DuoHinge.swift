// SPDX-License-Identifier: GPL-2.0-only
// The iPhone Duo's hinge: whether it is half open, where the fold runs, and whether a window gets the iPad layout.

import SwiftUI
import UIKit

@MainActor @Observable final class DuoHinge {
    static let shared = DuoHinge()

    private(set) var present = false
    /// Half open: tabletop or book pose.
    private(set) var partiallyOpen = false

    private init() {}

    fileprivate func set(present: Bool, partiallyOpen: Bool) {
        if self.present != present { self.present = present }
        if self.partiallyOpen != partiallyOpen { self.partiallyOpen = partiallyOpen }
    }

    /// At least 600 pt on the shorter side: the unfolded inner screen gets the iPad layout, the cover screen the phone one.
    static func isLarge(_ windowSize: CGSize) -> Bool {
        UIDevice.current.userInterfaceIdiom == .pad || min(windowSize.width, windowSize.height) >= 600
    }
}

/// Adds the hinge interaction and feeds DuoHinge. Draws nothing and takes no touches.
struct DuoHingeReader: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.isUserInteractionEnabled = false
        if #available(iOS 27.1, *) {
            view.addInteraction(UIHingeInteraction { _, update in
                // An update with no hinge comes from a reader leaving its window and says nothing about the hinge.
                guard let status = update.hinge?.status else { return }
                DuoHinge.shared.set(present: status != .unknown, partiallyOpen: status == .partiallyOpen)
            })
        }
        return view
    }

    func updateUIView(_ view: UIView, context: Context) {}
}

extension GeometryProxy {
    /// The fold while the hinge is half open, in this proxy's space; nil when flat, closed, or before iOS 27.1.
    @MainActor func halfOpenFold() -> CGRect? {
        guard DuoHinge.shared.partiallyOpen, #available(iOS 27.1, *) else { return nil }
        let bounds = CGRect(origin: .zero, size: size)
        return reservedRegions(kind: .division).first { $0.isActive && $0.frame.intersects(bounds) }?.frame
    }

    @MainActor func tabletopFold() -> CGRect? {
        halfOpenFold().flatMap { $0.width > $0.height ? $0 : nil }
    }

    @MainActor func bookFold() -> CGRect? {
        halfOpenFold().flatMap { $0.height > $0.width ? $0 : nil }
    }
}

extension View {
    /// Measured on the layout frame, so an `.offset` applied before this does not move what it reports.
    func onBookFoldChange(_ action: @escaping (ClosedRange<CGFloat>?) -> Void) -> some View {
        background {
            GeometryReader { geo in
                let fold = geo.bookFold().map { $0.minX...$0.maxX }
                Color.clear.onChange(of: fold, initial: true) { _, value in action(value) }
            }
            .background { DuoHingeReader() }
        }
    }

    func inLeftLeafInBookPose() -> some View {
        modifier(LeftLeafPlacement())
    }
}

private struct LeftLeafPlacement: ViewModifier {
    @State private var fold: ClosedRange<CGFloat>?

    func body(content: Content) -> some View {
        // The fold is read on the outer, full-width frame.
        content
            .frame(maxWidth: fold?.lowerBound ?? .infinity)
            .frame(maxWidth: .infinity, alignment: fold == nil ? .center : .leading)
            .onBookFoldChange { fold = $0 }
    }
}
