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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * robots.txt 解析（移植自 scripts/crawl/fetch_sources.py）
 *
 * <p>组切分：User-agent 行开组（前一组已有规则时该行开启新组）；组内消费
 * Disallow / Crawl-delay（其余字段忽略）。组匹配优先级：与本 UA 全串或首
 * token（如 polyuguide-feed）匹配的组 &gt; `*` 组 &gt; 无规则（允许全部）。
 * 空 Disallow（`Disallow:` 无值）表示允许全部，不入约束列表。
 */
public final class RobotsTxtParser {

    private RobotsTxtParser() {
    }

    /**
     * 解析 robots.txt 文本，取适用本 UA 的规则组
     */
    public static RobotsRules parse(String robotsText, String userAgent) {
        Map<String, List<String>> groupByUa = splitGroups(robotsText == null ? "" : robotsText);
        String uaFull = userAgent == null ? "" : userAgent.strip().toLowerCase(Locale.ROOT);
        String uaToken = uaFull.split("/", 2)[0].strip();

        List<String> starRules = null;
        List<String> specificRules = null;
        for (Map.Entry<String, List<String>> entry : groupByUa.entrySet()) {
            String groupUa = entry.getKey();
            if ("*".equals(groupUa) && starRules == null) {
                starRules = entry.getValue();
            }
            if ((groupUa.equals(uaFull) || groupUa.equals(uaToken) || matchesTokenList(groupUa, uaToken))
                    && specificRules == null) {
                specificRules = entry.getValue();
            }
        }
        List<String> chosen = specificRules != null ? specificRules : (starRules != null ? starRules : List.of());

        Double crawlDelay = null;
        List<String> disallow = new ArrayList<>();
        for (String rule : chosen) {
            int colon = rule.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String name = rule.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = rule.substring(colon + 1).strip();
            if ("crawl-delay".equals(name) && !value.isEmpty()) {
                try {
                    crawlDelay = Double.parseDouble(value);
                } catch (NumberFormatException ignore) {
                    // 非数值 Crawl-delay 视为缺失
                }
            } else if ("disallow".equals(name) && !value.isEmpty()) {
                disallow.add(value);
            }
        }
        return new RobotsRules(crawlDelay, disallow);
    }

    /**
     * 切组：UA 名（可能逗号多列）→ 该组规则行列表（保持出现顺序）
     */
    private static Map<String, List<String>> splitGroups(String text) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        List<String> currentUseras = new ArrayList<>();
        boolean groupHasRule = false;
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.split("#", 2)[0].strip();
            if (line.isEmpty() || !line.contains(":")) {
                continue;
            }
            int colon = line.indexOf(':');
            String name = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            if ("user-agent".equals(name)) {
                if (groupHasRule) {
                    // 上一组已有规则，本行开启新组
                    currentUseras = new ArrayList<>();
                    groupHasRule = false;
                }
                if (!value.isEmpty()) {
                    currentUseras.add(value.toLowerCase(Locale.ROOT));
                }
            } else if ("disallow".equals(name) || "allow".equals(name) || "crawl-delay".equals(name)) {
                if (!currentUseras.isEmpty()) {
                    groupHasRule = true;
                    for (String ua : currentUseras) {
                        groups.computeIfAbsent(ua, k -> new ArrayList<>()).add(name + ":" + value);
                    }
                }
            }
        }
        return groups;
    }

    /**
     * robots 组 UA 可能是逗号分隔多列（如 "Googlebot, Bingbot"）
     */
    private static boolean matchesTokenList(String groupUa, String uaToken) {
        if (!groupUa.contains(",")) {
            return false;
        }
        for (String part : groupUa.split(",")) {
            if (part.strip().equals(uaToken)) {
                return true;
            }
        }
        return false;
    }
}
