// SPDX-License-Identifier: GPL-2.0-or-later
// Adapted from ARMSX2's iOS app.

import SwiftUI
import UIKit

/// How an in-game overlay should arrange its content for the current device class.
/// Replaces a single broad isLandscape/useWideLayout boolean so iPad, iPhone
/// landscape, and iPhone portrait can each get the right layout without one
/// device's needs compromising another's.
enum PauseLayoutVariant {
    /// iPad, any orientation: two side-by-side columns inside one unified scroll
    /// surface.
    case ipadTwoColumn
    /// iPhone landscape: one or two columns according to the panel's measured size.
    /// Covers every iPhone in landscape, including Pro Max / Plus models that report
    /// a regular width size class.
    case phoneLandscape
    /// iPhone portrait: one single column.
    case phonePortrait
}

/// Adaptive sizing values for an in-game overlay card, derived from the available
/// geometry, the device class (iPad vs iPhone), and the host's safe-area insets.
///
/// Device classes:
/// - iPad: a compact floating panel that leaves most gameplay visible.
/// - iPhone landscape: a wide, height-relieved band so the section content and pinned
///   Resume action are not cramped.
/// - iPhone portrait: the liked bounded card.
struct OverlayMetrics {
    /// Maximum card size for the overlay content.
    let cardMaxWidth: CGFloat
    let cardMaxHeight: CGFloat
    /// How the hosted content should arrange itself for the current device class.
    let variant: PauseLayoutVariant
    /// Deterministic dim behind the card. A plain opacity scrim is used instead of a
    /// SwiftUI Material, which collapses to a flat grey over a paused Metal frame
    /// (worst on iPad and under Reduce Transparency). Bumped for Reduce Transparency
    /// users while remaining light enough for the paused game to show through glass.
    let scrimOpacity: Double
    /// The narrowest card a panel lays out in two columns.
    static let twoColumnWidth: CGFloat = 560

    /// `size` is already the safe region, insets removed. Do not take them off again.
    init(size: CGSize, isIPad: Bool, reduceTransparency: Bool, ipadPortraitHeightCap: CGFloat = 1000) {
        let isLandscape = size.width > size.height

        if isIPad {
            // iPad. Landscape gets a wider/taller card than portrait so the deck and Per-Game
            // Settings fill more of the wide screen instead of reading as a small centered island.
            variant = .ipadTwoColumn
            let widthMargin: CGFloat = isLandscape ? 96 : 64
            let heightMargin: CGFloat = isLandscape ? 88 : 72
            let widthCap: CGFloat = isLandscape ? 980 : 620
            // Taller, so the quick menu and in-game settings do not scroll with half the screen empty.
            let heightCap: CGFloat = isLandscape ? 900 : ipadPortraitHeightCap
            cardMaxWidth = max(0, min(widthCap, size.width - widthMargin))
            cardMaxHeight = max(0, min(heightCap, size.height - heightMargin))
            scrimOpacity = reduceTransparency ? OverlayTheme.scrimPadReduceTransparency : OverlayTheme.scrimPad
        } else if isLandscape {
            // iPhone landscape: a tall floating command panel. Bounds leave a real margin
            // beyond the safe-area insets so the panel clears the notch/Dynamic Island and
            // the virtual pad instead of reading as an edge-to-edge slab. Caps keep wide
            // phones from stretching past a comfortable reading width/height.
            variant = .phoneLandscape
            cardMaxWidth = max(0, min(760, size.width - 40))
            cardMaxHeight = max(0, min(468, size.height - 36))
            scrimOpacity = reduceTransparency ? OverlayTheme.scrimPhoneLandscapeReduceTransparency : OverlayTheme.scrimPhoneLandscape
        } else {
            // iPhone portrait: the liked compact card, slightly roomier.
            variant = .phonePortrait
            cardMaxWidth = max(0, min(480, size.width - 32))
            // Taller, so most of the quick menu fits without scrolling.
            cardMaxHeight = max(0, min(720, size.height - 48))
            scrimOpacity = reduceTransparency ? OverlayTheme.scrimPhonePortraitReduceTransparency : OverlayTheme.scrimPhonePortrait
        }
    }
}

