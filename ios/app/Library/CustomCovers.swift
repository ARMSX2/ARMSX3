// SPDX-License-Identifier: GPL-2.0-only
// The player's own covers, re-encoded as JPEG. A download sends only the serial, and only on the player's tap.

import Foundation
import UIKit

@MainActor enum CustomCovers {
    static let maxDownload = 10 * 1024 * 1024

    static var folder: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("armsx3", isDirectory: true)
            .appendingPathComponent("covers", isDirectory: true)
    }

    static func isValidSerial(_ serial: String) -> Bool {
        (1...16).contains(serial.count) && serial.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber) }
    }

    private static func file(_ serial: String, _ ext: String) -> URL {
        folder.appendingPathComponent(serial + "." + ext, isDirectory: false)
    }

    static func customPath(for serial: String) -> String? {
        guard isValidSerial(serial) else { return nil }
        return ["jpg", "png"].map { file(serial, $0).path }.first { FileManager.default.fileExists(atPath: $0) }
    }

    static func path(for serial: String, fallback: String?) -> String? {
        customPath(for: serial) ?? fallback
    }

    /// The covers' file names, read once so a long list looks each game up without a disk check.
    static func names() -> Set<String> {
        Set((try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? [])
    }

    static func path(for serial: String, fallback: String?, in names: Set<String>) -> String? {
        guard isValidSerial(serial) else { return fallback }
        return ["jpg", "png"].first { names.contains(serial + "." + $0) }.map { file(serial, $0).path } ?? fallback
    }

    // Keeps `data` as the title's cover when it decodes as an image. True when written.
    @discardableResult
    static func set(_ data: Data, serial: String, fallback: String? = nil) -> Bool {
        guard isValidSerial(serial), let image = UIImage(data: data), let jpeg = image.jpegData(compressionQuality: 0.9) else {
            return false
        }
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try jpeg.write(to: file(serial, "jpg"), options: .atomic)
            try? FileManager.default.removeItem(at: file(serial, "png"))
        } catch {
            return false
        }
        forget(serial: serial, fallback: fallback)
        return true
    }

    // Returns the sentence to show.
    static func download(serial: String, fallback: String? = nil) async -> String {
        let failed = String(localized: "Couldn't download the cover. Check your connection and try again.")
        let none = String(localized: "No cover was found for this game.")
        guard isValidSerial(serial),
              let url = URL(string: "https://raw.githubusercontent.com/aldostools/Resources/main/COV/\(serial).JPG") else {
            return none
        }
        var request = URLRequest(url: url, timeoutInterval: 20)
        request.setValue("ARMSX3", forHTTPHeaderField: "User-Agent")
        guard let (body, response) = try? await URLSession.shared.data(for: request) else {
            return failed
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 404 {
            return none
        }
        // shortcut: URLSession.data buffers the whole body before the cap is checked; stream it if a hostile response matters.
        guard (200..<300).contains(status), body.count <= maxDownload else {
            return failed
        }
        return set(body, serial: serial, fallback: fallback) ? String(localized: "Cover updated.") : none
    }

    static func remove(serial: String, fallback: String? = nil) {
        guard isValidSerial(serial) else { return }
        try? FileManager.default.removeItem(at: file(serial, "jpg"))
        try? FileManager.default.removeItem(at: file(serial, "png"))
        forget(serial: serial, fallback: fallback)
    }

    private static func forget(serial: String, fallback: String?) {
        CoverImageView.forget(path: file(serial, "jpg").path)
        CoverImageView.forget(path: file(serial, "png").path)
        if let fallback { CoverImageView.forget(path: fallback) }
    }
}
