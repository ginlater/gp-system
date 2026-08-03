#!/usr/bin/env python3
"""
冒烟测试：每次改完代码跑一下。
  python3 smoke_test.py
  python3 smoke_test.py --base http://127.0.0.1:5058 --user 13800000001 --pwd xxxx

它会做这些事：
  1. 拉登录页（确认服务还活着、HTTP 200）。
  2. 用一个顾问账号登录（默认 13800000001，密码可由 --pwd / SMOKE_PWD 给）。
  3. 顺序访问一批"读"接口，挨个判断：
     - HTTP 状态码 < 500
     - 返回是合法 JSON（专门防"Unexpected token '<', '<!doctype'..."这种事故）
  4. 任何一项失败就 exit 1，并打印响应前 500 字方便定位。

不会做任何写操作（不会真正绑定/删除录音），所以可以随便跑。
"""

import argparse
import json
import os
import sys
import urllib.parse

import requests


GET_ENDPOINTS = [
    "/api/consultant/recordings/pending",
    "/api/consultant/recordings/needs_confirm",
    "/api/consultant/today_reception",
    "/api/consultant/today_reception?date=2026-05-28",
    "/api/consultant/has_history_pending",
]

# 这些是登录后能打开的页面（HTML），只检查 200。
PAGE_ENDPOINTS = [
    "/consultant",
    "/",
]


def color(s, c):
    if not sys.stdout.isatty():
        return s
    return f"\033[{c}m{s}\033[0m"


OK = lambda s: color("✓ " + s, "32")
FAIL = lambda s: color("✗ " + s, "31")
WARN = lambda s: color("! " + s, "33")


def check_login(sess, base, user, pwd):
    r = sess.get(base + "/login", timeout=10, allow_redirects=False)
    if r.status_code not in (200, 302):
        print(FAIL(f"GET /login 返回 {r.status_code}"))
        return False
    r = sess.post(
        base + "/login",
        data={"username": user, "password": pwd},
        allow_redirects=False,
        timeout=10,
    )
    if r.status_code != 302:
        # 登录失败时 Flask 会渲染 login.html (200)
        print(FAIL(f"登录失败：HTTP {r.status_code}（账号/密码错？）"))
        return False
    print(OK(f"登录成功：{user}"))
    return True


def check_json(sess, base, path):
    url = base + path
    try:
        r = sess.get(url, timeout=15, allow_redirects=False)
    except Exception as e:
        print(FAIL(f"GET {path} 抛异常：{e}"))
        return False
    if r.status_code == 302:
        print(FAIL(f"GET {path} 被重定向到 {r.headers.get('Location')}（session 失效？）"))
        return False
    if r.status_code >= 500:
        print(FAIL(f"GET {path} HTTP {r.status_code}"))
        print("  >>", r.text[:500].replace("\n", " "))
        return False
    ct = r.headers.get("Content-Type", "")
    if "application/json" not in ct:
        print(FAIL(f"GET {path} 返回的不是 JSON（Content-Type={ct}）"))
        print("  >>", r.text[:500].replace("\n", " "))
        return False
    try:
        r.json()
    except json.JSONDecodeError as e:
        print(FAIL(f"GET {path} JSON 解析失败：{e}"))
        print("  >>", r.text[:500].replace("\n", " "))
        return False
    print(OK(f"GET {path}  [{r.status_code}]"))
    return True


def check_page(sess, base, path):
    url = base + path
    try:
        r = sess.get(url, timeout=15, allow_redirects=False)
    except Exception as e:
        print(FAIL(f"GET {path} 抛异常：{e}"))
        return False
    if r.status_code >= 500:
        print(FAIL(f"GET {path} HTTP {r.status_code}"))
        return False
    if r.status_code == 302:
        print(WARN(f"GET {path} → 302 {r.headers.get('Location')}"))
        return True
    print(OK(f"GET {path}  [{r.status_code}]"))
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=os.environ.get("SMOKE_BASE", "http://127.0.0.1:5058"))
    ap.add_argument("--user", default=os.environ.get("SMOKE_USER", "13800000001"))
    ap.add_argument("--pwd", default=os.environ.get("SMOKE_PWD", ""))
    args = ap.parse_args()

    if not args.pwd:
        print(WARN("未提供密码，只做无登录的 health check。可用 --pwd 或 SMOKE_PWD 提供。"))

    base = args.base.rstrip("/")
    sess = requests.Session()

    # 1) health
    try:
        r = sess.get(base + "/login", timeout=10)
        if r.status_code >= 500:
            print(FAIL(f"服务好像挂了：/login 返回 {r.status_code}"))
            sys.exit(1)
        print(OK(f"服务在线：{base}"))
    except Exception as e:
        print(FAIL(f"连不上 {base}：{e}"))
        sys.exit(1)

    if not args.pwd:
        sys.exit(0)

    # 2) login
    if not check_login(sess, base, args.user, args.pwd):
        sys.exit(1)

    # 3) JSON endpoints
    failed = 0
    for p in GET_ENDPOINTS:
        if not check_json(sess, base, p):
            failed += 1

    # 4) pages
    for p in PAGE_ENDPOINTS:
        if not check_page(sess, base, p):
            failed += 1

    print()
    if failed:
        print(FAIL(f"共 {failed} 项失败"))
        sys.exit(1)
    print(OK("全部通过"))


if __name__ == "__main__":
    main()
