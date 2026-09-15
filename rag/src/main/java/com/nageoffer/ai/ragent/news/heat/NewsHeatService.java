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

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceMapper;
import com.nageoffer.ai.ragent.news.heat.NewsStoryAssembler.NewsStoryWindow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 热度模型服务
 *
 * <p>热度 =（Σ覆盖信源权重 + 覆盖信源数）× 24h 半衰期衰减，衰减时间锚=故事线
 * 首报（簇内最早发布时刻）；同簇条目写入同一簇热度。未来开始的条目（events 型
 * 活动预告）不因负龄放大，衰减系数封顶 1。
 *
 * <p>重算由抓取轮末尾触发（NewsFetchJob 每轮一次，节奏 3 段/日），持久化到
 * t_news_item.heat——查询侧（/hot、主题近期焦点、卡片）直接按已持久化热度排序，
 * 允许 ≤ 一个抓取轮的陈旧。窗口取 4 天：基数上限≈9 时 4 天衰减 <0.3，更旧条目
 * 热度归零（90 天保留期由 NewsRetentionJob 收尾），窗口外不重算。
 */
@Slf4j
@Service
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

    private final NewsItemMapper itemMapper;
    private final NewsStoryAssembler assembler;
    private final NewsStoryClusterer clusterer;
    private final NewsHeatProperties properties;
    private final Supplier<Date> nowSupplier;

    @org.springframework.beans.factory.annotation.Autowired
    public NewsHeatService(NewsItemMapper itemMapper,
                           NewsStoryAssembler assembler,
                           NewsStoryClusterer clusterer,
                           NewsHeatProperties properties) {
        this(itemMapper, assembler, clusterer, properties, Date::new);
    }

    /**
     * 全参构造器（测试注入时钟，FakeWindowCounter 时间旅行先例同源）
     */
    NewsHeatService(NewsItemMapper itemMapper,
                    NewsStoryAssembler assembler,
                    NewsStoryClusterer clusterer,
                    NewsHeatProperties properties,
                    Supplier<Date> nowSupplier) {
        this.itemMapper = itemMapper;
        this.assembler = assembler;
        this.clusterer = clusterer;
        this.properties = properties;
        this.nowSupplier = nowSupplier;
    }

    /**
     * 全库窗口内热度重算；返回发生变更的条目数。抓取轮末尾调用，单轮失败不阻断抓取。
     */
    public int recomputeHeat() {
        Date now = nowSupplier.get();
        Date windowFloor = new Date(now.getTime() - HEAT_WINDOW_DAYS * 24L * 3600L * 1000L);
        NewsStoryWindow window = assembler.loadPublished(windowFloor);
        if (window.items().isEmpty()) {
            return 0;
        }
        List<NewsStoryCluster> clusters = clusterer.cluster(window.items(), properties.isStoryMergeEnabled());
        int changed = 0;
        for (NewsStoryCluster cluster : clusters) {
            int heat = clusterHeat(cluster, window.sourcesById(), now);
            for (NewsStoryItem member : cluster.members()) {
                Integer current = member.heat();
                if (current != null && current == heat) {
                    continue;
                }
                itemMapper.update(null, Wrappers.lambdaUpdate(NewsItemDO.class)
                        .eq(NewsItemDO::getId, member.id())
                        .set(NewsItemDO::getHeat, heat));
                changed++;
            }
        }
        log.info("[news] 热度重算完成：窗口内 {} 条目 / {} 故事线，更新 {} 条",
                window.items().size(), clusters.size(), changed);
        return changed;
    }

    /**
     * 簇热度 =（Σ去重信源权重 + 覆盖信源数）× 半衰衰减；四舍五入取整，未来时间封顶 1 倍
     */
    int clusterHeat(NewsStoryCluster cluster, Map<Long, NewsSourceDO> sourcesById, Date now) {
        Set<Long> sourceIds = cluster.distinctSourceIds();
        int base = sourceIds.size();
        for (Long sourceId : sourceIds) {
            NewsSourceDO source = sourcesById.get(sourceId);
            if (source != null && source.getSourceKey() != null) {
                base += properties.getSourceWeights().getOrDefault(source.getSourceKey(), 0);
            }
        }
        if (base <= 0) {
            return 0;
        }
        Date earliest = cluster.earliestPublish();
        if (earliest == null) {
            return base;
        }
        double ageHours = (now.getTime() - earliest.getTime()) / 3600000.0;
        double decay = ageHours <= 0 ? 1.0 : Math.pow(0.5, ageHours / HALF_LIFE_HOURS);
        return (int) Math.max(0, Math.round(base * decay));
    }
}
