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

package com.nageoffer.ai.ragent.rag.security;

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.domain.enums.SourceType;
import com.nageoffer.ai.ragent.rag.controller.request.DocumentSourceRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文档源 URL 出站校验测试。
 *
 * <p>全部用字面量 IP 或主机名黑名单做输入，不解析真实域名——避免测试依赖 DNS。
 */
class IngestionUrlGuardTest {

    private final IngestionUrlGuard strict = new IngestionUrlGuard(false);
    private final IngestionUrlGuard permissive = new IngestionUrlGuard(true);

    private static DocumentSourceRequest url(String location) {
        DocumentSourceRequest source = new DocumentSourceRequest();
        source.setType(SourceType.URL);
        source.setLocation(location);
        return source;
    }

    private static DocumentSourceRequest urlWithCredentials(String location, Map<String, String> credentials) {
        DocumentSourceRequest source = url(location);
        source.setCredentials(credentials);
        return source;
    }

    // ------------------------------------------------------------ 放行面

    @Test
    void allowsPublicHttpAndHttpsTargets() {
        assertThatCode(() -> strict.validate(url("https://93.184.216.34/doc.pdf"))).doesNotThrowAnyException();
        assertThatCode(() -> strict.validate(url("http://1.1.1.1/doc.pdf"))).doesNotThrowAnyException();
        assertThatCode(() -> strict.validate(url("https://8.8.8.8:8443/a/b?c=d#e"))).doesNotThrowAnyException();
    }

    @Test
    void allowsOrdinaryCredentialHeaders() {
        assertThatCode(() -> strict.validate(
                urlWithCredentials("https://1.1.1.1/a", Map.of("token", "abc123"))))
                .doesNotThrowAnyException();
    }

