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
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Google News RSS 检索源抓取器（扩源批次二，strategy=RSS_GNEWS）
 *
 * <p>Google News 返回的是中转跳转链（news.google.com/rss/articles/…），不稳定且不得落库；
 * 媒体原文链接取 item description 内嵌的首个超链接。标题尾部的「 - 媒体名」后缀同步清洗
 * （优先取 description 锚文本=干净标题，兜底按 source 元素媒体名做后缀剥离，不盲切）。
 */
@Component
@RequiredArgsConstructor
public class GoogleNewsRssFetcher implements NewsSourceFetcher {

    private final NewsHttpFetchClient fetchClient;

    @Override
    public String supportedStrategy() {
        return "RSS_GNEWS";
    }

    @Override
    public List<RawNewsItem> fetch(NewsSourceDO source) {
        byte[] xml = fetchClient.get(source.getFetchEndpoint());
        List<RawNewsItem> items = new ArrayList<>();
        for (NewsRssParser.RssEntry entry : NewsRssParser.parse(xml)) {
            // description 片段只解析一次，供原文链接提取与锚文本标题清洗共用
            Document fragment = entry.description() == null || entry.description().isBlank()
                    ? null : Jsoup.parse(entry.description());
            String articleUrl = extractArticleUrl(fragment);
            if (articleUrl == null) {
                // 无原文链接=单条降级丢弃（Google 中转链不得落库），不影响同轮其余条目
                continue;
            }
            String url = NewsUrlNormalizer.normalize(articleUrl);
            String title = cleanTitle(entry.title(), fragment, entry.sourcePublisher());
            items.add(new RawNewsItem(url, NewsUrlNormalizer.urlHash(url), title, null,
                    detectLangRaw(title), entry.publishTime(), null, source.getSourceKey()));
        }
        return items;
    }

    /**
     * description 内嵌超链接即媒体原文 URL；仅接受 http(s) 且非 news.google.com 域
     */
    static String extractArticleUrl(Document fragment) {
        if (fragment == null) {
            return null;
        }
        for (org.jsoup.nodes.Element anchor : fragment.select("a[href]")) {
            String href = anchor.attr("href").trim();
            if (!href.regionMatches(true, 0, "http://", 0, 7)
                    && !href.regionMatches(true, 0, "https://", 0, 8)) {
                continue;
            }
            String host = hostOf(href);
            if (!host.isEmpty() && !"news.google.com".equals(host)) {
                return href;
            }
        }
        return null;
    }

    private static String hostOf(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 标题清洗：锚文本（=干净标题）优先；否则按 source 媒体名剥「 - 媒体名」尾缀
     * （不盲切——无媒体名依据时保留原标题，防真实标题含「 - 」被误截）
     */
    static String cleanTitle(String rssTitle, Document fragment, String sourcePublisher) {
        String anchorText = null;
        if (fragment != null) {
            org.jsoup.nodes.Element anchor = fragment.selectFirst("a[href]");
            if (anchor != null) {
                anchorText = anchor.text().trim();
            }
        }
        if (anchorText != null && !anchorText.isEmpty()) {
            return anchorText;
        }
        if (rssTitle == null) {
            return null;
        }
        if (sourcePublisher != null && !sourcePublisher.isBlank()) {
            String suffix = " - " + sourcePublisher;
            if (rssTitle.regionMatches(true, rssTitle.length() - suffix.length(),
                    suffix, 0, suffix.length())) {
                return rssTitle.substring(0, rssTitle.length() - suffix.length()).trim();
            }
        }
        return rssTitle;
    }

    /**
     * langRaw（票面兜底口径：源行无 lang 列，按标题 CJK 检测——HK 查询返回繁体为主）
     */
    static String detectLangRaw(String title) {
        if (title == null) {
            return "en";
        }
        return title.chars().anyMatch(c -> (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF))
                ? "zh-Hant"
                : "en";
    }
}
