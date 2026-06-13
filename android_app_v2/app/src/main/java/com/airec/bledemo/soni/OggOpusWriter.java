package com.airec.bledemo.soni;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 把声云录音卡片的 opus 裸帧流（16kHz 单声道、固定 40 字节/帧、每帧 20ms）打包成标准 Ogg Opus 文件。
 *
 * 为什么需要它：声云 SDK 的实时流 / 文件传输回调给的都是【无容器】的 opus 裸帧（160B/包 = 4×40B 帧），
 * ffmpeg/浏览器/ASR 都认不了裸帧；包上 Ogg 容器后变成标准 .ogg(Opus)，后端 /api/consultant/upload
 * 本来就收 ogg、ffprobe 测时长 / ASR 转写全链路直接可用——服务器零改动、端上也不用解码（CPU/体积都省）。
 *
 * 实现遵循 RFC 7845（Ogg 封装 Opus）+ RFC 3533（Ogg 页结构）：
 *  - 页 0：OpusHead（BOS），页 1：OpusTags，之后每页最多 50 帧（=1 秒），末页带 EOS。
 *  - granule position 按 48kHz 计：每 20ms 帧 +960。
 *  - 页校验为 Ogg 专用 CRC32（多项式 0x04C11DB7、初值 0、不反射、无终异或）。
 */
public final class OggOpusWriter {

    private static final int FRAME_BYTES = 40;        // 声云固定帧长
    private static final int SAMPLES_PER_FRAME = 960; // 20ms @ 48kHz（granule 单位）
    private static final int FRAMES_PER_PAGE = 50;    // 1 秒一页

    private static final int[] CRC_TABLE = buildCrcTable();

    private OggOpusWriter() {}

    /**
     * 裸帧文件 → 标准 Ogg Opus 文件。
     * @return 输出文件路径；输入无效 / 写失败返回 null（调用方按"转换失败"处理，不抛）。
     */
    public static String wrapFile(String rawPath, String oggPath) {
        if (rawPath == null || oggPath == null) return null;
        File raw = new File(rawPath);
        if (!raw.exists() || raw.length() < FRAME_BYTES) return null;
        try (FileInputStream in = new FileInputStream(raw);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(oggPath))) {
            int serial = (int) (raw.getName().hashCode() ^ raw.length());
            int pageSeq = 0;
            writePage(out, serial, pageSeq++, 0x02, 0L, new byte[][]{opusHead()});
            writePage(out, serial, pageSeq++, 0x00, 0L, new byte[][]{opusTags()});

            long totalFrames = raw.length() / FRAME_BYTES;   // 末尾不足一帧的散字节丢弃（不可解码）
            byte[][] frames = new byte[FRAMES_PER_PAGE][];
            long written = 0;
            while (written < totalFrames) {
                int n = (int) Math.min(FRAMES_PER_PAGE, totalFrames - written);
                for (int i = 0; i < n; i++) {
                    byte[] f = new byte[FRAME_BYTES];
                    int got = in.read(f);
                    if (got != FRAME_BYTES) { n = i; break; }
                    frames[i] = f;
                }
                if (n <= 0) break;
                written += n;
                byte[][] payload = new byte[n][];
                System.arraycopy(frames, 0, payload, 0, n);
                boolean last = (written >= totalFrames);
                long granule = written * SAMPLES_PER_FRAME;
                writePage(out, serial, pageSeq++, last ? 0x04 : 0x00, granule, payload);
            }
            out.flush();
            return oggPath;
        } catch (IOException e) {
            try { new File(oggPath).delete(); } catch (Exception ignore) {}
            return null;
        }
    }

    /** 裸帧字节数 → 估算秒数（40B=20ms）。 */
    public static int rawBytesToSeconds(long bytes) {
        return (int) (bytes / FRAME_BYTES * 20 / 1000);
    }

    /** OpusHead：version1、单声道、preskip 0、原始采样率 16000、gain 0、mapping family 0。 */
    private static byte[] opusHead() {
        byte[] h = new byte[19];
        System.arraycopy("OpusHead".getBytes(), 0, h, 0, 8);
        h[8] = 1;            // version
        h[9] = 1;            // channels
        h[10] = 0; h[11] = 0; // preskip = 0（硬件编码器无已知 preskip，0 最安全）
        putLE32(h, 12, 16000);
        h[16] = 0; h[17] = 0; // output gain
        h[18] = 0;           // mapping family
        return h;
    }

    private static byte[] opusTags() {
        byte[] vendor = "gongpai-soni".getBytes();
        byte[] t = new byte[8 + 4 + vendor.length + 4];
        System.arraycopy("OpusTags".getBytes(), 0, t, 0, 8);
        putLE32(t, 8, vendor.length);
        System.arraycopy(vendor, 0, t, 12, vendor.length);
        putLE32(t, 12 + vendor.length, 0);   // 0 条 user comment
        return t;
    }

    /** 写一个 Ogg 页：每个 packet 一段 lacing（40 字节 < 255，一段即一包）。 */
    private static void writePage(OutputStream out, int serial, int pageSeq, int headerType,
                                  long granule, byte[][] packets) throws IOException {
        int bodyLen = 0;
        for (byte[] p : packets) bodyLen += p.length;
        int segCount = 0;
        for (byte[] p : packets) segCount += (p.length / 255) + 1;
        byte[] page = new byte[27 + segCount + bodyLen];
        System.arraycopy("OggS".getBytes(), 0, page, 0, 4);
        page[4] = 0;                        // version
        page[5] = (byte) headerType;        // 0x02=BOS 0x04=EOS
        putLE64(page, 6, granule);
        putLE32(page, 14, serial);
        putLE32(page, 18, pageSeq);
        // 22..25 CRC 先置 0
        page[26] = (byte) segCount;
        int idx = 27;
        for (byte[] p : packets) {
            int rem = p.length;
            while (rem >= 255) { page[idx++] = (byte) 255; rem -= 255; }
            page[idx++] = (byte) rem;
        }
        for (byte[] p : packets) {
            System.arraycopy(p, 0, page, idx, p.length);
            idx += p.length;
        }
        int crc = 0;
        for (byte b : page) crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ (b & 0xFF)) & 0xFF];
        putLE32(page, 22, crc);
        out.write(page);
    }

    private static int[] buildCrcTable() {
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int j = 0; j < 8; j++) {
                r = ((r & 0x80000000) != 0) ? (r << 1) ^ 0x04C11DB7 : (r << 1);
            }
            table[i] = r;
        }
        return table;
    }

    private static void putLE32(byte[] b, int off, int v) {
        b[off] = (byte) v; b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16); b[off + 3] = (byte) (v >>> 24);
    }

    private static void putLE64(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) (v >>> (8 * i));
    }
}
