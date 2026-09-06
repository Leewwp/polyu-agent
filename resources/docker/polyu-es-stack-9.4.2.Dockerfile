# PolyU-agent ES 栈镜像 · Elasticsearch 9.4.2 + analysis-ik 9.4.2（2026-09-06 批复⑤）
#
# 版本三点锁步（2026-09-06 实测核验，缺一不可）：
#   1. 服务端镜像：docker.elastic.co/elasticsearch/elasticsearch:9.4.2（docker manifest 确认存在）
#   2. IK 插件：analysis-ik 9.4.2 —— zip 内 plugin-descriptor.properties 实读
#      elasticsearch.version=9.4.2 / name=analysis-ik / classname=com.infinilabs.ik.elasticsearch.AnalysisIkPlugin
#      （IK 版本与 ES 服务端必须严格同版，否则插件拒绝装载）
#   3. 应用客户端：co.elastic.clients:elasticsearch-java 9.4.2（bootstrap-0.0.1-SNAPSHOT.jar
#      实际打包版本，Spring Boot 4.1.0 BOM 托管）
#
# 本地 zip 安装（不走构建期网络下载）：zip 已随 compose 批复核验，
#   来源 https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-9.4.2.zip
#   SHA256 f5aa2434f9a6b4af0fc940088bdcf82dea1c0860e07c76808c0cc120bc02b4a5（4,619,506 B）
#   （该源站实测 ~11KB/s，故选 COPY 本地包；如需改回在线安装：RUN elasticsearch-plugin install --batch <上述 URL>）
FROM docker.elastic.co/elasticsearch/elasticsearch:9.4.2
# COPY 默认属主 root，而镜像默认用户 elasticsearch(uid 1000) 在带 sticky bit 的 /tmp
# 无权删他人文件（EPERM）——须 chown 给运行用户，下方 rm 才能成功
COPY --chown=1000:0 elasticsearch-analysis-ik-9.4.2.zip /tmp/analysis-ik.zip
RUN elasticsearch-plugin install --batch file:///tmp/analysis-ik.zip \
    && rm /tmp/analysis-ik.zip
