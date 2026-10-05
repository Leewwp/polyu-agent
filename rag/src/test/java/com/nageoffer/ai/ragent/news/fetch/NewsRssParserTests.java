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
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;

/**
 * RSS（YouTube Atom）型解析测试
 *
 * <p>fixture 按公开规范合成（本机 DNS 污染未实测端点，已知遗留）：
 * entry = title + link@href（alternate 或无 rel）+ published/updated + media:description。
 */
class NewsRssParserTests {

    private byte[] fixture() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/news/youtube-rss.xml")) {
            assertNotNull(in, "fixture 缺失：/fixtures/news/youtube-rss.xml");
            return in.readAllBytes();
        }
    }

    // ================== #277 三媒体/政府源实采 fixture（部署出口 2026-10-05） ==================

    private static byte[] feedFixture(String name) throws Exception {
        try (InputStream in = NewsRssParserTests.class.getResourceAsStream("/fixtures/news/" + name)) {
            assertNotNull(in, "fixture 缺失：/fixtures/news/" + name);
            return in.readAllBytes();
        }
    }

    @Test
    void scmpEducationFeedParsesWithDescriptionAndPrecisePubDate() throws Exception {
        List<NewsRssParser.RssEntry> entries = NewsRssParser.parse(feedFixture("scmp-education-rss.xml"));
        assertEquals(8, entries.size(), "截取样本 8 条（全量 50）");
        NewsRssParser.RssEntry first = entries.get(0);
        assertTrue(first.link().startsWith("https://www.scmp.com/"), "原文链接直指 SCMP 官网");
        assertNotNull(first.publishTime(), "精确 pubDate（UTC RFC1123）");
        assertTrue(first.description() != null && first.description().contains("University of Hong Kong"),
                "description 保留（八校门准入证据）");
        assertTrue(first.title().startsWith("HKU eyes Northern Metropolis"));
    }

    @Test
    void rthkLocalFeedParsesCdataTitlesWithPreciseHktPubDate() throws Exception {
        List<NewsRssParser.RssEntry> entries = NewsRssParser.parse(feedFixture("rthk-local-rss.xml"));
        assertEquals(8, entries.size(), "截取样本 8 条（全量 20——驻留 ~2 天，覆盖 13h 采集间隔）");
        NewsRssParser.RssEntry first = entries.get(0);
        assertTrue(first.title().contains("owners"), "CDATA 标题正常解出");
        assertTrue(first.link().contains("news.rthk.hk"));
        assertNotNull(first.publishTime(), "精确 pubDate（+0800）");
    }

    @Test
    void giaFeedParsesWithHtmlEntityDescriptions() throws Exception {
        List<NewsRssParser.RssEntry> entries = NewsRssParser.parse(feedFixture("gia-general-en-rss.xml"));
        assertEquals(8, entries.size(), "截取样本 8 条（全量 100）");
        NewsRssParser.RssEntry first = entries.get(0);
        assertTrue(first.link().startsWith("https://www.info.gov.hk/gia/"));
        assertNotNull(first.description(), "含 &nbsp; 实体的 description 保留（fetcher 侧去 HTML）");
    }

    @Test
    void rssFetcherCarriesStrippedHtmlSummaryAsGateEvidence() throws Exception {
        // #277：RssNewsFetcher 随行去 HTML 原始摘要（八校门证据），HTML 标签不残留
        NewsHttpFetchClient client = org.mockito.Mockito.mock(NewsHttpFetchClient.class);
        NewsFetchProperties properties = new NewsFetchProperties();
        NewsSourceDO gia = NewsSourceDO.builder()
                .id(21L).sourceKey("gia-news").platform("media")
                .fetchEndpoint("https://www.info.gov.hk/gia/rss/general_en.xml")
                .fetchStrategy("RSS").enabled(true).build();
        org.mockito.Mockito.when(client.get(gia.getFetchEndpoint())).thenReturn(feedFixture("gia-general-en-rss.xml"));

        List<RawNewsItem> items = new RssNewsFetcher(client, properties).fetch(gia);

        org.junit.jupiter.api.Assertions.assertFalse(items.isEmpty());
        for (RawNewsItem item : items) {
            org.junit.jupiter.api.Assertions.assertNotNull(item.rawSummary(), "原摘要随行");
            org.junit.jupiter.api.Assertions.assertFalse(item.rawSummary().contains("<") && item.rawSummary().contains(">"),
                    "HTML 标签已剥离: " + item.rawSummary().substring(0, Math.min(40, item.rawSummary().length())));
            org.junit.jupiter.api.Assertions.assertEquals("en", item.langRaw());
            org.junit.jupiter.api.Assertions.assertEquals(PublishTimePrecision.DATETIME, item.publishTimePrecision());
        }
    }

    @Test
    void parsesAtomEntriesWithLinkAndPublished() throws Exception {
        List<NewsRssParser.RssEntry> entries = NewsRssParser.parse(fixture());
        assertEquals(3, entries.size());

        NewsRssParser.RssEntry first = entries.get(0);
        assertEquals("https://www.youtube.com/watch?v=fixture0001", first.link());
        assertEquals("PolyU Convocation 2026 Highlights", first.title());
        assertEquals(java.util.Date.from(Instant.parse("2026-09-09T01:00:00Z")), first.publishTime());
    }

    @Test
    void linkWithoutRelAttributeIsAccepted() throws Exception {
        // fixture0002 的 <link href=…> 无 rel 属性（YouTube 实际输出形态之一）
        NewsRssParser.RssEntry second = NewsRssParser.parse(fixture()).get(1);
        assertEquals("https://www.youtube.com/watch?v=fixture0002", second.link());
    }

    @Test
    void malformedXmlFailsClosed() {
        assertThrows(NewsFetchException.class, () -> NewsRssParser.parse("<feed>".getBytes()));
    }

    @Test
    void entriesWithoutLinkAreSkippedButZeroEntriesFails() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry><title>no link</title></entry>
                </feed>
                """;
        assertThrows(NewsFetchException.class, () -> NewsRssParser.parse(xml.getBytes()));
    }

    // ---------- #186：允许空变体（allow-empty 源的「有效空」是健康结果） ----------

    @Test
    void zeroEntriesReturnsEmptyListWhenAllowEmptyWhileGarbageStillFails() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom"></feed>
                """;
        assertTrue(NewsRssParser.parse(xml.getBytes(), false).isEmpty(),
                "allow-empty 源零条目=空列表（调用方归 VALID_EMPTY，源健康）");
        // 垃圾输入不因 allow-empty 冒充有效空：XML 不合法仍抛结构失配
        assertThrows(NewsFetchStructureException.class, () -> NewsRssParser.parse("<feed>".getBytes(), false));
    }

    @Test
    void parsesRss2ItemsWhenNoAtomEntriesPresent() throws Exception {
        // Atom 零条目回落 RSS 2.0（Google News）——link 文本元素/RFC 1123/source 媒体名
        List<NewsRssParser.RssEntry> entries;
        try (InputStream in = getClass().getResourceAsStream("/fixtures/news/google-news-rss.xml")) {
            assertNotNull(in, "fixture 缺失：/fixtures/news/google-news-rss.xml");
            entries = NewsRssParser.parse(in.readAllBytes());
        }
        assertEquals(3, entries.size());

        NewsRssParser.RssEntry first = entries.get(0);
        assertEquals("https://news.google.com/rss/articles/CBMiMGZpeHR1cmVfZ29vZ2xlX3JlZGlyZWN0X3Rva2VuXzAwMQ?oc=5",
                first.link());
        assertEquals("PolyU announces new scholarship scheme for non-local students - SCMP", first.title());
        assertEquals(java.util.Date.from(Instant.parse("2026-09-08T07:00:00Z")), first.publishTime());
        assertEquals("SCMP", first.sourcePublisher());

        NewsRssParser.RssEntry second = entries.get(1);
        assertEquals(java.util.Date.from(Instant.parse("2026-09-07T04:30:00Z")), second.publishTime());
        assertEquals("香港文匯報", second.sourcePublisher());
    }

    @Test
    void atomEntriesCarryNullSourcePublisher() throws Exception {
        List<NewsRssParser.RssEntry> entries = NewsRssParser.parse(fixture());
        assertNull(entries.get(0).sourcePublisher());
    }
}
