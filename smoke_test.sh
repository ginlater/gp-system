#!/usr/bin/env bash
# 改完代码跑一下：python3 smoke_test.py
# 这里就是一个壳子，调真正的 Python 测试。
exec python3 "$(dirname "$0")/smoke_test.py" "$@"
