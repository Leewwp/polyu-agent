# deploy/ · 生产部署包（2026-09-09）

> 载体：云上单机轻量服务器（实例 IP / 登录密钥等接入信息由维护者保管，不入公开仓库）。
> 本目录入 git（`polyu-prod.env` 除外）；`scripts/gen-prod-env.py` 入 scripts/（仅本地）。
> 域名注册生效前：HTTP 先行 + SSH 隧道访问（验收期=非公开阶段，控制台防火墙不放行公网）。

## 文件一览

| 文件 | 角色 |
| --- | --- |
| `backend.Dockerfile` | 后端镜像：Maven 3.9.11 构建 → JRE 17，入口=bootstrap fat jar，非 root 运行 |
| `frontend-nginx.Dockerfile` | 前端+网关镜像：Vite build → nginx 直出 SPA + 反代 |
| `nginx/polyu-http.conf` | HTTP 网关（随镜像烧入）：/api/ → polyu-app，/minio/ → 资产桶，ACME webroot |
| `nginx/polyu-tls.conf.disabled` | 预制 TLS server 块（不入镜像，域名生效后启用） |
| `polyu-prod.compose.yaml` | 生产八件套：app / PG16+pgvector / Redis / MinIO / RocketMQ 5.2 / ES9.4.2+IK / nginx / certbot(tls profile) |
| `.env.example` | 密钥 env 模板（占位值）；真实文件由 gen 脚本生成，不入仓 |
| `server-init.sh` | 宿主首启初始化（幂等）：vm.max_map_count、/opt/polyu 目录、broker 存储属主 |

配套：`bootstrap/src/main/resources/application-prod.yaml`（prod profile，拓扑覆盖无密钥）、
`.github/workflows/deploy.yml`（部署 CI）、`scripts/gen-prod-env.py`（密钥 env 生成器，仅本地）。

## 密钥面（三不纪律：不入仓/不入镜像/不入 compose）

```
~/.polyu-agent/secrets.yaml ──(gen-prod-env.py)──> deploy/polyu-prod.env (0600)
                                                        │ scp
                                                        ▼
                                            服务器 /opt/polyu/polyu-prod.env
```

CI 需要的 GitHub Actions secrets（Settings → Secrets → Actions，一次性）：

| Secret | 值 |
| --- | --- |
| `DEPLOY_HOST` | 部署机 IP |
| `DEPLOY_USER` | SSH 用户 |
| `DEPLOY_KEY` | 专用 SSH 私钥全文 |

服务器不存任何长期 GitHub 凭据：每次部署用一次性 `GITHUB_TOKEN` 登录 GHCR 拉取后即 logout。

## 首启部署（一次性，按序）

```bash
cd <仓库根>

# 0. 前置：GitHub Actions secrets 已配（DEPLOY_HOST/USER/KEY，见上表）；
#         代码已 push main 且 deploy workflow 至少成功跑完三个 build 任务（镜像已在 GHCR）
# 1. 宿主初始化（幂等；vm.max_map_count + /opt/polyu 目录 + broker 存储属主）
ssh -i <密钥> <用户>@<服务器IP> 'sudo bash -s' < deploy/server-init.sh

# 2. 生成并上传密钥 env（生成后只打掩码；重复执行保留既有值不轮换）
python3 scripts/gen-prod-env.py
scp -i <密钥> deploy/polyu-prod.env <用户>@<服务器IP>:/opt/polyu/polyu-prod.env

# 3. 首启 = 重跑 CI（GitHub → Actions → deploy → Run workflow）。
#    deploy 步骤检测到 polyu-prod.env 已就位后自动：sed 更新镜像 tag → 临时 token 登录 GHCR →
#    pull → up -d → 退出登录。服务器全程不需要 GitHub 凭据。
#
#    备选手动路径（不想走 CI 时，需要一个 read:packages 的 PAT）：
#      ssh -i <密钥> <用户>@<服务器IP>
#      cd /opt/polyu && printf '%s' "<GHCR PAT>" | docker login ghcr.io -u <github用户名小写> --password-stdin
#      docker compose --env-file polyu-prod.env -f polyu-prod.compose.yaml up -d
```

非公开验收（staging 口径）：防火墙不放行 80/443，本地开隧道访问——

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
ssh -i <密钥> <用户>@<服务器IP> 'docker exec -i polyu-pg psql -U polyu -d ragent' \
  < resources/database/upgrades/v1.1.0/xxxx.sql
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

2026-09-09 实测判例（复现排查用）：

- **broker 健康检查勿用 `mqadmin`**：每次探测在容器内 fork JVM，叠加 broker 堆撞 mem_limit 被 cgroup OOM kill（exit 137）——用日志行 `grep 'boot success'`。
- **rocketmq logs/store 勿用命名卷**：镜像未预建目录，命名卷首挂 root 属主，uid 3000 写入全瘫——日志走 stdout；存储 bind mount `rmq-store/`（宿主 chown 3000:3000）。
- **broker 堆与限额**：768m 堆启动突发 RSS>1.4g，640m 堆 + mem_limit 1536m 稳定。
- **nginx upstream**：必须变量 + `resolver 127.0.0.11` 运行时解析，静态 proxy_pass 固化 IP 在容器重建后持续 502。

## TLS 启用（域名注册并解析后）

前置：域名 A 记录 `@ / www → <服务器IP>` 生效。

1. **控制台放行 80/443**（云防火墙）；
2. 首签证书：
   ```bash
   # 服务器 /opt/polyu
   docker compose --env-file polyu-prod.env -f polyu-prod.compose.yaml run --rm certbot \
     certonly --webroot -w /var/www/certbot \
     -d <域名> -d www.<域名> \
     --email <维护者邮箱> --agree-tos --no-eff-email
   ```
3. 启用 TLS：把 `nginx/polyu-tls.conf.disabled` 补全（从 polyu-http.conf 复制各 location）改为
   `polyu-tls.conf` 并在 frontend-nginx.Dockerfile 中 COPY；80 server 收敛为 ACME + 301 跳转；
   `polyu-prod.env` 的 `ASSETS_PUBLIC_URL` 改 `https://<域名>/minio`；push 走 CI 重建；
4. 续期：登记 crontab `certbot renew --webroot`（首签后）；
5. 后续口径（归认证安全实施票，不在本包范围）：`__Host-` cookie、HSTS、
   应用侧 real IP 只信本网关、CORS 锁单 origin。

## 维护者待办

| 待办 | 时点 |
| --- | --- |
| 域名注册 + A 记录回填 | TLS 启用前 |
| 云控制台关 3389 / 放行 80/443（TLS 时点） / 磁盘 >70% 告警注册 | 尽快 / TLS 时 / 尽快 |
| 配置 CI secrets（DEPLOY_HOST/USER/KEY） | 首次 deploy 前 |
| gen-prod-env.py 生成 + scp polyu-prod.env | 首启前 |
