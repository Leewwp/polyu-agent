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

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HTML_LIST 翻页测试（历史回灌口径，NewsFetchProperties.fetch-pages-max）：
 * 默认页深 1=仅首页（现行口径）；页深 &gt;1 时按 page=N 逐页翻取、空页到头
 * 自动停、跨页 url_hash 去重；首页零条目保持 fail-closed；无页参端点追加
 * page=N（PRN 形态）。
 */
class HtmlListNewsFetcherTests {

    private static final String OFFICIAL_ENDPOINT = "https://www.polyu.edu.hk/media/media-releases/?page=1";
    private static final String PRN_ENDPOINT =
            "https://www.prnewswire.com/news/the-hong-kong-polytechnic-university-(polyu)/";

    private NewsHttpFetchClient fetchClient;
    private NewsFetchProperties properties;
    private HtmlListNewsFetcher fetcher;
    private NewsSourceDO source;

    @BeforeEach
    void setUp() {
        fetchClient = mock(NewsHttpFetchClient.class);
        properties = new NewsFetchProperties();
        fetcher = new HtmlListNewsFetcher(fetchClient, properties);
        source = NewsSourceDO.builder()
                .id(1L).sourceKey("media-releases").platform("official")
                .fetchEndpoint(OFFICIAL_ENDPOINT)
                .fetchStrategy("HTML_LIST").enabled(true).build();
    }

    private static byte[] officialPage(String... slugs) {
        StringBuilder html = new StringBuilder("<html><body>");
        for (String slug : slugs) {
            html.append("<a class=\"border-hover-shadow-list__itm\" href=\"/media/media-releases/")
                    .append(slug).append("/\"><span class=\"long-img-side-blk__title\">Title ")
                    .append(slug).append("</span></a>");
        }
        return html.append("</body></html>").toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void defaultPageDepthFetchesOnlyFirstPage() {
        when(fetchClient.get(OFFICIAL_ENDPOINT)).thenReturn(officialPage("2026/0901_polyu-a", "2026/0902_polyu-b"));

        List<RawNewsItem> items = fetcher.fetch(source);

        assertEquals(2, items.size());
        org.mockito.Mockito.verify(fetchClient, org.mockito.Mockito.times(1)).get(anyString());
    }

    @Test
    void paginatesUntilPageWithNoEntries() {
        properties.setFetchPagesMax(5);
        when(fetchClient.get(OFFICIAL_ENDPOINT)).thenReturn(officialPage("2026/0901_polyu-a", "2026/0902_polyu-b"));
        when(fetchClient.get("https://www.polyu.edu.hk/media/media-releases/?page=2"))
                .thenReturn(officialPage("2026/0815_polyu-c"));
        when(fetchClient.get("https://www.polyu.edu.hk/media/media-releases/?page=3"))
                .thenReturn("<html></html>".getBytes(StandardCharsets.UTF_8));

        List<RawNewsItem> items = fetcher.fetch(source);

        assertEquals(3, items.size());
        verify(fetchClient).get("https://www.polyu.edu.hk/media/media-releases/?page=3");
        verify(fetchClient, never()).get("https://www.polyu.edu.hk/media/media-releases/?page=4");
    }

    @Test
    void crossPageDuplicatesDedupedByHash() {
        properties.setFetchPagesMax(2);
        when(fetchClient.get(OFFICIAL_ENDPOINT)).thenReturn(officialPage("2026/0901_polyu-a"));
        when(fetchClient.get("https://www.polyu.edu.hk/media/media-releases/?page=2"))
                .thenReturn(officialPage("2026/0901_polyu-a", "2026/0820_polyu-d"));

        List<RawNewsItem> items = fetcher.fetch(source);

        assertEquals(2, items.size());
    }

    @Test
    void firstPageWithoutEntriesStillFailsClosed() {
        when(fetchClient.get(OFFICIAL_ENDPOINT))
                .thenReturn("<html></html>".getBytes(StandardCharsets.UTF_8));

        assertThrows(NewsFetchException.class, () -> fetcher.fetch(source));
    }

    @Test
    void appendsPageParamWhenEndpointHasNone() {
        source.setFetchEndpoint(PRN_ENDPOINT);
        properties.setFetchPagesMax(2);
        when(fetchClient.get(PRN_ENDPOINT)).thenReturn(officialPage("2026/0901_polyu-a"));
        when(fetchClient.get(PRN_ENDPOINT + "?page=2")).thenReturn(officialPage("2026/0818_polyu-e"));

        List<RawNewsItem> items = fetcher.fetch(source);

        assertEquals(2, items.size());
        verify(fetchClient).get(PRN_ENDPOINT + "?page=2");
    }

    @Test
    void pageUrlReplacesExistingPageNumber() {
        assertEquals(OFFICIAL_ENDPOINT, HtmlListNewsFetcher.pageUrl(OFFICIAL_ENDPOINT, 1));
        assertEquals("https://www.polyu.edu.hk/media/media-releases/?page=7",
                HtmlListNewsFetcher.pageUrl(OFFICIAL_ENDPOINT, 7));
        assertEquals(PRN_ENDPOINT + "?page=3", HtmlListNewsFetcher.pageUrl(PRN_ENDPOINT, 3));
        assertEquals(PRN_ENDPOINT + "?lang=en&page=3", HtmlListNewsFetcher.pageUrl(PRN_ENDPOINT + "?lang=en", 3));
    }
}
