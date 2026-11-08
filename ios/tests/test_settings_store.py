#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""SettingsStore.swift, run on the Mac host with stand-ins for the lifted types it names, reports every member's default,
persists each under armsx3.settings.<member> and reads back clamped or unknown values safely; static checks pin its
header, key form and absence of PCSX2 vocabulary. It says nothing about iOS."""

import re
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run

IOS = Path(__file__).resolve().parents[1]
STORE = IOS / "app" / "Settings" / "SettingsStore.swift"
# The lifted background model the store's picked-background members name, compiled as is.
BACKGROUND_ASSET = IOS / "app" / "Themes" / "BackgroundAsset.swift"
HOTKEYS = IOS / "app" / "Controllers" / "HotkeyTable.swift"

VOCAB = re.compile(r"PCSX2|EmuCore|GameDB|memcard|RetroAchievements|pnach|\b(EE|IOP|VU0|VU1)\b")
KEY_CALL = re.compile(r'key\("([A-Za-z0-9_]+)"\)')
DECLARED = re.compile(r"^\s*(?:(?:private|fileprivate)\s+)?(?:var|let|func)\s+([A-Za-z_][A-Za-z0-9_]*)", re.M)

# Stand-ins for the lifted types the store names, and the driver, written into a temporary directory at run time.
DRIVER = r"""
import Foundation
import SwiftUI

enum ThemePalette: String, Codable { case blue, cyan, violet
    var colors: [Color] { [Color(red: 0.1, green: 0.2, blue: 0.9), Color(red: 0.0, green: 0.8, blue: 1.0)] }
    var semanticAccentColor: Color { self == .blue ? Color(red: 0.2, green: 0.5, blue: 1.0) : .purple }
    var semanticInterfaceTextColor: Color { .white }
}
struct SavedPaletteColor: Codable, Hashable { let hex: String }
struct DynamicAppearancePreferences: Codable, Equatable {
    var sharedPalette: ThemePalette
    static let standard = DynamicAppearancePreferences(sharedPalette: .blue)
}
// The three lifted types the store's focus members name.
enum ControllerFocusBoxStyle: String { case neonBlue, softGlow }
enum ControllerNavigationFocusTravelStyle: String { case easeOutBack, linear }
struct ControllerTextAppearance: Equatable { var marker = "standard"; static let standard = ControllerTextAppearance() }
// The two router types the store's controller chord members name. The store reads
// the action's raw value and builds the binding from a raw chord, so the stand-ins carry those.
// the action
// mirrors the router's eleven PS3 cases and its now-optional defaultBinding, which the store never reads
// (HotkeyTable owns the defaults).
struct ControllerMacroBinding { let rawValue: String; init?(rawValue: String) { self.rawValue = rawValue } }
enum ControllerMacroAction: String {
    case quickMenu, saveGameState, loadGameState, cycleSaveSlot, saveStateAndExit, togglePerformanceOverlay
    case resolutionUp, resolutionDown, closeGame, resetGame, pressureModifier
    var defaultBinding: ControllerMacroBinding? { nil }
}

func line(_ s: String) { print(s) }

