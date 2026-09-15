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
 * （已知遗留）：失败计入滞回不阻断他源，生产香港服务器侧复验列入后续待办。
 */
@Component
@RequiredArgsConstructor
public class RssNewsFetcher implements NewsSourceFetcher {

    private final NewsHttpFetchClient fetchClient;

    @Override
    public String supportedStrategy() {
        return "RSS";
    }

    @Override
    public List<RawNewsItem> fetch(NewsSourceDO source) {
        byte[] xml = fetchClient.get(source.getFetchEndpoint());
        List<RawNewsItem> items = new ArrayList<>();
        for (NewsRssParser.RssEntry entry : NewsRssParser.parse(xml)) {
            String url = NewsUrlNormalizer.normalize(entry.link());
            items.add(new RawNewsItem(url, NewsUrlNormalizer.urlHash(url), entry.title(), null,
                    "en", entry.publishTime(), null, source.getSourceKey()));
        }
        return items;
    }
}
