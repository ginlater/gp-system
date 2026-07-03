import Foundation

/// 把声云录音卡片的 opus 裸帧流(16kHz 单声道、固定 40 字节/帧、20ms/帧)打包成标准 Ogg Opus。
/// android 端对应 `soni/OggOpusWriter.java`。遵循 RFC 7845(Ogg 封装 Opus)+ RFC 3533(Ogg 页)。
/// 声云 SDK 实时流/文件回调都是【无容器】裸帧,包 Ogg 后变标准 .ogg,后端 /upload 直收、ASR 直用,零改动。
enum OggOpusWriter {
    private static let frameBytes = 40
    private static let samplesPerFrame = 960   // 20ms @ 48kHz(granule 单位)
    private static let framesPerPage = 50       // 1 秒一页

    /// 裸帧字节数 → 估算秒数(40B=20ms)。
    static func seconds(_ bytes: Int) -> Int { bytes / frameBytes * 20 / 1000 }

    /// 裸 opus 帧流 → 标准 Ogg Opus 数据。无效返回 nil。
    static func wrap(_ raw: Data) -> Data? {
        guard raw.count >= frameBytes else { return nil }
        var out = Data()
        let serial = UInt32(truncatingIfNeeded: raw.count) ^ 0x676F_6E67   // "gong"
        var seq: UInt32 = 0
        writePage(&out, serial, &seq, 0x02, 0, [opusHead()])   // BOS
        writePage(&out, serial, &seq, 0x00, 0, [opusTags()])

        let totalFrames = raw.count / frameBytes   // 末尾不足一帧丢弃
        var written = 0
        raw.withUnsafeBytes { (rawBuf: UnsafeRawBufferPointer) in
            let base = rawBuf.bindMemory(to: UInt8.self).baseAddress!
            while written < totalFrames {
                let n = min(framesPerPage, totalFrames - written)
                var packets: [[UInt8]] = []
                packets.reserveCapacity(n)
                for i in 0..<n {
                    let start = (written + i) * frameBytes
                    packets.append(Array(UnsafeBufferPointer(start: base + start, count: frameBytes)))
                }
                written += n
                let last = written >= totalFrames
                let granule = UInt64(written * samplesPerFrame)
                writePage(&out, serial, &seq, last ? 0x04 : 0x00, granule, packets)
            }
        }
        return out
    }

    // OpusHead：version1、单声道、preskip 0、原始采样率 16000、gain 0、mapping family 0。
    private static func opusHead() -> [UInt8] {
        var h = [UInt8](repeating: 0, count: 19)
        h.replaceSubrange(0..<8, with: Array("OpusHead".utf8))
        h[8] = 1; h[9] = 1                       // version / channels
        putLE32(&h, 12, 16000)                   // 原始采样率
        return h
    }
    private static func opusTags() -> [UInt8] {
        let vendor = Array("gongpai-soni".utf8)
        var t = [UInt8]("OpusTags".utf8)
        appendLE32(&t, UInt32(vendor.count)); t += vendor
        appendLE32(&t, 0)                         // 0 条 comment
        return t
    }

    private static func writePage(_ out: inout Data, _ serial: UInt32, _ seq: inout UInt32,
                                  _ headerType: UInt8, _ granule: UInt64, _ packets: [[UInt8]]) {
        var page = [UInt8]("OggS".utf8)          // 0..3
        page.append(0)                            // version
        page.append(headerType)                   // 0x02=BOS 0x04=EOS
        for i in 0..<8 { page.append(UInt8(truncatingIfNeeded: granule >> (8 * i))) }   // granule LE64
        appendLE32(&page, serial)
        appendLE32(&page, seq); seq &+= 1
        appendLE32(&page, 0)                      // CRC 占位(22..25)
        // segment table
        var segs: [UInt8] = []
        for p in packets {
            var rem = p.count
            while rem >= 255 { segs.append(255); rem -= 255 }
            segs.append(UInt8(rem))
        }
        page.append(UInt8(segs.count))            // segCount(26)
        page += segs
        for p in packets { page += p }
        // Ogg CRC32(poly 0x04C11DB7,初值0,不反射,无终异或),CRC 字段先为 0
        var crc: UInt32 = 0
        for b in page { crc = (crc << 8) ^ crcTable[Int(((crc >> 24) ^ UInt32(b)) & 0xFF)] }
        page[22] = UInt8(truncatingIfNeeded: crc)
        page[23] = UInt8(truncatingIfNeeded: crc >> 8)
        page[24] = UInt8(truncatingIfNeeded: crc >> 16)
        page[25] = UInt8(truncatingIfNeeded: crc >> 24)
        out.append(contentsOf: page)
    }

    private static func putLE32(_ b: inout [UInt8], _ off: Int, _ v: UInt32) {
        b[off] = UInt8(truncatingIfNeeded: v); b[off+1] = UInt8(truncatingIfNeeded: v >> 8)
        b[off+2] = UInt8(truncatingIfNeeded: v >> 16); b[off+3] = UInt8(truncatingIfNeeded: v >> 24)
    }
    private static func appendLE32(_ b: inout [UInt8], _ v: UInt32) {
        b.append(UInt8(truncatingIfNeeded: v)); b.append(UInt8(truncatingIfNeeded: v >> 8))
        b.append(UInt8(truncatingIfNeeded: v >> 16)); b.append(UInt8(truncatingIfNeeded: v >> 24))
    }

    private static let crcTable: [UInt32] = {
        var t = [UInt32](repeating: 0, count: 256)
        for i in 0..<256 {
            var r = UInt32(i) << 24
            for _ in 0..<8 { r = (r & 0x8000_0000) != 0 ? (r << 1) ^ 0x04C1_1DB7 : (r << 1) }
            t[i] = r
        }
        return t
    }()
}
