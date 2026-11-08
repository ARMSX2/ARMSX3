// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of
// the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.

// Derived from StikJIT's Sources/ScriptRunner.swift at 3623e725 with the full-width address fix; keep the bodies equal to engine/ScriptRunner.swift.

enum BlessCommands {

    static let jitPageSize: UInt64 = 16384
    static let commandsPerBatch = 128

    /// The packed `$M<addr>,1:69#cs` commands and `pageCount + 1` offsets; command `i` spans `offsets[i]..<offsets[i + 1]`.
    static func makeBlessCommands(startAddress: UInt64, pageCount: Int) -> ([UInt8], [Int]) {
        var buffer = [UInt8]()
        buffer.reserveCapacity(pageCount * 26)
        var offsets = [Int]()
        offsets.reserveCapacity(pageCount + 1)

        for page in 0..<pageCount {
            let pageAddress = startAddress + UInt64(page) * jitPageSize
            offsets.append(buffer.count)

            buffer.append(UInt8(ascii: "$"))
            let bodyStart = buffer.count
            buffer.append(UInt8(ascii: "M"))
            writeHexAddress(pageAddress, into: &buffer)
            buffer.append(contentsOf: Array(",1:69".utf8))
            let hashIndex = buffer.count
            buffer.append(contentsOf: [UInt8(ascii: "#"), 0, 0])
            writeChecksum(into: &buffer, bodyStart: bodyStart, hashIndex: hashIndex)
        }
        offsets.append(buffer.count)

        return (buffer, offsets)
    }

    /// Each 128-command batch's byte range, sliced by the recorded offsets because full-width commands vary in length.
    static func batches(offsets: [Int], pageCount: Int) -> [(byteOffset: Int, byteCount: Int)] {
        var result = [(byteOffset: Int, byteCount: Int)]()
        for batchStart in stride(from: 0, to: pageCount, by: commandsPerBatch) {
            let commandsInBatch = min(commandsPerBatch, pageCount - batchStart)
            let byteOffset = offsets[batchStart]
            let byteCount = offsets[batchStart + commandsInBatch] - byteOffset
            result.append((byteOffset: byteOffset, byteCount: byteCount))
        }
        return result
    }

    /// Full width, no leading zeros: the nine-nibble writer this replaces dropped bits 36 and up.
    static func writeHexAddress(_ address: UInt64, into buffer: inout [UInt8]) {
        var top = 15
        while top > 0 && (address >> UInt64(top * 4)) & 0xf == 0 {
            top -= 1
        }
        for nibble in stride(from: top, through: 0, by: -1) {
            buffer.append(hexDigit(UInt8((address >> UInt64(nibble * 4)) & 0xf)))
        }
    }

    static func writeChecksum(into buffer: inout [UInt8], bodyStart: Int, hashIndex: Int) {
        var checksum: UInt8 = 0
        for index in bodyStart..<hashIndex {
            checksum &+= buffer[index]
        }
        buffer[hashIndex + 1] = hexDigit((checksum & 0xf0) >> 4)
        buffer[hashIndex + 2] = hexDigit(checksum & 0x0f)
    }

    static func hexDigit(_ value: UInt8) -> UInt8 {
        value < 10 ? value + UInt8(ascii: "0") : value - 10 + UInt8(ascii: "a")
    }
}
