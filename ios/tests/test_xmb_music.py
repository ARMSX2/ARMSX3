#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""XmbPlaylists.swift compiled on the Mac host: playlists, the playing order with shuffle and repeat, the console's
lengths, albums gathered from tags and folders, the cover picked beside the songs, zip entries and free names."""

import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run

IOS = Path(__file__).resolve().parents[1]
SOURCES = [IOS / "app" / "Xmb" / "XmbPlaylists.swift"]

DRIVER = r"""
import Foundation

let reversed: ([Int]) -> [Int] = { $0.reversed() }

var p = XmbPlaylists()
print("P1 \(p.add("a", to: "Mix")) \(p.add("b", to: "Mix")) \(p.add("a", to: "Mix")) \(p.songs("Mix"))")
p.create("Empty")
p.create("Mix")
print("P2 \(p.names) \(p.songs("Mix")) \(p.songs("None"))")
p.remove("a", from: "Mix")
p.delete("Empty")
let back = XmbPlaylists(json: p.json)
print("P3 \(back.names) \(back.songs("Mix")) \(back == p) \(XmbPlaylists(json: Data("junk".utf8)).names)")

var o = XmbPlayOrder()
o.set(count: 4, index: 1)
print("O1 \(o.order) \(o.skip(1, positionMs: 0)) \(o.skip(1, positionMs: 0)) \(o.skip(1, positionMs: 0)) \(o.index)")
print("O2 \(o.skip(-1, positionMs: 4000)) \(o.skip(-1, positionMs: 0))")
o.repeatMode = .off
print("O3 \(o.skip(1, positionMs: 0)) \(o.index) \(o.loops)")
o.rewind()
print("O3b \(o.index) \(o.skip(-1, positionMs: 0))")
var s = XmbPlayOrder()
s.setShuffle(true, shuffled: reversed)
s.set(count: 5, index: 2, shuffled: reversed)
let steps = (0 ..< 5).map { _ in "\(s.skip(1, positionMs: 0, shuffled: reversed))" }
print("O4 \(steps) \(s.order)")
s.setShuffle(false)
print("O5 \(s.order) \(s.index)")
var l = XmbPlayOrder()
var loops = [l.loops, { l.set(count: 1, index: 0); return l.loops }(), { l.repeatMode = .off; return l.loops }()]
l.set(count: 3, index: 9)
l.repeatMode = .one
loops.append(l.loops)
var empty = XmbPlayOrder()
print("O6 \(loops) \(l.index) \(empty.skip(1, positionMs: 0))")

print("T1 [\(XmbTime.length(0))] \(XmbTime.length(212_000)) \(XmbTime.length(3_725_000)) \(XmbTime.length(59_999)) \(XmbTime.length(212_000, hours: "%1h", minutes: "%1m%2s"))")
print("T2 \(XmbTime.clock(167_000)) \(XmbTime.clock(3_725_000)) \(XmbTime.clock(-5))")

func t(_ id: String, _ folder: String?, _ title: String, album: String? = nil, albumArtist: String? = nil, artist: String? = nil,
       disc: Int = 0, number: Int = 0) -> XmbTrack {
    var track = XmbTrack(id: id, name: (id as NSString).lastPathComponent, folder: folder, size: 1, modified: 0, title: title)
    track.album = album
    track.albumArtist = albumArtist
    track.artist = artist
    track.disc = disc
    track.number = number
    return track
}
let tracks = [
    t("Music/A/2.mp3", "Music/A", "Second", album: "Blue", artist: "X", disc: 1, number: 2),
    t("Music/A/1.mp3", "Music/A", "First", album: "Blue", artist: "X", disc: 1, number: 1),
    t("Music/B/1.mp3", "Music/B", "Other blue", album: "blue", artist: "Y", number: 1),
    t("Music/C/x.mp3", "Music/C", "Zed", album: "Comp", albumArtist: "VA", artist: "P", number: 1),
    t("Music/E/s.mp3", "Music/E", "Song"),
    t("Music/D/y.mp3", "Music/D", "Yo", album: "Comp", albumArtist: "va", artist: "Q", number: 2),
    t("Music/loose.mp3", nil, "beta"),
    t("Music/a.mp3", nil, "Alpha"),
    t("Music/F/1.mp3", "Music/F", "Bbb", album: "Duets", artist: "M", number: 1),
    t("Music/F/2.mp3", "Music/F", "Aaa", album: "Duets", artist: "N", number: 1),
]
var (albums, loose) = XmbMusicLibrary.gather(tracks)
for album in albums { print("G \(album.title)|\(album.artist ?? "-")|\(album.tracks.map(\.title))") }
print("G loose \(loose.map(\.title))")
albums[0].cover = "cover.jpg"
let lib = XmbMusicLibrary(albums: albums, loose: loose, looseCovers: ["Music/a.mp3": "a.jpg"], chosen: [albums[0].coverKey])
print("C1 \(lib.cover(of: "Music/A/1.mp3") ?? "-") \(lib.cover(of: "Music/a.mp3") ?? "-") \(lib.cover(of: "Music/loose.mp3") ?? "-") \(lib.coverKey("Music/loose.mp3"))")
print("C2 \(lib.coverKey("Music/A/2.mp3") == albums[0].coverKey) \(lib.isChosen(albums[0].coverKey)) \(lib.isChosen(albums[1].coverKey)) \(lib.inFolder("Music/F").count) \(lib.all.count) \(lib.album(of: "Music/B/1.mp3")?.title ?? "-")")

