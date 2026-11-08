#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""RpcnKeychain.swift keeps the RPCN login in one this-device-only, never-synced Keychain item, reads a missing item as
signed out and logs nothing; every launch restores it after prepare, and the model stores the façade's derived secret.
It reads text only and says nothing about the Keychain on a device."""

import re
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "ios" / "app"
KEYCHAIN = APP / "Online" / "RpcnKeychain.swift"
MODEL = APP / "Online" / "RpcnModel.swift"
LAUNCH_FILES = (APP / "Shell" / "ARMSX3FrontendRoot.swift", APP / "Shell" / "SetupFlow.swift")
# The library's refresh, which runs only after the root has prepared and handed the secret over.
LAUNCH_EXEMPT = ("LibraryModel.swift",)
LAUNCH_WINDOW = 12

PREPARE = "Rpcs3Bridge.prepare()"
RESTORE = "RpcnModel.shared.restoreSecret()"
ACCESSIBLE = "kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly"
OTHER_ACCESSIBLE = re.compile(r"\bkSecAttrAccessible(?!AfterFirstUnlockThisDeviceOnly\b)[A-Z]\w*")
LOGGING = re.compile(r"\b(?:print|NSLog|os_log|Logger)\s*\(|\bos_log\b")
SAVE_CALL = re.compile(r"RpcnKeychain\.save\(\s*npid:\s*([^,]+),\s*secret:\s*([^,]+),\s*token:")


def read(path):
    if not path.is_file():
        raise AssertionError("{0} is missing. FAILURE, not skip.".format(path))
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty. FAILURE, not skip.".format(path))
    return text


def live(source):
    source = re.sub(r"/\*.*?\*/", " ", source, flags=re.DOTALL)
    return re.sub(r"//[^\n]*", " ", source)


def brace_body(text, at):
    """The text from the first `{` at or after `at` to its matching `}`, or "" when unbalanced."""
    start = text.find("{", at)
    if start < 0:
        return ""
    depth = 0
    for index in range(start, len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return text[start:index + 1]
    return ""


def launch_problems(named_launch, other_names_calling_prepare):
    problems = []
    for name, text in named_launch:
        lines = live(text).splitlines()
        sites = [i for i, line in enumerate(lines) if PREPARE in line]
        if not sites:
            problems.append("launch: {0} has no {1} site".format(name, PREPARE))
        for i in sites:
            if not any(RESTORE in line for line in lines[i + 1:i + 1 + LAUNCH_WINDOW]):
                problems.append("launch: {0} line {1}: {2} is not followed by {3}".format(name, i + 1, PREPARE, RESTORE))
    for name in other_names_calling_prepare:
        if name not in LAUNCH_EXEMPT:
            problems.append("launch: {0} calls {1} and hands no secret over".format(name, PREPARE))
    return problems


def keychain_problems(text, model="", named_launch=(), other_prepare=()):
    code = live(text)
    problems = []

    if '"armsx3.rpcn"' not in code or "kSecAttrService" not in code or "kSecClassGenericPassword" not in code \
            or "kSecAttrAccount" not in code:
        problems.append('service: no kSecClassGenericPassword item with service "armsx3.rpcn" keyed by account')

    if ACCESSIBLE not in code or OTHER_ACCESSIBLE.search(code):
        problems.append("accessible: the item is not {0} only".format(ACCESSIBLE))

    if "kSecAttrSynchronizable" in code:
        problems.append("nosync: the file names kSecAttrSynchronizable")

    at = code.find("func load(")
    signature = code[at:code.find("{", at)] if at >= 0 else ""
    body = brace_body(code, at) if at >= 0 else ""
    if not re.search(r"->\s*\(\s*secret:\s*String,\s*token:\s*String\s*\)\?", signature) \
            or not re.search(r"errSecItemNotFound[^\n]*\n?[^\n]*return\s+nil", body) or re.search(r"\bthrows\b", code):
        problems.append("notfound: load does not answer errSecItemNotFound with nil, or the file throws")

    stored = [brace_body(code, m.start()) for m in re.finditer(r"\bstruct\s+\w+\s*:\s*Codable\b", code)]
    if not any(re.search(r"\bsecret\s*:\s*String", s) and re.search(r"\btoken\s*:\s*String", s) for s in stored) \
            or len(re.findall(r"\bSecItemAdd\s*\(", code)) != 1:
        problems.append("oneitem: the secret and token are not one Codable value written by one SecItemAdd")

    if LOGGING.search(code):
        problems.append("noprint: RpcnKeychain.swift logs")

    problems += launch_problems(named_launch, other_prepare)

    model_code = live(model)
    saves = SAVE_CALL.findall(model_code)
    if "Rpcs3Bridge.rpcnSign(in:" not in model_code or not saves \
            or any("password" in secret.lower() for _, secret in saves):
        problems.append("derivedonly: RpcnModel does not keep the façade's returned secret, or keeps the typed password")
    return problems


def real_launch():
    return [(path.name, read(path)) for path in LAUNCH_FILES]


def real_other_prepare():
    launch = {path.name for path in LAUNCH_FILES}
    return [p.name for p in sorted(APP.rglob("*.swift")) if p.name not in launch and PREPARE in live(read(p))]


def real_problems(keychain=None, model=None, named_launch=None, other_prepare=None):
    return keychain_problems(keychain if keychain is not None else read(KEYCHAIN),
                             model if model is not None else read(MODEL),
                             named_launch if named_launch is not None else real_launch(),
                             other_prepare if other_prepare is not None else real_other_prepare())


class TheKeychainItemKeepsItsContract(unittest.TestCase):
    # No docstrings, so `unittest -v` prints `... ok` on the method's own line.
    def test_the_real_files_hold(self):
        self.assertEqual([], real_problems())

    def test_an_empty_input_raises(self):
        with tempfile.NamedTemporaryFile("w", suffix=".swift") as empty:
            with self.assertRaises(AssertionError):
                read(Path(empty.name))


class EachCheckCanFail(unittest.TestCase):
    def edited(self, text, old, new):
        self.assertIn(old, text)
        changed = text.replace(old, new, 1)
        self.assertNotEqual(text, changed)
        return changed

    def fails_with(self, prefix, **inputs):
        found = real_problems(**inputs)
        self.assertTrue(any(p.startswith(prefix + ":") for p in found), found)

    def test_service_renamed_fails(self):
        self.fails_with("service", keychain=self.edited(read(KEYCHAIN), 'service = "armsx3.rpcn"', 'service = "armsx3.other"'))

    def test_accessible_plain_after_first_unlock_fails(self):
        self.fails_with("accessible", keychain=self.edited(read(KEYCHAIN), ACCESSIBLE, "kSecAttrAccessibleAfterFirstUnlock"))

    def test_nosync_synchronizable_true_fails(self):
        keychain = read(KEYCHAIN)
        self.fails_with("nosync", keychain=self.edited(keychain, "kSecAttrAccessible as String:",
                                                       "kSecAttrSynchronizable as String: true, kSecAttrAccessible as String:"))

    def test_notfound_answered_as_an_error_fails(self):
        self.fails_with("notfound", keychain=self.edited(read(KEYCHAIN), "errSecItemNotFound", "errSecSuccess + 1"))

    def test_oneitem_a_second_add_fails(self):
        keychain = read(KEYCHAIN)
        self.fails_with("oneitem", keychain=self.edited(keychain, "SecItemAdd(", "_ = SecItemAdd([:] as CFDictionary, nil); SecItemAdd("))

    def test_noprint_a_print_fails(self):
        keychain = read(KEYCHAIN)
        self.fails_with("noprint", keychain=self.edited(keychain, "static func delete(npid: String) -> Bool {",
                                                        'static func delete(npid: String) -> Bool {\n        print("x")'))

    def test_launch_hand_over_dropped_fails(self):
        named = real_launch()
        name, text = named[1]
        self.fails_with("launch", named_launch=[named[0], (name, self.edited(text, RESTORE, "_ = 0"))])

    def test_launch_a_new_prepare_site_fails(self):
        self.fails_with("launch", other_prepare=real_other_prepare() + ["NewPane.swift"])

    def test_derivedonly_the_typed_password_kept_fails(self):
        model = read(MODEL)
        call = SAVE_CALL.search(live(model))
        self.assertIsNotNone(call)
        self.fails_with("derivedonly", model=self.edited(model, "secret: " + call.group(2).strip(), "secret: password"))


if __name__ == "__main__":
    unittest.main()
