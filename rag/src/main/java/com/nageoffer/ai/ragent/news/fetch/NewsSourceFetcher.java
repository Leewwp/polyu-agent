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

import java.util.List;

/**
 * 抓取策略接口（U12-A A3，fetch_strategy 四型之一）
 *
 * <p>实现自带请求编排（events 型双月两请求等）；所有出站请求必须走
 * {@link NewsHttpFetchClient}（robots/限速/重试/UA 纪律单点收口）。
 */
public interface NewsSourceFetcher {

    /**
     * 支持的 fetch_strategy 值（SITEMAP / HTML_LIST / RSS / JSON_API）
     */
    String supportedStrategy();

    /**
     * 抓取并归一为原始条目；失败抛 {@link NewsFetchException}（由上层滞回计数）
     */
    List<RawNewsItem> fetch(NewsSourceDO source);
}
