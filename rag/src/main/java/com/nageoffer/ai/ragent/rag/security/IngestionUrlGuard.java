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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
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
 *   <li>DNS 重绑定：校验与抓取之间存在 TOCTOU，同一主机名可先解析为公网、抓取时解析为内网；</li>
 *   <li>/knowledge-base/{kb-id}/docs/upload 的 multipart 表单入口（sourceType=url）走 @ModelAttribute
 *       绑定，不经 RequestBodyAdvice；该入口在 /knowledge-base/** 的 admin 角色拦截覆盖内，
 *       抓取期由 {@link RedirectGuard} 对重定向目标兜底复校。</li>
 * </ul>
 * 彻底收敛需要在 HTTP 客户端层按解析结果复校或做出口策略。
 */
@Component
public class IngestionUrlGuard {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

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
     * 校验文档源。非 URL 类型不在此处拦（file/feishu 有各自的边界）。
     */
    public void validate(DocumentSourceRequest source) {
        if (source == null || source.getType() != SourceType.URL) {
            return;
        }
        validateOutboundTarget(source.getLocation());
        checkCredentialHeaders(source.getCredentials());
    }

    /**
     * 出站目标校验（O2/M4 公开复用面）：对任意运行期 URL 做 scheme/形状 +
     * 内网/元数据地址校验，口径与入站初始校验完全一致。重定向逐跳复校
     * （{@link RedirectGuard}）与 multipart 直传入口的抓取期兜底都走这里。
     */
    public void validateOutboundTarget(String location) {
        URI uri = parse(location);
        // scheme 与 userinfo 无论开关如何都拦：allow-private-hosts 只放宽地址段，
        // 不应把 file:// 之类非 HTTP 协议放进来
        checkSchemeAndShape(uri);
        if (!allowPrivateHosts) {
            checkHostIsPublic(uri.getHost());
        }
    }

    private URI parse(String location) {
        if (location == null || location.isBlank()) {
            throw new ClientException("文档源地址不能为空");
        }
        try {
            return new URI(location.trim());
        } catch (URISyntaxException e) {
            throw new ClientException("文档源地址不是合法的 URL");
        }
    }

    private void checkSchemeAndShape(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new ClientException("文档源仅支持 http/https 地址");
        }
        // http://trusted.example@169.254.169.254/ 这类写法会让人把 userinfo 误读成主机
        if (uri.getUserInfo() != null) {
            throw new ClientException("文档源地址不允许携带 userinfo");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new ClientException("文档源地址缺少主机名");
        }
    }

    private void checkHostIsPublic(String host) {
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
