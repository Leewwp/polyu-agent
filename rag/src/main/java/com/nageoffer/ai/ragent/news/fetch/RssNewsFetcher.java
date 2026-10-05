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
import java.util.List;

/**
 * RSS 型抓取器（V1 唯一社媒源 YouTube 校级频道）
 *
 * <p>官方 RSS 标准（channel_id=UCkio4asleKcQVRVEM8RnXlQ），Atom 格式最近 15 条；
 * 单条即含标题+watch 链接+发布时间+描述，无需详情抓取。本机 DNS 污染预期失败
 * （已知遗留）：失败计入滞回不阻断他源，生产香港服务器侧复验列入后续待办——
 * #186 起三连败自动隔离的源进日级探活复归轨道。
 */
@Component
@RequiredArgsConstructor
public class RssNewsFetcher implements NewsSourceFetcher {

    private final NewsHttpFetchClient fetchClient;
    private final NewsFetchProperties properties;

    @Override
    public String supportedStrategy() {
        return "RSS";
    }

    @Override
    public List<RawNewsItem> fetch(NewsSourceDO source) {
        byte[] xml = fetchClient.get(source.getFetchEndpoint());
        // allow-empty 源（#186）：零条目=有效空（VALID_EMPTY，健康）；未配置则 fail-closed
        List<RawNewsItem> items = new ArrayList<>();
        for (NewsRssParser.RssEntry entry : NewsRssParser.parse(xml,
                !properties.isAllowEmptySource(source.getSourceKey()))) {
            String url = NewsUrlNormalizer.normalize(entry.link());
            // #275：RSS pubDate 为 RFC1123 精确时刻→datetime；缺失 pubDate 精度 unknown。
            // #277：去 HTML 的 feed 原始摘要随行（八校确定性门的准入证据；langRaw=en=
            // 三候选媒体/政府 feed 均 language=en/en-UK 实证口径，不造第二套语言解析器）
            items.add(new RawNewsItem(url, NewsUrlNormalizer.urlHash(url), entry.title(), null,
                    "en", entry.publishTime(), null, source.getSourceKey(),
                    entry.publishTime() == null
                            ? PublishTimePrecision.UNKNOWN
                            : PublishTimePrecision.DATETIME,
                    stripHtml(entry.description())));
        }
        return items;
    }

    /** feed description 去 HTML（#277 原始摘要证据；空白归一，空文本→null） */
    static String stripHtml(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        String text = org.jsoup.Jsoup.parse(html).text().strip();
        return text.isEmpty() ? null : text;
    }
}
