#!/usr/bin/env python3
"""
批量上传录音脚本

文件名格式：顾客_顾问_录音YYYYMMDDHHMMSS_时长.mp3
用法：
    python batch_upload.py --username admin --password xxx
    python batch_upload.py --username admin --password xxx --dry-run
    python batch_upload.py --username admin --password xxx --folder data_tmp --url https://gp.aibeautyfulwomen.com
"""

import argparse
import re
import sys
from collections import defaultdict
from datetime import datetime
from pathlib import Path

import requests

FILENAME_RE = re.compile(
    r"^(?P<customer>[^-_/]+)[-_](?P<advisor>[^-_/]+)[-_]录音(?P<ts>\d{14})[-_](?P<dur>.+?)\.[A-Za-z0-9]{1,5}$"
)
AUDIO_EXTS = {".mp3", ".wav", ".m4a", ".flac", ".aac", ".ogg", ".opus"}


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


def login(base_url: str, username: str, password: str) -> requests.Session:
    s = requests.Session()
    resp = s.post(
        f"{base_url}/login",
        data={"username": username, "password": password},
        allow_redirects=False,
        timeout=15,
    )
    # 登录成功会 302 跳转
    if resp.status_code not in (200, 302):
        print(f"[ERROR] 登录失败，HTTP {resp.status_code}")
        sys.exit(1)
    # 验证 cookie 有效
    check = s.get(f"{base_url}/api/me", timeout=10)
    if check.status_code != 200:
        print("[ERROR] 登录后验证失败，请检查账号密码")
        sys.exit(1)
    me = check.json()
    print(f"[INFO] 登录成功，账号：{me.get('username')}，角色：{me.get('role')}")
    return s


def session_exists(http: requests.Session, base_url: str, advisor: str, customer: str, date: str) -> bool:
    resp = http.get(
        f"{base_url}/api/sessions",
        params={"advisor": advisor, "customer": customer, "date": date, "page_size": 1},
        timeout=10,
    )
    if resp.status_code != 200:
        print(f"[WARN] 查询 session 失败 HTTP {resp.status_code}，保守跳过")
        return True
    return resp.json().get("total", 0) > 0


def upload_file(http: requests.Session, base_url: str, filepath: Path, meta: dict) -> dict | None:
    with open(filepath, "rb") as f:
        resp = http.post(
            f"{base_url}/api/upload",
            files={"files": (filepath.name, f, "audio/mpeg")},
            timeout=120,
        )
    if resp.status_code != 200:
        print(f"  [ERROR] 上传失败 HTTP {resp.status_code}: {resp.text[:200]}")
        return None
    data = resp.json()
    created = data.get("created", [])
    if not created:
        print(f"  [ERROR] 上传返回为空: {data}")
        return None
    return created[0]  # {id, oss_key, session_id}


def trigger_analyze(http: requests.Session, base_url: str, session_id: int):
    resp = http.post(f"{base_url}/api/session/{session_id}/analyze", timeout=15)
    if resp.status_code == 200:
        print(f"  [OK] session {session_id} 分析已触发")
    else:
        print(f"  [WARN] 触发分析失败 HTTP {resp.status_code}: {resp.text[:200]}")


def main():
    parser = argparse.ArgumentParser(description="批量上传录音并触发分析")
    parser.add_argument("--url", default="https://gp.aibeautyfulwomen.com", help="站点地址")
    parser.add_argument("--username", required=True, help="登录账号")
    parser.add_argument("--password", required=True, help="登录密码")
    parser.add_argument("--folder", default="data_tmp", help="音频文件夹路径")
    parser.add_argument("--dry-run", action="store_true", help="只解析不上传")
    args = parser.parse_args()

    folder = Path(args.folder)
    if not folder.exists():
        print(f"[ERROR] 文件夹不存在: {folder}")
        sys.exit(1)

    # 收集并解析所有音频文件
    files = [p for p in sorted(folder.iterdir()) if p.suffix.lower() in AUDIO_EXTS]
    if not files:
        print(f"[INFO] {folder} 下没有音频文件")
        sys.exit(0)

    # 按 (顾客, 顾问, 服务日期) 分组
    groups: dict[tuple, list[tuple[Path, dict]]] = defaultdict(list)
    skipped_parse = []
    for p in files:
        meta = parse_filename(p.name)
        if meta is None:
            skipped_parse.append(p.name)
            continue
        key = (meta["customer"], meta["advisor"], meta["service_date"])
        groups[key].append((p, meta))

    print(f"[INFO] 共找到 {len(files)} 个音频文件，解析成功 {sum(len(v) for v in groups.values())} 个，分为 {len(groups)} 组")
    if skipped_parse:
        print(f"[WARN] 以下文件名不符合格式，已跳过：")
        for name in skipped_parse:
            print(f"       {name}")

    if args.dry_run:
        print("\n[DRY-RUN] 分组预览：")
        for (customer, advisor, date), items in sorted(groups.items()):
            print(f"  {customer} / {advisor} / {date}  ({len(items)} 段)")
            for p, _ in items:
                print(f"    {p.name}")
        return

    # 登录
    http = login(args.url, args.username, args.password)

    total_groups = len(groups)
    skipped_exist = 0
    uploaded_files = 0
    failed_files = 0
    analyzed_sessions = 0

    for idx, ((customer, advisor, date), items) in enumerate(sorted(groups.items()), 1):
        print(f"\n[{idx}/{total_groups}] 顾客={customer} 顾问={advisor} 日期={date}  ({len(items)} 段)")

        # 去重检查
        if session_exists(http, args.url, advisor, customer, date):
            print(f"  [SKIP] 系统中已存在该接诊，跳过")
            skipped_exist += 1
            continue

        # 逐文件上传
        session_id = None
        for p, meta in sorted(items, key=lambda x: x[1]["recorded_at"]):
            print(f"  上传: {p.name} ...", end=" ", flush=True)
            result = upload_file(http, args.url, p, meta)
            if result is None:
                failed_files += 1
                print("失败")
            else:
                uploaded_files += 1
                session_id = result["session_id"]
                print(f"OK (recording_id={result['id']}, session_id={session_id})")

        # 触发分析
        if session_id is not None:
            trigger_analyze(http, args.url, session_id)
            analyzed_sessions += 1

    print(f"""
====== 完成 ======
总分组数:     {total_groups}
已存在跳过:   {skipped_exist}
上传成功文件: {uploaded_files}
上传失败文件: {failed_files}
触发分析接诊: {analyzed_sessions}
""")


if __name__ == "__main__":
    main()
