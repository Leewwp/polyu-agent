# PolyU-agent MinIO 镜像 · 官方末社区版 RELEASE.2025-10-15T17-29-55Z 源码构建（B3 升级窗，2026-11-12）
#
# 为什么自建源码构建：MinIO 官方分发渠道已全线断供——
#   - dl.min.io 二进制全 410（2026-09 起含 archive/，官方声明不再提供社区版预编译件）
#   - minio/minio 自 Docker Hub 整仓删除（2026-09-11，deploy.yml D18 判例根因）
#   - quay.io/minio 匿名拉取 401（需 Red Hat 账号登录，注册=用户动作未走）
# Homebrew homebrew-core 同路线：tag 源码 `go build`（formula 2026-02 起标 deprecated :repo_archived，
# 其构建参数=本文件 ldflags 口径的依据）。
#
# 供应链锚定（与 es-ik 自建镜像同范式，可在 CI 日志复核）：
#   1. tag RELEASE.2025-10-15T17-29-55Z = commit 9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a
#      （Homebrew formula 钉的同一修订），构建期 `git rev-parse HEAD` 逐字比对，不符即失败；
#   2. CVE 口径：该 tag=官方末社区版，修复 CVE-2025-62506（GHSA-jjjj-jwhf-8rgr）。
# console UI 已随上游 2025-05-24 移除：镜像无 9001/管理 UI，S3 API 仍 9000；healthcheck 用
# MinIO 官方文档口径 /minio/health/live（curl），不依赖镜像内 mc。
FROM golang:1.25-alpine AS build
ARG MINIO_RELEASE=RELEASE.2025-10-15T17-29-55Z
ARG MINIO_COMMIT=9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a
RUN apk add --no-cache git \
    && git clone --quiet --depth 1 --branch "${MINIO_RELEASE}" https://github.com/minio/minio.git /src \
    && cd /src \
    && test "$(git rev-parse HEAD)" = "${MINIO_COMMIT}" \
    && go mod download
WORKDIR /src
# 版本戳 ldflags 口径对齐 Homebrew formula（cmd.Version/ReleaseTag/CommitID）
RUN CGO_ENABLED=0 go build -trimpath -ldflags "-s -w \
        -X github.com/minio/minio/cmd.Version=${MINIO_RELEASE} \
        -X github.com/minio/minio/cmd.ReleaseTag=${MINIO_RELEASE} \
        -X github.com/minio/minio/cmd.CommitID=${MINIO_COMMIT}" \
        -o /out/minio .

FROM redhat/ubi9-minimal
# curl-minimal 供 compose healthcheck（/minio/health/live）
RUN microdnf install -y curl-minimal && microdnf clean all
COPY --from=build /out/minio /usr/bin/minio
EXPOSE 9000
# 与官方镜像同形：minio 作为入口，compose 侧 command: server /data（console 已不存在）
ENTRYPOINT ["/usr/bin/minio"]
CMD ["server", "/data"]