func cover(_ files: [(String, Int64)]) -> String { XmbMusicLibrary.pickCover(files, name: { $0.0 }, size: { $0.1 })?.0 ?? "-" }
print("K \(cover([("back.jpg", 9), ("Folder.PNG", 1), ("cover.jpg", 1)])) \(cover([("AlbumArt_{X}_Large.jpg", 9), ("AlbumArtSmall.jpg", 1), ("x.jpg", 99)])) \(cover([("AlbumArt_{X}_Large.jpg", 10), ("big.jpg", 99)])) \(cover([("a.jpg", 5), ("b.jpg", 9)])) \(cover([]))")

let entries = ["Album/01.mp3", "__MACOSX/x.mp3", "../x.mp3", "a/.hidden.mp3", "notes.txt", "a\\b\\cover.JPG", "a/b/c/d/e/f.mp3"]
print("Z \(entries.map { XmbMusicLibrary.zipPath($0).map { $0.joined(separator: "/") } ?? "-" })")
let taken: Set<String> = ["song.mp3", "song (1).mp3", "Album", "v1.2"]
print("F \(XmbMusicLibrary.freeName("song.mp3") { taken.contains($0) }) \(XmbMusicLibrary.freeName("Album", isFolder: true) { taken.contains($0) }) \(XmbMusicLibrary.freeName("new.mp3") { taken.contains($0) }) \(XmbMusicLibrary.freeName("v1.2", isFolder: true) { taken.contains($0) })")
print("I \(["a.MP3", "b.m4a", "c.flac", "d.wav", "e"].map(XmbMusicLibrary.formatIcon))")
"""

EXPECTED = [
    'P1 true true false ["a", "b"]',
    'P2 ["Mix", "Empty"] ["a", "b"] []',
    'P3 ["Mix"] ["b"] true []',
    "O1 [0, 1, 2, 3] go(2) go(3) go(0) 0",
    "O2 restart go(3)",
    "O3 none 3 false",
    "O3b 0 none",
    'O4 ["go(4)", "go(3)", "go(1)", "go(0)", "go(4)"] [4, 3, 2, 1, 0]',
    "O5 [0, 1, 2, 3, 4] 4",
    "O6 [true, true, false, true] 2 none",
    "T1 [] 3'32'' 1:02'05'' 0'59'' 3m32s",
    "T2 2:47 1:02:05 0:00",
    'G Blue|X|["First", "Second"]',
    'G blue|Y|["Other blue"]',
    'G Comp|VA|["Zed", "Yo"]',
    'G Duets|-|["Aaa", "Bbb"]',
    'G E|-|["Song"]',
    'G loose ["Alpha", "beta"]',
    "C1 cover.jpg a.jpg - song:Music/loose.mp3",
    "C2 true true false 2 10 blue",
    "K cover.jpg AlbumArtSmall.jpg AlbumArt_{X}_Large.jpg b.jpg -",
    'Z ["Album/01.mp3", "-", "-", "-", "-", "a/b/cover.JPG", "-"]',
    "F song (2).mp3 Album (1) new.mp3 v1.2 (1)",
    'I ["music_tex_mp3", "music_tex_aac", "music_tex_default", "music_tex_wav", "music_tex_default"]',
]


class XmbMusicHost(unittest.TestCase):
    def test_playlists_order_and_library(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = compile_and_run(SOURCES, DRIVER, Path(tmp))
        self.assertEqual(output.strip().splitlines(), EXPECTED)


if __name__ == "__main__":
    unittest.main()
