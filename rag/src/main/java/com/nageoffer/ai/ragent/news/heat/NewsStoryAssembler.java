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
 * 热度/聚类视野装配器：把窗口内的已发布条目连同主题关联与信源
 * 注册表组装成聚类输入——持久化重算（NewsHeatService）与查询侧
 * （/hot、列表徽章）共用同一次装配形状，保证两侧簇语义一致。
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
     * 装载窗口内已发布条目（publishTime ≥ 下界；下界 null=不限）
     */
    public NewsStoryWindow loadPublished(Date windowFloor) {
        LambdaQueryWrapper<NewsItemDO> query = Wrappers.lambdaQuery(NewsItemDO.class)
                .eq(NewsItemDO::getStatus, "published")
                .ge(windowFloor != null, NewsItemDO::getPublishTime, windowFloor);
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
                        topicsByItem.getOrDefault(item.getId(), Set.of()), item.getHeat()))
                .toList();
        return new NewsStoryWindow(storyItems, sourcesById);
    }
}