MainActor.assumeIsolated {
    let suiteName = "armsx3.settings-store-test." + UUID().uuidString
    let suite = UserDefaults(suiteName: suiteName)!
    defer { suite.removePersistentDomain(forName: suiteName) }

    let a = SettingsStore(defaults: suite)
    let accent = a.controllerNavigationAccentColor == ThemePalette.blue.semanticAccentColor
        && a.controllerBottomTabBarColor == ThemePalette.blue.semanticAccentColor
    let text = a.controllerTabTitleColor == .white && a.controllerCardTitleColor == .white
    line("T1 dyn=\(a.dynamicBackgroundsEnabled) prefs=\(a.dynamicAppearancePreferences == .standard) dim=\(a.backgroundDim)"
        + " glassQM=\(a.clearLiquidGlassUIQuickMenu) glass=\(a.clearLiquidGlassUI) haptics=\(a.hapticFeedback)"
        + " rumble=\(a.uiRumbleStrength) orbs=\(a.focusOrbsEnabled) depth=\(a.controllerNavigationDepthEffectEnabled)"
        + " introBar=\(a.hideIntroStatusBar) menuBar=\(a.hideMenuStatusBar) focusPalette=\(a.controllerFocusBoxPalette.rawValue)"
        + " custom=\(a.controllerFocusBoxCustomColor == nil) speed=\(a.controllerFocusBoxAnimationSpeed)"
        + " glow=\(a.controllerFocusBoxGlowIntensity) orbColors=\(a.controllerOrbColors == ThemePalette.blue.colors)"
        + " accent=\(accent) text=\(text) critical=\(a.controllerCriticalTextColor == .red)"
        + " hasBg=\(a.hasCustomBackground) localized=\(a.localized("OK")) rtl=\(a.localizedLayoutDirection == .rightToLeft)")
    line("T4 focusStyle=\(a.controllerFocusBoxStyle.rawValue) focusAnim=\(a.controllerNavigationFocusAnimation.rawValue)"
        + " text=\(a.controllerTextAppearance == .standard)")

    a.dynamicBackgroundsEnabled = false
    a.dynamicAppearancePreferences = DynamicAppearancePreferences(sharedPalette: .violet)
    a.backgroundDim = 0.4
    a.clearLiquidGlassUIQuickMenu = false
    a.clearLiquidGlassUI = false
    a.hapticFeedback = false
    a.uiRumbleStrength = 0.25
    a.focusOrbsEnabled = false
    a.controllerNavigationDepthEffectEnabled = true
    a.hideIntroStatusBar = true
    a.hideMenuStatusBar = true
    a.controllerFocusBoxPalette = .cyan
    a.controllerFocusBoxAnimationSpeed = 1.5
    a.controllerFocusBoxGlowIntensity = 0.5
    a.controllerFocusBoxStyle = .softGlow
    a.controllerNavigationFocusAnimation = .linear

    let b = SettingsStore(defaults: suite)
    line("T2 dyn=\(b.dynamicBackgroundsEnabled) prefs=\(b.dynamicAppearancePreferences.sharedPalette.rawValue) dim=\(b.backgroundDim)"
        + " glassQM=\(b.clearLiquidGlassUIQuickMenu) glass=\(b.clearLiquidGlassUI) haptics=\(b.hapticFeedback)"
        + " rumble=\(b.uiRumbleStrength) orbs=\(b.focusOrbsEnabled) depth=\(b.controllerNavigationDepthEffectEnabled)"
        + " introBar=\(b.hideIntroStatusBar) menuBar=\(b.hideMenuStatusBar) focusPalette=\(b.controllerFocusBoxPalette.rawValue)"
        + " speed=\(b.controllerFocusBoxAnimationSpeed) glow=\(b.controllerFocusBoxGlowIntensity)"
        + " hasBg=\(b.hasCustomBackground) accent=\(b.controllerNavigationAccentColor == .purple)")
    line("T4b focusStyle=\(b.controllerFocusBoxStyle.rawValue) focusAnim=\(b.controllerNavigationFocusAnimation.rawValue)")

    suite.set(3.0, forKey: SettingsStore.key("backgroundDim"))
    suite.set("not-a-palette", forKey: SettingsStore.key("controllerFocusBoxPalette"))
    let c = SettingsStore(defaults: suite)
    line("T2b dim=\(c.backgroundDim) focusPalette=\(c.controllerFocusBoxPalette.rawValue) key=\(SettingsStore.key("backgroundDim"))")
    suite.set("not-a-style", forKey: SettingsStore.key("controllerFocusBoxStyle"))
    suite.set("not-a-curve", forKey: SettingsStore.key("controllerNavigationFocusAnimation"))
    let d = SettingsStore(defaults: suite)
    line("T4c focusStyle=\(d.controllerFocusBoxStyle.rawValue) focusAnim=\(d.controllerNavigationFocusAnimation.rawValue)")

    // Show advanced settings defaults off and persists; the Settings tab's background switch is on.
    let e = SettingsStore(defaults: suite)
    let advancedDefault = e.showAdvancedSettings
    e.showAdvancedSettings = true
    line("T5 advanced=\(advancedDefault) persisted=\(SettingsStore(defaults: suite).showAdvancedSettings) background=\(e.backgroundEnabledInSettings)")

    // The hotkeys are one stored dictionary, empty by default, read back by a new store.
    let f = SettingsStore(defaults: suite)
    let hotkeysDefault = f.controllerMacroBindings.isEmpty
    f.hotkeys = HotkeyTable(stored: ["saveGameState": "select+r1", "loadGameState": ""])
    let g = SettingsStore(defaults: suite)
    let raw = suite.dictionary(forKey: "armsx3.settings.controllerMacroBindings") as? [String: String]
    line("T6 empty=\(hotkeysDefault) save=\(g.hotkeys.chord(for: "saveGameState") ?? "nil")"
        + " load=\(g.controllerMacroBinding(for: .loadGameState)?.rawValue ?? "nil")"
        + " menu=\(g.controllerMacroBinding(for: .quickMenu)?.rawValue ?? "nil")"
        + " binding=\(g.controllerMacroSaveGameState?.rawValue ?? "nil") raw=\(raw?.count ?? -1)")

    // Verbose logging defaults off and persists under its own key.
    let h = SettingsStore(defaults: suite)
    let verboseDefault = h.verboseLogging
    h.verboseLogging = true
    let verboseStored = suite.object(forKey: "armsx3.settings.verboseLogging") as? Bool ?? false
    line("T7 verbose=\(verboseDefault) persisted=\(SettingsStore(defaults: suite).verboseLogging && verboseStored)")

    // The picked background is stored, and drives hasCustomBackground.
    let i = SettingsStore(defaults: suite)
    let defaultsLine = "primary=\(i.backgroundPrimaryAsset == nil) landscape=\(i.backgroundLandscapeAsset == nil)"
        + " fit=\(i.backgroundFitMode.rawValue) landFit=\(i.backgroundLandscapeFitMode.rawValue) muted=\(i.backgroundVideoMuted)"
    let asset = BackgroundAsset(kind: .video, filename: "smoke.mov")
    i.dynamicBackgroundsEnabled = false
    i.backgroundPrimaryAsset = asset
    i.backgroundLandscapeFitMode = .stretch
    i.backgroundVideoMuted = false
    let j = SettingsStore(defaults: suite)
    let kept = j.backgroundPrimaryAsset == asset && j.backgroundLandscapeFitMode == .stretch && !j.backgroundVideoMuted
    let hasBg = j.hasCustomBackground
    j.backgroundPrimaryAsset = nil
    let cleared = suite.object(forKey: "armsx3.settings.backgroundPrimaryAsset") == nil && !j.hasCustomBackground
    suite.set("not-a-mode", forKey: SettingsStore.key("backgroundFitMode"))
    line("T8 \(defaultsLine) kept=\(kept) hasBg=\(hasBg) cleared=\(cleared) unknown=\(SettingsStore(defaults: suite).backgroundFitMode.rawValue)")
}
"""

T1 = ("T1 dyn=true prefs=true dim=0.0 glassQM=true glass=true haptics=true rumble=0.5 orbs=true depth=false"
      " introBar=false menuBar=false focusPalette=blue custom=true speed=1.0 glow=1.0 orbColors=true"
      " accent=true text=true critical=true hasBg=true localized=OK rtl=false")
T2 = ("T2 dyn=false prefs=violet dim=0.4 glassQM=false glass=false haptics=false rumble=0.25 orbs=false depth=true"
      " introBar=true menuBar=true focusPalette=cyan speed=1.5 glow=0.5 hasBg=false accent=true")
T2B = "T2b dim=1.0 focusPalette=blue key=armsx3.settings.backgroundDim"
# The focus box style, the focus travel curve and the text appearance.
T4 = "T4 focusStyle=neonBlue focusAnim=easeOutBack text=true"
T4B = "T4b focusStyle=softGlow focusAnim=linear"
T4C = "T4c focusStyle=neonBlue focusAnim=easeOutBack"
# Show advanced settings (stored, default off) and the Settings tab background switch (computed true).
T5 = "T5 advanced=false persisted=true background=true"
# The hotkeys' stored dictionary.
T6 = "T6 empty=true save=select+r1 load=nil menu=select+start binding=select+r1 raw=2"
# Verbose logging (stored, default off).
T7 = "T7 verbose=false persisted=true"
# The picked background members.
T8 = "T8 primary=true landscape=true fit=fill landFit=fill muted=true kept=true hasBg=true cleared=true unknown=fill"


def read(path):
    if not path.is_file():
        raise AssertionError("{0} is missing. This guard fails rather than skipping.".format(path))
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty. This guard fails rather than skipping.".format(path))
    return text


def store_problems(text):
    problems = []
    head = text.split("\n", 1)[0]
    if head != "// SPDX-License-Identifier: GPL-2.0-only":
        problems.append("header: line 1 is {0!r}, not the GPL-2.0-only SPDX line".format(head))
    if "Adapted from ARMSX2" in text:
        problems.append("header: the file carries the lifted-file header")
    for match in VOCAB.finditer(text):
        problems.append("vocab: the file names PCSX2 vocabulary {0!r}".format(match.group(0)))
    if '"armsx3.settings." + member' not in text:
        problems.append("keys: key(_:) does not build `armsx3.settings.<member>`")
    declared = set(DECLARED.findall(text))
    keys = KEY_CALL.findall(text)
    if not keys:
        problems.append("keys: no key(\"<name>\") call; nothing is persisted")
    for name in sorted(set(keys) - declared):
        problems.append("keys: key(\"{0}\") names no member the file declares".format(name))
    return problems


class TheStoreHolds(unittest.TestCase):
    lines = None

    @classmethod
    def setUpClass(cls):
        read(STORE)
        with tempfile.TemporaryDirectory() as tmp:
            out = compile_and_run([STORE, HOTKEYS, BACKGROUND_ASSET], DRIVER, tmp)
        cls.lines = {line.split(" ", 1)[0]: line for line in out.splitlines() if line.strip()}

    def test_t1_a_fresh_store_reports_every_default(self):
        self.assertEqual(T1, self.lines.get("T1"))

    def test_t2_a_new_store_over_the_same_suite_reads_the_values_back(self):
        self.assertEqual(T2, self.lines.get("T2"))

    def test_t2b_an_out_of_range_or_unknown_stored_value_reads_as_safe(self):
        self.assertEqual(T2B, self.lines.get("T2b"))

    def test_t4_the_focus_members_default_persist_and_refuse_an_unknown_value(self):
        self.assertEqual([T4, T4B, T4C], [self.lines.get("T4"), self.lines.get("T4b"), self.lines.get("T4c")])

    def test_t5_show_advanced_settings_defaults_off_and_persists(self):
        self.assertEqual(T5, self.lines.get("T5"))

    def test_t6_the_hotkeys_default_empty_and_persist(self):
        self.assertEqual(T6, self.lines.get("T6"))

    def test_t7_verbose_logging_defaults_off_and_persists(self):
        self.assertEqual(T7, self.lines.get("T7"))

    def test_t8_the_picked_background_defaults_persists_and_drives_has_custom_background(self):
        self.assertEqual(T8, self.lines.get("T8"))


class TheStoreFileIsClean(unittest.TestCase):
    def test_t3_the_real_file_has_no_problem(self):
        self.assertEqual([], store_problems(read(STORE)))

    def test_edge_missing_and_empty_inputs_raise(self):
        with tempfile.TemporaryDirectory() as tmp:
            missing = Path(tmp) / "SettingsStore.swift"
            with self.assertRaises(AssertionError):
                read(missing)
            missing.write_text("  \n", encoding="utf-8")
            with self.assertRaises(AssertionError):
                read(missing)


def edited(test, text, old, new):
    test.assertIn(old, text, "the mutation's anchor is gone from SettingsStore.swift")
    changed = text.replace(old, new, 1)
    test.assertNotEqual(text, changed)
    return changed


class TheStoreGuardFailsOnAMutation(unittest.TestCase):
    def setUp(self):
        self.text = read(STORE)

    def expect(self, prefix, text):
        problems = store_problems(text)
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), (prefix, problems))

    def test_mutation_header_a_gpl_3_line(self):
        self.expect("header", edited(self, self.text, "GPL-2.0-only", "GPL-3.0-or-later"))

    def test_mutation_header_no_d_7_13_sentence(self):
        self.expect("header", edited(self, self.text, "// SPDX-License-Identifier: GPL-2.0-only\n", "// SPDX-License-Identifier: GPL-2.0-only\n// Adapted from ARMSX2's iOS app.\n"))

    def test_mutation_vocab_an_ee_clock_member(self):
        self.expect("vocab", edited(self, self.text, "var hapticFeedback: Bool", "var EE: Bool = false\n    var hapticFeedback: Bool"))

    def test_mutation_vocab_a_gamedb_mention(self):
        self.expect("vocab", edited(self, self.text, "UI preferences, never emulator settings", "UI preferences and GameDB fixes"))

    def test_mutation_keys_a_key_that_drifted_from_its_member(self):
        self.expect("keys", edited(self, self.text, 'key("hapticFeedback")', 'key("haptics")'))

    def test_mutation_keys_another_prefix(self):
        self.expect("keys", edited(self, self.text, '"armsx3.settings." + member', '"settings." + member'))

    def test_control_a_word_containing_ee_is_not_vocabulary(self):
        self.assertFalse(VOCAB.search("feedback between speed and steer"))


if __name__ == "__main__":
    unittest.main()
