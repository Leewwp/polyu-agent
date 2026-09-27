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
import okhttp3.HttpUrl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 文档源 URL 的出站目标校验：把「请求方可指定任意 URL 让服务端去抓」收敛为
 * 「仅 http/https 公网地址」。
 *
 * <p>覆盖 POST /ingestion/tasks 的 JSON 请求体（经 {@link IngestionSourceValidationAdvice}）。
 * 重定向跳转目标由 {@link RedirectGuard} 在 HTTP 客户端层逐跳复校（O2/M4，
 * 复用本类 {@link #validateOutboundTarget}）。仍需另行处理、不要误认为此处已是完整防护：
 * <ul>
 *   <li>DNS 重绑定：校验与抓取之间存在 TOCTOU，同一主机名可先解析为公网、抓取时解析为内网
 *       （建连层由 {@link GuardedDns} 对解析结果复校兜底）；</li>
 *   <li>IP 字面量主机不走 OkHttp Dns SPI（RouteSelector 对字面量直接 InetAddress 快路径，
 *       okhttp 5.3.2 反汇编确认），连接层复校对字面量是盲区——故初始 URL 与每个重定向
 *       跳点的校验必须发生在建请求之前（#153），这正是本类的职责边界。</li>
 * </ul>
 *
 * <p><b>allow-private-hosts = 本地/开发档 escape hatch</b>（#153 更名定性）：它关闭的是
 * <b>整个私址/内网地址段限制</b>（本地开发抓 localhost MockWebServer 源不被拦），不是
 * host 白名单，开启时所有内网目标整体放行。生产必须关闭（prod profile 显式 false +
 * ProdConfigTripwireTest 绊网防 env 漂移）。
 */
@Component
public class IngestionUrlGuard {

    /**
     * 凭证 map 会被上游抓取器直接写成请求头，这些头必须由 HTTP 客户端自己决定，
     * 不能由请求方指定（否则可劫持连接目标或绕过按 Host 的校验）。
     */
    private static final Set<String> BLOCKED_HEADER_NAMES = Set.of(
            "host", "content-length", "transfer-encoding", "connection", "upgrade",
            "proxy-authorization", "proxy-connection", "te", "trailer",
            "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "x-real-ip");

    /**
     * 常见云厂商元数据服务主机名。对应的地址段（169.254.0.0/16、100.64.0.0/10）由
     * {@link #isInternalAddress} 覆盖，这里补的是别名解析前后的主机名形态。
     */
    private static final Set<String> BLOCKED_HOST_NAMES = Set.of(
            "metadata", "metadata.google.internal", "metadata.azure.com", "instance-data");

    private final boolean allowPrivateHosts;

    public IngestionUrlGuard(
            @Value("${ragent.ingestion.url-guard.allow-private-hosts:false}") boolean allowPrivateHosts) {
        this.allowPrivateHosts = allowPrivateHosts;
    }

    /**
     * 校验文档源。非 URL 类型不在此处拦（file/feishu 的文档面有各自边界；
     * feishu 非文档分支的任意 URL 由 HttpClientHelper 统一入口校验，#153）。
     */
    public void validate(DocumentSourceRequest source) {
        if (source == null || source.getType() != SourceType.URL) {
            return;
        }
        validateOutboundTarget(source.getLocation());
        checkCredentialHeaders(source.getCredentials());
    }

    /**
     * 出站目标校验（O2/M4 公开复用面，#153 起单一权威入口）：对任意运行期 URL 在
     * <b>建请求之前</b>做 scheme/形状 + 内网/元数据地址校验。host 判定输入是与实际
     * HTTP client <b>同一解析器</b>（OkHttp {@link HttpUrl}）的 canonicalization 结果——
     * URI/OkHttp/InetAddress 三者对 hostname 与 IP 字面量变体的规范化不一致正是
     * 字面量盲区的成因；解析失败一律 fail closed。重定向逐跳复校
     * （{@link RedirectGuard}）、multipart 直传入口的抓取期兜底、news/抓取 helper 的
     * 初始 URL 前置校验都走这里。
     */
    public void validateOutboundTarget(String location) {
        if (location == null || location.isBlank()) {
            throw new ClientException("文档源地址不能为空");
        }
        // HttpUrl 只接受 http/https（其余 scheme 返回 null），且对 IP 字面量做与实际
        // client 一致的规范化（十进制/十六进制等变体归一为点分十进制，IPv6 归一形态）
        HttpUrl url = HttpUrl.parse(location.trim());
        if (url == null) {
            throw new ClientException("文档源地址不是合法的 http/https URL");
        }
        // http://trusted.example@169.254.169.254/ 这类写法会让人把 userinfo 误读成主机
        if (!url.username().isEmpty() || !url.password().isEmpty()) {
            throw new ClientException("文档源地址不允许携带 userinfo");
        }
        if (!allowPrivateHosts) {
            checkHostIsPublic(url.host());
        }
    }

    /**
     * 连接级复校（#101 Dns SPI 复用面）：对建连时实际解析出的地址逐个做内网/元数据判定，
     * 命中即抛 ClientException（调用方整单失败）。与 {@link #checkHostIsPublic} 同一
     * {@link #isInternalAddress} 判定，不复制逻辑；escape hatch 开启时同口径整体放行
     * （本地档抓 localhost 源文件不被拦）。注意 IP 字面量主机不走 Dns SPI（类注释），
     * 字面量的防线在 {@link #validateOutboundTarget} 的建请求前校验。
     */
    public void checkResolvedAddresses(String host, java.util.List<InetAddress> addresses) {
        if (allowPrivateHosts) {
            return;
        }
        String normalized = normalizeHost(host);
        if (BLOCKED_HOST_NAMES.contains(normalized)) {
            throw new ClientException("出站连接指向内部主机，已拒绝");
        }
        for (InetAddress address : addresses) {
            if (isInternalAddress(address)) {
                throw new ClientException("出站连接解析到内网或保留地址，已拒绝（DNS 重绑定防护）");
            }
        }
    }

    private void checkHostIsPublic(String host) {
        // HttpUrl.host() 的 IPv6 形态带方括号，剥掉后送 getAllByName 才能落到地址分类；
        // 非字面量主机名同路解析（域名解析到私址一样拒绝），非标准 IPv4 变体
        // （十进制/十六进制/八进制）由 Java 地址解析归一后分类
        String normalized = normalizeHost(host);
        if (BLOCKED_HOST_NAMES.contains(normalized)) {
            throw new ClientException("文档源地址指向内部主机，已拒绝");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(normalized);
        } catch (UnknownHostException e) {
            throw new ClientException("文档源地址无法解析");
        }
        for (InetAddress address : addresses) {
            if (isInternalAddress(address)) {
                throw new ClientException("文档源地址解析到内网或保留地址，已拒绝");
            }
        }
    }

    /**
     * URI.getHost() 对 IPv6 字面量返回带方括号的形态（如 [::1]），
     * 带括号送进 getAllByName 会解析失败，须先剥掉才能落到真正的地址分类上。
     */
    private String normalizeHost(String host) {
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            return normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private boolean isInternalAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        if (raw.length == 4) {
            int first = raw[0] & 0xFF;
            int second = raw[1] & 0xFF;
            int third = raw[2] & 0xFF;
            // 0.0.0.0/8、100.64.0.0/10（运营商级 NAT，阿里云元数据 100.100.100.200 在此段）、
            // 192.0.0.0/24、198.18.0.0/15（基准测试段）
            return first == 0
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 192 && second == 0 && third == 0)
                    || (first == 198 && (second == 18 || second == 19));
        }
        // fc00::/7 唯一本地地址；回环/链路本地已由上面的 InetAddress 判定覆盖
        return (raw[0] & 0xFE) == 0xFC;
    }

    private void checkCredentialHeaders(Map<String, String> credentials) {
        if (credentials == null || credentials.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : credentials.entrySet()) {
            String name = entry.getKey();
            if (name == null) {
                continue;
            }
            String normalized = name.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()
                    || normalized.indexOf(':') >= 0
                    || normalized.indexOf('\r') >= 0
                    || normalized.indexOf('\n') >= 0
                    || BLOCKED_HEADER_NAMES.contains(normalized)) {
                throw new ClientException("文档源凭证包含不允许的请求头");
            }
            String value = entry.getValue();
            if (value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)) {
                throw new ClientException("文档源凭证包含非法的请求头取值");
            }
        }
    }
}