/// Reusable in-game overlay shell: a deterministic dim behind a bounded, centered
/// card. Owns the backdrop, card sizing, clipping, shadow, safe-area clearance,
/// tap-outside behavior, and orientation-aware metrics so the pause menu and other
/// in-game panels share one coherent presentation instead of one-off inline overlays.
///
/// The host presents this inside an `.overlay { if isPresented { ... } }` and drives an
/// `.animation(_:value:)` on the presenting bool; this view supplies its own transitions.
/// Clipping and shadow are applied here so every hosted card looks identical.
enum OverlayFrameMode {
    /// Centered, bounded card with rounded corners and a drop shadow. Used for
    /// portrait, iPad, and the polished iPhone-landscape panel.
    case bounded
    /// Polished iPhone-landscape panel: keeps the deck's tall, usable shape but
    /// renders as a floating rounded card with margins, corner radius, and shadow
    /// instead of a full-bleed slab.
    case landscapePanel
    /// Full-bleed edge-to-edge deck (no corner clip, no shadow). Retained for
    /// reversibility; not selected by the current overlay hosts.
    case landscapeDeck
}

struct GameOverlayContainer<Content: View>: View {
    var onTapOutside: (() -> Void)? = nil
    var frameMode: OverlayFrameMode = .bounded
    var dimsBackground = true
    /// Room kept free below the card, for the controller shortcut bar.
    var bottomReserve: CGFloat = 0
    /// Save States lists ten rows, so it may use the whole height of an upright iPad.
    var ipadPortraitHeightCap: CGFloat = 1000
    // In book pose a panel that splits itself spreads over both leaves; any other panel sits on the right leaf, off the hinge.
    var spansFold = false
    @ViewBuilder let content: (OverlayMetrics) -> Content

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    // iPad vs iPhone is decided by device idiom, not the horizontal size class: the
    // largest iPhones (Pro Max / Plus) report a regular width size class in landscape,
    // which previously misrouted them onto the bounded iPad card and starved the
    // landscape deck. GeometryReader still re-evaluates on orientation/size change.
    private var isIPad: Bool { UIDevice.current.userInterfaceIdiom == .pad }

