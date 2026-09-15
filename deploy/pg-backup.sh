#!/usr/bin/env bash
# PG 每日备份（pg_dump：对数据库这一唯一不可逆风险的对冲）
#
# 服务器安装（cron 身份须可免密执行 docker，server-init.sh 预配时同理）：
#   crontab -e   # 建议每日 04:30（避开 04:00 证书续期 reload 与 RocketMQ deleteWhen 窗口）
#   30 4 * * * /opt/polyu/pg-backup.sh >> /opt/polyu/backups/pg-backup.log 2>&1
#
# 凭据纪律：脚本不落任何明文口令——pg_dump 经 docker compose exec 在 polyu-pg 容器内
# 走本地 socket（官方镜像 local=trust），口令不参与认证；POSTGRES_USER 非密钥，
# 从 polyu-prod.env 只取该键（不 source 整文件，防 env 内其他变量泄漏进 cron 环境）。
#
# 恢复：
#   gunzip -c pg-YYYYMMDD-HHMM.sql.gz | docker compose ... exec -T polyu-pg psql -U $PG_USER ragent
set -euo pipefail

DEPLOY_DIR="/opt/polyu"          # server-init.sh 布局：compose/env/脚本同目录
BACKUP_DIR="/opt/polyu/backups"
RETAIN_DAYS=7                    # 滚动保留天数，可按需调整
SVC="polyu-pg"
DB="ragent"

mkdir -p "$BACKUP_DIR"
PG_USER="$(awk -F= '/^POSTGRES_USER=/{print $2; exit}' "$DEPLOY_DIR/polyu-prod.env")"
[ -n "$PG_USER" ] || { echo "[pg-backup] polyu-prod.env 缺 POSTGRES_USER" >&2; exit 1; }

STAMP="$(date +%Y%m%d-%H%M)"
OUT="$BACKUP_DIR/pg-$STAMP.sql.gz"

docker compose --env-file "$DEPLOY_DIR/polyu-prod.env" -f "$DEPLOY_DIR/polyu-prod.compose.yaml" \
  exec -T "$SVC" pg_dump -U "$PG_USER" "$DB" | gzip > "$OUT"

# 最小完整性验证：非空 + gzip 流可解
[ -s "$OUT" ] || { echo "[pg-backup] 产物为空：$OUT" >&2; exit 1; }
gzip -t "$OUT" || { echo "[pg-backup] gzip 校验失败：$OUT" >&2; exit 1; }
echo "[pg-backup] OK $OUT ($(du -h "$OUT" | cut -f1), $(date '+%F %T'))"

# 滚动保留：7 天外的旧备份删除（find -mtime +7 = 修改时间严格大于 7×24h）
find "$BACKUP_DIR" -name 'pg-*.sql.gz' -mtime +"$RETAIN_DAYS" -delete
