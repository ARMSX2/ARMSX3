// SPDX-License-Identifier: GPL-2.0-only
// A PARAM.SFO read from its bytes in rpcs3/Loader/PSF.cpp's format. Anything out of bounds makes the whole file nil.

import Foundation

struct ParamSfo {
    let strings: [String: String]
    let integers: [String: UInt32]

    var title: String? { strings["TITLE"] }
    var subtitle: String? { strings["SUB_TITLE"] }
    var detail: String? { strings["DETAIL"] }
    var savedataDirectory: String? { strings["SAVEDATA_DIRECTORY"] }

    init?(data: Data) {
        let bytes = [UInt8](data)
        func u16(_ at: Int) -> Int { Int(bytes[at]) | Int(bytes[at + 1]) << 8 }
        func u32(_ at: Int) -> Int { u16(at) | u16(at + 2) << 16 }

        guard bytes.count >= 20, bytes[0] == 0, bytes[1] == 0x50, bytes[2] == 0x53, bytes[3] == 0x46,
              u32(4) == 0x101 else { return nil }
        let keyTable = u32(8), dataTable = u32(12), count = u32(16)
        guard keyTable >= 20, keyTable <= dataTable, dataTable <= bytes.count,
              count <= (keyTable - 20) / 16 else { return nil }

        var strings: [String: String] = [:]
        var integers: [String: UInt32] = [:]
        for index in 0..<count {
            let at = 20 + index * 16
            let keyOff = u16(at), format = u16(at + 2), length = u32(at + 4), maximum = u32(at + 8), dataOff = u32(at + 12)
            guard keyOff < dataTable - keyTable, length <= maximum,
                  dataOff <= bytes.count - dataTable, maximum <= bytes.count - dataTable - dataOff else { return nil }
            let keyStart = keyTable + keyOff
            let keyEnd = bytes[keyStart..<dataTable].firstIndex(of: 0) ?? dataTable
            let key = String(decoding: bytes[keyStart..<keyEnd], as: UTF8.self)
            guard !key.isEmpty, strings[key] == nil, integers[key] == nil else { return nil }
            let value = bytes[(dataTable + dataOff)..<(dataTable + dataOff + length)]
            switch format {
            case 0x0404 where length == 4:
                integers[key] = UInt32(u32(dataTable + dataOff))
            case 0x0204, 0x0004:
                strings[key] = String(decoding: value.prefix { $0 != 0 }, as: UTF8.self)
            default:
                continue
            }
        }
        self.strings = strings
        self.integers = integers
    }
}