    var body: some View {
        // Outer reader sees the keyboard, inner one does not. Card is sized off the inner
        // one so it cannot collapse; the difference is how much the keyboard covers.
        GeometryReader { keyboardGeo in
            GeometryReader { geo in
                // This reader spans the whole width, so the window is its width.
                let window = CGSize(width: geo.size.width,
                                    height: geo.size.height + geo.safeAreaInsets.top + geo.safeAreaInsets.bottom)
                // Centred with the narrower side's inset on both sides, as the touch pad is, so a one-sided status column does not shift it.
                let even = min(keyboardGeo.safeAreaInsets.leading, keyboardGeo.safeAreaInsets.trailing)
                let region = CGSize(width: geo.size.width - 2 * even, height: geo.size.height)
                // Gameplay scene geometry, so rotation and Stage Manager come for free.
                let metrics = OverlayMetrics(
                    size: region,
                    isIPad: isIPad || DuoHinge.isLarge(window),
                    reduceTransparency: reduceTransparency,
                    ipadPortraitHeightCap: ipadPortraitHeightCap
                )
                // Half open, the card keeps off the fold: the bottom leaf in tabletop, the right leaf in book pose unless the panel spans it.
                let tabletop = geo.tabletopFold()
                let book = geo.bookFold()
                let rightLeaf = spansFold ? nil : book.map { (x: $0.maxX, width: geo.size.width - even - $0.maxX) }
                let leafHeight = tabletop.map { geo.size.height - $0.maxY } ?? geo.size.height
                let cardWidth = min(metrics.cardMaxWidth, rightLeaf.map { max(0, $0.width - 32) } ?? region.width)
                let cardX = rightLeaf.map { $0.x + ($0.width - cardWidth) / 2 } ?? (geo.size.width - cardWidth) / 2
                let keyboardOverlap = max(0, geo.size.height - keyboardGeo.size.height)
                // The card is centred, so the keyboard covers less of it than of the region.
                let cardMargin = max(0, (geo.size.height - metrics.cardMaxHeight) / 2)
                // What the bottom margin lacks, measured from the screen edge; the top margin stays.
                let lift = bottomReserve > 0
                    ? max(0, bottomReserve + geo.safeAreaInsets.bottom - cardMargin) : 0

                ZStack {
                    backdrop(metrics: metrics)

                    // Told, not inset here: an inset shrinks the box the panel measures itself in.
                    let hosted = content(metrics)
                        .environment(\.overlayKeyboardOverlap, max(0, keyboardOverlap - cardMargin))
                        // Wider than tall and roomy enough for two columns, with the fold in the card's own space.
                        .environment(\.overlayWideCard, cardWidth >= OverlayMetrics.twoColumnWidth
                                     && cardWidth > min(metrics.cardMaxHeight, leafHeight))
                        .environment(\.overlayBookFold, rightLeaf == nil ? book.map { ($0.minX - cardX)...($0.maxX - cardX) } : nil)
                        // A phone on its side, or the bottom leaf in tabletop.
                        .environment(\.overlayShortCard, metrics.variant == .phoneLandscape || tabletop != nil)

                    if frameMode == .landscapeDeck && metrics.variant == .phoneLandscape {
                        let deckGutter: CGFloat = 8
                        hosted
                            .padding(deckGutter)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .transition(reduceMotion ? .opacity : .scale(scale: 0.97).combined(with: .opacity))
                    } else {
                        // One arm, so a flip cannot swap it and take the panel's state along.
                        let popScale: CGFloat = frameMode == .landscapePanel && metrics.variant == .phoneLandscape ? 0.97 : 0.96
                        hosted
                            .frame(maxWidth: cardWidth,
                                   maxHeight: min(metrics.cardMaxHeight - lift, max(0, leafHeight - 24)))
                            .clipShape(RoundedRectangle(cornerRadius: 26, style: .continuous))
                            .shadow(color: .black.opacity(0.28), radius: 22, x: 0, y: 12)
                            .offset(y: -lift / 2)
                            .offset(y: tabletop.map { $0.maxY + leafHeight / 2 - geo.size.height / 2 } ?? 0)
                            .offset(x: cardX + cardWidth / 2 - geo.size.width / 2)
                            .transition(reduceMotion ? .opacity : .scale(scale: popScale).combined(with: .opacity))
                    }
                }
            }
            // The whole width, so the card centres on the screen; the even inset above keeps it clear of the narrower side.
            .ignoresSafeArea(.container, edges: .horizontal)
            .ignoresSafeArea(.keyboard)
        }
        // Keeps DuoHinge current while an overlay is up.
        .background { DuoHingeReader() }
        .transition(.opacity)
    }

    /// Plain opacity scrim, not a Material, which washes out to flat grey over a paused
    /// Metal frame. Near-black and light enough that the game still reads through. Eats
    /// taps, so gameplay gets none while an overlay is up.
    @ViewBuilder
    private func backdrop(metrics: OverlayMetrics) -> some View {
        let scrim = Group {
            if dimsBackground {
                OverlayTheme.scrimBase.opacity(metrics.scrimOpacity)
            } else {
                Color.clear
            }
        }
            .ignoresSafeArea()
            .contentShape(Rectangle())

        if let onTapOutside {
            scrim.onTapGesture { onTapOutside() }
        } else {
            scrim
        }
    }
}

// What the container tells its panel about the card's shape and the fold, so the panel can lay itself out.
private struct OverlayWideCardKey: EnvironmentKey {
    static let defaultValue = false
}

private struct OverlayShortCardKey: EnvironmentKey {
    static let defaultValue = false
}

private struct OverlayBookFoldKey: EnvironmentKey {
    static let defaultValue: ClosedRange<CGFloat>? = nil
}

extension EnvironmentValues {
    var overlayWideCard: Bool {
        get { self[OverlayWideCardKey.self] }
        set { self[OverlayWideCardKey.self] = newValue }
    }

    var overlayBookFold: ClosedRange<CGFloat>? {
        get { self[OverlayBookFoldKey.self] }
        set { self[OverlayBookFoldKey.self] = newValue }
    }

    var overlayShortCard: Bool {
        get { self[OverlayShortCardKey.self] }
        set { self[OverlayShortCardKey.self] = newValue }
    }
}
