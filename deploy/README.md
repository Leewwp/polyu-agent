# deploy/ · 生产部署指南

单机 Docker Compose 部署方案：后端 fat-jar 镜像 + 前端 nginx 网关镜像 + 自托管中间件（PostgreSQL 16 + pgvector / Redis / MinIO / RocketMQ 5.2 / Elasticsearch 9.4.2 + IK）+ certbot 证书签发续期。

> 服务器 IP、SSH 密钥等接入信息由部署方自行保管，不入仓库；密钥载体 `polyu-prod.env` 同样不入仓不入镜像（模板见 `.env.example`）。

## 文件一览

| 文件 | 角色 |
| --- | --- |
| `backend.Dockerfile` | 后端镜像：Maven 3.9.11 构建 → JRE 17，入口=bootstrap fat jar，非 root 运行 |
| `frontend-nginx.Dockerfile` | 前端+网关镜像：Vite build → nginx 直出 SPA + 反代 |
| `nginx/polyu-http.conf` | HTTP 网关（随镜像烧入）：ACME webroot + 301；http 顶层共用声明（map/resolver/limit_req zone） |
| `nginx/polyu-tls.conf` | TLS server 块（443）：/api/ /minio/ SPA；chat 端点 limit_req |
| `polyu-prod.compose.yaml` | 生产八件套：app / PG / Redis / MinIO / RocketMQ / ES+IK / nginx / certbot(tls profile) |
| `.env.example` | 密钥 env 模板（占位值）；复制为 `polyu-prod.env` 填真实值，不入仓 |
| `pg-backup.sh` | PG 每日备份（crontab 调用；容器内 socket 信任连接，脚本零明文凭据） |
| `server-init.sh` | 宿主首启初始化（幂等）：vm.max_map_count、/opt/polyu 目录、broker 存储属主 |

配套：`bootstrap/src/main/resources/application-prod.yaml`（prod profile，拓扑覆盖无密钥）、`.github/workflows/deploy.yml`（部署 CI）。

## 密钥面（不入仓 / 不入镜像 / 不入 compose）

```
deploy/.env.example ──(复制填值, 600 权限)──> polyu-prod.env ──(scp)──> 服务器 /opt/polyu/polyu-prod.env
```

CI 需要的 GitHub Actions secrets（Settings → Secrets and variables → Actions，一次性）：

| Secret | 值 |
| --- | --- |
| `DEPLOY_HOST` | 部署机 IP |
| `DEPLOY_USER` | SSH 用户 |
| `DEPLOY_KEY` | 专用 SSH 私钥全文 |

服务器不存任何长期 GitHub 凭据：每次部署用一次性 `GITHUB_TOKEN` 登录 GHCR 拉取后即 logout。

## 首次部署（按序）

```bash
cd <仓库根>

# 0. 前置：GitHub Actions secrets 已配（见上表）；
#    代码已 push main 且 deploy workflow 至少成功跑完三个 build 任务（镜像已在 GHCR）
# 1. 宿主初始化（幂等；vm.max_map_count + /opt/polyu 目录 + broker 存储属主）
ssh -i <密钥> <用户>@<服务器IP> 'sudo bash -s' < deploy/server-init.sh

# 2. 准备并上传密钥 env（本地复制 .env.example 填值后）
scp -i <密钥> polyu-prod.env <用户>@<服务器IP>:/opt/polyu/polyu-prod.env

# 3. 首启 = 重跑 CI（GitHub → Actions → deploy → Run workflow）。
#    deploy 步骤检测到 polyu-prod.env 已就位后自动：sed 更新镜像 tag → 临时 token 登录 GHCR →
#    pull → up -d → 退出登录。服务器全程不需要 GitHub 凭据。
#
#    备选手动路径（不想走 CI 时，需要一个 read:packages 的 PAT）：
#      ssh -i <密钥> <用户>@<服务器IP>
#      cd /opt/polyu && printf '%s' "<GHCR PAT>" | docker login ghcr.io -u <github用户名小写> --password-stdin
#      docker compose --env-file polyu-prod.env -f polyu-prod.compose.yaml up -d
```

内网/未公开阶段可通过 SSH 隧道访问（防火墙不必放行 80/443）：

```bash
ssh -i <密钥> -L 8080:localhost:80 <用户>@<服务器IP>
# 浏览器 http://localhost:8080 ；后端直查 http://localhost:9090（HOST_PORT_APP 映射）
```

