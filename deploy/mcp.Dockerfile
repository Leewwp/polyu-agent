# PolyU-agent MCP 工具服务镜像（#164 第九常驻服务，2026-09-29）
#
# 与 backend.Dockerfile 同构：Maven 3.9.11 + JDK 17 构建 → JRE 17 运行，
# 入口=mcp-server 模块 fat jar。本模块无密钥无 DB（#164 裁剪掉上游 demo 的
# ragent_bit 业务库），仅 compose 内网可达（polyu-mcp:9099，不发布宿主端口）。
#
# 堆 256m 对应 compose mem_limit 384m（维护者裁定 cap）：单工具深链构造、
# 无业务数据缓存，实测显著低于此前电商 demo 形态。
#
# 本地构建（根目录为 context）：docker build -f deploy/mcp.Dockerfile -t polyu-mcp .

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先只拷各模块 pom 预取依赖：pom 不变时源码层重建不触发依赖下载（backend.Dockerfile 同款缓存策略）。
# 七模块 pom 必须齐拷：根 pom <modules> 无条件列全部子模块，缺兄弟 pom 时 Maven reactor 解析直接失败
# （Child module does not exist，本机 build 实测），与是否 -pl 单模块无关
COPY pom.xml ./
COPY lombok.config ./
COPY resources/format/copyright.txt resources/format/copyright.txt
COPY framework/pom.xml framework/
COPY infra-ai/pom.xml infra-ai/
COPY system/pom.xml system/
COPY rag/pom.xml rag/
COPY agent/pom.xml agent/
COPY bootstrap/pom.xml bootstrap/
COPY mcp-server/pom.xml mcp-server/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY mcp-server mcp-server
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests -pl mcp-server package

FROM eclipse-temurin:17-jre-noble AS runtime
LABEL org.opencontainers.image.source=https://github.com/Leewwp/polyu-agent

WORKDIR /app
RUN groupadd --system polyu && useradd --system --gid polyu polyu
COPY --from=build /build/mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar app.jar

USER polyu
ENV TZ=Asia/Shanghai
# 含空格的值必须带引号，否则 build 解析成两条键值报 Syntax error（本机实测）
ENV JAVA_OPTS="-Xms128m -Xmx256m"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
