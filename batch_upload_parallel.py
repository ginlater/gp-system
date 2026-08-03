#!/usr/bin/env python3
"""
批量上传录音脚本（并行版）

文件名格式：顾客_顾问_录音YYYYMMDDHHMMSS_时长.mp3
用法：
    python batch_upload_parallel.py --username admin --password xxx
    python batch_upload_parallel.py --username admin --password xxx --workers 4
    python batch_upload_parallel.py --username admin --password xxx --dry-run

优化点（vs 旧版）：
  1. 每个 worker 复用一个登录会话，不再每个 group 重新登录（原来 90 组 = 90 次登录）。
  2. 不再上传完立刻 POST /analyze —— 服务端 ASR 完成会自动入队分析，
     新版 webapp.py 已经把分析限到全局 4 并发 FIFO，平滑消化。
  3. 默认 workers=4，避免上传通道过载抢占带宽/内存。
"""

import argparse
import re
import sys
import threading
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from pathlib import Path

import requests

FILENAME_RE = re.compile(
    r"^(?P<customer>[^-_/]+)[-_](?P<advisor>[^-_/]+)[-_]录音(?P<ts>\d{14})[-_](?P<dur>.+?)\.[A-Za-z0-9]{1,5}$"
)
AUDIO_EXTS = {".mp3", ".wav", ".m4a", ".flac", ".aac", ".ogg", ".opus"}

print_lock = threading.Lock()
_tls = threading.local()  # 每个 worker 线程一份 session


def log(msg: str):
    with print_lock:
        print(msg, flush=True)


def parse_filename(filename: str):
    name = Path(filename).name
    m = FILENAME_RE.match(name)
    if not m:
        return None
    ts = m.group("ts")
    try:
        dt = datetime.strptime(ts, "%Y%m%d%H%M%S")
        recorded_at = dt.strftime("%Y-%m-%d %H:%M:%S")
        service_date = dt.strftime("%Y-%m-%d")
    except ValueError:
        return None
    return {
        "customer": m.group("customer"),
        "advisor": m.group("advisor"),
        "recorded_at": recorded_at,
        "service_date": service_date,
        "duration_label": m.group("dur"),
    }


def _login(base_url: str, username: str, password: str) -> requests.Session:
    s = requests.Session()
    resp = s.post(
        f"{base_url}/login",
        data={"username": username, "password": password},
        allow_redirects=False,
        timeout=15,
    )
    if resp.status_code not in (200, 302):
        raise RuntimeError(f"登录失败 HTTP {resp.status_code}")
    check = s.get(f"{base_url}/api/me", timeout=10)
    if check.status_code != 200:
        raise RuntimeError("登录后验证失败")
    return s


def get_worker_session(base_url: str, username: str, password: str) -> requests.Session:
    """每个 worker 线程持有一个 requests.Session，全程复用。"""
    s = getattr(_tls, "session", None)
    if s is None:
        s = _login(base_url, username, password)
        _tls.session = s
    return s


def session_exists(http: requests.Session, base_url: str, advisor: str, customer: str, date: str) -> bool:
    resp = http.get(
        f"{base_url}/api/sessions",
        params={"advisor": advisor, "customer": customer, "date": date, "page_size": 1},
        timeout=10,
    )
    if resp.status_code != 200:
        log(f"  [WARN] 查询 session 失败 HTTP {resp.status_code}，保守跳过")
        return True
    return resp.json().get("total", 0) > 0


def upload_file(http: requests.Session, base_url: str, filepath: Path) -> dict | None:
    with open(filepath, "rb") as f:
        resp = http.post(
            f"{base_url}/api/upload",
            files={"files": (filepath.name, f, "audio/mpeg")},
            timeout=180,
        )
    if resp.status_code != 200:
        log(f"    [ERROR] 上传失败 HTTP {resp.status_code}: {resp.text[:200]}")
        return None
    created = resp.json().get("created", [])
    if not created:
        log(f"    [ERROR] 上传返回为空")
        return None
    return created[0]


