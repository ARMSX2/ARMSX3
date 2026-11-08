// SPDX-License-Identifier: GPL-2.0-only
// Screenshots of the next frame into Documents/Screenshots. Photos access is add-only and asked only on a tap.

import Foundation
import Photos
import UIKit

enum ScreenshotStore {
    static var folder: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Screenshots", isDirectory: true)
    }

    // A serial that is not 1-16 ASCII letters and digits becomes ARMSX3.
    static func fileName(serial: String, at date: Date) -> String {
        let ok = (1...16).contains(serial.count) && serial.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber) }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        return (ok ? serial : "ARMSX3") + "_" + formatter.string(from: date) + ".png"
    }

    // Alpha ignored, written atomically; nil when anything fails.
    static func save(rgba: Data, width: Int, height: Int, serial: String, at date: Date = Date()) -> URL? {
        guard width > 0, height > 0, rgba.count == width * height * 4,
              let provider = CGDataProvider(data: rgba as CFData),
              let space = CGColorSpace(name: CGColorSpace.sRGB),
              let image = CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32,
                                  bytesPerRow: width * 4, space: space,
                                  bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                                  provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent),
              let png = UIImage(cgImage: image).pngData() else { return nil }
        let file = folder.appendingPathComponent(fileName(serial: serial, at: date), isDirectory: false)
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try png.write(to: file, options: .atomic)
        } catch {
            return nil
        }
        return file
    }

    // -23 no frame came, -9 not running; a 0 whose save failed comes back with no file.
    static func capture(serial: String) async -> (status: Int, file: URL?) {
        let (rgba, width, height, status) = await Rpcs3Bridge.captureScreenshot()
        let file = status == 0 ? save(rgba: rgba, width: width, height: height, serial: serial) : nil
        return (status, file)
    }

    // Add-only authorization on first use. True when it was added.
    static func saveToPhotos(_ file: URL, type: PHAssetResourceType = .photo) async -> Bool {
        let access = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard access == .authorized || access == .limited else {
            return false
        }
        do {
            try await PHPhotoLibrary.shared().performChanges {
                PHAssetCreationRequest.forAsset().addResource(with: type, fileURL: file, options: nil)
            }
            return true
        } catch {
            return false
        }
    }
}
