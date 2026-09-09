# PolyU-agent 前端静态 + nginx 网关镜像（生产部署，2026-09-09）
#
# 「nginx+前端静态」合并为一个镜像（生产八件套口径）：
#   构建段 Vite build → 运行段 nginx:1.27-alpine 直出 SPA 并反代后端/MinIO。
# 网关配置=deploy/nginx/polyu-http.conf（80：ACME+301）+ polyu-tls.conf（443：TLS server 块）；
# 证书不随镜像（/etc/letsencrypt 由 compose 挂载 certbot 产物），启用实录见 deploy/README「TLS 启用」。
#
# 本地构建（根目录为 context）：docker build -f deploy/frontend-nginx.Dockerfile -t polyu-frontend .

FROM node:24-alpine AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
# VITE_API_BASE_URL 缺省为空=同源相对路径：生产经网关同源转发，无需构建期注入
RUN npm run build

FROM nginx:1.27-alpine
LABEL org.opencontainers.image.source=https://github.com/Leewwp/polyu-agent

ENV TZ=Asia/Shanghai
RUN rm -f /etc/nginx/conf.d/default.conf
COPY deploy/nginx/polyu-http.conf /etc/nginx/conf.d/polyu-http.conf
COPY deploy/nginx/polyu-tls.conf /etc/nginx/conf.d/polyu-tls.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80 443
