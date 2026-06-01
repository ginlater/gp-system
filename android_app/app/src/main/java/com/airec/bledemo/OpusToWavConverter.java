package com.airec.bledemo;

import android.util.Log;

import com.droidkit.opus.OpusLib;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * ATW/KA → WAV 转换器
 *
 * 流程：ATW/KA → ATWOpusConverter 转 OGG Opus → droidkit OpusLib 解码 → WAV
 *
 * OpusLib 内置 libopus native 库，不依赖设备 MediaCodec，任何设备都能解码。
 */
public class OpusToWavConverter {

    private static final String TAG = "AIREC_WAV";
    private static final int SAMPLE_RATE = 48000; // OpusLib 输出固定 48kHz
    private static final int CHANNELS = 1;
    private static final int BITS_PER_SAMPLE = 16;
    // 读取缓冲区：960 samples * 2 bytes * 1 channel = 1920 bytes
    private static final int READ_BUF_SIZE = 960 * 2;

    /**
     * 转换 ATW/KA 文件为 WAV，返回 .wav 路径，失败返回 null
     */
    public static String convert(String srcPath) {
        String wavPath = srcPath + ".wav";
        try {
            // 第一步：ATW/KA → OGG Opus
            String ogg = ATWOpusConverter.convert(srcPath);
            if (ogg == null) {
                Log.e(TAG, "ATW→OGG failed");
                return null;
            }
            Log.d(TAG, "OGG: " + ogg + " " + new File(ogg).length() + "B");

            // 第二步：OGG Opus → PCM → WAV（用 OpusLib native 解码）
            boolean ok = decodeOggToWav(ogg, wavPath);
            if (!ok) {
                Log.e(TAG, "OGG→WAV failed");
                new File(wavPath).delete();
                return null;
            }

            Log.d(TAG, "WAV: " + wavPath + " " + new File(wavPath).length() + "B");
            return wavPath;
        } catch (Exception e) {
            Log.e(TAG, "convert failed", e);
            new File(wavPath).delete();
            return null;
        }
    }

    private static boolean decodeOggToWav(String oggPath, String wavPath) {
        OpusLib lib = new OpusLib();
        FileOutputStream fos = null;
        try {
            // 检查是否是有效的 Opus 文件
            int isOpus = lib.isOpusFile(oggPath);
            if (isOpus == 0) {
                Log.e(TAG, "Not a valid opus file: " + oggPath);
                return false;
            }

            // 打开文件
            int openResult = lib.openOpusFile(oggPath);
            if (openResult == 0) {
                Log.e(TAG, "Failed to open opus file");
                return false;
            }

            long totalPcmDuration = lib.getTotalPcmDuration();
            Log.d(TAG, "Opus duration: " + totalPcmDuration + " samples");

            // 先写一个占位 WAV 头（44 字节），后面回填大小
            fos = new FileOutputStream(wavPath);
            byte[] placeholderHeader = new byte[44];
            fos.write(placeholderHeader);

            // 读取 PCM 数据
            ByteBuffer readBuf = ByteBuffer.allocateDirect(READ_BUF_SIZE);
            int totalBytes = 0;

            while (lib.getFinished() == 0) {
                readBuf.clear();
                lib.readOpusFile(readBuf, READ_BUF_SIZE);
                int position = readBuf.position();
                if (position <= 0) {
                    // readOpusFile 可能不更新 position，用 getFinished 判断
                    // 写入整个 buffer
                    readBuf.rewind();
                    byte[] chunk = new byte[READ_BUF_SIZE];
                    readBuf.get(chunk);
                    fos.write(chunk);
                    totalBytes += READ_BUF_SIZE;
                } else {
                    readBuf.flip();
                    byte[] chunk = new byte[position];
                    readBuf.get(chunk);
                    fos.write(chunk);
                    totalBytes += position;
                }
            }

            lib.closeOpusFile();
            fos.close();

            if (totalBytes == 0) {
                Log.e(TAG, "No PCM data decoded");
                return false;
            }

            Log.d(TAG, "Decoded " + totalBytes + " PCM bytes");

            // 回填 WAV 头
            writeWavHeader(wavPath, totalBytes);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "decodeOgg error", e);
            try { lib.closeOpusFile(); } catch (Exception ignored) {}
            if (fos != null) try { fos.close(); } catch (Exception ignored) {}
            return false;
        }
    }

    private static void writeWavHeader(String path, int dataSize) throws IOException {
        int byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8;
        int blockAlign = CHANNELS * BITS_PER_SAMPLE / 8;

        ByteBuffer hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        hdr.put("RIFF".getBytes());
        hdr.putInt(dataSize + 36);
        hdr.put("WAVE".getBytes());
        hdr.put("fmt ".getBytes());
        hdr.putInt(16);
        hdr.putShort((short) 1);
        hdr.putShort((short) CHANNELS);
        hdr.putInt(SAMPLE_RATE);
        hdr.putInt(byteRate);
        hdr.putShort((short) blockAlign);
        hdr.putShort((short) BITS_PER_SAMPLE);
        hdr.put("data".getBytes());
        hdr.putInt(dataSize);

        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(path, "rw");
        raf.seek(0);
        raf.write(hdr.array());
        raf.close();
    }
}
