# PolyU-agent 后端镜像（生产部署，2026-09-09）
#
# 多阶段：Maven 3.9.11 + JDK 17 构建（与 .mvn/wrapper/maven-wrapper.properties 的
# distributionUrl 对齐）→ JRE 17 运行，入口=bootstrap 模块 fat jar。
#
# 镜像内无密钥（安全基线「密钥三不」）：本文件不含任何凭据；运行期配置由
# SPRING_PROFILES_ACTIVE=prod（bootstrap/src/main/resources/application-prod.yaml）
# + 环境变量注入（deploy/polyu-prod.env，部署方按模板填值后 scp，不入仓不入镜像）。
#
# 本地构建（根目录为 context）：docker build -f deploy/backend.Dockerfile -t polyu-backend .

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先只拷各模块 pom 预取依赖：pom 不变时源码层重建不触发依赖下载。
# wrapper jar 不入 git（.gitignore），故直接用镜像内 mvn（3.9.x 与 wrapper 版本同线）。
COPY pom.xml ./
COPY lombok.config ./
# Spotless 版权头模板（根 pom licenseHeader 引用，缺了 validate 阶段直接失败）
COPY resources/format/copyright.txt resources/format/copyright.txt
COPY framework/pom.xml framework/
COPY infra-ai/pom.xml infra-ai/
COPY system/pom.xml system/
COPY rag/pom.xml rag/
COPY agent/pom.xml agent/
COPY bootstrap/pom.xml bootstrap/
COPY mcp-server/pom.xml mcp-server/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY framework framework
COPY infra-ai infra-ai
COPY system system
COPY rag rag
COPY agent agent
COPY bootstrap bootstrap
# 只构建 bootstrap 及其上游依赖（mcp-server 是独立应用，不在生产八件套内）
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests -pl bootstrap -am package

FROM eclipse-temurin:17-jre-noble AS runtime
LABEL org.opencontainers.image.source=https://github.com/Leewwp/polyu-agent

WORKDIR /app
RUN groupadd --system polyu && useradd --system --gid polyu polyu \
    && mkdir -p /home/polyu/logs \
    && chown -R polyu:polyu /home/polyu
# /home/polyu/logs：rocketmq-client logback 落日志用（HOME 缺失时每次启动刷 ERROR，冒烟实测）
COPY --from=build /build/bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar app.jar

USER polyu
ENV TZ=Asia/Shanghai
# 堆参数由部署侧经 JAVA_OPTS 注入（compose 默认 -Xms1g -Xmx2g，压测后调）
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
