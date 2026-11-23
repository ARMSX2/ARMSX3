// SPDX-License-Identifier: GPL-2.0-only
// Moves the core's config entries from Library/Application Support/rpcs3 to Documents/rpcs3, where the Files app shows them.

import Foundation

enum ConfigFolderMigration {
    /// Cache the core and the bridge write through fs::get_cache_dir() and fs::get_log_dir(); it stays in the old folder.
    private static let cacheNames: Set<String> = [
        "cache", "ppu_progs", "spu_progs", "shaderlog", "DEBUG", "ASMJIT",  // compiler, shader and dump caches
        "TTY.log", "RPCS3.log", "RPCS3.log.gz", "RPCS3.buf",
        "rpcs3_vm_sparse.tmp",  // vm_native.cpp
        "framegen_shaders.bin", "lsfg_spirv.cache", "command_dump.log",  // VKFrameGen.cpp, LosslessDll.cpp, rsx_debugger.cpp
        "armsx3-product.yml",  // ios_render.cpp
    ]
    private static let cachePrefixes = [
        "rpcs3-mem-",  // vm_native.cpp
        "audio_",  // AudioDumper.cpp
        "vk_pipeline_cache.bin",  // device.cpp, with its .tmp sibling
    ]

    /// Moves every config entry and returns the ones left in the old folder, each as "relative path: reason".
    /// Call it before the core starts, which reads its config folder once. Nothing is overwritten, copied or followed.
    static func run(home: URL) -> [String] {
        let old = home.appendingPathComponent("Library/Application Support/rpcs3", isDirectory: true).path
        let new = home.appendingPathComponent("Documents/rpcs3", isDirectory: true).path
        var info = stat()
        guard lstat(old, &info) == 0 else { return [] }
        guard (info.st_mode & S_IFMT) == S_IFDIR else { return [". not a directory"] }
        let names: [String]
        do { names = try FileManager.default.contentsOfDirectory(atPath: old) } catch {
            return [". \(error.localizedDescription)"]
        }
        let moving = names.filter { !isCache($0) }
        var kept: [String] = []
        if !moving.isEmpty {
            try? FileManager.default.createDirectory(atPath: new, withIntermediateDirectories: true)
            for name in moving { move(name, from: old, to: new, kept: &kept) }
        }
        rebase(games: new + "/games.yml", from: old + "/", to: new + "/")
        return kept
    }

    /// games.yml holds absolute paths, a disc game booted from dev_hdd0 among them; run even when nothing moved.
    private static func rebase(games path: String, from old: String, to new: String) {
        guard let text = try? String(contentsOfFile: path, encoding: .utf8), text.contains(old) else { return }
        try? text.replacingOccurrences(of: old, with: new).write(toFile: path, atomically: true, encoding: .utf8)
    }

    private static func isCache(_ name: String) -> Bool {
        cacheNames.contains(name) || cachePrefixes.contains { name.hasPrefix($0) }
    }

    private static func kind(_ path: String) -> mode_t? {
        var info = stat()
        return lstat(path, &info) == 0 ? info.st_mode & S_IFMT : nil
    }

    /// `relative` is the entry's path below both roots.
    private static func move(_ relative: String, from oldRoot: String, to newRoot: String, kept: inout [String]) {
        let src = oldRoot + "/" + relative
        let dst = newRoot + "/" + relative
        if renamex_np(src, dst, UInt32(RENAME_EXCL)) == 0 { return }
        let code = errno
        guard code == EEXIST || code == ENOTEMPTY else {
            kept.append("\(relative): \(String(cString: strerror(code)))")
            return
        }
        guard kind(src) == S_IFDIR, kind(dst) == S_IFDIR,
              let children = try? FileManager.default.contentsOfDirectory(atPath: src) else {
            kept.append("\(relative): already in Documents/rpcs3")
            return
        }
        for child in children { move(relative + "/" + child, from: oldRoot, to: newRoot, kept: &kept) }
        _ = rmdir(src)  // a source that still holds a kept entry is not empty, so it stays
    }
}
