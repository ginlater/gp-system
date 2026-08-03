#!/bin/bash
# 工牌(gongpai)从 COS 恢复。默认只下载+校验(安全), 加 --apply 才覆盖线上库。
# 用法:
#   restore_from_cos.sh                 # 拉最新一份, 校验, 不改线上
#   restore_from_cos.sh 2026-06-28-20   # 指定时间点
#   restore_from_cos.sh latest --apply  # 真正恢复(会停服务/备份当前/覆盖)
set -euo pipefail
COSCMD=/usr/local/bin/coscmd
CONF=/root/.cos.conf
PREFIX=server-110-gongpai
LIVE=/opt/gp-system/recordings.db

TS="${1:-latest}"; APPLY="${2:-}"
if [ "$TS" = "--apply" ]; then APPLY="--apply"; TS="latest"; fi
if [ "$TS" = "latest" ]; then
  TS=$($COSCMD -c "$CONF" list /$PREFIX/hourly/ | awk '{print $1}' \
       | grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{2}' | sort | tail -1)
fi
echo "==> 恢复点: $TS  (apply=${APPLY:-no})"
DEST=/tmp/gongpai-restore-$TS; rm -rf "$DEST"; mkdir -p "$DEST"
$COSCMD -c "$CONF" download "/$PREFIX/hourly/$TS.tar.gz" "$DEST/bk.tar.gz" >/dev/null
tar -xzf "$DEST/bk.tar.gz" -C "$DEST"
DB="$DEST/gongpai/recordings.db"
echo "完整性: $(sqlite3 "$DB" 'PRAGMA integrity_check;' | head -1)"
echo "备份内账号数: $(sqlite3 "$DB" 'SELECT count(*) FROM users;')"
echo "解压位置: $DEST/gongpai/"
if [ "$APPLY" = "--apply" ]; then
  echo "==> 应用恢复: 停服务 → 备份当前 → 覆盖 → 启动"
  systemctl stop gongpai.service || true
  [ -f "$LIVE" ] && cp -a "$LIVE" "$LIVE.before-restore-$(date +%s)"
  cp -a "$DB" "$LIVE"
  systemctl start gongpai.service
  echo "==> 已恢复并重启 gongpai.service"
else
  echo "(演练模式) 确认无误后加 --apply 真正覆盖线上库。"
fi
