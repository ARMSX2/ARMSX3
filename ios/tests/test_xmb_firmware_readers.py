#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""Rco.swift, Gim.swift and Vag.swift compiled on the Mac host and run against a firmware-shaped container built
here: the image, the stereo sound and the English words come out, and a cut or bent file reads as absent."""

import struct
import tempfile
import unittest
import zlib
from pathlib import Path

from swiftc_host import compile_and_run

IOS = Path(__file__).resolve().parents[1]
SOURCES = [IOS / "app" / "Xmb" / "Rco.swift", IOS / "app" / "Xmb" / "Gim.swift", IOS / "app" / "Xmb" / "Vag.swift",
           IOS / "app" / "Themes" / "ThemeBytes.swift"]


def gim(fmt, width, height, pixels, pitch_align=1):
    """A big-endian GIM: root (2) holding a picture (3) holding the image (4), its pixels 0x40 after its header."""
    image = struct.pack(">HHHHHHHH", 0, 0, fmt, 0, width, height, 0, pitch_align) + bytes(0x1C - 16)
    image += struct.pack(">I", 0x40) + bytes(0x40 - 0x20) + pixels
    picture = struct.pack(">HHIII", 4, 0, 16 + len(image), 0, 16) + image
    root = struct.pack(">HHIII", 3, 0, 16 + len(picture), 0, 16) + picture
    return b".GIM1.00" + bytes(8) + struct.pack(">HHIII", 2, 0, 16 + len(root), 0, 16) + root


def vag(nibble, rate=48000):
    frame = bytes([0x00, 0x00]) + bytes([nibble | nibble << 4]) * 14
    data = frame + bytes([0x00, 0x07]) + bytes(14)
    return b"VAGp" + struct.pack(">III", 0x20, 0, len(data)) + struct.pack(">I", rate) + bytes(48 - 20) + data


def entry(label, fields, next_distance):
    head = struct.pack(">II", 0, label) + bytes(8) + struct.pack(">II", 0, next_distance) + bytes(0x28 - 24)
    return head + fields


def table_node(count):
    return bytes(0x10) + struct.pack(">I", count) + bytes(0x28 - 0x14)


def rco():
    """A compressed container with one RGBA image, one stereo sound and one English string."""
    picture = gim(3, 2, 2, bytes([255, 0, 0, 255, 0, 255, 0, 128] + [0] * 8 + [0, 0, 255, 255, 9, 9, 9, 0] + [0] * 8),
                  pitch_align=16)
    packed_picture = zlib.compress(picture)
    left, right = vag(1), vag(0xF)
    words = "Game".encode("utf-16-be")

    labels = b"tex_game\0snd_cursor\0msg_game\0"
    base = 0xA4
    image_table = base
    image_entry = entry(0, struct.pack(">HHIIII", 5, 1, len(packed_picture), 0, 1, len(picture)), 0x40)
    image_entry += bytes(0x40 - len(image_entry))
    sound_table = image_table + 0x28 + len(image_entry)
    sound_entry = entry(9, struct.pack(">HBBIIIIII", 1, 2, 0, len(left) + len(right), 0, len(left), 0,
                                       len(right), len(left)), 0x44)
    text_table = sound_table + 0x28 + len(sound_entry)
    text_entry = entry(0, struct.pack(">HHI", 1, 1, 1) + struct.pack(">III", 20, len(words), 0), 0x3C)
    label_at = text_table + 0x28 + len(text_entry)
    block = table_node(1) + image_entry + table_node(1) + sound_entry + table_node(1) + text_entry + labels
    packed_block = zlib.compress(block)

    data_at = 0xB0 + len(packed_block)
    image_data = data_at
    sound_data = image_data + len(packed_picture)
    text_data = sound_data + len(left) + len(right)
    packed_words = zlib.compress(words)
    text_block = struct.pack(">HHIII", 1, 0, 0, len(packed_words), len(words)) + packed_words

    header = bytearray(0xA4)
    header[0:4] = b"FRP\0"
    struct.pack_into(">II", header, 4, 0x130, 0)
    struct.pack_into(">I", header, 0x0C, 0x10)
    struct.pack_into(">I", header, 0x18, text_table)
    struct.pack_into(">I", header, 0x1C, sound_table)
    struct.pack_into(">I", header, 0x24, image_table)
    struct.pack_into(">II", header, 0x38, text_data, len(text_block))
    struct.pack_into(">I", header, 0x40, label_at)
    struct.pack_into(">I", header, 0x80, image_data)
    struct.pack_into(">I", header, 0x88, sound_data)
    return (bytes(header) + struct.pack(">III", len(packed_block), len(block), 4) + packed_block
            + packed_picture + left + right + text_block)


def dxt5():
    """One 4x4 DXT5 block: alpha 0 everywhere (index 1 of a0=255, a1=0), colour 0 pure red."""
    alpha = bytes([255, 0]) + (0o1111111111111111).to_bytes(6, "little")
    colour = struct.pack("<HHI", 0xF800, 0x001F, 0)
    return gim(10, 4, 4, alpha + colour)


DRIVER = r"""
import Foundation

