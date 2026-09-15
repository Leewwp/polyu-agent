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
import java.util.Map;

/**
 * HTML_LIST 型抓取器（页深外置归 {@link NewsFetchProperties}）
 *
 * <p>media-releases / recent-focus / campus-reports / PRN 列表页条目发现：
 * 标题+链接+日期就地可取，正文与双语留给详情抓取阶段（LLM 摘要输入）。日期取值
 * 优先级：页面日期文本 → URL slug 日期；两者皆无的条目跳过（无法参与回灌窗口判定）。
 *
 * <p>翻页：默认仅拉 fetch_endpoint 一页（首页，现行口径）；页深 &gt;1 时按
 * {@code page=N} 逐页翻取（端点无页参时追加），用于历史回灌。停止条件：页解析
 * 零条目（末页/官网错误页）或该页零有效条目；首页零条目仍 fail-closed（模板
 * 改版嫌疑）。跨页按 url_hash 去重。
 */
@Component
@RequiredArgsConstructor
public class HtmlListNewsFetcher implements NewsSourceFetcher {

    private final NewsHttpFetchClient fetchClient;
    private final NewsFetchProperties properties;

    @Override
    public String supportedStrategy() {
        return "HTML_LIST";
    }

    @Override
    public List<RawNewsItem> fetch(NewsSourceDO source) {
        String endpoint = source.getFetchEndpoint();
        int pages = Math.max(1, properties.getFetchPagesMax());
        Map<String, RawNewsItem> byHash = new LinkedHashMap<>();
        int firstPageAccepted = 0;
        for (int page = 1; page <= pages; page++) {
            String pageEndpoint = pageUrl(endpoint, page);
            List<NewsHtmlListParser.ListEntry> entries;
            try {
                entries = NewsHtmlListParser.parse(fetchClient.get(pageEndpoint), pageEndpoint, page > 1);
            } catch (NewsFetchException e) {
                if (page == 1) {
                    throw e;
                }
                // 末页之后：官网 404/错误页或空页，视为列表到头
                break;
            }
            int accepted = 0;
            for (NewsHtmlListParser.ListEntry entry : entries) {
                Date publishTime = NewsHtmlListParser.parseDateText(entry.dateText());
                if (publishTime == null) {
                    publishTime = entry.slugDate();
                }
                if (publishTime == null) {
                    continue;
                }
                String url = NewsUrlNormalizer.normalize(entry.link());
                accepted += byHash.putIfAbsent(NewsUrlNormalizer.urlHash(url),
                        new RawNewsItem(url, NewsUrlNormalizer.urlHash(url), entry.title(), null,
                                "en", publishTime, entry.categoryHint(), source.getSourceKey())) == null ? 1 : 0;
            }
            if (page == 1) {
                firstPageAccepted = accepted;
            }
            if (accepted == 0) {
                // 时间倒序列表：本页已无有效条目=更老页不再贡献
                break;
            }
        }
        if (firstPageAccepted == 0) {
            throw new NewsFetchException("HTML_LIST 条目全部缺日期（模板改版嫌疑，fail-closed）: "
                    + source.getSourceKey(), false);
        }
        return new ArrayList<>(byHash.values());
    }

    /**
     * 页 N 端点：端点自带 {@code page=M} 时整体替换页号（如官网 {@code ?page=1}）；
     * 无页参时追加（PRN 列表页形态）
     */
    static String pageUrl(String endpoint, int page) {
        if (page == 1) {
            return endpoint;
        }
        if (endpoint.matches(".*[?&]page=\\d+.*")) {
            return endpoint.replaceAll("([?&]page=)\\d+", "$1" + page);
        }
        return endpoint + (endpoint.contains("?") ? "&" : "?") + "page=" + page;
    }
}
