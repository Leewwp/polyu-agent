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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 官网活动日历 JSON API 解析器（U12-A A3，doc 19 §2 JSON_API 型）
 *
 * <p>端点 {@code /en/api/sitecore/calendar/get?id=…&date=YYYY/MM}（大写 Calendar
 * 会 302 到小写，OkHttp 默认跟随重定向即覆盖）；响应结构 {@code events[]} 每条含
 * title / eventStartDate（ISO 带 +08:00）/ start-date / type / content（HTML 片段，
 * 内嵌详情页绝对链接）。条目 URL 从 content 的第一个 {@code <a href>} 提取——
 * 无链接的条目跳过（无永久外链不满足「卡片永远外链原文」纪律）。
 */
public final class NewsEventsJsonParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private NewsEventsJsonParser() {
    }

    /**
     * 解析 events API 响应
     *
     * @throws NewsFetchException JSON 不合法或 events 零条目（防结构改版静默空结果）
     */
    public static List<EventEntry> parse(byte[] json) {
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(new String(json, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new NewsFetchException("events JSON 解析失败: " + e.getMessage(), false, e);
        }
        JsonNode events = root.path("events");
        if (!events.isArray()) {
            throw new NewsFetchException("events JSON 缺少 events 数组（结构改版嫌疑，fail-closed）", false);
        }
        List<EventEntry> parsed = new ArrayList<>();
        for (JsonNode event : events) {
            String title = event.path("title").asText(null);
            String detailUrl = extractDetailUrl(event.path("content").asText(""));
            if (title == null || title.isBlank() || detailUrl == null) {
                continue;
            }
            Date start = parseInstant(event.path("eventStartDate").asText(null));
            if (start == null) {
                start = parseDateOnly(event.path("start-date").asText(null));
            }
            parsed.add(new EventEntry(detailUrl, title.trim(), start,
                    event.path("type").asText(null)));
        }
        if (parsed.isEmpty()) {
            throw new NewsFetchException("events 解析零条目（无链接条目全跳过或结构改版，fail-closed）", false);
        }
        return parsed;
    }

    /**
     * content HTML 片段里第一个绝对链接（详情页）
     */
    private static String extractDetailUrl(String contentHtml) {
        if (contentHtml == null || contentHtml.isBlank()) {
            return null;
        }
        return Jsoup.parse(contentHtml).select("a[href]").stream()
                .map(anchor -> anchor.attr("abs:href").isBlank() ? anchor.attr("href") : anchor.attr("abs:href"))
                .filter(href -> href.startsWith("http://") || href.startsWith("https://"))
                .findFirst()
                .orElse(null);
    }

    private static Date parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Date.from(OffsetDateTime.parse(value.trim()).toInstant());
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * "2026-09-14" 裸日期按 HKT 零点解释（doc 19 §13-3 时区统一）
     */
    private static Date parseDateOnly(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Date.from(java.time.LocalDate.parse(value.trim())
                    .atStartOfDay(java.time.ZoneId.of("Asia/Hong_Kong")).toInstant());
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * 活动条目：详情页链接 + 标题 + 开始时间 + 类型原文
     */
    public record EventEntry(String link, String title, Date start, String typeHint) {
    }
}
