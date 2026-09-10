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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * URL 规范化 + sha256 幂等键测试（U12-A A3；规则移植自 fetch_sources.py 的
 * normalize_url 用例——列表页/详情页/sitemap 同源同条目必须落同一 hash）
 */
class NewsUrlNormalizerTests {

    @Test
    void stripsFragmentLowercasesHostAndDefaultPort() {
        assertEquals("https://www.polyu.edu.hk/media/media-releases",
                NewsUrlNormalizer.normalize("HTTPS://WWW.POLYU.EDU.HK:443/media/media-releases/#top"));
        assertEquals("http://example.com:8080/a",
                NewsUrlNormalizer.normalize("http://example.com:8080/a"));
    }

    @Test
    void stripsTrailingSlashOnNonRootPathAndKeepsQuery() {
        assertEquals("https://www.polyu.edu.hk/en/api/sitecore/calendar/get",
                        NewsUrlNormalizer.normalize("https://www.polyu.edu.hk/en/api/sitecore/calendar/get/"));
        assertEquals("https://www.prnewswire.com/news/the-hong-kong-polytechnic-university-(polyu)?page=2",
                NewsUrlNormalizer.normalize("https://www.prnewswire.com/news/the-hong-kong-polytechnic-university-(polyu)/?page=2"));
        // 空 query 去除：? 与空串等价
        assertEquals("https://example.com/list",
                NewsUrlNormalizer.normalize("https://example.com/list?"));
    }

    @Test
    void emptyPathBecomesRoot() {
        assertEquals("https://example.com/", NewsUrlNormalizer.normalize("https://example.com"));
    }

    @Test
    void equivalentUrlsShareHashDistinctUrlsDiffer() {
        String withSlash = "https://www.polyu.edu.hk/media/media-releases/2026/0909_slug/";
        String noSlash = "https://www.polyu.edu.hk/media/media-releases/2026/0909_slug";
        assertEquals(NewsUrlNormalizer.urlHash(withSlash), NewsUrlNormalizer.urlHash(noSlash));

        String other = "https://www.polyu.edu.hk/tc/media/media-releases/2026/0909_slug/";
        assertNotEquals(NewsUrlNormalizer.urlHash(withSlash), NewsUrlNormalizer.urlHash(other));

        String hash = NewsUrlNormalizer.urlHash(withSlash);
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    @Test
    void hostKeyIgnoresSchemeCaseAndFillsDefaultPort() {
        assertEquals("https://www.polyu.edu.hk:443",
                NewsUrlNormalizer.hostKey("https://www.PolyU.edu.hk/media/"));
        assertEquals("https://www.prnewswire.com:443",
                NewsUrlNormalizer.hostKey("https://www.prnewswire.com/news/x"));
    }

    @Test
    void robotsUrlPointsAtRoot() {
        assertEquals("https://www.polyu.edu.hk/robots.txt",
                NewsUrlNormalizer.robotsUrl("https://www.polyu.edu.hk/media/media-releases/?page=1"));
    }

    @Test
    void pathAndQueryJoinsQuery() {
        assertEquals("/media/media-releases/?page=1",
                NewsUrlNormalizer.pathAndQuery("https://www.polyu.edu.hk/media/media-releases/?page=1"));
        assertEquals("/events/",
                NewsUrlNormalizer.pathAndQuery("https://www.polyu.edu.hk/events/"));
    }
}
