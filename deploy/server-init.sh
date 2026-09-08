#!/usr/bin/env bash
# PolyU-agent 生产服务器首启初始化（2026-09-09；目标服务器=部署执行票 §1 已购实例）
#
# 幂等：重复执行无副作用。不含任何密钥。
# 执行（本地开发机，密钥路径与服务器地址见部署执行票 §1 实例信息表）：
#   ssh -i <密钥> <用户>@<服务器IP> 'sudo bash -s' < deploy/server-init.sh
set -euo pipefail

echo "== [1/4] ES 内核参数（vm.max_map_count 非命名空间参数，容器内不可调） =="
if [ "$(cat /proc/sys/vm/max_map_count)" -lt 262144 ]; then
  echo "vm.max_map_count=262144" > /etc/sysctl.d/99-polyu-es.conf
  sysctl -p /etc/sysctl.d/99-polyu-es.conf
  echo "  已写入并生效"
else
  echo "  已达标，跳过（$(cat /proc/sys/vm/max_map_count)）"
fi

echo "== [2/4] 部署目录（/opt/polyu，compose 与密钥 env 的落位根） =="
mkdir -p /opt/polyu/certs /opt/polyu/certbot-www /opt/polyu/rmq-store
# 先把整树归部署用户，再单独把 RocketMQ broker 存储目录让给容器内 uid 3000
# （顺序颠倒会被递归 chown 覆盖，broker 写入即失败）
chown -R ubuntu:ubuntu /opt/polyu
chown 3000:3000 /opt/polyu/rmq-store
echo "  /opt/polyu/{certs,certbot-www,rmq-store} 就绪（rmq-store 归 uid 3000）"

echo "== [3/4] swap（镜像预置 /swap.img 1.9G，OOM 缓冲） =="
free -h | grep -i swap || true

echo "== [4/4] 需维护者在腾讯云控制台人工确认的事项（脚本不动防火墙） =="
cat <<'REMIND'
  [ ] 云控制台防火墙关闭 3389（Linux 无 RDP 用途）
  [ ] 80/443 放行时点 = 启用 TLS 时（域名解析生效后；非公开验收期走 SSH 隧道）
  [ ] 磁盘用量 >70% 云监控告警注册（云厂商免费层）
REMIND

echo "== 完成。下一步见 deploy/README.md「首启部署」 =="