func bytes(_ name: String) -> [UInt8] { [UInt8](try! Data(contentsOf: URL(fileURLWithPath: @@TMP@@ + "/" + name))) }
func px(_ g: GimImage, _ i: Int) -> String { (0 ..< 4).map { String(g.rgba[i * 4 + $0]) }.joined(separator: ",") }

let file = bytes("fixture.rco")
let r = Rco(bytes: file)!
print("R1 images=\(r.imageNames.sorted()) sounds=\(r.soundNames.sorted())")
let g = GimImage(r.image("tex_game")!)!
print("R2 size=\(g.width)x\(g.height) p0=\(px(g, 0)) p1=\(px(g, 1)) p2=\(px(g, 2)) p3=\(px(g, 3))")
let channels = r.sound("snd_cursor")!.map { Vag.decode($0)! }
print("R3 channels=\(channels.count) rate=\(channels[0].rate) count=\(channels[0].samples.count) l=\(channels[0].samples[0]) r=\(channels[1].samples[27])")
let wav = Vag.wav(channels)!
print("R4 wav=\(wav.count) riff=\(String(decoding: wav.prefix(4), as: UTF8.self)) second=\(Int16(bitPattern: UInt16(wav[46]) | UInt16(wav[47]) << 8))")
print("R5 en=\(r.strings(1)) ja=\(r.strings(0).count) missing=\(r.image("nope") == nil)")
let d = GimImage(bytes("dxt5.gim"))!
print("R6 dxt=\(d.width)x\(d.height) p0=\(px(d, 0)) p15=\(px(d, 15))")
print("R7 cut=\(Rco(bytes: Array(file.prefix(0xC0))) == nil) magic=\(Rco(bytes: [0x46, 0x52, 0x51, 0] + file.dropFirst(4)) == nil) gimCut=\(GimImage(Array(bytes("dxt5.gim").prefix(90))) == nil) vagRate=\(Vag.decode(Array(bytes("left.vag").prefix(16)) + [0, 0, 0, 0] + Array(bytes("left.vag").dropFirst(20))) == nil)")
"""

EXPECTED = [
    "R1 images=[\"tex_game\"] sounds=[\"snd_cursor\"]",
    "R2 size=2x2 p0=255,0,0,255 p1=0,255,0,128 p2=0,0,255,255 p3=9,9,9,0",
    "R3 channels=2 rate=48000 count=28 l=4096 r=-4096",
    "R4 wav=156 riff=RIFF second=-4096",
    "R5 en=[\"msg_game\": \"Game\"] ja=0 missing=true",
    "R6 dxt=4x4 p0=255,0,0,0 p15=255,0,0,0",
    "R7 cut=true magic=true gimCut=true vagRate=true",
]


class XmbFirmwareReadersHost(unittest.TestCase):
    def test_container_image_sound_and_words(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            (root / "fixture.rco").write_bytes(rco())
            (root / "dxt5.gim").write_bytes(dxt5())
            (root / "left.vag").write_bytes(vag(1))
            work = root / "build"
            work.mkdir()
            output = compile_and_run(SOURCES, DRIVER.replace("@@TMP@@", '"{0}"'.format(root)), work)
        self.assertEqual(output.strip().splitlines(), EXPECTED)


if __name__ == "__main__":
    unittest.main()
