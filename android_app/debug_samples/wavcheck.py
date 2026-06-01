#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
wavcheck.py — 离线 WAV 音频质量快检（录音笔解码 bug 调试用）

只用 Python 标准库：wave / struct / sys / math，无第三方依赖。

用法:
    python3 wavcheck.py some.wav

打印声道数、采样率、采样位宽、帧数、时长、峰值振幅(含满量程%)、
RMS、非零样本占比、削顶样本占比，并给一句启发式结论：
    - 非零占比 < 2%                                   → 疑似静音
    - 削顶占比 > 20%  或  (非零占比 > 95% 且 RMS 很高) → 疑似噪音/乱码
    - 否则                                            → 可能是真实语音

支持 8-bit（无符号）/ 16-bit（有符号小端）PCM。
"""

import wave
import struct
import sys
import math


def analyze(path):
    with wave.open(path, "rb") as wf:
        n_channels = wf.getnchannels()
        sampwidth = wf.getsampwidth()          # 每个样本字节数
        framerate = wf.getframerate()
        n_frames = wf.getnframes()
        raw = wf.readframes(n_frames)

    duration = (n_frames / framerate) if framerate else 0.0

    if sampwidth == 1:
        # 8-bit PCM：无符号，0..255，中点 128
        bits = 8
        full_scale = 128.0                      # 满量程幅度（相对中点）
        clip_threshold = 127                    # |x-128| >= 127 视为削顶
        total = len(raw)
        samples = (b - 128 for b in raw)         # 转成以 0 为中心的有符号值
    elif sampwidth == 2:
        # 16-bit PCM：有符号小端
        bits = 16
        full_scale = 32768.0
        clip_threshold = 32000                  # 按任务要求 |x| >= 32000
        total = len(raw) // 2
        samples = (v[0] for v in struct.iter_unpack("<h", raw))
    else:
        raise ValueError(
            "不支持的采样位宽 %d 字节（仅支持 8-bit / 16-bit PCM）" % sampwidth
        )

    peak = 0
    sumsq = 0.0
    nonzero = 0
    clipped = 0
    count = 0

    for s in samples:
        count += 1
        a = abs(s)
        if a > peak:
            peak = a
        sumsq += float(s) * float(s)
        if s != 0:
            nonzero += 1
        if a >= clip_threshold:
            clipped += 1

    if count == 0:
        rms = 0.0
        nonzero_pct = 0.0
        clipped_pct = 0.0
        peak_pct = 0.0
        rms_pct = 0.0
    else:
        rms = math.sqrt(sumsq / count)
        nonzero_pct = 100.0 * nonzero / count
        clipped_pct = 100.0 * clipped / count
        peak_pct = 100.0 * peak / full_scale
        rms_pct = 100.0 * rms / full_scale

    # 启发式结论
    # RMS "很高" 定义为 RMS 超过满量程的 35%（接近持续大音量 → 更像噪音而非语音）
    rms_high = rms_pct > 35.0
    if nonzero_pct < 2.0:
        verdict = "疑似静音"
    elif clipped_pct > 20.0 or (nonzero_pct > 95.0 and rms_high):
        verdict = "疑似噪音/乱码"
    else:
        verdict = "可能是真实语音"

    print("文件:           %s" % path)
    print("声道数:         %d" % n_channels)
    print("采样率:         %d Hz" % framerate)
    print("采样位宽:       %d 字节 (%d-bit)" % (sampwidth, bits))
    print("帧数:           %d" % n_frames)
    print("时长:           %.3f 秒" % duration)
    print("总样本数:       %d" % count)
    print("峰值绝对振幅:   %d  (%.2f%% 满量程)" % (peak, peak_pct))
    print("RMS:            %.2f  (%.2f%% 满量程)" % (rms, rms_pct))
    print("非零样本占比:   %.2f%%" % nonzero_pct)
    print("接近削顶占比:   %.2f%%  (阈值 |x| >= %d)" % (clipped_pct, clip_threshold))
    print("结论:           %s" % verdict)
    return 0


def main(argv):
    if len(argv) != 2:
        sys.stderr.write("用法: python3 wavcheck.py some.wav\n")
        return 2
    try:
        return analyze(argv[1])
    except wave.Error as e:
        sys.stderr.write("错误: 不是合法的 WAV 文件: %s\n" % e)
        return 1
    except FileNotFoundError:
        sys.stderr.write("错误: 文件不存在: %s\n" % argv[1])
        return 1
    except ValueError as e:
        sys.stderr.write("错误: %s\n" % e)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
