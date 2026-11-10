#!/usr/bin/env bash
# certbot 续期 + notAfter 校验 + 失败邮件告警（B10；doc103 §A.4 拍板=复用生产 SMTP 凭据，零新增凭据面）。
# 安装位：服务器 /opt/polyu/certbot-renew-alert.sh；cron 周日 04:00（保持原节奏，doc103 观察点=11-15 04:00）。
# 告警收件：默认 MAIL_FROM 自身；polyu-prod.env 可选 MAIL_ALERT_TO=<邮箱> 覆盖（不加即零 env 变更）。
#
# 用法：
#   certbot-renew-alert.sh              # 常规：renew → nginx reload → notAfter≥now+20d 校验 → 不满足才发信
#   certbot-renew-alert.sh --dry-run    # certbot renew --dry-run（LE staging，不动真证书）→ 校验路径演练
#   certbot-renew-alert.sh --test-alert # 不跑 renew，强制发一封告警邮件验证 SMTP 路径
#
# 设计注：失败不提前退出（set -e 会吞掉后续校验与告警）——所有失败汇入 reasons 一次告警；
# docker compose exec 一律 < /dev/null（脚本经 `ssh bash -s` 管道执行时防内层命令吞吃脚本剩余行）。
set -uo pipefail
cd /opt/polyu
CERT=certs/live/polyuguide.com/fullchain.pem
MIN_DAYS=20
TAG="[$(date '+%F %T')]"
DC="docker compose --env-file polyu-prod.env -f polyu-prod.compose.yaml"

RENEW_ARGS=(renew --quiet)
MODE=normal
case "${1:-}" in
  --dry-run) RENEW_ARGS=(renew --dry-run); MODE=dry-run ;;
  --test-alert) MODE=test-alert ;;
esac

reasons=""
if [ "$MODE" = test-alert ]; then
  reasons="测试告警路径（--test-alert 手动触发）; "
else
  if ! $DC --profile tls run --rm certbot "${RENEW_ARGS[@]}" < /dev/null; then
    reasons="${reasons}certbot renew 非零退出; "
  fi
  # 沿用原 cron 行为：renew 后 reload（证书未变时 reload 无害）
  $DC exec -T polyu-nginx nginx -s reload < /dev/null || reasons="${reasons}nginx reload 失败; "
fi

NOT_AFTER=$(sudo openssl x509 -in "$CERT" -noout -enddate 2>/dev/null | cut -d= -f2)
if [ "$MODE" != test-alert ] && ! sudo openssl x509 -checkend $((MIN_DAYS*86400)) -noout -in "$CERT" 2>/dev/null; then
  reasons="${reasons}notAfter 距今不足 ${MIN_DAYS} 天; "
fi

echo "$TAG mode=$MODE notAfter=${NOT_AFTER:-<unreadable>} reasons=${reasons:-none}"

if [ -n "$reasons" ]; then
  # 凭据从 polyu-prod.env 运行时读（MAIL_HOST/PORT/USERNAME/PASSWORD/FROM），脚本本身零明文凭据
  if python3 - "$NOT_AFTER" "$reasons" <<'PY'
import datetime, smtplib, sys
from email.mime.text import MIMEText

env = {}
for line in open('/opt/polyu/polyu-prod.env'):
    line = line.strip()
    if line.startswith('MAIL_') and '=' in line and not line.startswith('#'):
        key, value = line.split('=', 1)
        env[key] = value
not_after, reasons = sys.argv[1], sys.argv[2]
to = env.get('MAIL_ALERT_TO') or env['MAIL_FROM']
body = ('polyuguide.com 证书续期告警（生产 43.128.5.17）\n'
        '原因: ' + reasons + '\nnotAfter: ' + (not_after or '<unreadable>')
        + '\n时间: ' + datetime.datetime.now().isoformat(sep=' ') + '\n')
msg = MIMEText(body, 'plain', 'utf-8')
msg['Subject'] = '[polyuguide] certbot 续期告警'
msg['From'] = env['MAIL_FROM']
msg['To'] = to
with smtplib.SMTP_SSL(env['MAIL_HOST'], int(env['MAIL_PORT']), timeout=15) as smtp:
    smtp.login(env['MAIL_USERNAME'], env['MAIL_PASSWORD'])
    smtp.send_message(msg)
print('alert sent to', to)
PY
  then echo "$TAG ALERT-SENT"; else echo "$TAG ALERT-MAIL-FAILED"; fi
else
  echo "$TAG OK"
fi
