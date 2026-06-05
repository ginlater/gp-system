#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 AIREC 录音笔(USB 模式挂成 U 盘)里 /RECORD/*.ops 的录音全部解码成 wav 导出到电脑。
.ops 是杰理 KA 私有 opus 封装(80字节块/块=1帧 SILK-WB 20ms，TOC 0x48 被剥离)，本脚本就地解码。
依赖：macOS 自带 python3 + libopus(brew install opus，路径 /opt/homebrew/lib/libopus.dylib)。
用法：python3 导出录音笔.py   (自动找 U 盘；导出到 ~/Desktop/录音笔导出/)
"""
import ctypes, struct, math, os, glob, sys

LIBOPUS = "/opt/homebrew/lib/libopus.dylib"
OUT_DIR = os.path.expanduser("~/Desktop/录音笔导出")

def load_opus():
    o = ctypes.CDLL(LIBOPUS)
    o.opus_decoder_create.restype = ctypes.c_void_p
    o.opus_decoder_create.argtypes = [ctypes.c_int32, ctypes.c_int, ctypes.POINTER(ctypes.c_int)]
    o.opus_decode.restype = ctypes.c_int
    o.opus_decode.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int32,
                              ctypes.POINTER(ctypes.c_int16), ctypes.c_int, ctypes.c_int]
    return o

def find_pen_record_dirs():
    """找所有挂载卷里含 RECORD/*.ops 的目录。"""
    dirs = []
    for vol in glob.glob("/Volumes/*"):
        rec = os.path.join(vol, "RECORD")
        if os.path.isdir(rec) and glob.glob(os.path.join(rec, "*.ops")):
            dirs.append(rec)
    return dirs

def decode_ka(raw, opus):
    """KA 封装 → 16k 单声道 PCM(bytes)。返回 (pcm, ok, bad)。"""
    N = len(raw); FRAME = 5760; TOC = 0x48
    err = ctypes.c_int(0)
    dec = opus.opus_decoder_create(16000, 1, ctypes.byref(err))
    pcm = (ctypes.c_int16 * FRAME)()
    out = bytearray(); ok = bad = 0; pos = 0
    while pos + 80 <= N:
        b0, b1 = raw[pos], raw[pos + 1]
        if b0 == 0x4B and b1 == 0x41:                      # 短帧块：补回 TOC
            pad = raw[pos + 2]; plen = max(0, min(77, 77 - pad))
            fr = bytes([TOC]) + raw[pos + 3:pos + 3 + plen]
        else:                                              # 长帧块：整块即完整 opus 包
            fr = raw[pos:pos + 80]
        n = opus.opus_decode(dec, fr, len(fr), pcm, FRAME, 0)
        if n > 0:
            ok += 1
            for i in range(n):
                out += struct.pack('<h', pcm[i])
        else:
            bad += 1
        pos += 80
    opus.opus_decoder_destroy.argtypes = [ctypes.c_void_p]
    try: opus.opus_decoder_destroy(dec)
    except Exception: pass
    return bytes(out), ok, bad

def write_wav(path, pcm, sr=16000, ch=1):
    data = len(pcm)
    with open(path, 'wb') as w:
        w.write(b'RIFF'); w.write(struct.pack('<I', 36 + data)); w.write(b'WAVE')
        w.write(b'fmt '); w.write(struct.pack('<IHHIIHH', 16, 1, ch, sr, sr*ch*2, ch*2, 16))
        w.write(b'data'); w.write(struct.pack('<I', data)); w.write(pcm)

def nice_name(ops_name):
    """20260604233655.ops → 2026-06-04_23-36-55"""
    base = os.path.splitext(os.path.basename(ops_name))[0]
    d = ''.join(c for c in base if c.isdigit())[:14]
    if len(d) == 14:
        return f"{d[0:4]}-{d[4:6]}-{d[6:8]}_{d[8:10]}-{d[10:12]}-{d[12:14]}"
    return base

def main():
    if not os.path.exists(LIBOPUS):
        print("缺 libopus，请先：brew install opus"); sys.exit(1)
    opus = load_opus()
    dirs = find_pen_record_dirs()
    if not dirs:
        print("没找到录音笔(U盘里没有 RECORD/*.ops)。确认笔已开USB模式并插好。"); sys.exit(1)
    os.makedirs(OUT_DIR, exist_ok=True)
    total = 0
    for rec in dirs:
        files = sorted(glob.glob(os.path.join(rec, "*.ops")))
        print(f"\n== {rec} （{len(files)} 段）==")
        for f in files:
            raw = open(f, 'rb').read()
            pcm, ok, bad = decode_ka(raw, opus)
            sec = len(pcm) // 2 / 16000
            # 简单判人声/静音：算整体 RMS
            import audioop
            rms = audioop.rms(pcm, 2) if pcm else 0
            tag = "🔊有声" if rms > 300 else "🔈很安静"
            out = os.path.join(OUT_DIR, nice_name(f) + ".wav")
            write_wav(out, pcm)
            total += 1
            print(f"  {os.path.basename(f):24s} → {nice_name(f)}.wav  {sec:6.1f}s  帧{ok}/{ok+bad}  RMS{rms} {tag}")
    print(f"\n完成：共导出 {total} 段到 {OUT_DIR}")
    print("用 访达 打开该文件夹双击播放即可。")

if __name__ == '__main__':
    main()
