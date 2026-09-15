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
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SITEMAP 型抓取器（主引擎）
 *
 * <p>news-sitemap.xml 增量发现 + 三语对齐。实采形态（2026-09-10 实测轮）：
 * 同一故事以 en（无前缀）/ zh-Hant（/tc/，news:language=zh-tw）/ zh-Hans（/sc/，
 * news:language=zh-cn）三个独立 {@code <url>} 条目出现——本类按「语言无关 key
 * （URL 去 /tc/ /sc/ 前缀）」合并为一个故事条目：
 * <ul>
 *   <li>规范外链 = en 变体 URL（卡片永久外链语义；无 en 变体时取首个条目）；</li>
 *   <li>titleEn = en 条目 news:title，titleZh = 简体变体 news:title（繁体兜底，
 *       LLM 繁转简兜底位）；</li>
 *   <li>publication_date 取 en 变体秒级时间戳。</li>
 * </ul>
 * 不合并则同一故事三 URL 三 hash 落三行、资讯流三语重复展示。
 */
@Component
@RequiredArgsConstructor
public class SitemapNewsFetcher implements NewsSourceFetcher {

    private final NewsHttpFetchClient fetchClient;

    @Override
    public String supportedStrategy() {
        return "SITEMAP";
    }

    @Override
    public List<RawNewsItem> fetch(NewsSourceDO source) {
        byte[] xml = fetchClient.get(source.getFetchEndpoint());
        List<NewsSitemapParser.SitemapEntry> entries = NewsSitemapParser.parse(xml);

        // 语言无关 key → 语言 → 条目（保持 sitemap 出现顺序）
        Map<String, Map<String, NewsSitemapParser.SitemapEntry>> stories = new LinkedHashMap<>();
        for (NewsSitemapParser.SitemapEntry entry : entries) {
            stories.computeIfAbsent(storyKey(entry.loc()), k -> new LinkedHashMap<>())
                    .putIfAbsent(languageOf(entry), entry);
        }

        List<RawNewsItem> items = new ArrayList<>();
        for (Map<String, NewsSitemapParser.SitemapEntry> byLanguage : stories.values()) {
            NewsSitemapParser.SitemapEntry canonical = byLanguage.containsKey("en")
                    ? byLanguage.get("en")
                    : byLanguage.values().iterator().next();
            String titleZh = titleOf(byLanguage, "zh-Hans");
            if (titleZh == null) {
                titleZh = titleOf(byLanguage, "zh-Hant");
            }
            String url = NewsUrlNormalizer.normalize(canonical.loc());
            Date publishTime = canonical.publishTime() != null
                    ? canonical.publishTime()
                    : firstNonNullDate(byLanguage);
            items.add(new RawNewsItem(url, NewsUrlNormalizer.urlHash(url), canonical.title(), titleZh,
                    "en", publishTime, null, source.getSourceKey()));
        }
        return items;
    }

    /**
     * 语言无关故事 key：URL path 去掉首位 /tc/ 或 /sc/ 语言段（en 无前缀）
     */
    static String storyKey(String loc) {
        String path = pathOf(loc);
        if (path.startsWith("/tc/") || path.startsWith("/sc/")) {
            return path.substring(3);
        }
        return path;
    }

    /**
     * 语言判定：URL 前缀优先（/tc/=繁 /sc/=简），news:language 兜底
     * （实采值域 en / zh-tw / zh-cn，归一到 en / zh-Hant / zh-Hans）
     */
    static String languageOf(NewsSitemapParser.SitemapEntry entry) {
        String path = pathOf(entry.loc());
        if (path.startsWith("/tc/")) {
            return "zh-Hant";
        }
        if (path.startsWith("/sc/")) {
            return "zh-Hans";
        }
        String declared = entry.language() == null ? "" : entry.language().trim().toLowerCase(Locale.ROOT);
        return switch (declared) {
            case "zh-tw", "zh-hant", "zh-hk" -> "zh-Hant";
            case "zh-cn", "zh-hans", "zh" -> "zh-Hans";
            default -> "en";
        };
    }

    private static String pathOf(String loc) {
        int schemeIdx = loc.indexOf("://");
        int pathStart = schemeIdx < 0 ? 0 : loc.indexOf('/', schemeIdx + 3);
        return pathStart < 0 ? "/" : loc.substring(pathStart);
    }

    private static String titleOf(Map<String, NewsSitemapParser.SitemapEntry> byLanguage, String language) {
        NewsSitemapParser.SitemapEntry entry = byLanguage.get(language);
        return entry == null || entry.title() == null || entry.title().isBlank() ? null : entry.title();
    }

    private static Date firstNonNullDate(Map<String, NewsSitemapParser.SitemapEntry> byLanguage) {
        for (NewsSitemapParser.SitemapEntry entry : byLanguage.values()) {
            if (entry.publishTime() != null) {
                return entry.publishTime();
            }
        }
        return null;
    }
}
