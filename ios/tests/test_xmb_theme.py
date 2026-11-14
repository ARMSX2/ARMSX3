#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""XmbTheme.swift's readers compiled on the Mac host against a theme built here: its XMB icons, its own icon and its
stereo sound come out as PNG and WAV, each kept file stands in for the firmware names Android maps it to, a part that
does not read is left out, and a cut or bent theme reads as having none."""

import struct
import tempfile
import unittest
import zlib
from pathlib import Path

from swiftc_host import compile_and_run
from test_xmb_firmware_readers import gim, vag

IOS = Path(__file__).resolve().parents[1]
SOURCES = [IOS / "app" / "Xmb" / "XmbTheme.swift", IOS / "app" / "Xmb" / "Gim.swift", IOS / "app" / "Xmb" / "Vag.swift",
           IOS / "app" / "Themes" / "ThemeBytes.swift"]


def p3t():
    """Icons icon_game, icon_unknown and one pointing past the file table; the info icon; se_cursor in stereo and a
    se_decide whose left channel is no VAG."""
    strings = bytearray()
    ids = bytearray()
    files = bytearray()

    def string(text):
        at = len(strings)
        strings.extend(text.encode() + b"\0")
        return at

    def ident(text):
        at = len(ids)
        ids.extend(b"\0\0\0\0" + text.encode() + b"\0")
        return at

    def blob(data):
        at = len(files)
        files.extend(data)
        return at, len(data)

    def attr(name, kind, a, b=0):
        return struct.pack(">IIII", string(name), kind, a, b)

    def element(name, attrs):
        return struct.pack(">II", string(name), len(attrs)) + bytes(20) + b"".join(attrs)

    red = gim(3, 1, 1, bytes([255, 0, 0, 255]))
    blue = gim(3, 1, 1, bytes([0, 0, 255, 255]))
    elements = b""
    for name, picture in (("icon_game", red), ("icon_unknown", red)):
        at, size = blob(zlib.compress(picture))
        elements += element("icon", [attr("id", 7, ident(name)), attr("src", 6, at, size), attr("size", 1, len(picture))])
    elements += element("icon", [attr("id", 7, ident("icon_music")), attr("src", 6, 1 << 20, 64)])
    at, size = blob(zlib.compress(blue))
    elements += element("info", [attr("icon", 6, at, size), attr("iconsize", 1, len(blue))])
    left, right = blob(vag(1)), blob(vag(0xF))
    elements += element("se", [attr("id", 7, ident("se_cursor")), attr("left", 6, *left), attr("right", 6, *right)])
    bad = blob(b"NOPE" + bytes(60))
    elements += element("se", [attr("id", 7, ident("se_decide")), attr("left", 6, *bad)])

    tables = [bytes(elements), bytes(ids), bytes(strings), b"", b"", bytes(files)]
    header = bytearray(56)
    header[0:4] = b"P3TF"
    at = 56
    body = b""
    for i, table in enumerate(tables):
        struct.pack_into(">II", header, 8 + i * 8, at, len(table))
        body += table
        at += len(table)
    return bytes(header) + body


DRIVER = r"""
import Foundation
import ImageIO

let data = try! Data(contentsOf: URL(fileURLWithPath: "THEME_PATH"))
let out = URL(fileURLWithPath: "OUT_PATH", isDirectory: true)
let theme = DataByteSource(data)
print("A " + XmbThemeAssets.assets(theme).map { "\($0.kind):\($0.id):\($0.parts.count)" }.joined(separator: " "))
print("X \(XmbThemeAssets.extract(theme, into: out))")
let listed = FileManager.default.subpaths(atPath: out.path)!.filter { $0.contains(".") }.sorted()
print("F " + listed.joined(separator: " "))
let found = XmbThemeAssets.files(in: out)
print("I " + found.icons.map { "\($0.key)=\($0.value.lastPathComponent)" }.sorted().joined(separator: " "))
print("S " + found.sounds.map { "\($0.key)=\($0.value.lastPathComponent)" }.sorted().joined(separator: " "))
let counts = XmbThemeAssets.counts(in: out)!
print("C \(counts.icons) \(counts.sounds) \(XmbThemeAssets.counts(in: out.appendingPathComponent("none")) == nil)")
func pixel(_ path: String) -> String {
    let source = CGImageSourceCreateWithURL(out.appendingPathComponent(path) as CFURL, nil)!
    let image = CGImageSourceCreateImageAtIndex(source, 0, nil)!
    var rgba = [UInt8](repeating: 0, count: 4)
    let context = CGContext(data: &rgba, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    context.draw(image, in: CGRect(x: 0, y: 0, width: 1, height: 1))
    return "\(image.width)x\(image.height) \(rgba)"
}
print("P \(pixel("icons/icon_game.png")) \(pixel("icon.png"))")
let wav = try! Data(contentsOf: out.appendingPathComponent("sounds/se_cursor.wav"))
print("W \(String(decoding: wav[0 ..< 4], as: UTF8.self)) \(wav[22]) \(wav.count)")
let cut = DataByteSource(data.prefix(40))
var bent = data
bent[56 + 4] = 0x7F
print("D \(XmbThemeAssets.assets(cut).count) \(XmbThemeAssets.assets(DataByteSource(bent)).count) \(XmbThemeAssets.assets(DataByteSource(Data(repeating: 0, count: 80))).count)")
"""


class XmbThemeTest(unittest.TestCase):
    def test_theme_assets(self):
        with tempfile.TemporaryDirectory() as tmp:
            theme = Path(tmp) / "theme.p3t"
            theme.write_bytes(p3t())
            out = Path(tmp) / "xmb"
            driver = DRIVER.replace("THEME_PATH", str(theme)).replace("OUT_PATH", str(out))
            lines = compile_and_run(SOURCES, driver, tmp).splitlines()
        got = {line[0]: line[2:] for line in lines}
        self.assertEqual(got["A"], "icon:icon_game:1 icon:icon_unknown:1 icon:theme:1 se:se_cursor:2 se:se_decide:1")
        self.assertEqual(got["X"], "true")
        self.assertEqual(got["F"], "icon.png icons/icon_game.png icons/icon_unknown.png sounds/se_cursor.wav")
        self.assertEqual(got["I"], "xmb_ingame/tex_game=icon_game.png")
        self.assertEqual(got["S"], "snd_category_decide=se_cursor.wav snd_cursor=se_cursor.wav")
        self.assertEqual(got["C"], "2 1 true")
        self.assertEqual(got["P"], "1x1 [255, 0, 0, 255] 1x1 [0, 0, 255, 255]")
        wav = got["W"].split()
        self.assertEqual(wav[:2], ["RIFF", "2"])
        self.assertGreater(int(wav[2]), 44)
        self.assertEqual(got["D"], "0 0 0")


if __name__ == "__main__":
    unittest.main()
