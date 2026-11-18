#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""PairingFileCheck.swift, run alone on the Mac host over synthetic fixtures, accepts the RP pairing shape in both plist
formats, names an old lockdown record, refuses any missing or misshapen field and tells which files carry lockdown keys.
It does not mirror idevice's Ed25519 point check, so a well-shaped file with bad key bytes passes here."""

import os
import plistlib
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run

CHECK = Path(__file__).resolve().parents[1] / "app" / "JIT" / "PairingFileCheck.swift"


def key():
    return os.urandom(32)


FIXTURES = {
    "valid-xml": (plistlib.dumps({"public_key": key(), "private_key": key(), "identifier": "synthetic-host"}), "valid"),
    "valid-binary": (plistlib.dumps({"public_key": key(), "private_key": key(), "identifier": "synthetic-host"},
                                    fmt=plistlib.FMT_BINARY), "valid"),
    "valid-extra": (plistlib.dumps({"public_key": key(), "private_key": key(), "identifier": "x",
                                    "alt_irk": os.urandom(16)}), "valid"),
    "merged": (plistlib.dumps({"public_key": key(), "private_key": key(), "identifier": "synthetic-host",
                               "HostCertificate": b"synthetic", "SystemBUID": "SYNTHETIC-BUID"}), "valid"),
    "lockdown": (plistlib.dumps({"HostCertificate": b"synthetic", "DeviceCertificate": b"synthetic",
                                 "HostPrivateKey": b"synthetic", "SystemBUID": "SYNTHETIC-BUID",
                                 "HostID": "SYNTHETIC-HOST"}), "legacyLockdown"),
    "no-private": (plistlib.dumps({"public_key": key(), "identifier": "x"}), "invalid"),
    "short-key": (plistlib.dumps({"public_key": os.urandom(31), "private_key": key(), "identifier": "x"}), "invalid"),
    "string-key": (plistlib.dumps({"public_key": "not data", "private_key": key(), "identifier": "x"}), "invalid"),
    "no-identifier": (plistlib.dumps({"public_key": key(), "private_key": key()}), "invalid"),
    "array": (plistlib.dumps([1, 2, 3]), "invalid"),
    "garbage": (os.urandom(64), "invalid"),
    "empty": (b"", "invalid"),
}

# The fixtures that carry lockdown keys; every other fixture carries none.
CARRIES = {"merged", "lockdown"}

DRIVER = r'''
import Foundation
let folder = URL(fileURLWithPath: "@@DIR@@")
for name in try! FileManager.default.contentsOfDirectory(atPath: folder.path).sorted() where name.hasSuffix(".bin") {
    let data = try! Data(contentsOf: folder.appendingPathComponent(name))
    print("\(name.dropLast(4))=\(PairingFileCheck.classify(data)),\(PairingFileCheck.carriesLockdownKeys(data))")
}
'''


class TheShapeCheckHolds(unittest.TestCase):
    def test_fixtures(self):
        with tempfile.TemporaryDirectory() as tmp, tempfile.TemporaryDirectory() as fixtures:
            for name, (data, _) in FIXTURES.items():
                Path(fixtures, name + ".bin").write_bytes(data)
            out = compile_and_run([CHECK], DRIVER.replace("@@DIR@@", fixtures), tmp)
        got = dict(line.split("=", 1) for line in out.strip().split("\n"))
        want = {name: "{0},{1}".format(verdict, "true" if name in CARRIES else "false") for name, (_, verdict) in FIXTURES.items()}
        self.assertEqual(want, got)


if __name__ == "__main__":
    unittest.main()
