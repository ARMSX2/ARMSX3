// SPDX-License-Identifier: GPL-2.0-only
// The Problem Report's rules for endings, the last error and listed settings. Foundation only, for the host test.

import Foundation

enum ReportFacts {
    static let format = 2

    static let processName = "ARMSX3"
    static func isOwnCrash(_ crashJSON: String) -> Bool { crashJSON.contains("\"\(processName)\"") }

    static let freezeTailSeconds = 20
    static let jetsamHeadroomMiB = 350.0
    static let renderMinSeconds = 30
    static let stuckBootSeconds = 180.0

    static let endingLabels: [String: String] = [
        "fatal": "Fatal error (crash)",
        "freeze": "Froze, then killed",
        "jetsam": "Likely jetsam (out of memory)",
        "stuck_boot": "Never rendered, killed",
        "left_boot": "Closed during boot",
        "killed_running": "Ended abruptly while running",
        "ended_paused": "Closed while paused or in background",
        "clean_stop": "Stopped normally",
        "no_game": "No game booted",
    ]

    static let meaningfulChannels = ["RSX:", "VK:", "VM:", "PPU:", "SPU:", "SIG:", "LLVM:", "JIT:", "sys_rsx:", "ppu_loader:", "SELF:"]

    // HLE results games hit routinely; skipped like the lv2 results DiagnosticsStore.isHarmless names.
    static let benignPatterns = [
        "'sceNp[A-Za-z0-9_]*' failed with 0x[0-9a-f]{8}",
        "'cellSaveData[A-Za-z0-9_]*' failed with 0x[0-9a-f]{8}",
        "'cellSysutil[A-Za-z0-9_]*' failed with 0x[0-9a-f]{8}",
        "\\} Trophy: ",
    ]
    private static let benign = benignPatterns.map { try! NSRegularExpression(pattern: $0) }

    static func isBenign<S: StringProtocol>(_ line: S) -> Bool {
        let text = String(line)
        return benign.contains { $0.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil }
    }

    // The class an error line names, the first word after its thread: `RSX:` in `·E 0:00:01.0 {RSX} RSX: ...`.
    static func channel<S: StringProtocol>(of line: S) -> String {
        guard let close = line.firstIndex(of: "}") else { return "" }
        let rest = line[line.index(after: close)...].drop { $0 == " " }
        return String(rest.prefix { $0 != " " })
    }

    // Listed even at their defaults, as the dump's `@@`-joined paths.
    static let keySettings = [
        "Core@@PPU Decoder", "Core@@SPU Decoder", "Core@@SPU Block Size", "Core@@Preferred SPU Threads",
        "Core@@Max LLVM Compile Threads", "Core@@SPU XFloat Accuracy", "Core@@Accurate RSX reservation access",
        "Core@@Pause Game During SPU Compilation", "Core@@Clocks scale",
        "Video@@Resolution Scale", "Video@@Write Color Buffers", "Video@@Strict Rendering Mode",
        "Video@@Multithreaded RSX", "Video@@Shader Mode", "Video@@Shader Compiler Threads",
        "Video@@Frame limit", "Video@@Vblank Rate",
    ]

    // shortcut: only the last few MiB are read, so a run whose rendering came before them reads as never rendered.
    static func ending(of text: String, head: String = "") -> String {
        var perf: [(fps: Double, headroom: Double, paused: Bool)] = []
        var critical: [Double] = []
        var title = false, serial = false, fatal = false, stopped = false
        var pausedAt: Double?
        var lastT = 0.0

        for line in (head + "\n" + text).split(separator: "\n", omittingEmptySubsequences: false) where line.contains("SYS: Title: ") || line.contains("SYS: Serial: ") {
            if line.contains("SYS: Title: ") { title = true } else { serial = true }
        }
        for line in text.split(separator: "\n", omittingEmptySubsequences: false) {
            let t = line.hasPrefix("\u{00B7}") ? timestamp(line.prefix(40)) : nil
            if let t { lastT = max(lastT, t) }
            if let mark = line.range(of: "IOSDIAG: perf ") {
                let kv = pairs(line[mark.upperBound...])
                perf.append((Double(kv["fps"] ?? "") ?? 0, Double(kv["headroom_mib"] ?? "") ?? 0, pausedAt != nil))
            } else if line.contains("IOSDIAG: memory-pressure") {
                if pairs(line)["level"] == "critical" { critical.append(t ?? 0) }
            } else if line.contains("Emulation is being paused") {
                if pausedAt == nil { pausedAt = t ?? 0 }
            } else if line.contains("Emulation has been resumed") {
                pausedAt = nil
            } else if line.contains("SYS: Stopping emulator") {
                stopped = true
            } else if line.hasPrefix("\u{00B7}F "), !line.contains("Host backtrace:") {
                fatal = true
            }
        }

        let rendered = perf.filter { $0.fps > 1 && !$0.paused }.count
        let live = perf.filter { !$0.paused }
        let tail = live.suffix(freezeTailSeconds)
        let tailDead = tail.count >= freezeTailSeconds && tail.allSatisfy { $0.fps < 0.5 }
        let lowHeadroom = perf.suffix(10).contains { $0.headroom > 0 && $0.headroom < jetsamHeadroomMiB }
        let criticalLate = critical.contains { $0 >= lastT - 60 }

        if !title && !serial { return "no_game" }
        if fatal { return "fatal" }
        if stopped { return "clean_stop" }
        if lowHeadroom || criticalLate { return "jetsam" }
        if tailDead && rendered >= renderMinSeconds { return "freeze" }
        if rendered < renderMinSeconds && lastT < stuckBootSeconds { return "left_boot" }
        if rendered < renderMinSeconds && (tailDead || perf.isEmpty) { return "stuck_boot" }
        if pausedAt != nil { return "ended_paused" }
        return "killed_running"
    }

    private static let timeShape = try! NSRegularExpression(pattern: "(\\d+):(\\d{2}):(\\d{2})\\.(\\d{1,6})")

    private static func timestamp<S: StringProtocol>(_ text: S) -> Double? {
        let s = String(text)
        guard let m = timeShape.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) else { return nil }
        func group(_ i: Int) -> String { Range(m.range(at: i), in: s).map { String(s[$0]) } ?? "0" }
        let fraction = Double("0." + group(4).padding(toLength: 6, withPad: "0", startingAt: 0)) ?? 0
        return (Double(group(1)) ?? 0) * 3600 + (Double(group(2)) ?? 0) * 60 + (Double(group(3)) ?? 0) + fraction
    }

    private static func pairs<S: StringProtocol>(_ text: S) -> [String: String] {
        var out: [String: String] = [:]
        for token in text.split(separator: " ") {
            guard let eq = token.firstIndex(of: "=") else { continue }
            out[String(token[..<eq])] = String(token[token.index(after: eq)...])
        }
        return out
    }
}
