package com.airec.bledemo;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * 统一音频转换器：任意格式 → WAV
 *
 * ATW/KA 私有格式：固定步长提取帧 → OGG Opus → droidkit/MediaCodec 解码 → WAV
 * 标准格式：MediaExtractor + MediaCodec → WAV
 * WAV：直接返回
 */
public class AudioConverter {

    private static final String TAG = "AIREC_CONV";
    private static final int BITS = 16;

    public static String toWav(String srcPath) {
        if (srcPath == null || !new File(srcPath).exists()) return null;
        if (isWav(srcPath)) return srcPath;

        String wavPath = srcPath + ".wav";
        // 强制重新生成，不使用缓存
        new File(wavPath).delete();
        new File(srcPath + ".fixed.ogg").delete();
        new File(srcPath + ".fixed.opus").delete();
        new File(srcPath + ".opus").delete();

        if (isPrivateFormat(srcPath)) {
            return convertPrivate(srcPath, wavPath);
        }
        return convertStandard(srcPath, wavPath);
    }

    // ─── ATW/KA 私有格式 ────────────────────────────────────────────────────

    private static String convertPrivate(String srcPath, String wavPath) {
        try {
            // 固定步长提取帧 → OGG → droidkit 解码
            String result = decodeWithFixedStride(srcPath, wavPath);
            if (result != null) return result;

            // 第二优先：原始 ATWOpusConverter → OGG → MediaCodec
            Log.w(TAG, "FixedStride failed, trying ATWOpusConverter...");
            new File(wavPath).delete();
            String ogg = ATWOpusConverter.convert(srcPath);
            if (ogg != null) {
                String r = convertStandard(ogg, wavPath);
                if (r != null && new File(wavPath).length() > 44) return r;
            }

            // 第三优先：直接返回 OGG 文件路径让 ExoPlayer 播放
            // ExoPlayer 内置 Opus 软解码器，不走 MediaCodec，可能不会崩
            String fixedOgg = srcPath + ".fixed.ogg";
            if (new File(fixedOgg).exists() && new File(fixedOgg).length() > 100) {
                Log.w(TAG, "All WAV conversions failed, returning OGG for ExoPlayer: " + fixedOgg);
                return fixedOgg;
            }
            String fixedOpus = srcPath + ".fixed.opus";
            if (new File(fixedOpus).exists() && new File(fixedOpus).length() > 100) {
                Log.w(TAG, "All WAV conversions failed, returning OGG for ExoPlayer: " + fixedOpus);
                return fixedOpus;
            }
            String atwOgg = srcPath + ".opus";
            if (new File(atwOgg).exists() && new File(atwOgg).length() > 100) {
                Log.w(TAG, "All WAV conversions failed, returning OGG for ExoPlayer: " + atwOgg);
                return atwOgg;
            }

            Log.e(TAG, "All decode methods failed for: " + srcPath);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "convertPrivate failed", e);
            new File(wavPath).delete();
            return null;
        }
    }

    /**
     * 固定步长提取帧 → OGG Opus → droidkit OpusLib 解码 → WAV
     * 解决 KA 格式分隔符 4B41 在 Opus 数据中频繁出现导致误切的问题。
     */
    private static String decodeWithFixedStride(String srcPath, String wavPath) {
        try {
            String format = detectFormat(srcPath);
            if (format == null) return null;

            byte[] raw = readAllBytes(srcPath);
            int sep0 = format.equals("ATW") ? 0x5B : 0x4B;
            int sep1 = format.equals("ATW") ? 0x50 : 0x41;

            // 扫描所有分隔符位置
            List<Integer> sepPos = new ArrayList<>();
            for (int i = 0; i < raw.length - 1; i++) {
                if ((raw[i] & 0xFF) == sep0 && (raw[i + 1] & 0xFF) == sep1) {
                    sepPos.add(i);
                }
            }
            if (sepPos.size() < 2) return null;

            // 计算间距众数 → 真实帧步长
            java.util.Map<Integer, Integer> gapCount = new java.util.HashMap<>();
            for (int i = 1; i < sepPos.size(); i++) {
                gapCount.merge(sepPos.get(i) - sepPos.get(i - 1), 1, Integer::sum);
            }
            int stride = 0, maxCnt = 0;
            for (java.util.Map.Entry<Integer, Integer> e : gapCount.entrySet()) {
                if (e.getValue() > maxCnt) { maxCnt = e.getValue(); stride = e.getKey(); }
            }
            int frameDataSize = stride - 2;
            Log.d(TAG, "FixedStride: stride=" + stride + " data=" + frameDataSize + " gaps=" + gapCount);
            if (frameDataSize < 10 || frameDataSize > 500) return null;

            // 按固定步长提取帧（包含 4B/5B 41/50 头字节，让 droidkit 能识别）
            List<byte[]> frames = new ArrayList<>();
            int pos = sepPos.get(0);
            while (pos + stride <= raw.length) {
                byte[] frame = new byte[Math.min(stride, raw.length - pos)];
                System.arraycopy(raw, pos, frame, 0, frame.length);
                frames.add(frame);
                pos += stride;
            }
            if (frames.isEmpty()) return null;
            Log.d(TAG, "FixedStride: " + frames.size() + " frames");

            // 生成 OGG Opus（用 .ogg 扩展名，droidkit 可能检查扩展名）
            String oggPath = srcPath + ".fixed.ogg";
            new File(oggPath).delete();
            // 也删除旧的 .opus 文件
            new File(srcPath + ".fixed.opus").delete();
            writeOggOpus(frames, oggPath);

            // 尝试 droidkit 解码
            String droidkitResult = decodeDroidkit(oggPath, wavPath);
            if (droidkitResult != null && isValidWav(wavPath)) {
                Log.d(TAG, "FixedStride→droidkit OK: " + new File(wavPath).length() + "B");
                return droidkitResult;
            }
            // droidkit 解码可能产生噪音，验证失败则删除
            if (droidkitResult != null) {
                Log.w(TAG, "droidkit WAV may be invalid, discarding");
                new File(wavPath).delete();
            }

            // droidkit 失败或噪音，尝试 MediaCodec
            Log.w(TAG, "droidkit failed, trying MediaCodec on fixed OGG...");
            new File(wavPath).delete();
            String mcResult = convertStandard(oggPath, wavPath);
            if (mcResult != null && new File(wavPath).length() > 44) {
                Log.d(TAG, "FixedStride→MediaCodec OK: " + new File(wavPath).length() + "B");
                return mcResult;
            }

            return null;
        } catch (Exception e) {
            Log.w(TAG, "decodeWithFixedStride failed: " + e.getMessage());
            new File(wavPath).delete();
            return null;
        }
    }

    private static String decodeDroidkit(String oggPath, String wavPath) {
        com.droidkit.opus.OpusLib lib;
        try {
            lib = new com.droidkit.opus.OpusLib();
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "OpusLib native not available");
            return null;
        }

        // 打印 OGG 文件头用于调试
        try (FileInputStream fis = new FileInputStream(oggPath)) {
            byte[] hdr = new byte[100];
            int read = fis.read(hdr);
            StringBuilder sb = new StringBuilder("OGG dump (" + read + "B): ");
            for (int i = 0; i < Math.min(read, 60); i++) {
                sb.append(String.format("%02X ", hdr[i] & 0xFF));
            }
            Log.d(TAG, sb.toString());
            // 找 OpusHead 位置
            for (int i = 0; i < read - 8; i++) {
                if (hdr[i]=='O' && hdr[i+1]=='p' && hdr[i+2]=='u' && hdr[i+3]=='s'
                        && hdr[i+4]=='H' && hdr[i+5]=='e' && hdr[i+6]=='a' && hdr[i+7]=='d') {
                    StringBuilder oh = new StringBuilder("OpusHead @" + i + ": ");
                    for (int j = i; j < Math.min(i + 19, read); j++) {
                        oh.append(String.format("%02X ", hdr[j] & 0xFF));
                    }
                    Log.d(TAG, oh.toString());
                    break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to dump OGG header", e);
        }

        int isOpus = lib.isOpusFile(oggPath);
        Log.d(TAG, "droidkit isOpusFile=" + isOpus + " path=" + oggPath);
        if (isOpus == 0) {
            // 验证 OGG 结构：逐页检查
            try (FileInputStream fis2 = new FileInputStream(oggPath)) {
                byte[] all = new byte[(int) new File(oggPath).length()];
                fis2.read(all);
                int pageIdx = 0;
                int off = 0;
                while (off + 27 < all.length) {
                    if (all[off]!='O'||all[off+1]!='g'||all[off+2]!='g'||all[off+3]!='S') {
                        Log.e(TAG, "Bad OggS at offset " + off);
                        break;
                    }
                    int nSegs = all[off + 26] & 0xFF;
                    if (off + 27 + nSegs > all.length) break;
                    int dataLen = 0;
                    for (int s = 0; s < nSegs; s++) dataLen += all[off + 27 + s] & 0xFF;
                    int pageLen = 27 + nSegs + dataLen;

                    // 验证 CRC
                    int storedCrc = (all[off+22]&0xFF) | ((all[off+23]&0xFF)<<8)
                            | ((all[off+24]&0xFF)<<16) | ((all[off+25]&0xFF)<<24);
                    byte[] pageCopy = new byte[pageLen];
                    System.arraycopy(all, off, pageCopy, 0, pageLen);
                    pageCopy[22]=0; pageCopy[23]=0; pageCopy[24]=0; pageCopy[25]=0;
                    int calcCrc = oggCrc(pageCopy);
                    boolean crcOk = (storedCrc == calcCrc);

                    Log.d(TAG, "Page[" + pageIdx + "] off=" + off + " len=" + pageLen
                            + " segs=" + nSegs + " data=" + dataLen
                            + " crc=" + (crcOk ? "OK" : "MISMATCH stored="
                            + Integer.toHexString(storedCrc) + " calc=" + Integer.toHexString(calcCrc)));

                    off += pageLen;
                    pageIdx++;
                    if (pageIdx > 5) break; // 只检查前几页
                }
            } catch (Exception ex) {
                Log.e(TAG, "OGG verify failed", ex);
            }
            return null;
        }

        FileOutputStream fos = null;
        try {
            int opened = lib.openOpusFile(oggPath);
            if (opened == 0) return null;

            fos = new FileOutputStream(wavPath);
            fos.write(new byte[44]);

            int bufSize = 960 * 2;
            ByteBuffer buf = ByteBuffer.allocateDirect(bufSize);
            byte[] chunk = new byte[bufSize];
            int total = 0;
            long dur = lib.getTotalPcmDuration();
            Log.d(TAG, "droidkit totalPcmDuration=" + dur);
            // 不依赖 dur 计算 maxLoops，直接用 getFinished() 判断
            int maxLoops = 100000; // 足够大的上限

            for (int i = 0; lib.getFinished() == 0 && i < maxLoops; i++) {
                buf.rewind();
                lib.readOpusFile(buf, bufSize);
                buf.rewind();
                buf.get(chunk);
                fos.write(chunk);
                total += bufSize;
            }
            Log.d(TAG, "droidkit decoded: " + total + "B");
            lib.closeOpusFile();
            fos.close();
            fos = null;

            if (total == 0) { new File(wavPath).delete(); return null; }
            // 尝试 16kHz（原始采样率）和 48kHz 两种，先用 16kHz
            fillWavHeader(wavPath, total, 16000, 1);
            Log.d(TAG, "droidkit WAV: " + total + "B @ 16kHz");
            return wavPath;
        } catch (Exception e) {
            Log.w(TAG, "droidkit decode error: " + e.getMessage());
            try { lib.closeOpusFile(); } catch (Exception ignored) {}
            if (fos != null) try { fos.close(); } catch (Exception ignored) {}
            new File(wavPath).delete();
            return null;
        }
    }

    // ─── OGG Opus 写入（与 iOS 参数一致）────────────────────────────────────

    private static void writeOggOpus(List<byte[]> frames, String outPath) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(outPath)) {
            int serial = 0x12345678;
            int seq = 0;
            int preSkip = 0;

            // OpusHead
            byte[] head = new byte[19];
            ByteBuffer hb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            hb.put("OpusHead".getBytes()); hb.put((byte)1); hb.put((byte)1);
            hb.putShort((short)preSkip); hb.putInt(48000); hb.putShort((short)0); hb.put((byte)0);
            writeOggPage(fos, new byte[][]{head}, serial, 0, seq++, true, false);

            // OpusTags
            String vendor = "AIREC";
            byte[] tags = new byte[8 + 4 + vendor.length() + 4];
            ByteBuffer tb = ByteBuffer.wrap(tags).order(ByteOrder.LITTLE_ENDIAN);
            tb.put("OpusTags".getBytes()); tb.putInt(vendor.length());
            tb.put(vendor.getBytes()); tb.putInt(0);
            writeOggPage(fos, new byte[][]{tags}, serial, 0, seq++, false, false);

            // Audio
            long granule = preSkip;
            int i = 0;
            while (i < frames.size()) {
                int cnt = Math.min(50, frames.size() - i);
                granule += (long) cnt * 960;
                byte[][] pkts = new byte[cnt][];
                for (int j = 0; j < cnt; j++) pkts[j] = frames.get(i + j);
                writeOggPage(fos, pkts, serial, granule, seq++, false, i + cnt >= frames.size());
                i += cnt;
            }
        }
    }

    private static void writeOggPage(FileOutputStream fos, byte[][] packets,
            int serial, long granule, int seq, boolean bos, boolean eos) throws IOException {
        List<Byte> segs = new ArrayList<>();
        int dataLen = 0;
        for (byte[] p : packets) {
            int r = p.length;
            while (r >= 255) { segs.add((byte)255); r -= 255; }
            segs.add((byte)r);
            dataLen += p.length;
        }
        int nSegs = segs.size();
        byte[] page = new byte[27 + nSegs + dataLen];
        ByteBuffer h = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN);
        h.put(new byte[]{'O','g','g','S'}); h.put((byte)0);
        h.put((byte)((bos ? 0x02 : 0) | (eos ? 0x04 : 0)));
        h.putLong(granule); h.putInt(serial); h.putInt(seq);
        h.putInt(0); h.put((byte)nSegs);
        for (byte s : segs) h.put(s);
        int off = 27 + nSegs;
        for (byte[] p : packets) { System.arraycopy(p, 0, page, off, p.length); off += p.length; }
        int crc = oggCrc(page);
        page[22]=(byte)crc; page[23]=(byte)(crc>>8); page[24]=(byte)(crc>>16); page[25]=(byte)(crc>>24);
        fos.write(page);
    }

    // ─── 标准格式（mp3/aac/flac/ogg/m4a）───────────────────────────────────

    private static String convertStandard(String srcPath, String wavPath) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        FileOutputStream fos = null;
        try {
            extractor.setDataSource(srcPath);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) return null;

            int sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            Log.d(TAG, "Standard: " + mime + " " + sr + "Hz " + ch + "ch");

            extractor.selectTrack(track);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();

            fos = new FileOutputStream(wavPath);
            fos.write(new byte[44]);
            int total = 0;
            boolean inDone = false, outDone = false;

            while (!outDone) {
                if (!inDone) {
                    int idx = codec.dequeueInputBuffer(10000);
                    if (idx >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(idx);
                        int size = extractor.readSampleData(inBuf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            codec.queueInputBuffer(idx, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int idx = codec.dequeueOutputBuffer(info, 10000);
                if (idx >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        codec.releaseOutputBuffer(idx, false);
                        outDone = true;
                    } else if (info.size > 0) {
                        ByteBuffer outBuf = codec.getOutputBuffer(idx);
                        byte[] pcm = new byte[info.size];
                        outBuf.position(info.offset);
                        outBuf.get(pcm);
                        fos.write(pcm);
                        total += pcm.length;
                        codec.releaseOutputBuffer(idx, false);
                    } else {
                        codec.releaseOutputBuffer(idx, false);
                    }
                }
            }
            try { MediaFormat of = codec.getOutputFormat();
                sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            } catch (Exception ignored) {}

            codec.stop(); codec.release(); codec = null;
            extractor.release(); fos.close(); fos = null;

            if (total == 0) { new File(wavPath).delete(); return null; }
            fillWavHeader(wavPath, total, sr, ch);
            return wavPath;
        } catch (Exception e) {
            Log.e(TAG, "convertStandard failed", e);
            if (codec != null) { try{codec.stop();}catch(Exception ignored){} try{codec.release();}catch(Exception ignored){} }
            extractor.release();
            if (fos != null) try { fos.close(); } catch (Exception ignored) {}
            new File(wavPath).delete();
            return null;
        }
    }

    // ─── 工具 ────────────────────────────────────────────────────────────────

    private static String detectFormat(String path) {
        try (FileInputStream fis = new FileInputStream(path)) {
            byte[] h = new byte[2];
            if (fis.read(h) == 2) {
                int b0 = h[0] & 0xFF, b1 = h[1] & 0xFF;
                if (b0 == 0x5B && b1 == 0x50) return "ATW";
                if (b0 == 0x4B && b1 == 0x41) return "KA";
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static byte[] readAllBytes(String path) throws IOException {
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

    private static void fillWavHeader(String path, int dataSize, int sr, int ch) throws IOException {
        int byteRate = sr * ch * BITS / 8;
        int blockAlign = ch * BITS / 8;
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()); h.putInt(dataSize + 36);
        h.put("WAVE".getBytes()); h.put("fmt ".getBytes());
        h.putInt(16); h.putShort((short)1); h.putShort((short)ch);
        h.putInt(sr); h.putInt(byteRate);
        h.putShort((short)blockAlign); h.putShort((short)BITS);
        h.put("data".getBytes()); h.putInt(dataSize);
        try (RandomAccessFile raf = new RandomAccessFile(path, "rw")) {
            raf.seek(0); raf.write(h.array());
        }
    }

    static boolean isWav(String path) {
        try (FileInputStream fis = new FileInputStream(path)) {
            byte[] h = new byte[12];
            if (fis.read(h) < 12) return false;
            return h[0]=='R'&&h[1]=='I'&&h[2]=='F'&&h[3]=='F'
                    &&h[8]=='W'&&h[9]=='A'&&h[10]=='V'&&h[11]=='E';
        } catch (Exception e) { return false; }
    }

    static boolean isPrivateFormat(String path) {
        return detectFormat(path) != null;
    }

    /**
     * 检查 WAV 文件是否包含有效音频（非全静音/非全噪音）
     */
    private static boolean isValidWav(String path) {
        try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
            if (raf.length() < 1000) return false;
            raf.seek(44); // 跳过 WAV 头
            byte[] sample = new byte[2000];
            int read = raf.read(sample);
            if (read < 100) return false;
            // 检查是否全是 0 或全是极端值（噪音特征）
            int zeros = 0, extremes = 0;
            for (int i = 0; i + 1 < read; i += 2) {
                short s = (short)((sample[i] & 0xFF) | ((sample[i+1] & 0xFF) << 8));
                if (s == 0) zeros++;
                if (Math.abs(s) > 30000) extremes++;
            }
            int totalSamples = read / 2;
            // 如果超过 80% 是极端值，可能是噪音
            if (extremes > totalSamples * 0.8) {
                Log.w(TAG, "WAV looks like noise: extremes=" + extremes + "/" + totalSamples);
                return false;
            }
            return true;
        } catch (Exception e) { return false; }
    }

    private static final int[] OGG_CRC = buildOggCrc();
    private static int[] buildOggCrc() {
        int[] t = new int[256];
        for (int i = 0; i < 256; i++) {
            int c = i << 24;
            for (int j = 0; j < 8; j++) c = (c & 0x80000000) != 0 ? (c << 1) ^ 0x04c11db7 : c << 1;
            t[i] = c;
        }
        return t;
    }
    private static int oggCrc(byte[] data) {
        int c = 0;
        for (byte b : data) c = (c << 8) ^ OGG_CRC[((c >>> 24) ^ (b & 0xFF)) & 0xFF];
        return c;
    }
}
