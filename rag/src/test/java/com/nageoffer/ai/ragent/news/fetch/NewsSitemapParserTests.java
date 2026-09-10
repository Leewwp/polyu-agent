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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SITEMAP 型解析测试（U12-A A3 过门必选之一，doc 19 §12-A）
 *
 * <p>fixture 取自 T1 实测样本（research/u12-source-feasibility.md §1.2）：
 * Google News 命名空间 + en/zh-Hant/zh-Hans 三语 hreflang + 秒级 publication_date。
 */
class NewsSitemapParserTests {

    private byte[] fixture() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/news/news-sitemap.xml")) {
            assertNotNull(in, "fixture 缺失：/fixtures/news/news-sitemap.xml");
            return in.readAllBytes();
        }
    }

    @Test
    void parsesEntriesWithTitleAndSecondPrecisionDate() throws Exception {
        List<NewsSitemapParser.SitemapEntry> entries = NewsSitemapParser.parse(fixture());
        assertEquals(8, entries.size());  // 0909(en+tc+sc)+0908(en+sc)+0811(tc+en)+0904(en)

        NewsSitemapParser.SitemapEntry first = entries.get(0);
        assertEquals("https://www.polyu.edu.hk/media/media-releases/2026/"
                + "0909_the-hong-kong-polytechnic-university-mourns-the-passing-of-mr-tung-chee-hwa/", first.loc());
        assertEquals("The Hong Kong Polytechnic University Mourns the Passing of Mr Tung Chee Hwa", first.title());
        assertEquals("en", first.language());
        // 秒级 + 带时区：2026-09-09T19:04:12+08:00 → UTC 11:04:12
        assertEquals(DateFrom.instant("2026-09-09T11:04:12Z"), first.publishTime());
    }

    @Test
    void capturesHreflangTriLanguageVariants() throws Exception {
        List<NewsSitemapParser.SitemapEntry> entries = NewsSitemapParser.parse(fixture());
        Map<String, String> alternates = entries.get(0).alternates();
        assertEquals("https://www.polyu.edu.hk/media/media-releases/2026/"
                        + "0909_the-hong-kong-polytechnic-university-mourns-the-passing-of-mr-tung-chee-hwa/",
                alternates.get("en"));
        assertEquals("https://www.polyu.edu.hk/tc/media/media-releases/2026/"
                        + "0909_the-hong-kong-polytechnic-university-mourns-the-passing-of-mr-tung-chee-hwa/",
                alternates.get("zh-Hant"));
        assertEquals("https://www.polyu.edu.hk/sc/media/media-releases/2026/"
                        + "0909_the-hong-kong-polytechnic-university-mourns-the-passing-of-mr-tung-chee-hwa/",
                alternates.get("zh-Hans"));
        // 同 slug 三变体只有前缀不同（A5 双语详情抓取的权威对齐依据）
        String slug = "0909_the-hong-kong-polytechnic-university-mourns-the-passing-of-mr-tung-chee-hwa";
        assertTrue(alternates.get("zh-Hant").endsWith(slug + "/"));
        assertTrue(alternates.get("zh-Hans").endsWith(slug + "/"));
    }

    @Test
    void malformedXmlFailsClosed() {
        assertThrows(NewsFetchException.class, () -> NewsSitemapParser.parse("not xml".getBytes()));
    }

    @Test
    void emptyUrlsetFailsClosedInsteadOfSilentEmpty() {
        String empty = """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"></urlset>
                """;
        assertThrows(NewsFetchException.class, () -> NewsSitemapParser.parse(empty.getBytes()));
    }

    /**
     * 毫秒精度 Date 断言辅助
     */
    static final class DateFrom {

        static java.util.Date instant(String iso) {
            return java.util.Date.from(Instant.parse(iso));
        }
    }
}