    @Test
    void ignoresNonUrlSourceTypes() {
        DocumentSourceRequest file = new DocumentSourceRequest();
        file.setType(SourceType.FILE);
        file.setLocation("file:///etc/passwd");
        assertThatCode(() -> strict.validate(file)).doesNotThrowAnyException();

        DocumentSourceRequest noType = new DocumentSourceRequest();
        noType.setLocation("http://169.254.169.254/");
        assertThatCode(() -> strict.validate(noType)).doesNotThrowAnyException();
        assertThatCode(() -> strict.validate(null)).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------ 协议与形态

    @Test
    void rejectsNonHttpSchemes() {
        assertThatThrownBy(() -> strict.validate(url("file:///etc/passwd")))
                .isInstanceOf(ClientException.class).hasMessageContaining("http/https");
        assertThatThrownBy(() -> strict.validate(url("ftp://1.1.1.1/x")))
                .isInstanceOf(ClientException.class).hasMessageContaining("http/https");
        assertThatThrownBy(() -> strict.validate(url("gopher://1.1.1.1/x")))
                .isInstanceOf(ClientException.class).hasMessageContaining("http/https");
    }

    @Test
    void rejectsUserInfoDisguisedAsHost() {
        // 主机被写成 userinfo，真实目标藏在 @ 之后
        assertThatThrownBy(() -> strict.validate(url("http://trusted.example@169.254.169.254/latest/meta-data/")))
                .isInstanceOf(ClientException.class).hasMessageContaining("userinfo");
    }

    @Test
    void rejectsBlankAndMalformedLocation() {
        assertThatThrownBy(() -> strict.validate(url(null)))
                .isInstanceOf(ClientException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> strict.validate(url("   ")))
                .isInstanceOf(ClientException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> strict.validate(url("http://exa mple.com/x")))
                .isInstanceOf(ClientException.class).hasMessageContaining("不是合法");
        // #153 起 host 判定与实际 client 同源（HttpUrl）：空主机形态走「无法解析」fail closed
        assertThatThrownBy(() -> strict.validate(url("http:///no-host")))
                .isInstanceOf(ClientException.class).hasMessageContaining("无法解析");
    }

    // ------------------------------------------------------------ 内网与保留地址

    @Test
    void rejectsLoopbackPrivateAndReservedIpv4() {
        for (String location : new String[]{
                "http://127.0.0.1/x",
                "http://127.1.2.3/x",
                "http://10.0.0.5/x",
                "http://172.16.3.4/x",
                "http://192.168.1.1/x",
                "http://169.254.169.254/latest/meta-data/",
                "http://0.0.0.0/x",
                "http://198.18.0.1/x",
                "http://192.0.0.8/x"}) {
            assertThatThrownBy(() -> strict.validate(url(location)))
                    .as("应拒绝: %s", location)
                    .isInstanceOf(ClientException.class)
                    .hasMessageContaining("内网或保留地址");
        }
    }

    @Test
    void rejectsAliyunMetadataAddressBehindCgnatRange() {
        // 阿里云元数据在 100.64.0.0/10 内网段，普通的私有段判定不会覆盖它
        assertThatThrownBy(() -> strict.validate(url("http://100.100.100.200/latest/meta-data/")))
                .isInstanceOf(ClientException.class).hasMessageContaining("内网或保留地址");
        assertThatThrownBy(() -> strict.validate(url("http://100.64.0.1/x")))
                .isInstanceOf(ClientException.class).hasMessageContaining("内网或保留地址");
    }

    @Test
    void rejectsIpv6LoopbackAndUniqueLocalViaAddressClassification() {
        // 断言的是"内网或保留地址"而不是"无法解析"：URI.getHost() 对 IPv6 字面量
        // 返回带方括号的形态，若不去括号就会走解析失败分支，看似拦住实则没有分类
        assertThatThrownBy(() -> strict.validate(url("http://[::1]/x")))
                .isInstanceOf(ClientException.class).hasMessageContaining("内网或保留地址");
        assertThatThrownBy(() -> strict.validate(url("http://[fd00::1]/x")))
                .isInstanceOf(ClientException.class).hasMessageContaining("内网或保留地址");
        assertThatThrownBy(() -> strict.validate(url("http://[fe80::1]/x")))
                .isInstanceOf(ClientException.class).hasMessageContaining("内网或保留地址");
    }

    @Test
    void rejectsCloudMetadataHostNames() {
        assertThatThrownBy(() -> strict.validate(url("http://metadata.google.internal/computeMetadata/v1/")))
                .isInstanceOf(ClientException.class).hasMessageContaining("内部主机");
    }

    // ------------------------------------------------------------ #153：IP 字面量与变体语义类别矩阵
    // 连接层 Dns SPI 对字面量是盲区（okhttp RouteSelector 快路径），这些类别必须在
    // 建请求前被拦；host 判定与实际 client 同源（HttpUrl canonicalization + Java 地址归一）

    @Test
    void rejectsHostnameResolvingToLoopback() {
        // localhost 解析到 127.0.0.1：主机名形态的内网目标（DNS 解析到私址同样拒绝）
        assertThatThrownBy(() -> strict.validate(url("http://localhost:9200/_cat/indices")))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("内网或保留地址");
    }

    @Test
    void rejectsNonStandardIpv4LiteralVariants() {
        // 探针实证（okhttp 5.3.2 HttpUrl + Java 地址解析）：十进制 2130706433 与缺段简写
        // 127.1 均归一到 127.0.0.1，按内网分类拒绝；裸十六进制 0x7f000001 Java 字面量解析
        // 不接受——落入「无法解析」fail closed，同样是拒绝。合同=变体必拒（分类或
        // fail closed），绝无放行。注：0177.0.0.1 在 Java 语义下是十进制 177.0.0.1（公网），
        // 守卫与实际 client 对它的连接目标判定一致，不构成内网绕过，不在拒绝面内
        for (String location : new String[]{
                "http://2130706433/x",
                "http://127.1/x"}) {
            assertThatThrownBy(() -> strict.validate(url(location)))
                    .as("非标准 IPv4 变体应按内网拒绝: %s", location)
                    .isInstanceOf(ClientException.class)
                    .hasMessageContaining("内网或保留地址");
        }
        assertThatThrownBy(() -> strict.validate(url("http://0x7f000001/x")))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("无法解析");
    }

    @Test
    void rejectsIpv4MappedIpv6Forms() {
        // IPv4-mapped IPv6（::ffff:127.0.0.1 与十六进制形态）：分类必须穿透映射看到内网 v4
        assertThatThrownBy(() -> strict.validate(url("http://[::ffff:127.0.0.1]/x")))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("内网或保留地址");
        assertThatThrownBy(() -> strict.validate(url("http://[::ffff:7f00:1]/x")))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("内网或保留地址");
    }

    // ------------------------------------------------------------ 配置开关

    @Test
    void allowsPrivateTargetsOnlyWhenExplicitlyEnabled() {
        assertThatCode(() -> permissive.validate(url("http://10.0.0.5/wiki"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> strict.validate(url("http://10.0.0.5/wiki")))
                .isInstanceOf(ClientException.class);
    }

    @Test
    void enablingPrivateTargetsStillRejectsNonHttpSchemesAndDisguisedUserInfo() {
        assertThatThrownBy(() -> permissive.validate(url("file:///etc/passwd")))
                .isInstanceOf(ClientException.class).hasMessageContaining("http/https");
        assertThatThrownBy(() -> permissive.validate(url("http://trusted.example@10.0.0.5/x")))
                .isInstanceOf(ClientException.class).hasMessageContaining("userinfo");
    }

    // ------------------------------------------------------------ 凭证请求头

    @Test
    void rejectsCredentialHeadersThatHijackTheRequest() {
        for (String header : new String[]{
                "Host", "host", " HOST ",
                "Content-Length", "Transfer-Encoding", "Connection", "Upgrade",
                "Proxy-Authorization", "X-Forwarded-For", "X-Forwarded-Host", "X-Real-IP"}) {
            assertThatThrownBy(() -> strict.validate(
                    urlWithCredentials("https://1.1.1.1/a", Map.of(header, "evil"))))
                    .as("应拒绝请求头: %s", header)
                    .isInstanceOf(ClientException.class)
                    .hasMessageContaining("不允许的请求头");
        }
    }

    @Test
    void rejectsCredentialHeaderNameWithColonOrNewline() {
        assertThatThrownBy(() -> strict.validate(
                urlWithCredentials("https://1.1.1.1/a", Map.of("X-Evil:X-Injected", "1"))))
                .isInstanceOf(ClientException.class).hasMessageContaining("不允许的请求头");
        assertThatThrownBy(() -> strict.validate(
                urlWithCredentials("https://1.1.1.1/a", Map.of("X-Evil\nInjected", "1"))))
                .isInstanceOf(ClientException.class).hasMessageContaining("不允许的请求头");
    }

    @Test
    void rejectsCredentialValueWithNewline() {
        assertThatThrownBy(() -> strict.validate(
                urlWithCredentials("https://1.1.1.1/a", Map.of("token", "abc\r\nX-Injected: 1"))))
                .isInstanceOf(ClientException.class).hasMessageContaining("非法的请求头取值");
    }
}
