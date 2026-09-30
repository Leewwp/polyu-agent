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

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemStatus;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemTopicDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemTopicMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 热度/聚类视野装配器：把窗口内的已发布条目（#187 起含中文摘要词面与
 * summary_source——事件合并的 E1 有效摘要门输入）连同主题关联与信源
 * 注册表组装成聚类输入——持久化重算（NewsEventService）与查询侧
 * （/hot、列表徽章）共用同一次装配形状，保证两侧簇语义一致。
 *
 * <p>两个装载口径（#185）：{@link #loadPublished} 供事件重归组/热度持久化重算
 * （内部面，status=published 全量，含发布门未开启条目——门的意义正是让聚类先于
 * 公开看到新条目）；{@link #loadVisible} 供公开查询面（/hot、徽章），叠加发布门
 * （eligible_time ≤ gateFloor 或历史行 NULL，统一公开资格，见
 * {@link NewsItemStatus} 类 javadoc）。
 */
@Component
@RequiredArgsConstructor
public class NewsStoryAssembler {

    private final NewsItemMapper itemMapper;
    private final NewsItemTopicMapper itemTopicMapper;
    private final NewsSourceMapper sourceMapper;

    /**
     * 装配结果：窗口条目（含主题关联与已持久化热度）+ 信源注册表
     */
    public record NewsStoryWindow(List<NewsStoryItem> items, Map<Long, NewsSourceDO> sourcesById) {
    }

    /**
     * 装载窗口内已发布条目（publishTime ≥ 下界；下界 null=不限）——
     * 内部面（热度重算）口径：不施加发布门
     */
    public NewsStoryWindow loadPublished(Date windowFloor) {
        return load(windowFloor, null);
    }

    /**
     * 装载窗口内公开可见条目（#185 统一公开资格）：status=published 且发布门
     * 已开启（eligible_time ≤ gateFloor；NULL 历史 行视同早已开启）——
     * 公开查询面（/hot、列表徽章）口径
     */
    public NewsStoryWindow loadVisible(Date windowFloor, Date gateFloor) {
        return load(windowFloor, gateFloor);
    }

    private NewsStoryWindow load(Date windowFloor, Date gateFloor) {
        LambdaQueryWrapper<NewsItemDO> query = Wrappers.lambdaQuery(NewsItemDO.class)
                .eq(NewsItemDO::getStatus, NewsItemStatus.PUBLISHED)
                .ge(windowFloor != null, NewsItemDO::getPublishTime, windowFloor);
        if (gateFloor != null) {
            query.and(w -> w.isNull(NewsItemDO::getEligibleTime).or().le(NewsItemDO::getEligibleTime, gateFloor));
        }
        List<NewsItemDO> items = itemMapper.selectList(query);
        if (items.isEmpty()) {
            return new NewsStoryWindow(List.of(), Map.of());
        }
        Map<Long, Set<Long>> topicsByItem = new HashMap<>();
        List<Long> itemIds = items.stream().map(NewsItemDO::getId).toList();
        for (NewsItemTopicDO link : itemTopicMapper.selectList(Wrappers.lambdaQuery(NewsItemTopicDO.class)
                .in(NewsItemTopicDO::getItemId, itemIds))) {
            topicsByItem.computeIfAbsent(link.getItemId(), k -> new HashSet<>()).add(link.getTopicId());
        }
        Set<Long> sourceIds = new HashSet<>();
        for (NewsItemDO item : items) {
            if (item.getSourceId() != null) {
                sourceIds.add(item.getSourceId());
            }
        }
        Map<Long, NewsSourceDO> sourcesById = new HashMap<>();
        if (!sourceIds.isEmpty()) {
            for (NewsSourceDO source : sourceMapper.selectBatchIds(List.copyOf(sourceIds))) {
                sourcesById.put(source.getId(), source);
            }
        }
        List<NewsStoryItem> storyItems = items.stream()
                .map(item -> new NewsStoryItem(item.getId(), item.getTitleZh(), item.getTitleEn(),
                        item.getCategory(), item.getSourceId(), item.getPublishTime(),
                        topicsByItem.getOrDefault(item.getId(), Set.of()), item.getHeat(),
                        item.getSummaryZh(), item.getSummarySource()))
                .toList();
        return new NewsStoryWindow(storyItems, sourcesById);
    }
}
