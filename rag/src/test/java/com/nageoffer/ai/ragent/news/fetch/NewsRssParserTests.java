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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RSS（YouTube Atom）型解析测试（U12-A A3，doc 19 §2）
 *
 * <p>fixture 按公开规范合成（本机 DNS 污染未实测端点，T1 遗留）：
 * entry = title + link@href（alternate 或无 rel）+ published/updated + media:description。
 */
class NewsRssParserTests {

    private byte[] fixture() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/news/youtube-rss.xml")) {
            assertNotNull(in, "fixture 缺失：/fixtures/news/youtube-rss.xml");
            return in.readAllBytes();
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
}