admin 初始口令：`polyu-prod.env` 的 `RAGENT_BOOTSTRAP_ADMIN_PASSWORD`；留空则
`AdminUserBootstrap` 随机生成并在首次启动日志打印一次（`docker compose logs polyu-app | grep -i admin`）。

## 日常部署

push main（代码/部署物路径变更）→ `deploy.yml` 自动：构建三镜像推 GHCR（tag=commit 短 SHA + latest）
→ scp compose 与建表 SQL → 服务器 sed 更新镜像 tag → pull + 滚动重启。

## 数据库升级（新环境首启外）

PG 建表只在**空数据卷首启**自动执行（schema_pg.sql + init_data_pg.sql）；此后升级按
`resources/database/upgrades/` 手工执行：

```bash
# 容器走 compose 默认命名（项目名 + 服务名），不是裸服务名；
# 用 compose exec 免依赖具体容器名，与部署链同一 env/compose 文件
ssh -i <密钥> <用户>@<服务器IP> \
  'cd /opt/polyu && docker compose --env-file polyu-prod.env -f polyu-prod.compose.yaml exec -T polyu-pg psql -U polyu -d ragent -v ON_ERROR_STOP=1' \
  < resources/database/upgrades/v2.0.0/xxxx.sql
```

## 本机冒烟（不占生产端口）

镜像构建（根目录为 context；本机若 Docker Hub 不可达，可经镜像源补拉基础镜像后 retag）：

```bash
docker build -f deploy/backend.Dockerfile -t polyu-backend:f2 .
docker build -f deploy/frontend-nginx.Dockerfile -t polyu-frontend:f2 .
```

起一套冒烟栈（ES 需要宿主 `vm.max_map_count ≥ 262144`，Linux 按 server-init.sh 落 sysctl；
本机若已有服务占用标准端口，HOST_PORT_* 全套错峰）：

```bash
mkdir -p /tmp/polyu-prod-smoke/rmq-store && cd /tmp/polyu-prod-smoke
cp <仓库根>/deploy/polyu-prod.compose.yaml .
cp <仓库根>/resources/database/schema_pg.sql <仓库根>/resources/database/init_data_pg.sql .
cat > smoke.env <<'EOF'
IMAGE_BACKEND=polyu-backend:f2
IMAGE_FRONTEND=polyu-frontend:f2
IMAGE_ES=<本机已有 ES+IK 镜像>
POSTGRES_USER=polyu
POSTGRES_PASSWORD=smoke-pg-pass
REDIS_PASSWORD=smoke-redis-pass
MINIO_ROOT_USER=polyu-minio
MINIO_ROOT_PASSWORD=smoke-minio-pass
ASSETS_PUBLIC_URL=http://localhost:18080/minio
HOST_PORT_APP=19090
HOST_PORT_PG=15432
HOST_PORT_REDIS=16379
HOST_PORT_MINIO=19000
HOST_PORT_MINIO_CONSOLE=19001
HOST_PORT_ES=19200
HOST_PORT_HTTP=18080
HOST_PORT_HTTPS=18443
APP_JAVA_OPTS=-Xms512m -Xmx1g
ES_JAVA_OPTS=-Xms512m -Xmx512m
EOF
docker compose --env-file smoke.env -f polyu-prod.compose.yaml up -d
# 自检：网关 SPA / 后端经网关 / ES 健康
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:18080/            # 200
curl -s http://localhost:18080/api/ragent/ | head -c 80                     # Spring 业务 JSON=反代通
curl -s http://localhost:19200/_cluster/health | head -c 60                 # ES
docker compose --env-file smoke.env -f polyu-prod.compose.yaml down -v
```

编排设计说明（复现排查用）：

- **broker 健康检查勿用 `mqadmin`**：每次探测在容器内 fork JVM，叠加 broker 堆撞 mem_limit 被 cgroup OOM kill——用日志行 `grep 'boot success'`。
- **rocketmq logs/store 勿用命名卷**：镜像未预建目录，命名卷首挂 root 属主，uid 3000 写入全瘫——日志走 stdout；存储 bind mount `rmq-store/`（宿主 chown 3000:3000）。
- **broker 堆与限额**：768m 堆启动突发 RSS 偏大，640m 堆 + mem_limit 1536m 稳定。
- **nginx upstream**：必须变量 + `resolver 127.0.0.11` 运行时解析，静态 proxy_pass 固化 IP 在容器重建后持续 502。

