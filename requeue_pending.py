#!/usr/bin/env python3
"""一次性脚本：把 analysis_status='pending'/'running' 但 ASR 已 done 的 session
重新入队到全局分析队列（webapp.py 的 4 并发 FIFO 闸）。

适用场景：服务器崩溃/重启后，残留的 pending 状态不会自动被拉起来。

用法：
  python3 requeue_pending.py --username admin --password xxx
  python3 requeue_pending.py --username admin --password xxx --url http://127.0.0.1:5058
  python3 requeue_pending.py --username admin --password xxx --dry-run
"""
import argparse
import sqlite3
import sys
import time
from pathlib import Path

import requests

DB = Path(__file__).parent / "recordings.db"


def find_ready_sessions() -> list[int]:
    conn = sqlite3.connect(DB)
    cur = conn.execute("""
        SELECT s.id FROM sessions s
        WHERE s.analysis_status IN ('pending','running')
          AND EXISTS (SELECT 1 FROM recordings r WHERE r.session_id=s.id)
          AND NOT EXISTS (
              SELECT 1 FROM recordings r
              WHERE r.session_id=s.id AND r.asr_status!='done'
          )
        ORDER BY s.id
    """)
    sids = [r[0] for r in cur.fetchall()]
    conn.close()
    return sids


def login(base_url: str, username: str, password: str) -> requests.Session:
    s = requests.Session()
    r = s.post(f"{base_url}/login",
               data={"username": username, "password": password},
               allow_redirects=False, timeout=15)
    if r.status_code not in (200, 302):
        raise SystemExit(f"登录失败 HTTP {r.status_code}")
    if s.get(f"{base_url}/api/me", timeout=10).status_code != 200:
        raise SystemExit("登录后验证失败")
    return s


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://127.0.0.1:5058")
    ap.add_argument("--username", required=True)
    ap.add_argument("--password", required=True)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--sleep", type=float, default=0.2,
                    help="每个请求之间的间隔秒，避免瞬时打爆，默认 0.2s")
    args = ap.parse_args()

    sids = find_ready_sessions()
    print(f"[INFO] 待重新入队 session 数: {len(sids)}")
    if not sids:
        return
    print(f"        前 10 个: {sids[:10]}")

    if args.dry_run:
        return

    http = login(args.url, args.username, args.password)
    print(f"[INFO] 登录成功，开始入队 (间隔 {args.sleep}s/个) ...")

    ok = 0
    fail = 0
    for i, sid in enumerate(sids, 1):
        try:
            r = http.post(f"{args.url}/api/session/{sid}/analyze",
                          json={}, timeout=15)
            if r.status_code == 200:
                ok += 1
                print(f"  [{i}/{len(sids)}] sid={sid} -> {r.json().get('status')}")
            else:
                fail += 1
                print(f"  [{i}/{len(sids)}] sid={sid} -> HTTP {r.status_code}: {r.text[:120]}")
        except Exception as e:
            fail += 1
            print(f"  [{i}/{len(sids)}] sid={sid} -> 异常: {e}")
        time.sleep(args.sleep)

    print(f"\n====== 完成 ======\n入队成功: {ok}\n入队失败: {fail}")
    print(f"查看队列: curl {args.url}/healthz")


if __name__ == "__main__":
    main()
