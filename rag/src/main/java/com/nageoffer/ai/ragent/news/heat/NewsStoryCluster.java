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
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 故事线聚类结果（「同一故事线合并」口径）
 *
 * <p>一条故事线=至少一个条目；多信源对同一事件的关联报道经聚类器归入同簇，
 * 热度以簇为粒度计算（Σ信源权重+覆盖信源数）。代表条目=最早发布成员
 * （榜单标题语义=「首报」），时间锚=簇内最早/最晚发布时刻。
 */
public record NewsStoryCluster(List<NewsStoryItem> members) {

    public NewsStoryCluster {
        members = List.copyOf(members);
    }

    /**
     * 覆盖信源数（去重 sourceId；同源多 URL 报道只计一次）
     */
    public long distinctSourceCount() {
        return members.stream().map(NewsStoryItem::sourceId).filter(Objects::nonNull).distinct().count();
    }

    /**
     * 簇内去重信源 ID（来源名单与权重求和共用）
     */
    public Set<Long> distinctSourceIds() {
        return members.stream().map(NewsStoryItem::sourceId).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    /**
     * 首报时刻（簇内最早发布时间；成员均无发布时间返回 null）
     */
    public Date earliestPublish() {
        return members.stream().map(NewsStoryItem::publishTime).filter(Objects::nonNull)
                .min(Date::compareTo).orElse(null);
    }

    /**
     * 最新报道时刻（爆/发酵中的 6h 窗口判定锚）
     */
    public Date latestPublish() {
        return members.stream().map(NewsStoryItem::publishTime).filter(Objects::nonNull)
                .max(Date::compareTo).orElse(null);
    }

    /**
     * 代表条目：最早发布成员（同刻取 ID 小者稳定排序）；榜单标题取自它
     */
    public NewsStoryItem representative() {
        return members.stream()
                .sorted(java.util.Comparator
                        .comparing(NewsStoryItem::publishTime,
                                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                        .thenComparing(NewsStoryItem::id, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .findFirst()
                .orElseThrow();
    }
}
