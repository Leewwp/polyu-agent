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

package com.nageoffer.ai.ragent.news.service;

/**
 * 站点出口 SEO 渲染（#213，父票 #182 r3 §RSS、发现面——P2-b）：站点级 news RSS
 * feed 与 sitemap 的确定性 XML 生成。
 *
 * <p>出口一致性合同（#180 R4 同源）：条目可见性<strong>不在此另写判据</strong>——
 * feed 复用 {@link NewsQueryService#listPublished}（与页面 /list 同一面，隐藏/未过门
 * 条目在查询层即隔离）；sitemap 的资讯/主题/日报段复用同一服务族。双语回退=
 * 中文优先、英文兜底（与日报 RSS 渲染同口径）。
 *
 * <p>零 LLM：渲染全部来自确定性查询面（页面/feed 请求不触发模型调用）。
 */
public interface NewsSeoService {

    /**
     * 站点级 news RSS 2.0 feed（feed.xml）：最近 {@code limit} 条公开资格条目。
     * item link/guid = 本站详情页 canonical URL（/news/{id}），描述尾附原文回链
     * （来源回链口径——feed 读者可溯源，但链接面保持本站 canonical）。
     *
     * @param limit 条目数上限（调用方钳制）
     * @return RSS 2.0 XML 原文（UTF-8，无 JSON Result 包裹）
     */
    String renderNewsFeed(int limit);

    /**
     * 站点 sitemap（sitemap.xml，sitemaps.org 0.9 协议）：静态公共路由 + 资讯面
     * （news flag 开时：主题目录页/近期详情页/日报归档页）。资讯段条目与页面共用
     * 公开查询面——一个出口不泄露另一出口已隐藏内容。
     *
     * @return sitemap XML 原文（urlset）
     */
    String renderSitemap();
}
