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
 * <p>覆盖四个已知模板族，按「首个产出条目的族胜出」自动探测——扩源=加
 * t_news_source 行即可命中同族模板，无代码改动：
 * <ol>
 *   <li>PolyU 官网列表族（media-releases / campus-releases / 批 2 alumni-news、
 *       fb-news、fhss-news 同组件库）：{@code a.border-hover-shadow-list__itm}，
 *       标题 {@code .long-img-side-blk__title}、日期 {@code .p-date}（"10 Sep, 2026"）、
 *       类别 {@code .color-plate-text__color}；</li>
 *   <li>PR Newswire 通稿族：{@code a.newsreleaseconsolidatelink}，日期在
 *       {@code h3 > small}（"Jul 20, 2026, 00:00 ET"），标题=h3 文本去 small；</li>
 *   <li>PolyU recent-focus 族：{@code a[href^=/recent-focus/YYYYMMDD_slug/]}，
 *       卡片无日期（日期在 URL slug），标题取最近 .slogan-open-blk 容器内
 *       .slogan-tag__slogan 文本。</li>
 *   <li>图书馆 Drupal views 族（#188 批 2 lib-news，www.lib.polyu.edu.hk）：
 *       {@code div.views-row h3.views-field-title a}，日期=行内
 *       .views-news-events-posted 的裸文本节点（"Friday, September 18, 2026 - 08:30"，
 *       前 .badge 为类别 News/Event/Notice）。列表页无 URL slug 日期可回退，
 *       日期元素缺失的行 publishTime=null 由调用方跳过。</li>
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
     * "September 18, 2026"（图书馆 Drupal 站星期前缀剥离后的全月名形态）
     */
    private static final DateTimeFormatter ENGLISH_DATE_FULL_MONTH =
            DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.ENGLISH);

    /**
     * "30 Sep 2026"（SPEED 站 d MMM uuuu 无逗号，#276 第 5 族）
     */
    private static final DateTimeFormatter ENGLISH_DATE_NO_COMMA =
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);

    /**
     * media-releases 详情 URL 内的 /2026/0910_slug/ 日期段
     */
    private static final Pattern SLUG_DATE_WITH_YEAR = Pattern.compile("/(\\d{4})/(\\d{2})(\\d{2})_[^/]+/");

    /**
     * recent-focus URL 内的 /20260819_slug/ 日期段
     */
    private static final Pattern SLUG_DATE_COMPACT = Pattern.compile("/(\\d{4})(\\d{2})(\\d{2})_[^/]+/");

    /**
     * 图书馆 Drupal 站日期文本 "Friday, September 18, 2026 - 08:30" 的星期前缀剥离：
     * 捕获组=裸日期 "September 18, 2026"（交由 {@link #ENGLISH_DATE_FULL_MONTH} 解析——
     * 短月名 {@link #ENGLISH_DATE_ALT} 不吃全月名，java.time 严格解析）
     */
    private static final Pattern WEEKDAY_PREFIXED_DATE =
            Pattern.compile("(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday),\\s*([A-Za-z]+\\s+\\d{1,2},\\s*\\d{4})");

    /**
     * 官网列表页无时区信息的日期一律按 HKT（+08:00）解释（时区统一口径）
     */
    private static final ZoneId SITE_ZONE = ZoneId.of("Asia/Hong_Kong");

    private NewsHtmlListParser() {
    }

    /**
     * 解析列表页 HTML，产出条目（link 已绝对化）；零条目即抛（首页 fail-closed 口径）
     *
     * @throws NewsFetchStructureException 零条目（模板改版嫌疑，fail-closed；#186）
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
        if (!entries.isEmpty()) {
            return entries;
        }
        entries.addAll(parseLibViewsFamily(document));
        if (entries.isEmpty()) {
            entries.addAll(parseSpeedFamily(document));
        }
        if (failClosed && entries.isEmpty()) {
            throw new NewsFetchStructureException("HTML 列表页解析零条目（模板改版嫌疑，fail-closed）");
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
            // #275：slug 只有日期证据——23:59:59 HKT 归期代表值（非真实发布时刻）
            entries.add(new ListEntry(link, title, null, null,
                    PublishTimePrecision.dateOnlyRepresentative(date)));
        }
        return entries;
    }

    /**
     * 图书馆 Drupal views 族（#188 批 2 lib-news）：行容器 .views-row，标题锚
     * h3.views-field-title a；日期取 .views-news-events-posted 的<b>裸文本节点</b>
     * （.badge 子元素是类别 News/Event/Notice，不得混入日期文本）——&amp;nbsp; 经
     * jsoup 以 \u00A0 保留，统一替换为空格后 trim
     */
    private static List<ListEntry> parseLibViewsFamily(Document document) {
        List<ListEntry> entries = new ArrayList<>();
        for (Element anchor : document.select("div.views-row h3.views-field-title a[href]")) {
            String link = anchor.absUrl("href");
            if (link.isBlank()) {
                continue;
            }
            Element row = anchor.closest(".views-row");
            if (row == null) {
                continue;
            }
            String dateText = null;
            Element posted = row.selectFirst(".views-news-events-posted");
            if (posted != null) {
                List<org.jsoup.nodes.TextNode> textNodes = posted.textNodes();
                if (!textNodes.isEmpty()) {
                    String candidate = textNodes.get(textNodes.size() - 1).getWholeText()
                            .replace('\u00A0', ' ').trim();
                    dateText = candidate.isEmpty() ? null : candidate;
                }
            }
            entries.add(new ListEntry(link, textOrNull(anchor), dateText,
                    textOrNull(row.selectFirst(".views-news-events-posted .badge")), null));
        }
        return entries;
    }

    /**
     * Drupal 站 "Friday, September 18, 2026 - 08:30" 的 "- HH:mm" 尾巴
     * （站点时区 HKT，#275 起保留真实时刻而非截到日粒度）
     */
    private static final Pattern HKT_TIME_SUFFIX =
            Pattern.compile("-\\s*(\\d{1,2}):(\\d{2})\\s*$");

    /**
     * PR Newswire "Jul 20, 2026, 00:00 ET" 的 ", HH:mm ET" 尾巴：
     * ET 按 America/New_York 的该日期夏令时/冬令时换算为真实瞬时（#275——
     * 旧实现截掉时刻属精度丢失；ET 与 HKT 日期不同日的跨日结果如实保留）
     */
    private static final Pattern ET_TIME_SUFFIX =
            Pattern.compile(",\\s*(\\d{1,2}):(\\d{2})\\s+ET\\s*$");

    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");

    /**
     * 日期文本解析结果（#275）：instant=归期代表瞬时；precision=精度标记
     * （date=只有日期证据→D 23:59:59 HKT 代表值；datetime=真实瞬时证据）
     */
    public record ParsedDate(java.util.Date instant, String precision) {
    }

    /**
     * SPEED 专上学院新闻族（#276 第 5 族，#203 已核定）：
     * {@code a.news-list-item} 锚，标题 {@code .news-list-item__title}、
     * 日期 {@code .news-list-item__date}（"30 Sep 2026"，d MMM uuuu 无逗号）。
     * 只取列表锚——置顶段（a.news-feature-news，旧文 2025-2024）不纳入。
     */
    private static List<ListEntry> parseSpeedFamily(Document document) {
        List<ListEntry> entries = new ArrayList<>();
        for (Element anchor : document.select("a.news-list-item")) {
            String link = anchor.absUrl("href");
            if (link.isBlank()) {
                continue;
            }
            entries.add(new ListEntry(link,
                    textOrNull(anchor.selectFirst(".news-list-item__title")),
                    textOrNull(anchor.selectFirst(".news-list-item__date")),
                    null, null));
        }
        return entries;
    }

    /**
     * 解析日期文本，按输入精度分流（#275）：
     * <ul>
     *   <li>"Friday, September 18, 2026 - 08:30"（lib Drupal）→ HKT 真实时刻，datetime；</li>
     *   <li>"Jul 20, 2026, 00:00 ET"（PRN）→ America/New_York 夏令时/冬令时换算瞬时，datetime；</li>
     *   <li>"10 Sep, 2026" 等纯日期 → D 23:59:59 HKT 归期代表值，date。</li>
     * </ul>
     */
    static ParsedDate parseDate(String dateText) {
        if (dateText == null || dateText.isBlank()) {
            return null;
        }
        String cleaned = dateText.trim();
        Matcher weekdayPrefixed = WEEKDAY_PREFIXED_DATE.matcher(cleaned);
        if (weekdayPrefixed.find()) {
            // 只剥星期前缀、保留裸日期起的全部内容（含 "- HH:mm" 尾巴，#275）
            cleaned = cleaned.substring(weekdayPrefixed.start(1)).trim();
        }
        // lib HKT 时刻尾巴（须在剥星期前缀后、且优先于 ET 后缀识别）
        Matcher hktTime = HKT_TIME_SUFFIX.matcher(cleaned);
        if (hktTime.find()) {
            LocalDate date = parseBareDate(cleaned.substring(0, hktTime.start()).trim());
            if (date != null) {
                return new ParsedDate(
                        PublishTimePrecision.hktInstant(date,
                                Integer.parseInt(hktTime.group(1)), Integer.parseInt(hktTime.group(2))),
                        PublishTimePrecision.DATETIME);
            }
            return null;
        }
        // PRN ET 时刻尾巴：按该日期在 America/New_York 的实际偏移换算
        Matcher etTime = ET_TIME_SUFFIX.matcher(cleaned);
        if (etTime.find()) {
            LocalDate date = parseBareDate(cleaned.substring(0, etTime.start()).trim());
            if (date != null) {
                java.time.ZonedDateTime etInstant = date.atTime(
                                Integer.parseInt(etTime.group(1)),
                                Integer.parseInt(etTime.group(2)))
                        .atZone(ET_ZONE);
                return new ParsedDate(Date.from(etInstant.toInstant()), PublishTimePrecision.DATETIME);
            }
            return null;
        }
        LocalDate date = parseBareDate(cleaned);
        if (date != null) {
            return new ParsedDate(PublishTimePrecision.dateOnlyRepresentative(date), PublishTimePrecision.DATE);
        }
        return null;
    }

    /** 裸日期解析（三格式轮试）；不含任何时刻/时区尾巴 */
    private static LocalDate parseBareDate(String text) {
        for (DateTimeFormatter formatter : List.of(ENGLISH_DATE, ENGLISH_DATE_ALT, ENGLISH_DATE_FULL_MONTH, ENGLISH_DATE_NO_COMMA)) {
            try {
                return LocalDate.parse(text, formatter);
            } catch (Exception ignore) {
                // 换下一格式
            }
        }
        return null;
    }

    /**
     * 兼容旧调用面的瞬时视图（精度由调用面另行判定）
     */
    static java.util.Date parseDateText(String dateText) {
        ParsedDate parsed = parseDate(dateText);
        return parsed == null ? null : parsed.instant();
    }

    /**
     * 详情 URL slug 日期回退（/2026/0910_slug/）：只有日期证据——
     * D 23:59:59 HKT 归期代表值（#275）
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
        return PublishTimePrecision.dateOnlyRepresentative(date);
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
