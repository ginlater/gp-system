package com.airec.bledemo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * ATW/KA 私有 Opus 格式 → 标准 OGG Opus 文件
 *
 * ATW: 帧以 5B 50 分隔，KA: 帧以 4B 41 分隔
 * 每帧约 80 字节 Opus 数据，16kHz 单声道，20ms/帧
 */
public class ATWOpusConverter {

    private static final int CHANNELS = 1;
    // OGG Opus granule position 必须基于 48kHz
    private static final int FRAME_SAMPLES_48K = 960; // 20ms @ 48kHz
    private static final int PRE_SKIP = 312;          // 与 iOS 保持一致（约 6.5ms）
    private static final int MAX_PACKETS_PER_PAGE = 50; // 每页最多50帧（~1秒）

    public static String convert(String srcPath) {
        String outPath = srcPath + ".opus";
        try {
            String format = detectFormat(srcPath);
            if (format == null) {
                android.util.Log.e("AIREC_CONV", "Not ATW or KA");
                return null;
            }
            android.util.Log.d("AIREC_CONV", "Detected format: " + format);

            List<byte[]> frames = extractFrames(srcPath, format);
            if (frames.isEmpty()) return null;

            // 打印前几帧的大小用于调试
            StringBuilder sb = new StringBuilder("Frame sizes: ");
            for (int i = 0; i < Math.min(5, frames.size()); i++) {
                sb.append(frames.get(i).length).append(" ");
            }
            android.util.Log.d("AIREC_CONV", sb.toString());

            writeOggOpus(frames, outPath);
            return outPath;
        } catch (Exception e) {
            android.util.Log.e("AIREC_CONV", "convert failed: " + e.getMessage());
            new File(outPath).delete();
            return null;
        }
    }

    // 注：旧的 integrityWarning(基于"长度非80整数倍"判残缺)已删除——KA .ops 长度本就不一定是80整数倍，
    //   那个判据会把完整下载误判成残缺导致录音存不上。完整性改在 PenController 用"本地字节数 vs 笔报大小"判。

    private static String detectFormat(String path) throws IOException {
        try (FileInputStream fis = new FileInputStream(path)) {
            byte[] h = new byte[2];
            if (fis.read(h) < 2) return null;
            int b0 = h[0] & 0xFF, b1 = h[1] & 0xFF;
            if (b0 == 0x5B && b1 == 0x50) return "ATW";
            if (b0 == 0x4B && b1 == 0x41) return "KA";
        }
        return null;
    }