def process_group(key, items, base_url, username, password, group_idx, total_groups):
    customer, advisor, date = key
    label = f"[{group_idx}/{total_groups}] 顾客={customer} 顾问={advisor} 日期={date} ({len(items)}段)"

    try:
        http = get_worker_session(base_url, username, password)
    except RuntimeError as e:
        log(f"{label}\n  [ERROR] 登录失败: {e}")
        return {"skipped": 0, "uploaded": 0, "failed": 0, "session_ids": set()}

    if session_exists(http, base_url, advisor, customer, date):
        log(f"{label}\n  [SKIP] 已存在，跳过")
        return {"skipped": 1, "uploaded": 0, "failed": 0, "session_ids": set()}

    log(f"{label}")
    uploaded = 0
    failed = 0
    sids: set = set()

    for p, meta in sorted(items, key=lambda x: x[1]["recorded_at"]):
        log(f"  上传: {p.name} ...")
        result = upload_file(http, base_url, p)
        if result is None:
            failed += 1
        else:
            uploaded += 1
            sids.add(result["session_id"])
            log(f"  OK: {p.name} (recording_id={result['id']}, session_id={result['session_id']})")

    # 注意：不再手动 POST /analyze。
    # 服务端 ASR pipeline 会在所有 recording ASR=done 时自动触发分析，
    # 分析任务统一进入全局 4 并发 FIFO 队列（见 webapp.py: submit_analysis）。
    return {"skipped": 0, "uploaded": uploaded, "failed": failed, "session_ids": sids}


def main():
    parser = argparse.ArgumentParser(description="批量上传录音（并行版）")
    parser.add_argument("--url", default="https://gp.aibeautyfulwomen.com")
    parser.add_argument("--username", required=True)
    parser.add_argument("--password", required=True)
    parser.add_argument("--folder", default="data_tmp")
    parser.add_argument("--workers", type=int, default=4, help="并行线程数，默认4")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    folder = Path(args.folder)
    if not folder.exists():
        print(f"[ERROR] 文件夹不存在: {folder}")
        sys.exit(1)

    files = [p for p in sorted(folder.iterdir()) if p.suffix.lower() in AUDIO_EXTS]
    if not files:
        print(f"[INFO] {folder} 下没有音频文件")
        sys.exit(0)

    groups: dict[tuple, list] = defaultdict(list)
    skipped_parse = []
    for p in files:
        meta = parse_filename(p.name)
        if meta is None:
            skipped_parse.append(p.name)
            continue
        key = (meta["customer"], meta["advisor"], meta["service_date"])
        groups[key].append((p, meta))

    print(f"[INFO] 共 {len(files)} 个文件，解析成功 {sum(len(v) for v in groups.values())} 个，分为 {len(groups)} 组，workers={args.workers}")
    if skipped_parse:
        print(f"[WARN] 以下文件名不符合格式，已跳过：")
        for name in skipped_parse:
            print(f"       {name}")

    if args.dry_run:
        print("\n[DRY-RUN] 分组预览：")
        for (customer, advisor, date), items in sorted(groups.items()):
            print(f"  {customer} / {advisor} / {date}  ({len(items)} 段)")
        return

    # 先在主线程验证一次登录（不存进 worker tls，避免被复用）
    try:
        _login(args.url, args.username, args.password)
        print(f"[INFO] 登录验证成功，开始上传 ...")
    except RuntimeError as e:
        print(f"[ERROR] {e}")
        sys.exit(1)

    sorted_groups = sorted(groups.items())
    total_groups = len(sorted_groups)
    stats = {"skipped": 0, "uploaded": 0, "failed": 0}
    all_sids: set = set()

    with ThreadPoolExecutor(max_workers=args.workers) as executor:
        futures = {
            executor.submit(
                process_group, key, items, args.url, args.username, args.password, idx, total_groups
            ): key
            for idx, (key, items) in enumerate(sorted_groups, 1)
        }
        for future in as_completed(futures):
            result = future.result()
            for k in stats:
                stats[k] += result[k]
            all_sids |= result["session_ids"]

    print(f"""
====== 上传完成 ======
总分组数:           {total_groups}
已存在跳过:         {stats['skipped']}
上传成功文件:       {stats['uploaded']}
上传失败文件:       {stats['failed']}
涉及 session 数:    {len(all_sids)}

分析任务会随 ASR 完成自动入队，全局并发上限 4，FIFO 平滑处理。
查看队列深度：curl {args.url}/healthz
""")


if __name__ == "__main__":
    main()
