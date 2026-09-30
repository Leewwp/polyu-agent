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

import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * 热度数学与独立来源组语义（#187 起收敛为纯计算面——持久化重算编排移
 * {@link NewsEventService}，查询侧沿用已持久化热度）
 *
 * <p><b>热度公式</b>：事件热度=（48h 证据窗内独立来源组数 + Σ组内最大源权重）×
 * 24h 半衰期衰减，衰减锚=事件首报（成员最早发布时刻）；未来开始的条目
 * （events 型活动预告）不因负龄放大，衰减系数封顶 1。
 *
 * <p><b>独立来源组</b>（#187 投票去重）：t_news_source.independence_group 显式
 * 映射同机构多 feed/聚合口（官网各栏目+官方 YouTube=polyu-official、PRN 双语
 * wire=prn-wire、GNews=gnews），NULL=按 source_key 自成一组——同组只计一票，
 * 组权重取组内源权重最大值（不重复加票也不放大权重）。
 *
 * <p><b>窗口</b>：重算窗口 4 天（{@link #HEAT_WINDOW_DAYS}）——基数上限≈9 时
 * 4 天衰减 &lt;0.3，更旧条目热度归零（90 天保留期由 NewsRetentionJob 收尾），
 * 窗口外不重算；投票证据窗 {@link #EVIDENCE_WINDOW_HOURS}=48h（事件首报起算，
 * 超窗追报不计热度票——CLU-038 口径「热度各自记账」，但身份归属不受影响）。
 */
@Service
@RequiredArgsConstructor
public class NewsHeatService {

    /**
     * 热度/聚簇共用窗口（天）：窗口内条目重算热度并参与查询侧簇与标签计算，
     * 窗口外视为零热度不进榜单与徽章
     */
    public static final int HEAT_WINDOW_DAYS = 4;

    /**
     * 半衰期（小时，定值 24）
     */
    static final double HALF_LIFE_HOURS = 24.0;

    /**
     * 参与者投票证据窗（小时，事件首报起算）——窗外报道不计热度票
     */
    static final long EVIDENCE_WINDOW_HOURS = 48;

    private final NewsHeatProperties properties;

    /**
     * 独立来源组键：independence_group 优先，NULL 按 source_key 自成一组
     * （源未注册视同 unknown，只计数不加权重）
     */
    static String independenceKey(NewsSourceDO source) {
        if (source == null) {
            return "unknown";
        }
        if (source.getIndependenceGroup() != null && !source.getIndependenceGroup().isBlank()) {
            return source.getIndependenceGroup();
        }
        return source.getSourceKey() != null ? source.getSourceKey() : "source-" + source.getId();
    }

    /**
     * 源权重（source-weights 覆盖表；缺 key 按 0 计——覆盖信源数仍计入基数）
     */
    int sourceWeight(NewsSourceDO source) {
        if (source == null || source.getSourceKey() == null) {
            return 0;
        }
        return properties.getSourceWeights().getOrDefault(source.getSourceKey(), 0);
    }

    /**
     * 半衰热度：base × 0.5^(龄/24h)，四舍五入取整，未来时间（负龄）封顶 1 倍
     */
    static int decayedHeat(int base, Date anchor, Date now) {
        if (base <= 0) {
            return 0;
        }
        if (anchor == null) {
            return base;
        }
        double ageHours = (now.getTime() - anchor.getTime()) / 3600000.0;
        double decay = ageHours <= 0 ? 1.0 : Math.pow(0.5, ageHours / HALF_LIFE_HOURS);
        return (int) Math.max(0, Math.round(base * decay));
    }
}
