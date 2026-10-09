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

import java.time.LocalDate;
import java.util.List;

/**
 * 校园活动模型读取口（#323 活动实体模型，#330 活动版面装配消费）
 *
 * <p>把「有明确起止日期的条目」（t_news_item.activity_end_time 非空——events 等
 * 活动型来源，起止成对）以<b>活动实体</b>（标题/起止日期/链接）暴露给日报装配层，
 * 使其<b>不依赖具体抓取源</b>即可读未来滚动窗口内的大学级活动——装配层只面向
 * 本投影编程，不感知 events/CPEO/SAO 等源的适配细节（#317 线一）。
 *
 * <p>读取语义与日报候选同一「统一公开资格」判据（#180 R4）：status=published
 * 且过 180s 发布门；过期（结束日早于 as-of）自动落窗外。活动日期按 HKT 历日
 * 粒度暴露（起止换算 Asia/Hong_Kong），排序=开始日期升序（进行中的跨月活动
 * 开始日早、自然在前，装配层可按 startDate 与 as-of 的大小分「进行中/即将来临」
 * 两组，#317 口径）。
 */
public interface NewsActivityQueryService {

    /**
     * as-of 起滚动窗口内重叠的大学级活动
     *
     * <p>窗口=[asOf, asOf+windowDays-1] 含端共 windowDays 个历日；活动区间
     * [startDate, endDate] 与窗口<b>任一历日重叠</b>即入选——已开始未结束
     * （进行中）与未开始（即将来临）都覆盖，两端均在窗外（早已结束/远未开始）
     * 不出现。零条目=空列表。
     *
     * @param asOf       基准日（活动版面 as-of，如刊日；进行中判定基准）
     * @param windowDays 窗口历日数（含 asOf 当天；#317 活动版面默认口径 56=8 周）
     * @return 按 startDate 升序（同日按 itemId 升序）的活动实体列表
     */
    List<CampusActivity> campusActivities(LocalDate asOf, int windowDays);

    /**
     * 活动实体投影（标题/起止日期/链接；#323 票面字段合同）
     *
     * @param itemId    溯源 t_news_item.id（无外键语义，装配层快照独立性红线）
     * @param titleZh   标题中文（LLM 富化补译前为 null）
     * @param titleEn   标题英文（events 条目来源标题）
     * @param url       详情页永久外链（卡片外链语义）
     * @param startDate 开始日期（HKT 历日；对应 t_news_item.publish_time 活动开始语义）
     * @param endDate   结束日期（HKT 历日；对应 t_news_item.activity_end_time）
     */
    record CampusActivity(Long itemId, String titleZh, String titleEn, String url,
                          LocalDate startDate, LocalDate endDate) {
    }
}
