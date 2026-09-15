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

import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Google News RSS 检索源抓取器测试（扩源批次二）
 *
 * <p>票面三点职责逐一断言：媒体原文链接取自 description 内嵌超链接（Google 中转链
 * 不得落库）、「 - 媒体名」后缀清洗、langRaw 标题 CJK 检测；外加单条降级
 * （description 无原文链接的条目丢弃不抛断全局）。
 */
class GoogleNewsRssFetcherTests {

    private NewsHttpFetchClient fetchClient;
    private GoogleNewsRssFetcher fetcher;
    private NewsSourceDO source;

    @BeforeEach
    void setUp() throws Exception {
        fetchClient = mock(NewsHttpFetchClient.class);
        fetcher = new GoogleNewsRssFetcher(fetchClient);
        source = NewsSourceDO.builder()
                .id(10L).sourceKey("gnews-polyu-en").platform("gnews")
                .fetchEndpoint("https://news.google.com/rss/search?q=PolyU&hl=en-HK&gl=HK&ceid=HK:en")
                .fetchStrategy("RSS_GNEWS").official(false).enabled(true).build();
        try (InputStream in = getClass().getResourceAsStream("/fixtures/news/google-news-rss.xml")) {
            assertNotNull(in, "fixture 缺失：/fixtures/news/google-news-rss.xml");
            when(fetchClient.get(source.getFetchEndpoint())).thenReturn(in.readAllBytes());
        }
    }

    @Test
    void extractsArticleUrlFromDescriptionAndNeverPersistsGoogleRedirect() {
        List<RawNewsItem> items = fetcher.fetch(source);

        // fixture 3 条 → 第 3 条无 description 链接降级丢弃
        assertEquals(2, items.size());
        // 产出 URL 全部是媒体原文，零 news.google.com 中转链
        for (RawNewsItem item : items) {
            org.junit.jupiter.api.Assertions.assertFalse(item.url().contains("news.google.com"),
                    "Google 中转链落库：" + item.url());
        }
        assertEquals("https://www.scmp.com/news/hong-kong/education/article/1234567/polyu-announces-new-scholarship-scheme",
                items.get(0).url());
        assertEquals(NewsUrlNormalizer.urlHash(items.get(0).url()), items.get(0).urlHash());
    }

    @Test
    void cleansTitleViaAnchorTextAndKeepsCategoryHintNull() {
        RawNewsItem first = fetcher.fetch(source).get(0);

        // 标题取 description 锚文本（干净标题），「 - SCMP」后缀不落库
        assertEquals("PolyU announces new scholarship scheme for non-local students", first.title());
        assertNull(first.titleZh());
        assertNull(first.categoryHint());
        assertEquals("gnews-polyu-en", first.sourceKey());
    }

    @Test
    void detectsTraditionalChineseTitleAndOffsetPubDate() {
        RawNewsItem second = fetcher.fetch(source).get(1);

        assertEquals("理大新增人工智能學士課程 下學年招生", second.title());
        assertEquals("zh-Hant", second.langRaw());
        // RFC 1123 带时区偏移的 pubDate 正确换算 Instant（+0800 → UTC）
        assertEquals(java.util.Date.from(Instant.parse("2026-09-07T04:30:00Z")), second.publishTime());
    }

    @Test
    void firstItemUsesEnglishLangAndGmtPubDate() {
        RawNewsItem first = fetcher.fetch(source).get(0);

        assertEquals("en", first.langRaw());
        assertEquals(java.util.Date.from(Instant.parse("2026-09-08T07:00:00Z")), first.publishTime());
    }

    @Test
    void blankDescriptionEntryIsSkippedInsteadOfFailingWholeFetch() {
        // fixture 第 3 条 description 为空：单条降级=丢弃，同轮其余条目照常产出（非抛断）
        List<RawNewsItem> items = fetcher.fetch(source);
        assertEquals(2, items.size());
    }

    @Test
    void extractArticleUrlRejectsNonHttpAndGoogleHosts() {
        assertNull(GoogleNewsRssFetcher.extractArticleUrl(null));
        assertNull(GoogleNewsRssFetcher.extractArticleUrl(org.jsoup.Jsoup.parse("")));
        assertNull(GoogleNewsRssFetcher.extractArticleUrl(org.jsoup.Jsoup.parse("<p>no anchor</p>")));
        assertNull(GoogleNewsRssFetcher.extractArticleUrl(org.jsoup.Jsoup.parse(
                "<a href=\"https://news.google.com/rss/articles/CBMiX\">headline</a>")));
        assertNull(GoogleNewsRssFetcher.extractArticleUrl(org.jsoup.Jsoup.parse(
                "<a href=\"/relative/path\">headline</a>")));
        assertEquals("https://www.example.com/article",
                GoogleNewsRssFetcher.extractArticleUrl(org.jsoup.Jsoup.parse("<a href=\"https://www.example.com/article\">t</a>")));
    }

    @Test
    void cleanTitleFallsBackToSourceSuffixStripAndRawTitle() {
        // 锚文本缺失 → 按 source 媒体名剥尾缀
        assertEquals("PolyU wins robotics award",
                GoogleNewsRssFetcher.cleanTitle("PolyU wins robotics award - SCMP", org.jsoup.Jsoup.parse("<p>no anchor</p>"), "SCMP"));
        // 媒体名不匹配尾缀 → 不盲切，保留原标题（防真实标题含「 - 」被误截）
        assertEquals("Growth - the real story",
                GoogleNewsRssFetcher.cleanTitle("Growth - the real story", org.jsoup.Jsoup.parse("<p>no anchor</p>"), "SCMP"));
        // 双兜底皆缺 → 原样返回
        assertEquals("Raw headline stays", GoogleNewsRssFetcher.cleanTitle("Raw headline stays", null, null));
        assertNull(GoogleNewsRssFetcher.cleanTitle(null, null, null));
    }

    @Test
    void langDetectionCoversCjkRangesOnly() {
        assertEquals("zh-Hant", GoogleNewsRssFetcher.detectLangRaw("理大新增課程"));
        assertEquals("en", GoogleNewsRssFetcher.detectLangRaw("PolyU opens new programme"));
        assertEquals("en", GoogleNewsRssFetcher.detectLangRaw(null));
    }
}
