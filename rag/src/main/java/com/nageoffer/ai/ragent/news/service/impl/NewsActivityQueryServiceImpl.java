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

package com.nageoffer.ai.ragent.news.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemStatus;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import com.nageoffer.ai.ragent.news.service.NewsActivityQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 校园活动模型读取实现（#323 活动实体模型，接口语义见
 * {@link NewsActivityQueryService}）
 *
 * <p><b>活动实体识别</b>：t_news_item.activity_end_time 非空（起止成对——events
 * 等活动型来源在抓取面成对透出）；纯新闻条目（无明确起止）不进本投影。SQL 侧
 * 只限定统一公开资格形状（status=published+发布门 OR 组+publish_time 非空——
 * 与日报候选 {@code selectWindowCandidates} 同判据），窗口重叠/起止非空在
 * Java 侧求值（量级=活动条目个位数，KeyDate 栏目「SQL 限定+内存过滤」同构；
 * mock 直测时 wrapper 过滤不可达，Java 侧双保险）。
 *
 * <p><b>历日粒度</b>：t_news_item 起止是 TIMESTAMP（date-only 证据为代表值
 * start=D 00:00 / end=D 23:59:59 HKT），投影统一换算 HKT 历日——活动版面按
 * 「日」组织（进行中/即将来临/倒计时），不消费瞬时时刻。
 */
@Service
public class NewsActivityQueryServiceImpl implements NewsActivityQueryService {

    /**
     * 活动日期时区（与抓取面 monthToken/日报窗口统一 HKT）
     */
    private static final ZoneId HKT = ZoneId.of("Asia/Hong_Kong");

    private final NewsItemMapper itemMapper;
    private final NewsFetchProperties properties;
    private final Supplier<Date> nowSupplier;

    @Autowired
    public NewsActivityQueryServiceImpl(NewsItemMapper itemMapper, NewsFetchProperties properties) {
        this(itemMapper, properties, Date::new);
    }

    /**
     * 全参构造器（测试注入时钟，NewsDailyDigestServiceImpl 先例同源）
     */
    NewsActivityQueryServiceImpl(NewsItemMapper itemMapper, NewsFetchProperties properties,
                                 Supplier<Date> nowSupplier) {
        this.itemMapper = itemMapper;
        this.properties = properties;
        this.nowSupplier = nowSupplier;
    }

    @Override
    public List<CampusActivity> campusActivities(LocalDate asOf, int windowDays) {
        Objects.requireNonNull(asOf, "asOf");
        int effectiveWindowDays = Math.max(1, windowDays);
        LocalDate windowEndInclusive = asOf.plusDays(effectiveWindowDays - 1L);
        Date gateFloor = new Date(nowSupplier.get().getTime()
                - properties.effectivePublishGateSeconds() * 1000L);
        List<NewsItemDO> candidates = itemMapper.selectList(new LambdaQueryWrapper<NewsItemDO>()
                .eq(NewsItemDO::getStatus, NewsItemStatus.PUBLISHED)
                .and(w -> w.isNull(NewsItemDO::getEligibleTime)
                        .or().le(NewsItemDO::getEligibleTime, gateFloor))
                .isNotNull(NewsItemDO::getPublishTime));
        return candidates.stream()
                .filter(row -> row.getPublishTime() != null && row.getActivityEndTime() != null)
                .map(row -> toActivity(row, asOf, windowEndInclusive))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(CampusActivity::startDate)
                        .thenComparing(CampusActivity::itemId))
                .toList();
    }

    /**
     * 行→活动实体（区间与窗口任一历日重叠才保留，null=落窗外）
     */
    private static CampusActivity toActivity(NewsItemDO row, LocalDate windowStart,
                                             LocalDate windowEndInclusive) {
        LocalDate start = hktDate(row.getPublishTime());
        LocalDate end = hktDate(row.getActivityEndTime());
        boolean overlaps = !end.isBefore(windowStart) && !start.isAfter(windowEndInclusive);
        if (!overlaps) {
            return null;
        }
        return new CampusActivity(row.getId(), row.getTitleZh(), row.getTitleEn(), row.getUrl(),
                start, end);
    }

    private static LocalDate hktDate(Date value) {
        return ZonedDateTime.ofInstant(value.toInstant(), HKT).toLocalDate();
    }
}
