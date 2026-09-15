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

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * HTML 列表页条目发现解析器（HTML_LIST 型）
 *
 * <p>覆盖三个已知模板族，按「首个产出条目的族胜出」自动探测——扩源=加
 * t_news_source 行即可命中同族模板，无代码改动：
 * <ol>
 *   <li>PolyU 官网列表族（media-releases / campus-releases 同组件库）：
 *       {@code a.border-hover-shadow-list__itm}，标题 {@code .long-img-side-blk__title}、
 *       日期 {@code .p-date}（"10 Sep, 2026"）、类别 {@code .color-plate-text__color}；</li>
 *   <li>PR Newswire 通稿族：{@code a.newsreleaseconsolidatelink}，日期在
 *       {@code h3 > small}（"Jul 20, 2026, 00:00 ET"），标题=h3 文本去 small；</li>
 *   <li>PolyU recent-focus 族：{@code a[href^=/recent-focus/YYYYMMDD_slug/]}，
 *       卡片无日期（日期在 URL slug），标题取最近 .slogan-open-blk 容器内
 *       .slogan-tag__slogan 文本。</li>
 * </ol>
 *
 * <p>实现裁量（已记录）：设计上写「复用 HtmlDocumentParser」，但该解析器的
 * Block 模型不保留链接 href，无法承载列表发现的 URL 抽取——列表发现改用同一
 * jsoup 库直选；HtmlDocumentParser 的复用位在详情正文抽取（LLM 摘要输入）。
 */
public final class NewsHtmlListParser {

    /**
     * "10 Sep, 2026" / "Jul 20, 2026"
     */
    private static final DateTimeFormatter ENGLISH_DATE =
            DateTimeFormatter.ofPattern("d MMM, uuuu", Locale.ENGLISH);

