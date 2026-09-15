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

package com.nageoffer.ai.ragent.news.fetch;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * URL 规范化 + sha256 幂等键（抓取纪律）
 *
 * <p>规范化规则移植自 scripts/crawl/fetch_sources.py 的 normalize_url：
 * 去 fragment；scheme/host 小写；去默认端口（http:80/https:443）；空 path 补 "/"；
 * 非根 path 去尾部斜杠；去空 query。幂等键 = sha256(规范化 URL) 的十六进制小写，
 * 与 t_news_item.url_hash 列、uq_news_item_url 唯一约束一一对应——同一条新闻
 * 在列表页/详情页/sitemap 重复出现时落同一键，天然去重。
 */
public final class NewsUrlNormalizer {

    private NewsUrlNormalizer() {
    }

    /**
     * 规范化 URL；无 host 或非 http(s) scheme 时原样返回（由调用方决定处置）。
     * 只做字符串层手术（沿 python urlunsplit 语义），不走 URI 多参构造器重编码——
     * 避免 %XX 序列或括号等合法字符被二次转义改变幂等键。
     */
    public static String normalize(String url) {
        try {
            URI raw = URI.create(url.strip());
            String scheme = raw.getScheme() == null ? "" : raw.getScheme().toLowerCase(Locale.ROOT);
            String host = raw.getHost() == null ? "" : raw.getHost().toLowerCase(Locale.ROOT);
            if (host.isEmpty() || (!scheme.equals("http") && !scheme.equals("https"))) {
                return url.strip();
            }
            String authority = host;
            if (raw.getPort() != -1 && !isDefaultPort(scheme, raw.getPort())) {
                authority = host + ":" + raw.getPort();
            }
            String path = raw.getRawPath() == null || raw.getRawPath().isEmpty() ? "/" : raw.getRawPath();
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
                if (path.isEmpty()) {
                    path = "/";
                }
            }
            String query = raw.getRawQuery();
            StringBuilder normalized = new StringBuilder(scheme).append("://").append(authority).append(path);
            if (query != null && !query.isEmpty()) {
                normalized.append('?').append(query);
            }
            return normalized.toString();
        } catch (Exception e) {
            return url.strip();
        }
    }

    /**
     * sha256(规范化 URL) 十六进制小写（64 字符）
     */
    public static String urlHash(String url) {
        return sha256Hex(normalize(url));
    }

    /**
     * 同 host 判定（含端口）——限速与 robots 缓存的 key
     */
    public static String hostKey(String url) {
        try {
            URI raw = URI.create(url.strip());
            String scheme = raw.getScheme() == null ? "" : raw.getScheme().toLowerCase(Locale.ROOT);
            String host = raw.getHost() == null ? "" : raw.getHost().toLowerCase(Locale.ROOT);
            if (host.isEmpty()) {
                return url;
            }
            int port = raw.getPort();
            if (port == -1) {
                port = "https".equals(scheme) ? 443 : 80;
            }
            return scheme + "://" + host + ":" + port;
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * robots.txt 位置：同 scheme/host（保留非默认端口）根路径
     */
    public static String robotsUrl(String url) {
        try {
            URI raw = URI.create(url.strip());
            String scheme = raw.getScheme() == null ? "" : raw.getScheme().toLowerCase(Locale.ROOT);
            String host = raw.getHost() == null ? "" : raw.getHost().toLowerCase(Locale.ROOT);
            if (host.isEmpty()) {
                return null;
            }
            String authority = host;
            if (raw.getPort() != -1 && !isDefaultPort(scheme, raw.getPort())) {
                authority = host + ":" + raw.getPort();
            }
            return scheme + "://" + authority + "/robots.txt";
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * path + query（robots Disallow 前缀匹配的对象；raw 组件不解码）
     */
    public static String pathAndQuery(String url) {
        try {
            URI raw = URI.create(url.strip());
            String path = raw.getRawPath() == null || raw.getRawPath().isEmpty() ? "/" : raw.getRawPath();
            return raw.getRawQuery() == null || raw.getRawQuery().isEmpty() ? path : path + "?" + raw.getRawQuery();
        } catch (Exception e) {
            return "/";
        }
    }

    private static boolean isDefaultPort(String scheme, int port) {
        return ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                String chunk = Integer.toHexString(Byte.toUnsignedInt(b));
                if (chunk.length() == 1) {
                    hex.append('0');
                }
                hex.append(chunk);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 摘要实现", e);
        }
    }
}