    private static List<byte[]> extractFrames(String path, String format) throws IOException {
        byte[] raw = readFile(path);

        // ── KA(杰理)专用分帧：固定 80 字节块，每块 = 1 个 Opus 帧(config9 SILK-WB 20ms, TOC=0x48)。
        //    短帧块(头 4B 41)：byte[2]=padlen，payload=block[3 : 3+(77-padlen)]，TOC 被剥离需补回 0x48 再封。
        //    长帧块(头非 4B41)：整 80 字节即完整 opus 包(含TOC)，原样。
        //    ★修：旧实现把 4B41 当"每帧分隔符"算众数 stride——但 4B41 只在【短帧块】出现，长帧块没有，
        //      方向就是错的；且短帧块的补零(padding)没剥、TOC 没补 → 写进 ogg 的 opus 包损坏 = 后段乱码。
        //      KA 本质是固定 80 字节块，这里与 OpusBridge.extractFrames(本地解码路径,参照厂家demo)对齐。
        if ("KA".equals(format)) {
            final int BLK = 80;
            final byte KA_TOC = 0x48;
            List<byte[]> kaFrames = new ArrayList<>();
            int pos = 0;
            while (pos + BLK <= raw.length) {
                int b0 = raw[pos] & 0xFF, b1 = raw[pos + 1] & 0xFF;
                byte[] frame;
                if (b0 == 0x4B && b1 == 0x41) {            // 短帧块：剥 padding、补回 TOC
                    int padlen = raw[pos + 2] & 0xFF;
                    int plen = 77 - padlen;
                    if (plen < 0) plen = 0;
                    if (plen > 77) plen = 77;
                    frame = new byte[1 + plen];
                    frame[0] = KA_TOC;
                    System.arraycopy(raw, pos + 3, frame, 1, plen);
                } else {                                   // 长帧块：整 80 字节即完整 opus 包
                    frame = new byte[BLK];
                    System.arraycopy(raw, pos, frame, 0, BLK);
                }
                kaFrames.add(frame);
                pos += BLK;
            }
            android.util.Log.d("AIREC_CONV", "KA extracted " + kaFrames.size() + " frames (80B blocks)");
            return kaFrames;
        }

        // ── ATW：5B 50 是纯分隔符(每帧都有)，按众数 stride 提取(此法对 ATW 成立) ──
        int sep0 = 0x5B, sep1 = 0x50;
        List<Integer> sepPositions = new ArrayList<>();
        for (int i = 0; i < raw.length - 1; i++) {
            if ((raw[i] & 0xFF) == sep0 && (raw[i + 1] & 0xFF) == sep1) {
                sepPositions.add(i);
            }
        }
        if (sepPositions.size() < 2) {
            android.util.Log.e("AIREC_CONV", "Too few separators: " + sepPositions.size());
            return new ArrayList<>();
        }
        java.util.Map<Integer, Integer> gapCount = new java.util.HashMap<>();
        for (int i = 1; i < sepPositions.size(); i++) {
            int gap = sepPositions.get(i) - sepPositions.get(i - 1);
            gapCount.merge(gap, 1, Integer::sum);
        }
        int stride = 0, maxCount = 0;
        for (java.util.Map.Entry<Integer, Integer> e : gapCount.entrySet()) {
            if (e.getValue() > maxCount) { maxCount = e.getValue(); stride = e.getKey(); }
        }
        int frameDataSize = stride - 2;
        android.util.Log.d("AIREC_CONV", "ATW Frame stride=" + stride + " dataSize=" + frameDataSize);
        if (frameDataSize < 10 || frameDataSize > 500) {
            android.util.Log.e("AIREC_CONV", "Invalid frame size: " + frameDataSize);
            return new ArrayList<>();
        }
        List<byte[]> frames = new ArrayList<>();
        int pos = sepPositions.get(0);
        while (pos + stride <= raw.length) {
            byte[] frame = new byte[frameDataSize];
            System.arraycopy(raw, pos + 2, frame, 0, frameDataSize);   // 5B 50 是纯分隔符，帧数据在其后
            frames.add(frame);
            pos += stride;
        }
        android.util.Log.d("AIREC_CONV", "ATW extracted " + frames.size() + " frames");
        return frames;
    }