    private static final DateTimeFormatter ENGLISH_DATE_ALT =
            DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.ENGLISH);

    /**
     * media-releases 详情 URL 内的 /2026/0910_slug/ 日期段
     */
    private static final Pattern SLUG_DATE_WITH_YEAR = Pattern.compile("/(\\d{4})/(\\d{2})(\\d{2})_[^/]+/");

    /**
     * recent-focus URL 内的 /20260819_slug/ 日期段
     */
    private static final Pattern SLUG_DATE_COMPACT = Pattern.compile("/(\\d{4})(\\d{2})(\\d{2})_[^/]+/");

    /**
     * 官网列表页无时区信息的日期一律按 HKT（+08:00）解释（时区统一口径）
     */
    private static final ZoneId SITE_ZONE = ZoneId.of("Asia/Hong_Kong");

    private NewsHtmlListParser() {
    }

    /**
     * 解析列表页 HTML，产出条目（link 已绝对化）；零条目即抛（首页 fail-closed 口径）
     *
     * @throws NewsFetchException 零条目（模板改版嫌疑，fail-closed）
     */
    public static List<ListEntry> parse(byte[] html, String baseUri) {
        return parse(html, baseUri, true);
    }

    /**
     * 宽和变体：翻页场景后续页零条目属正常到头（而非模板改版），由调用方以
     * {@code failClosed=false} 静默收空列表；首页仍应走 fail-closed 重载
     */
    public static List<ListEntry> parse(byte[] html, String baseUri, boolean failClosed) {
        Document document = Jsoup.parse(new String(html, StandardCharsets.UTF_8), baseUri);
        List<ListEntry> entries = new ArrayList<>();
        entries.addAll(parseOfficialListFamily(document));
        if (!entries.isEmpty()) {
            return entries;
        }
        entries.addAll(parsePrnFamily(document));
        if (!entries.isEmpty()) {
            return entries;
        }
        entries.addAll(parseRecentFocusFamily(document));
        if (failClosed && entries.isEmpty()) {
            throw new NewsFetchException("HTML 列表页解析零条目（模板改版嫌疑，fail-closed）", false);
        }
        return entries;
    }

    private static List<ListEntry> parseOfficialListFamily(Document document) {
        List<ListEntry> entries = new ArrayList<>();
        for (Element anchor : document.select("a.border-hover-shadow-list__itm")) {
            String link = anchor.absUrl("href");
            if (link.isBlank()) {
                continue;
            }
            String title = textOrNull(anchor.selectFirst(".long-img-side-blk__title"));
            String dateText = textOrNull(anchor.selectFirst(".p-date"));
            String category = textOrNull(anchor.selectFirst(".color-plate-text__color"));
            entries.add(new ListEntry(link, title, dateText, category, dateFromSlug(link)));
        }
        return entries;
    }

    private static List<ListEntry> parsePrnFamily(Document document) {
        List<ListEntry> entries = new ArrayList<>();
        for (Element anchor : document.select("a.newsreleaseconsolidatelink")) {
            String link = anchor.absUrl("href");
            if (link.isBlank()) {
                continue;
            }
            Element heading = anchor.selectFirst("h3");
            if (heading == null) {
                continue;
            }
            String dateText = textOrNull(heading.selectFirst("small"));
            String title = heading.ownText().trim();
            if (title.isEmpty()) {
                // 标题可能在 small 之外的子 span（多语卡），取 h3 全文去日期
                String full = heading.text().trim();
                if (dateText != null && full.startsWith(dateText)) {
                    full = full.substring(dateText.length()).trim();
                }
                title = full;
            }
            entries.add(new ListEntry(link, title, dateText, null, null));
        }
        return entries;
    }

    private static List<ListEntry> parseRecentFocusFamily(Document document) {
        List<ListEntry> entries = new ArrayList<>();
        for (Element anchor : document.select("a[href]")) {
            String href = anchor.attr("href");
            Matcher slugDate = SLUG_DATE_COMPACT.matcher(href);
            if (!slugDate.find() || !href.contains("/recent-focus/")) {
                continue;
            }
            String link = anchor.absUrl("href");
            if (link.isBlank() || entries.stream().anyMatch(e -> e.link().equals(link))) {
                continue;
            }
            Element container = anchor.closest(".slogan-open-blk");
            if (container == null) {
                container = anchor.parent();
                while (container != null && !container.classNames().contains("slogan-open-blk")
                        && !container.tagName().equals("body")) {
                    container = container.parent();
                }
            }
            String title = null;
            if (container != null) {
                title = textOrNull(container.selectFirst(".slogan-tag__slogan"));
            }
            LocalDate date = LocalDate.of(
                    Integer.parseInt(slugDate.group(1)),
                    Integer.parseInt(slugDate.group(2)),
                    Integer.parseInt(slugDate.group(3)));
            entries.add(new ListEntry(link, title, null, null,
                    Date.from(date.atStartOfDay(SITE_ZONE).toInstant())));
        }
        return entries;
    }

    /**
     * 解析日期文本（"10 Sep, 2026" / "Jul 20, 2026, 00:00 ET"），按 HKT 解释
     */
    static java.util.Date parseDateText(String dateText) {
        if (dateText == null || dateText.isBlank()) {
            return null;
        }
        String cleaned = dateText.trim();
        // 去 ", 00:00 ET" 时区后缀（ET 与站点日期不同日风险可忽略：列表给的是发布日）
        int commaIdx = cleaned.indexOf(',');
        int secondComma = commaIdx >= 0 ? cleaned.indexOf(',', commaIdx + 1) : -1;
        if (secondComma > 0) {
            cleaned = cleaned.substring(0, secondComma);
        }
        for (DateTimeFormatter formatter : List.of(ENGLISH_DATE, ENGLISH_DATE_ALT)) {
            try {
                LocalDate date = LocalDate.parse(cleaned, formatter);
                return Date.from(date.atStartOfDay(SITE_ZONE).toInstant());
            } catch (Exception ignore) {
                // 换下一格式
            }
        }
        return null;
    }

    /**
     * 详情 URL slug 日期回退（/2026/0910_slug/）
     */
    static java.util.Date dateFromSlug(String link) {
        Matcher matcher = SLUG_DATE_WITH_YEAR.matcher(link);
        if (!matcher.find()) {
            return null;
        }
        LocalDate date = LocalDate.of(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)));
        return Date.from(date.atStartOfDay(SITE_ZONE).toInstant());
    }

    private static String textOrNull(Element element) {
        if (element == null) {
            return null;
        }
        String text = element.text();
        return text.isBlank() ? null : text.trim();
    }

    /**
     * 列表条目：绝对链接 + 标题 + 日期文本 + 类别原文 + slug 日期（回退）
     *
     * @param slugDate URL slug 解析出的日期（recent-focus 主路径；其他族回退用）
     */
    public record ListEntry(String link,
                            String title,
                            String dateText,
                            String categoryHint,
                            Date slugDate) {
    }
}
