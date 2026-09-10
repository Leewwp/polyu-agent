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
import java.util.List;

/**
 * HTML_LIST 型抓取器（U12-A A3，doc 19 §2）
 *
 * <p>media-releases / recent-focus / campus-reports / PRN 列表页条目发现：
 * 只拉 fetch_endpoint 一页（首页），标题+链接+日期就地可取，正文与双语留给
 * A5 详情抓取（LLM 摘要输入）。日期取值优先级：页面日期文本 → URL slug 日期；
 * 两者皆无的条目跳过（无法参与 14 天回灌窗口判定）。
 */
@Component
@RequiredArgsConstructor
public class HtmlListNewsFetcher implements NewsSourceFetcher {

    private final NewsHttpFetchClient fetchClient;

    @Override
    public String supportedStrategy() {
        return "HTML_LIST";
    }

    @Override
    public List<RawNewsItem> fetch(NewsSourceDO source) {
        String endpoint = source.getFetchEndpoint();
        byte[] html = fetchClient.get(endpoint);
        List<RawNewsItem> items = new ArrayList<>();
        for (NewsHtmlListParser.ListEntry entry : NewsHtmlListParser.parse(html, endpoint)) {
            Date publishTime = NewsHtmlListParser.parseDateText(entry.dateText());
            if (publishTime == null) {
                publishTime = entry.slugDate();
            }
            if (publishTime == null) {
                continue;
            }
            String url = NewsUrlNormalizer.normalize(entry.link());
            items.add(new RawNewsItem(url, NewsUrlNormalizer.urlHash(url), entry.title(), null,
                    "en", publishTime, entry.categoryHint(), source.getSourceKey()));
        }
        if (items.isEmpty()) {
            throw new NewsFetchException("HTML_LIST 条目全部缺日期（模板改版嫌疑，fail-closed）: "
                    + source.getSourceKey(), false);
        }
        return items;
    }
}
