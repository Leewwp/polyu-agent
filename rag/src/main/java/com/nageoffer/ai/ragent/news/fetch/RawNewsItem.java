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

import java.util.Date;

/**
 * 抓取器产出的原始条目（U12-A A3，doc 19 §4-2）
 *
 * <p>四型抓取器（SITEMAP/HTML_LIST/RSS/JSON_API）统一归一到本形状，
 * 双语摘要与固定 8 类分类由 A5 的 LLM 管线补齐——A3 只落来源自带的标题
 * （SITEMAP 型经故事合并后天然双语：en 标题 + 简/繁标题）、原文语言与发布时间；
 * {@code urlHash} 在构造前由 {@link NewsUrlNormalizer#urlHash(String)} 按规范化 URL 计算。
 *
 * @param url          规范化后的原文永久外链（SITEMAP 型=en 变体 URL，卡片外链语义）
 * @param urlHash      sha256(url) 十六进制，幂等去重键
 * @param title        标题英文（或来源唯一标题——RSS/events/HTML_LIST 单语条目）
 * @param titleZh      标题中文（SITEMAP 型取简体变体 news:title，繁体兜底；其余型 null 归 A5 LLM 补译）
 * @param langRaw      原文语言（en/zh-Hant/zh-Hans；列表页默认 en）
 * @param publishTime  原文发布时间（events 型为活动开始时间）
 * @param categoryHint 来源侧分类原文（如 "Research &amp; Innovation"），仅作 A5 LLM 分类参考，不入固定 8 类
 * @param sourceKey    归属信源标识
 */
public record RawNewsItem(String url,
                          String urlHash,
                          String title,
                          String titleZh,
                          String langRaw,
                          Date publishTime,
                          String categoryHint,
                          String sourceKey) {
}
