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

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTML_LIST 型解析测试
 *
 * <p>media-releases fixture 裁自 2026-09-10 实采页面（三族模板之官网列表族）；
 * PRN fixture 裁自同日实采（PRN 通稿族）；recent-focus 用内联最小结构验证
 * 「日期在 URL slug」路径。日期文本 "10 Sep, 2026" 按 HKT 解释。
 *
 * <p>#188 批 2 四源 fixture 裁自 2026-09-30 实采（alumni-news/fb-news/fhss-news
 * 命中官网列表族零代码改动；lib-news 为图书馆 Drupal views 族——新族最小扩展），
 * 哈希与裁剪口径见 fixtures/news/README.md。
 */
class NewsHtmlListParserTests {

    private byte[] fixture(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/news/" + name)) {
            assertNotNull(in, "fixture 缺失：/fixtures/news/" + name);
            return in.readAllBytes();
        }
    }

    @Test
    void officialListFamilyYieldsTitleDateCategory() throws Exception {
        List<NewsHtmlListParser.ListEntry> entries =
                NewsHtmlListParser.parse(fixture("media-releases.html"), "https://www.polyu.edu.hk/media/media-releases/");
        assertEquals(4, entries.size());

        NewsHtmlListParser.ListEntry first = entries.get(0);
        assertTrue(first.link().startsWith("https://www.polyu.edu.hk/media/media-releases/2026/0910_"));
        assertTrue(first.title().startsWith("PolyU and Citybus sign MoU"));
        assertEquals("10 Sep, 2026", first.dateText());
        assertEquals("Research & Innovation", first.categoryHint());

        // 日期文本解析：裸日期按 HKT 零点解释（§13-3）
        Calendar parsed = Calendar.getInstance(TimeZone.getTimeZone("Asia/Hong_Kong"));
        parsed.setTime(NewsHtmlListParser.parseDateText(first.dateText()));
        assertEquals(2026, parsed.get(Calendar.YEAR));
        assertEquals(Calendar.SEPTEMBER, parsed.get(Calendar.MONTH));
        assertEquals(10, parsed.get(Calendar.DAY_OF_MONTH));
        assertEquals(0, parsed.get(Calendar.HOUR_OF_DAY));
    }

    @Test
    void slugDateFallbackParsesYearMonthDay() {
        java.util.Date date = NewsHtmlListParser.dateFromSlug(
                "https://www.polyu.edu.hk/media/media-releases/2026/0904_polyu-and-diagens-tech/");
        assertNotNull(date);
        Calendar parsed = Calendar.getInstance(TimeZone.getTimeZone("Asia/Hong_Kong"));
        parsed.setTime(date);
        assertEquals(2026, parsed.get(Calendar.YEAR));
        assertEquals(Calendar.SEPTEMBER, parsed.get(Calendar.MONTH));
        assertEquals(4, parsed.get(Calendar.DAY_OF_MONTH));
    }

    @Test
    void prnFamilyYieldsTitleAndEtDate() throws Exception {
        List<NewsHtmlListParser.ListEntry> entries =
                NewsHtmlListParser.parse(fixture("prn-list.html"), "https://www.prnewswire.com/news/the-hong-kong-polytechnic-university-(polyu)/");
        assertEquals(3, entries.size());

        NewsHtmlListParser.ListEntry first = entries.get(0);
        assertEquals("https://www.prnewswire.com/apac/news-releases/"
                + "advancing-innovation-through-emerging-technologies-"
                + "polyu-launches-free-course-on-advanced-technologies-302829356.html", first.link());
        assertEquals("Jul 20, 2026, 00:00 ET", first.dateText());
        assertTrue(first.title().startsWith("Advancing innovation through emerging technologies"));

        // "MMM d, uuuu" 格式 + 去时区后缀
        java.util.Date parsed = NewsHtmlListParser.parseDateText(first.dateText());
        assertNotNull(parsed);
        Calendar hkt = Calendar.getInstance(TimeZone.getTimeZone("Asia/Hong_Kong"));
        hkt.setTime(parsed);
        assertEquals(2026, hkt.get(Calendar.YEAR));
        assertEquals(Calendar.JULY, hkt.get(Calendar.MONTH));
        assertEquals(20, hkt.get(Calendar.DAY_OF_MONTH));
    }

    @Test
    void recentFocusFamilyTakesDateFromUrlSlug() {
        String html = """
                <!DOCTYPE html><html><body><main>
                <div class="accordion blk-jump-list">
                  <div class="slogan-open-blk slogan-open-blk--wide">
                    <a href="/recent-focus/20260819_silk-road-project-2026/"><img src="x.jpg" alt=""></a>
                    <a href="javascript:void(0)" role="button" class="slogan-tag slogan-tag--wide collapsed">
                      <p class="slogan-tag__slogan"><span><span>Silk Road Project 2026 Kicks Off</span></span></p>
                    </a>
                  </div>
                </div>
                </main></body></html>
                """;
        List<NewsHtmlListParser.ListEntry> entries =
                NewsHtmlListParser.parse(html.getBytes(), "https://www.polyu.edu.hk/recent-focus/");
        assertEquals(1, entries.size());
        NewsHtmlListParser.ListEntry entry = entries.get(0);
        assertEquals("https://www.polyu.edu.hk/recent-focus/20260819_silk-road-project-2026/", entry.link());
        assertEquals("Silk Road Project 2026 Kicks Off", entry.title());
        assertNotNull(entry.slugDate());
        Calendar hkt = Calendar.getInstance(TimeZone.getTimeZone("Asia/Hong_Kong"));
        hkt.setTime(entry.slugDate());
        assertEquals(2026, hkt.get(Calendar.YEAR));
        assertEquals(Calendar.AUGUST, hkt.get(Calendar.MONTH));
        assertEquals(19, hkt.get(Calendar.DAY_OF_MONTH));
    }

    @Test
    void unknownTemplateFailsClosed() {
        assertThrows(NewsFetchException.class, () ->
                NewsHtmlListParser.parse("<html><body><p>nothing here</p></body></html>".getBytes(), "https://example.com/"));
    }

    // ================== #188 批 2 四源（2026-09-30 实采 fixture） ==================

    @Test
    void alumniNewsFixtureYieldsEntriesWithoutCategory() throws Exception {
        List<NewsHtmlListParser.ListEntry> entries = NewsHtmlListParser.parse(
                fixture("alumni-news.html"), "https://www.polyu.edu.hk/alumni/news/");
        assertEquals(3, entries.size());
        NewsHtmlListParser.ListEntry first = entries.get(0);
        assertEquals("https://www.polyu.edu.hk/alumni/news/2026/20260923-opaa-master-class/", first.link());
        assertTrue(first.title().startsWith("【OPAA Master Class 2026】"));
        assertEquals("23 Sep, 2026", first.dateText());
        assertNull(first.categoryHint());
        // 官网列表族既有日期文本路径复用（同 media-releases）
        Calendar hkt = Calendar.getInstance(TimeZone.getTimeZone("Asia/Hong_Kong"));
        hkt.setTime(NewsHtmlListParser.parseDateText(first.dateText()));
        assertEquals(2026, hkt.get(Calendar.YEAR));
        assertEquals(Calendar.SEPTEMBER, hkt.get(Calendar.MONTH));
        assertEquals(23, hkt.get(Calendar.DAY_OF_MONTH));
    }

    @Test
    void fbNewsFixtureYieldsTitleDateCategory() throws Exception {
        List<NewsHtmlListParser.ListEntry> entries = NewsHtmlListParser.parse(
                fixture("fb-news.html"), "https://www.polyu.edu.hk/fb/news-events/news/");
        assertEquals(3, entries.size());
        NewsHtmlListParser.ListEntry first = entries.get(0);
        assertEquals("https://www.polyu.edu.hk/fb/news-events/news/2026/01092026/", first.link());
        assertTrue(first.title().startsWith("Result Announcement: Outstanding Alumni Award"));
        assertEquals("1 Sep, 2026", first.dateText());
        assertEquals("News", first.categoryHint());
    }

    @Test
    void fhssNewsFixtureYieldsEntriesIncludingAbsoluteCrossSiteLinks() throws Exception {
        List<NewsHtmlListParser.ListEntry> entries = NewsHtmlListParser.parse(
                fixture("fhss-news.html"), "https://www.polyu.edu.hk/fhss/news-and-events/news-and-events/");
        assertEquals(3, entries.size());
        // 首条为跨栏目绝对链接（学院列表直出媒体发布），次条为本栏目相对链接——两种形态都绝对化
        assertTrue(entries.get(0).link().startsWith("https://www.polyu.edu.hk/en/media/media-releases/2026/0902_"));
        assertEquals("https://www.polyu.edu.hk/fhss/news-and-events/news-and-events/2026/20260831/", entries.get(1).link());
        assertEquals("2 Sep, 2026", entries.get(0).dateText());
        assertEquals("News", entries.get(0).categoryHint());
    }

    @Test
    void libNewsFixtureYieldsDrupalViewsRows() throws Exception {
        List<NewsHtmlListParser.ListEntry> entries = NewsHtmlListParser.parse(
                fixture("lib-news.html"), "https://www.lib.polyu.edu.hk/news");
        assertEquals(6, entries.size());
        NewsHtmlListParser.ListEntry first = entries.get(0);
        assertEquals("https://www.lib.polyu.edu.hk/news/2026-09-18/readpolyu-meet-author-%E2%80%94-chen-qiufan", first.link());
        assertTrue(first.title().startsWith("READ@PolyU: Meet the Author"));
        // 日期文本=行内裸文本节点（.badge 类别不混入），"- HH:mm" 截到日粒度按 HKT 解释
        assertEquals("Friday, September 18, 2026 - 08:30", first.dateText());
        assertEquals("News", first.categoryHint());
        Calendar hkt = Calendar.getInstance(TimeZone.getTimeZone("Asia/Hong_Kong"));
        hkt.setTime(NewsHtmlListParser.parseDateText(first.dateText()));
        assertEquals(2026, hkt.get(Calendar.YEAR));
        assertEquals(Calendar.SEPTEMBER, hkt.get(Calendar.MONTH));
        assertEquals(18, hkt.get(Calendar.DAY_OF_MONTH));
        // URL 无日期段的行（/news/new-ai-workstations-...）日期仍取自 posted 文本
        assertTrue(entries.get(3).link().endsWith("/news/new-ai-workstations-notebooks-library-i-space"));
        assertEquals("Tuesday, March 31, 2026 - 08:30", entries.get(3).dateText());
        // Event 徽标行（类别提示透传）
        assertEquals("Event", entries.get(5).categoryHint());
    }

    @Test
    void weekdayPrefixedFullMonthDateTextParses() {
        Calendar hkt = Calendar.getInstance(TimeZone.getTimeZone("Asia/Hong_Kong"));
        hkt.setTime(NewsHtmlListParser.parseDateText("Monday, August 24, 2026 - 11:45"));
        assertEquals(2026, hkt.get(Calendar.YEAR));
        assertEquals(Calendar.AUGUST, hkt.get(Calendar.MONTH));
        assertEquals(24, hkt.get(Calendar.DAY_OF_MONTH));
    }

    @Test
    void emptyBatch2ListPagesFailClosed() {
        // 四源均未配 allow-empty（#186 分类学）：结构有效但零条目=fail-closed →
        // 服务层落 STRUCTURE_MISMATCH（既有 NewsFetchServiceTests 分类映射，此处守解析边界）
        String emptyOfficialList = "<html><body><main><div class=\"border-hover-shadow-list\"></div></main></body></html>";
        assertThrows(NewsFetchStructureException.class, () ->
                NewsHtmlListParser.parse(emptyOfficialList.getBytes(), "https://www.polyu.edu.hk/alumni/news/"));
        String emptyLibViews = "<html><body><main><div class=\"view view-news-events-except-notice-\">"
                + "<div class=\"view-content\"><div class=\"views-row\">"
                + "<h3 class=\"views-field views-field-title\"><span class=\"field-content\"></span></h3>"
                + "</div></div></div></main></body></html>";
        assertThrows(NewsFetchStructureException.class, () ->
                NewsHtmlListParser.parse(emptyLibViews.getBytes(), "https://www.lib.polyu.edu.hk/news"));
    }
}
