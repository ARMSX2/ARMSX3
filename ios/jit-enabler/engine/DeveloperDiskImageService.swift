import Foundation

public struct DDIPaths: Sendable {

    public var imagePath: String

    public var trustcachePath: String

    public var manifestPath: String

    public var cryptexInfoPath: String

    public var rootHashPath: String

    public init(imagePath: String,
                trustcachePath: String,
                manifestPath: String,
                cryptexInfoPath: String? = nil,
                rootHashPath: String? = nil) {
        self.imagePath = imagePath
        self.trustcachePath = trustcachePath
        self.manifestPath = manifestPath
        self.cryptexInfoPath = cryptexInfoPath ?? "\(imagePath).cryptex_info"
        self.rootHashPath = rootHashPath ?? "\(imagePath).root_hash"
    }

    public static func `default`(in directory: URL) -> DDIPaths {
        DDIPaths(
            imagePath: directory.appendingPathComponent("DDI/Image.dmg").path,
            trustcachePath: directory.appendingPathComponent("DDI/Image.dmg.trustcache").path,
            manifestPath: directory.appendingPathComponent("DDI/BuildManifest.plist").path)
    }

    var allFilesUsable: Bool {
        let fileManager = FileManager.default
        let requiredPaths = DDIMountMethod.current == .cryptex ? allPaths : personalizedPaths
        let requiredFilesUsable = requiredPaths.allSatisfy { path in
            guard fileManager.isReadableFile(atPath: path),
                  let attributes = try? fileManager.attributesOfItem(atPath: path),
                  attributes[.type] as? FileAttributeType == .typeRegular,
                  let size = attributes[.size] as? NSNumber else {
                return false
            }
            return size.int64Value > 0
        }
        if DDIMountMethod.current == .personalized {
            return requiredFilesUsable && cryptexOnlyPaths.allSatisfy { !fileManager.fileExists(atPath: $0) }
        }
        return requiredFilesUsable
    }

    var allPaths: [String] {
        personalizedPaths + cryptexOnlyPaths
    }

    var personalizedPaths: [String] {
        [imagePath, trustcachePath, manifestPath]
    }

    var cryptexOnlyPaths: [String] {
        [cryptexInfoPath, rootHashPath]
    }

    func removeCachedFiles() throws {
        let fileManager = FileManager.default
        for path in Set(allPaths) where fileManager.fileExists(atPath: path) {
            try fileManager.removeItem(atPath: path)
        }
    }

    func removeCryptexOnlyFiles() throws {
        let fileManager = FileManager.default
        for path in Set(cryptexOnlyPaths) where fileManager.fileExists(atPath: path) {
            try fileManager.removeItem(atPath: path)
        }
    }
}

enum DDIMountMethod: String {
    case cryptex
    case personalized

    static var current: DDIMountMethod {
        let version = ProcessInfo.processInfo.operatingSystemVersion
        return version.majorVersion > 26 || (version.majorVersion == 26 && version.minorVersion >= 4)
            ? .cryptex
            : .personalized
    }
}

struct DDIDownloadItem {
    let name: String
    let destinationPath: String
    let url: URL
}

enum DDIDownloadCatalog {
    static func items(for paths: DDIPaths) -> [DDIDownloadItem] {
        let directory = DDIMountMethod.current == .cryptex ? "Xcode_iOS_DDI_Cryptex" : "Xcode_iOS_DDI_Personalized"
        let baseURL = URL(string: "https://github.com/doronz88/DeveloperDiskImage/raw/refs/heads/main/PersonalizedImages/\(directory)")!
        var items = [
            DDIDownloadItem(name: "BuildManifest.plist", destinationPath: paths.manifestPath, url: baseURL.appendingPathComponent("BuildManifest.plist")),
            DDIDownloadItem(name: "Image.dmg", destinationPath: paths.imagePath, url: baseURL.appendingPathComponent("Image.dmg")),
            DDIDownloadItem(name: "Image.dmg.trustcache", destinationPath: paths.trustcachePath, url: baseURL.appendingPathComponent("Image.dmg.trustcache")),
        ]
        if DDIMountMethod.current == .cryptex {
            items.append(DDIDownloadItem(name: "Image.dmg.cryptex_info", destinationPath: paths.cryptexInfoPath, url: baseURL.appendingPathComponent("Image.dmg.cryptex_info")))
            items.append(DDIDownloadItem(name: "Image.dmg.root_hash", destinationPath: paths.rootHashPath, url: baseURL.appendingPathComponent("Image.dmg.root_hash")))
        }
        return items
    }
}

@available(iOS 17.4, *)
public actor DeveloperDiskImageService {

    private static let sharedInstance = DeveloperDiskImageService()

    public static var shared: DeveloperDiskImageService { sharedInstance }

    private let session: URLSession

    public init(session: URLSession = .shared) {
        self.session = session
    }

    public func downloadIfNeeded(to paths: DDIPaths, progress: @escaping (Double, String) -> Void = { _, _ in }) async throws {
        guard !paths.allFilesUsable else { return }
        try await download(to: paths, progress: progress)
    }

    public func download(to paths: DDIPaths, progress: @escaping (Double, String) -> Void = { _, _ in }) async throws {
        let items = DDIDownloadCatalog.items(for: paths)

        let total = Double(items.count)
        for (index, item) in items.enumerated() {
            progress(Double(index) / total, "Downloading \(item.name)...")
            try await downloadFile(from: item.url, to: URL(fileURLWithPath: item.destinationPath))
            progress(Double(index + 1) / total, "\(item.name) ready")
        }
        if DDIMountMethod.current == .personalized {
            try paths.removeCryptexOnlyFiles()
        }
    }

    private func downloadFile(from url: URL, to destinationURL: URL) async throws {
        let (temporaryURL, response) = try await session.download(from: url)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw StikJITError.ddiDownload("invalid response for \(url.absoluteString)")
        }
        guard (200..<300).contains(httpResponse.statusCode) else {
            throw StikJITError.ddiDownload("HTTP \(httpResponse.statusCode) for \(url.absoluteString)")
        }

        let fileManager = FileManager.default
        try fileManager.createDirectory(at: destinationURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        if fileManager.fileExists(atPath: destinationURL.path) {
            try fileManager.removeItem(at: destinationURL)
        }
        try fileManager.moveItem(at: temporaryURL, to: destinationURL)
    }
}
