import Foundation

enum BundledScript {

    static func source(for script: StikJIT.Script) throws -> String {
        switch script {
        case .custom(let url):
            guard url.isFileURL else {
                throw StikJITError.customScript("expected a file URL")
            }
            do {
                let source = try String(contentsOf: url, encoding: .utf8)
                guard !source.isEmpty else {
                    throw StikJITError.customScript("the file at \(url.path) is empty")
                }
                return source
            } catch let error as StikJITError {
                throw error
            } catch {
                throw StikJITError.customScript("could not read \(url.path): \(error.localizedDescription)")
            }
        case .customBase64(let encodedSource):
            guard let data = Data(base64Encoded: encodedSource) else {
                throw StikJITError.customScript("the provided script data is not valid base64")
            }
            guard let source = String(data: data, encoding: .utf8) else {
                throw StikJITError.customScript("the decoded script data is not valid UTF-8")
            }
            guard !source.isEmpty else {
                throw StikJITError.customScript("the decoded script is empty")
            }
            return source
        case .universal, .legacy:
            break
        }

        let bundle = Bundle(for: BundleToken.self)
        guard let url = bundle.url(forResource: script.name, withExtension: "js"),
              let source = try? String(contentsOf: url, encoding: .utf8),
              !source.isEmpty else {
            throw StikJITError.scriptUnavailable
        }
        return source
    }
}

private final class BundleToken {}
