#!/bin/bash
# 工牌(gongpai)系统数据备份 → 腾讯云 COS
#
#   /opt/gp-system   工牌接诊 Flask 后端 (gunicorn 127.0.0.1:5058)
#
# 双层保留:
#   hourly/YYYY-MM-DD-HH.tar.gz   每小时一份,保留 24 小时
#   daily/YYYY-MM-DD.tar.gz       每天 02 点一份,保留 15 天
#
# tarball 解压后目录结构:
#   gongpai/
#     recordings.db        (sqlite3 .backup 一致性快照, 含 users 登录账号表)
#     users_export.json    users 表的可读导出(便于只恢复账号)
#     .env                 FLASK_SECRET_KEY / OSS keys / BOSS_*
#
# 调度: /etc/cron.d/gongpai-backup 每小时跑一次 (root)
# 凭据: /root/.cos.conf (chmod 600)

set -euo pipefail

COSCMD=/usr/local/bin/coscmd
COS_CONF=/root/.cos.conf
COS_PREFIX=server-110-gongpai
TS=$(date +%Y-%m-%d-%H)
DATE=$(date +%Y-%m-%d)
HOUR=$(date +%H)

SRC=/opt/gp-system
DB="$SRC/recordings.db"

WORK=$(mktemp -d /tmp/gongpai-backup-XXXXXX)
trap "rm -rf $WORK" EXIT
PAY="$WORK/payload"

copy_if_exists() { [ -e "$1" ] && cp -r "$1" "$2" || true; }

mkdir -p "$PAY/gongpai"
# 一致性快照(WAL 模式安全)
sqlite3 "$DB" ".backup '$PAY/gongpai/recordings.db'"
# 账号表可读导出(快速只恢复登录账号用)
sqlite3 "$DB" ".mode json" ".once $PAY/gongpai/users_export.json" "SELECT * FROM users;" || true
copy_if_exists "$SRC/.env" "$PAY/gongpai/"

ARCHIVE="$WORK/${TS}.tar.gz"
tar -C "$PAY" -czf "$ARCHIVE" .

$COSCMD -c "$COS_CONF" upload "$ARCHIVE" "/${COS_PREFIX}/hourly/${TS}.tar.gz" >/dev/null
if [ "$HOUR" = "02" ]; then
    $COSCMD -c "$COS_CONF" upload "$ARCHIVE" "/${COS_PREFIX}/daily/${DATE}.tar.gz" >/dev/null
fi

# 清理过期对象
NOW_TS=$(date +%s)
cleanup_dir() {
    local prefix="$1" max_age_seconds="$2" pattern="$3"
    $COSCMD -c "$COS_CONF" list "/${COS_PREFIX}/${prefix}/" 2>/dev/null \
        | awk '{print $1}' \
        | { grep "\.tar\.gz$" || true; } \
        | while read -r path; do
            base=$(basename "$path")
            date_str=$(echo "$base" | grep -oE "$pattern" || true)
            [ -z "$date_str" ] && continue
            file_ts=$(date -d "$(echo "$date_str" | sed -E 's/^([0-9]{4}-[0-9]{2}-[0-9]{2})(-([0-9]{2}))?$/\1 \3:00:00/' | sed 's/  / 00:/' | sed 's/ $/ 00:00:00/')" +%s 2>/dev/null || echo 0)
            [ "$file_ts" = "0" ] && continue
            age=$((NOW_TS - file_ts))
            if [ $age -gt $max_age_seconds ]; then
                $COSCMD -c "$COS_CONF" delete -f "/${COS_PREFIX}/${prefix}/${base}" >/dev/null 2>&1 || true
            fi
        done
}
cleanup_dir "hourly" 86400   "[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{2}"
cleanup_dir "daily"  1296000 "[0-9]{4}-[0-9]{2}-[0-9]{2}"

echo "[$(date -Iseconds)] gongpai backup ok: ${TS}.tar.gz ($(du -h "$ARCHIVE" | cut -f1))"