## TLS 启用

前置：域名 A 记录 `@ / www → <服务器IP>` 生效；云防火墙放行 80/443。

1. 首签证书（Let's Encrypt；certbot 为 tls profile 服务，run 时须 `--profile tls`）：
   ```bash
   # 服务器 /opt/polyu
   docker compose --env-file polyu-prod.env -f polyu-prod.compose.yaml --profile tls run --rm certbot \
     certonly --webroot -w /var/www/certbot \
     -d <域名> -d www.<域名> \
     --email <邮箱> --agree-tos --no-eff-mail
   ```
2. 启用 TLS：`nginx/polyu-tls.conf`（443 server 块，`/api/`、`/minio/`、SPA 与 http 版同构）随镜像构建；
   `polyu-http.conf` 的 80 server 收敛为 ACME + 301（map/resolver 两文件共用，声明在 http 版顶层）；
   compose 的 nginx 健康检查改 https 直探（80 已 301，http 探测会跟随跳转撞证书域名不匹配）；
   `polyu-prod.env` 的 `ASSETS_PUBLIC_URL` 改 `https://<域名>/minio`；push 走 CI 重建。
   建议：先用一次性 nginx 容器挂真证书验证（`curl --resolve` 不带 `-k`，ssl_verify=0 通过）再上生产。
3. 续期：服务器 crontab（低峰期，如周日 04:00）`certbot renew --quiet` + nginx reload。
4. edge 加固（已随网关配置内置）：`X-Forwarded-For` 覆写 `$remote_addr`（应用侧限流/锁定/配额取真实来源）、
   `/api/`+`/minio/` 收敛 CORS 响应头、`/minio/` 非 GET 拒+大小上限、全 location nosniff、HSTS、
   `www.<域名>` 独立 server 301→apex、两个 GET chat 端点拒 `Sec-Fetch-Site: cross-site`。

## 备份与可观测（最小集：零新增服务）

### 1. PG 每日备份

```bash
# 服务器 crontab（建议 04:30：避开 04:00 证书续期 reload 与 RocketMQ deleteWhen 窗口）
crontab -e
#   30 4 * * * /opt/polyu/pg-backup.sh >> /opt/polyu/backups/pg-backup.log 2>&1
```

- 产物 `/opt/polyu/backups/pg-YYYYMMDD-HHMM.sql.gz`，滚动保留 7 天（可在脚本 `RETAIN_DAYS` 调整）；
  凭据面：pg_dump 经 `docker compose exec` 在容器内走本地 socket（trust），脚本零明文口令。
- 恢复：`gunzip -c pg-*.sql.gz | docker compose ... exec -T polyu-pg psql -U <用户> <库>`（`-T` 等效免 TTY）。
- 服务器侧先 `mkdir -p /opt/polyu/backups`（server-init.sh 未预建，首次安装 crontab 前补）。

### 2. 云监控告警（云厂商控制台免费层）

对轻量服务器建议配置：磁盘使用率 >70%、内存使用率 >85%、CPU >90%（各持续 5 分钟）告警，
通知走免费邮件/短信渠道。

### 3. 外部拨测（可选，如 UptimeRobot 免费档）

- `https://<域名>/`（SPA 静态，断言 200）+ `https://<域名>/api/ragent/user/me`
  （**任意响应即 up**——未登录返回业务 401 JSON 仍证明网关+后端链路活；别选「expects 200」）；
- 价值=外部视角全链路（DNS/证书/网关/后端），补云控制台内部监控的盲区。

### 4. nginx chat 端点限流（limit_req，LLM 成本面）

`polyu-http.conf` 顶层声明 `limit_req_zone $chat_limit_key zone=api_chat:10m rate=10r/m`
（map 空键技巧：非 chat 的 /api/ 请求不计数），`polyu-tls.conf` 的 /api/ location 内
`limit_req zone=api_chat burst=20 nodelay; limit_req_status 429;`。
- SSE 不受影响：一次提问=一个被放行的长请求，准入时计一次，流式响应期不再计数；
  burst=20 吸收前端自动重试+连发多轮。登录端点不必另压（应用已有失败锁定）。
- 速率 10r/m=起步值，按实测调整。