    private static void writeOggOpus(List<byte[]> frames, String outPath) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(outPath)) {
            int serialNo = 0x41495243; // "AIRC"
            int seqNo = 0;

            // Page 1: OpusHead (BOS)
            writeOggPage(fos, buildOpusHead(), serialNo, 0, seqNo++, true, false);

            // Page 2: OpusTags
            writeOggPage(fos, buildOpusTags(), serialNo, 0, seqNo++, false, false);

            // Audio pages: 每页最多 MAX_PACKETS_PER_PAGE 个 packet
            long granulePos = PRE_SKIP; // 从 pre-skip 开始
            int i = 0;
            while (i < frames.size()) {
                int count = Math.min(MAX_PACKETS_PER_PAGE, frames.size() - i);
                granulePos += (long) count * FRAME_SAMPLES_48K;
                boolean eos = (i + count >= frames.size());

                byte[][] packets = new byte[count][];
                for (int j = 0; j < count; j++) {
                    packets[j] = frames.get(i + j);
                }
                writeOggPageMulti(fos, packets, serialNo, granulePos, seqNo++, eos);
                i += count;
            }
        }
        android.util.Log.d("AIREC_CONV", "OGG written: " + outPath
                + " size=" + new File(outPath).length());
    }

    private static byte[] buildOpusHead() {
        ByteBuffer b = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        b.put("OpusHead".getBytes());
        b.put((byte) 1);           // version
        b.put((byte) CHANNELS);
        b.putShort((short) PRE_SKIP);
        b.putInt(48000);            // sample rate（OGG Opus 标准要求 48kHz）
        b.putShort((short) 0);      // output gain
        b.put((byte) 0);            // channel mapping family
        return b.array();
    }

    private static byte[] buildOpusTags() {
        String vendor = "AIREC";
        ByteBuffer b = ByteBuffer.allocate(8 + 4 + vendor.length() + 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        b.put("OpusTags".getBytes());
        b.putInt(vendor.length());
        b.put(vendor.getBytes());
        b.putInt(0);
        return b.array();
    }

    // ─── OGG 页写入 ─────────────────────────────────────────────────────────

    private static void writeOggPage(FileOutputStream fos, byte[] packet,
            int serialNo, long granulePos, int seqNo,
            boolean bos, boolean eos) throws IOException {
        writeOggPageMulti(fos, new byte[][]{packet}, serialNo, granulePos, seqNo,
                bos, eos);
    }

    private static void writeOggPageMulti(FileOutputStream fos, byte[][] packets,
            int serialNo, long granulePos, int seqNo, boolean eos) throws IOException {
        writeOggPageMulti(fos, packets, serialNo, granulePos, seqNo, false, eos);
    }

    private static void writeOggPageMulti(FileOutputStream fos, byte[][] packets,
            int serialNo, long granulePos, int seqNo,
            boolean bos, boolean eos) throws IOException {

        // Segment table (lacing values)
        List<Byte> segTable = new ArrayList<>();
        int totalData = 0;
        for (byte[] pkt : packets) {
            int rem = pkt.length;
            while (rem >= 255) {
                segTable.add((byte) 255);
                rem -= 255;
            }
            segTable.add((byte) rem);
            totalData += pkt.length;
        }

        int numSegs = segTable.size();

        // Page header
        ByteBuffer hdr = ByteBuffer.allocate(27 + numSegs).order(ByteOrder.LITTLE_ENDIAN);
        hdr.put(new byte[]{'O','g','g','S'});
        hdr.put((byte) 0); // version
        byte type = 0;
        if (bos) type |= 0x02;
        if (eos) type |= 0x04;
        hdr.put(type);
        hdr.putLong(granulePos);
        hdr.putInt(serialNo);
        hdr.putInt(seqNo);
        hdr.putInt(0); // CRC placeholder
        hdr.put((byte) numSegs);
        for (byte s : segTable) hdr.put(s);

        // Assemble full page
        byte[] page = new byte[27 + numSegs + totalData];
        byte[] hdrBytes = hdr.array();
        System.arraycopy(hdrBytes, 0, page, 0, hdrBytes.length);
        int pos = hdrBytes.length;
        for (byte[] pkt : packets) {
            System.arraycopy(pkt, 0, page, pos, pkt.length);
            pos += pkt.length;
        }

        // CRC
        int crc = oggCrc32(page);
        page[22] = (byte) (crc);
        page[23] = (byte) (crc >> 8);
        page[24] = (byte) (crc >> 16);
        page[25] = (byte) (crc >> 24);

        fos.write(page);
    }

    // ─── 工具 ────────────────────────────────────────────────────────────────

    private static byte[] readFile(String path) throws IOException {
        File f = new File(path);
        byte[] data = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) {
            int off = 0;
            while (off < data.length) {
                int n = fis.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
        }
        return data;
    }

    private static final int[] CRC_TABLE = buildCrcTable();
    private static int[] buildCrcTable() {
        int[] t = new int[256];
        for (int i = 0; i < 256; i++) {
            int c = i << 24;
            for (int j = 0; j < 8; j++)
                c = (c & 0x80000000) != 0 ? (c << 1) ^ 0x04c11db7 : c << 1;
            t[i] = c;
        }
        return t;
    }
    private static int oggCrc32(byte[] data) {
        int c = 0;
        for (byte b : data) c = (c << 8) ^ CRC_TABLE[((c >>> 24) ^ (b & 0xFF)) & 0xFF];
        return c;
    }
}
