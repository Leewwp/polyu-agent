/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产配置绊网（#150，审计 F-6/NV-2/F-5 补位）：把「生产档必须显式收紧」的三处
 * 配置钉进 CI——这些是安全收口而非功能默认值，靠 base yaml 的开发默认值漂回生产
 * 就是缺陷本身，回归时必须红得明确而不是静默漂移。
 * <ul>
 *   <li>application-prod.yaml：评测模式显式关闭（否则幂等切面被旁路、/rag/eval 随生产注册）；</li>
 *   <li>deploy/polyu-prod.compose.yaml：邮件模式无 logger 回落（缺键拒启而非验证码进日志）；</li>
 *   <li>deploy/nginx：stop 两条路径进独立限流桶，chat 桶速率与语义不变。</li>
 * </ul>
 */
class ProdConfigTripwireTest {

    @Test
    void 生产profile显式关闭评测模式() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-prod.yaml"));
        Properties properties = yaml.getObject();
        assertThat(properties).isNotNull();
        assertThat(properties.getProperty("ragent.eval.enabled"))
                .as("prod profile 必须显式 ragent.eval.enabled=false（base 开发默认 true 不得漂入生产）")
                .isEqualTo("false");
        // SSRF escape hatch 同绊（#153/审计 F-2）：allow-private-hosts 是本地/开发档整体
        // 旁路私址限制的开关，生产开启=出站守卫对内网目标整体放行
        assertThat(properties.getProperty("ragent.ingestion.url-guard.allow-private-hosts"))
                .as("prod profile 必须显式 allow-private-hosts=false（防 env 漂移关闭 SSRF 防线）")
                .isEqualTo("false");
    }

    @Test
    void 生产compose不得启用出站守卫escape_hatch() throws Exception {
        String compose = Files.readString(repoFile("deploy/polyu-prod.compose.yaml"));
        assertThat(compose)
                .as("生产 compose 不得把 allow-private-hosts 逃生口设为 true（审计 F-2 production tripwire）")
                .doesNotContainPattern("(?i)ALLOW[-_]PRIVATE[-_]HOSTS[^\\n]*:?[^\n]*true");
    }

    @Test
    void 生产compose邮件模式无logger回落() throws Exception {
        String compose = Files.readString(repoFile("deploy/polyu-prod.compose.yaml"));
        assertThat(compose)
                .as("RAGENT_MAIL_MODE 不得带 :-logger 回落（审计 NV-2：配置漂移时验证码不得进日志）")
                .doesNotContain("RAGENT_MAIL_MODE:-logger")
                .contains("${RAGENT_MAIL_MODE:?"); // 缺键拒启
    }

    @Test
    void nginx为stop建独立限流桶且chat桶语义不变() throws Exception {
        String http = Files.readString(repoFile("deploy/nginx/polyu-http.conf"));
        assertThat(http)
                .as("stop 两条路径必须进独立 map+zone（审计 F-5 网关补位）")
                .contains("(rag/v3/stop|agent/v1/stop)")
                .contains("zone=api_stop:10m rate=30r/m")
                .as("chat 桶速率与键控不变（10r/m、仅两条 chat 端点）")
                .contains("(rag/v3/chat|agent/v1/chat)  $binary_remote_addr")
                .contains("zone=api_chat:10m rate=10r/m");
        String tls = Files.readString(repoFile("deploy/nginx/polyu-tls.conf"));
        assertThat(tls)
                .as("stop 桶须在 /api/ 生效且宽松于 chat；chat 桶指令不变")
                .contains("limit_req zone=api_chat burst=20 nodelay")
                .contains("limit_req zone=api_stop burst=30 nodelay");
    }

    /**
     * surefire 工作目录=模块 basedir，deploy/ 在仓库根；逐级向上找 pom.xml+deploy 兜住
     * IDE 从仓库根运行等形态，找不到时让断言红而不是静默跳过（绊网不允许假绿）
     */
    private static Path repoFile(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (Path cursor = dir; cursor != null; cursor = cursor.getParent()) {
            Path candidate = cursor.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("仓库内找不到 " + relative + "（从 " + dir + " 起逐级向上）");
    }
}
