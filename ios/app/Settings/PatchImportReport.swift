// SPDX-License-Identifier: GPL-2.0-only
// What an import of a patch file says, and whether to offer replacing the stored patches; the patches sheet and the XMB share it.

import Foundation

enum PatchImportReport {
    struct Result: Equatable {
        let message: String
        let canReplace: Bool
        let failed: Bool
    }

    static func result(status: Int, imported: Int, total: Int) -> Result {
        if status == -24 {
            return Result(message: String(localized: "That file isn't a patch file this version can read; nothing changed."),
                          canReplace: false, failed: true)
        }
        if status != 0 {
            return Result(message: String(localized: "The imported patches couldn't be updated; nothing changed."),
                          canReplace: false, failed: true)
        }
        if total == 0 {
            return Result(message: String(localized: "That file has no patches in it; nothing changed."), canReplace: false, failed: true)
        }
        if imported == 0 {
            return Result(message: String(localized: "None of the \(total) patches in this file were imported."),
                          canReplace: true, failed: true)
        }
        return Result(message: String(localized: "Patches imported: \(imported) of \(total)."), canReplace: imported < total, failed: false)
    }

    static var replaceQuestion: String {
        String(localized: "Patches with the same names are already stored. Replace them with the versions in this file?")
    }
}
