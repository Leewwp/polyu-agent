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
 * SITEMAP 型故事合并测试（U12-A A3）：实采 sitemap 同一故事 en/tc/sc 三条目，
 * 抓取器按语言无关 key 合并为一条——en URL 为规范外链、titleEn+titleZh 就地双语，
 * 防止同故事三 URL 三 hash 落三行三语重复展示。
 */
class SitemapNewsFetcherTests {

    private NewsHttpFetchClient fetchClient;
    private SitemapNewsFetcher fetcher;
    private NewsSourceDO source;

    @BeforeEach
    void setUp() throws Exception {
        fetchClient = mock(NewsHttpFetchClient.class);
        fetcher = new SitemapNewsFetcher(fetchClient);
        source = NewsSourceDO.builder()
                .id(1L).sourceKey("news-sitemap").platform("official")
                .fetchEndpoint("https://www.polyu.edu.hk/news-sitemap.xml")
                .fetchStrategy("SITEMAP").enabled(true).build();
        try (InputStream in = getClass().getResourceAsStream("/fixtures/news/news-sitemap.xml")) {
            assertNotNull(in, "fixture 缺失");
            when(fetchClient.get(source.getFetchEndpoint())).thenReturn(in.readAllBytes());
        }
    }

    @Test
    void consolidatesLanguageVariantsIntoOneStory() {
        List<RawNewsItem> items = fetcher.fetch(source);

        // fixture：0909(en+tc+sc)+0908(en+sc)+0811(tc+en)+0904(en) = 8 条目 → 4 故事
        assertEquals(4, items.size());

        RawNewsItem story = items.get(0);
        assertEquals("https://www.polyu.edu.hk/media/media-releases/2026/"
                + "0909_the-hong-kong-polytechnic-university-mourns-the-passing-of-mr-tung-chee-hwa", story.url());
        assertEquals("The Hong Kong Polytechnic University Mourns the Passing of Mr Tung Chee Hwa", story.title());
        // titleZh 取简体变体（zh-cn → zh-Hans）
        assertEquals("香港理工大学沉痛悼念董建华先生辞世", story.titleZh());
        assertEquals("en", story.langRaw());
        assertEquals(java.util.Date.from(Instant.parse("2026-09-09T11:04:12Z")), story.publishTime());
        // 幂等键=规范(en) URL
        assertEquals(NewsUrlNormalizer.urlHash(story.url()), story.urlHash());
    }

    @Test
    void simplifiedVariantPreferredOverTraditional() {
        List<RawNewsItem> items = fetcher.fetch(source);
        // 0908 故事 en+sc：简体直取
        RawNewsItem alumniWeek = items.get(1);
        assertEquals("首届「理大校友周」圆满举行 逾1,200名校友共度缤纷盛夏", alumniWeek.titleZh());
    }

    @Test
    void storyWithoutSimplifiedFallsBackToTraditional() {
        List<RawNewsItem> items = fetcher.fetch(source);
        // 0811 故事 tc+en（无 sc）：繁体兜底（A5 LLM 繁转简的输入位）
        RawNewsItem patch = items.get(2);
        assertEquals("理大研發可穿戴微針貼片", patch.titleZh());
        assertEquals("PolyU develops wearable microneedle patch", patch.title());
    }

    @Test
    void storyWithoutChineseVariantLeavesTitleZhNull() {
        List<RawNewsItem> items = fetcher.fetch(source);
        assertNull(items.get(3).titleZh());
        assertEquals("PolyU and Diagens Tech establish joint laboratory to advance AI-driven medical innovation",
                items.get(3).title());
    }

    @Test
    void storyKeyStripsLanguagePrefixOnly() {
        assertEquals("/media/media-releases/2026/0909_slug/",
                SitemapNewsFetcher.storyKey("https://www.polyu.edu.hk/tc/media/media-releases/2026/0909_slug/"));
        assertEquals("/media/media-releases/2026/0909_slug/",
                SitemapNewsFetcher.storyKey("https://www.polyu.edu.hk/sc/media/media-releases/2026/0909_slug/"));
        assertEquals("/media/media-releases/2026/0909_slug/",
                SitemapNewsFetcher.storyKey("https://www.polyu.edu.hk/media/media-releases/2026/0909_slug/"));
    }
}
