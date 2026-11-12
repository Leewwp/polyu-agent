# PolyU-agent MinIO 镜像 · 官方末社区版 RELEASE.2025-10-15T17-29-55Z（B3 升级窗，2026-11-12）
#
# 为什么自建而不是拉官方镜像：minio/minio 与 minio/mc 已于 2026-09-11 自 Docker Hub 整仓删除
# （deploy.yml「基础设施镜像归运维面」注释的 D18 判例根因），quay.io/minio/minio 匿名拉取 401
# （需 Red Hat 账号登录）；官方二进制仍发布于 dl.min.io（TLS，MinIO 唯一官方分发点，与
# Docker Hub 时代同信任级）。供应链口径与 polyu-es-stack-9.4.2.Dockerfile 同范式：
# 构建期下载官方二进制 + 官方 sidecar SHA256 校验后打包，推 GHCR。
#
# 版本口径（2026-11-07 体检 B3 → 2026-11-10 拍板）：
#   - RELEASE.2025-10-15T17-29-55Z = 官方末社区版（仓库 2026-04-25 归档，无后续补丁），
#     修复 CVE-2025-62506（Service Accounts/STS 会话策略绕过提权，GHSA-jjjj-jwhf-8rgr）。
#   - 社区版 console UI 已随上游 2025-05-24 release 移除：镜像无 9001/管理 UI，S3 API 仍 9000；
#     运维面=S3 API / app StorageInitializer，迁移评估票另立（半年尺度后路）。
#   - healthcheck 不再依赖镜像内 mc（官方镜像捆绑 mc 已不可得）：用 MinIO 官方文档口径
#     /minio/health/live 端点（curl），见 polyu-prod.compose.yaml healthcheck。
FROM redhat/ubi9-minimal
ARG MINIO_RELEASE=RELEASE.2025-10-15T17-29-55Z

# BuildKit 远程 ADD：dl.min.io 侧同时取二进制与其官方 .sha256 sidecar
ADD https://dl.min.io/server/minio/release/linux-amd64/minio.${MINIO_RELEASE} /tmp/minio
ADD https://dl.min.io/server/minio/release/linux-amd64/minio.${MINIO_RELEASE}.sha256 /tmp/minio.sha256

# sidecar 内文件名是 minio.RELEASE.<tag>，与 /tmp/minio 名不同——awk 规整为本地文件名再校验；
# 校验失败（sidecar 不匹配/传输损坏）即构建失败，不带病出镜像
RUN microdnf install -y curl-minimal \
    && cd /tmp \
    && awk '{print $1"  minio"}' minio.sha256 | sha256sum -c - \
    && install -m 0755 minio /usr/bin/minio \
    && rm -f minio minio.sha256 \
    && microdnf clean all

EXPOSE 9000
# 与官方镜像同形：minio 作为入口，compose 侧 command: server /data（console 已不存在）
ENTRYPOINT ["/usr/bin/minio"]
CMD ["server", "/data"]
