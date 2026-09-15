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

package com.nageoffer.ai.ragent.news.heat;

import java.util.Date;
import java.util.Set;

/**
 * 热度/聚类视野内的资讯条目（热度模型输入形状）
 *
 * <p>热度计算与故事线聚类的统一输入形状：由持久化重算（NewsHeatService）与
 * 查询侧（NewsQueryServiceImpl /hot 与列表徽章）从 DO 组装，聚类器不碰库。
 *
 * @param id         t_news_item.id
 * @param titleZh    标题中文（可 null，补全前仅 sitemap 源有）
 * @param titleEn    标题英文（可 null；双题皆空时该条目不参与跨条合并）
 * @param category   固定 8 类（补全前统一 other——同主分类条件天然满足）
 * @param sourceId   所属信源 ID
 * @param publishTime 发布时间（热度衰减时间锚）
 * @param topicIds   主题关联 ID 集（故事线合并的「共享 ≥1 主题」条件；补全前恒空集）
 * @param heat       当前已持久化热度（查询侧直接复用；重算时作变更检测基线）
 */
public record NewsStoryItem(Long id,
                            String titleZh,
                            String titleEn,
                            String category,
                            Long sourceId,
                            Date publishTime,
                            Set<Long> topicIds,
                            Integer heat) {
}
