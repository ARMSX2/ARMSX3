// SPDX-License-Identifier: GPL-2.0-only
// StikDebug's link for this running process: with a pid it attaches without a relaunch, so the game keeps running.

import Foundation

enum JitSecondPassLink {
    static func url(bundleID: String, pid: Int32) -> URL? {
        guard !bundleID.isEmpty, pid > 0 else { return nil }
        var components = URLComponents()
        components.scheme = "stikdebug"
        components.host = "enable-jit"
        components.queryItems = [URLQueryItem(name: "bundle-id", value: bundleID),
                                 URLQueryItem(name: "pid", value: String(pid)),
                                 URLQueryItem(name: "script-name", value: "universal.js")]
        return components.url
    }
}
