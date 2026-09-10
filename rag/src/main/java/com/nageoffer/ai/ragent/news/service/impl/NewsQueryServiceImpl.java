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

import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.news.controller.vo.NewsItemVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsPageVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsSourceMetaVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsTopicDetailVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsTopicVO;
import com.nageoffer.ai.ragent.news.dao.dto.TopicPublishedCountDTO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsTopicDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemTopicMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsTopicMapper;
import com.nageoffer.ai.ragent.news.service.NewsQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 公开资讯查询服务实现（U12-A A2 骨架）
 *
 * <p>量级前提（doc 19 §3）：~10–40 条/日 × 90 天保留 ≈ 数千行，PG 足够、不建 ES。
 * 信源元数据以批量查回后内存 join（信源注册表 ≤10 行级，无 N+1）。
 * 热度分 A4 灌值前沿 publish_time 兜底排序，端点语义对空库天然返回空列表。
 */
@Service
@RequiredArgsConstructor
public class NewsQueryServiceImpl implements NewsQueryService {

    private static final String STATUS_PUBLISHED = "published";
    private static final String STATUS_ACTIVE = "active";
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_HOT_LIMIT = 50;
    private static final int MIN_PAGE = 1;

    private final NewsItemMapper newsItemMapper;
    private final NewsSourceMapper newsSourceMapper;
    private final NewsTopicMapper newsTopicMapper;
    private final NewsItemTopicMapper newsItemTopicMapper;

    /**
     * 列表默认页大小（doc 19 §13-9：首屏 20 条+加载更多）
     */
    @Value("${rag.news.page-size-default:20}")
    private int pageSizeDefault;

    @Override
    public NewsPageVO listPublished(String category, int page, int size) {
        Page<NewsItemDO> pager = newsItemMapper.selectPage(
                new Page<>(normalizePage(page), normalizeSize(size)),
                new LambdaQueryWrapper<NewsItemDO>()
                        .eq(NewsItemDO::getStatus, STATUS_PUBLISHED)
                        .eq(!isBlank(category), NewsItemDO::getCategory, category)
                        .orderByDesc(NewsItemDO::getPublishTime)
                        .orderByDesc(NewsItemDO::getId));
        return toPageVO(pager, pager.getRecords());
    }

    @Override
    public List<NewsItemVO> listHot(int limit) {
        int bounded = Math.min(Math.max(limit, 1), MAX_HOT_LIMIT);
        List<NewsItemDO> items = newsItemMapper.selectList(
                new LambdaQueryWrapper<NewsItemDO>()
                        .eq(NewsItemDO::getStatus, STATUS_PUBLISHED)
                        .orderByDesc(NewsItemDO::getHeat)
                        .orderByDesc(NewsItemDO::getPublishTime)
                        .orderByDesc(NewsItemDO::getId)
                        .last("LIMIT " + bounded));
        return toItemVOs(items);
    }

    @Override
    public List<NewsTopicVO> listCuratedTopics() {
        List<NewsTopicDO> topics = newsTopicMapper.selectList(
                new LambdaQueryWrapper<NewsTopicDO>()
                        .eq(NewsTopicDO::getStatus, STATUS_ACTIVE)
                        .eq(NewsTopicDO::getCurated, true)
                        .orderByAsc(NewsTopicDO::getTopicGroup)
                        .orderByAsc(NewsTopicDO::getId));
        if (topics.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, Long> counts = publishedCountByTopic();
        return topics.stream()
                .map(topic -> toTopicVO(topic, counts.getOrDefault(topic.getId(), 0L)))
                .toList();
    }

    @Override
    public NewsTopicDetailVO getTopicDetail(String slug, int page, int size) {
        Assert.notBlank(slug, () -> new ClientException("主题不存在"));
        NewsTopicDO topic = newsTopicMapper.selectOne(
                new LambdaQueryWrapper<NewsTopicDO>()
                        .eq(NewsTopicDO::getSlug, slug)
                        .eq(NewsTopicDO::getStatus, STATUS_ACTIVE)
                        .eq(NewsTopicDO::getCurated, true)
                        .last("LIMIT 1"));
        Assert.notNull(topic, () -> new ClientException("主题不存在"));
        Map<Long, Long> counts = publishedCountByTopic();
        IPage<NewsItemDO> items = newsItemTopicMapper.selectPublishedPageByTopic(
                new Page<>(normalizePage(page), normalizeSize(size)), topic.getId());
        return NewsTopicDetailVO.builder()
                .topic(toTopicVO(topic, counts.getOrDefault(topic.getId(), 0L)))
                .lastPublishTime(newsItemTopicMapper.selectLastPublishTime(topic.getId()))
                .items(toPageVO(items, items.getRecords()))
                .build();
    }

    /**
     * 分页语义收敛：页码 1 起、页大小 [1, 50]
     */
    private long normalizePage(int page) {
        return Math.max(page, MIN_PAGE);
    }

    private int normalizeSize(int size) {
        if (size <= 0) {
            return pageSizeDefault > 0 ? pageSizeDefault : DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private Map<Long, Long> publishedCountByTopic() {
        return newsItemTopicMapper.countPublishedByTopic().stream()
                .collect(Collectors.toMap(TopicPublishedCountDTO::getTopicId, TopicPublishedCountDTO::getCnt));
    }

    private NewsTopicVO toTopicVO(NewsTopicDO topic, long itemCount) {
        return NewsTopicVO.builder()
                .slug(topic.getSlug())
                .nameZh(topic.getNameZh())
                .nameEn(topic.getNameEn())
                .topicGroup(topic.getTopicGroup())
                .descriptionZh(topic.getDescriptionZh())
                .descriptionEn(topic.getDescriptionEn())
                .itemCount(itemCount)
                .build();
    }

    private NewsPageVO toPageVO(IPage<NewsItemDO> pager, List<NewsItemDO> records) {
        return NewsPageVO.builder()
                .records(toItemVOs(records))
                .total(pager.getTotal())
                .page(pager.getCurrent())
                .size(pager.getSize())
                .hasMore(pager.getCurrent() * pager.getSize() < pager.getTotal())
                .build();
    }

    private List<NewsItemVO> toItemVOs(List<NewsItemDO> items) {
        if (items.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, NewsSourceDO> sources = sourceMapByIds(
                items.stream().map(NewsItemDO::getSourceId).collect(Collectors.toSet()));
        return items.stream().map(item -> toItemVO(item, sources)).toList();
    }

    private NewsItemVO toItemVO(NewsItemDO item, Map<Long, NewsSourceDO> sources) {
        NewsSourceDO source = sources.get(item.getSourceId());
        return NewsItemVO.builder()
                .id(item.getId())
                .url(item.getUrl())
                .titleZh(item.getTitleZh())
                .titleEn(item.getTitleEn())
                .summaryZh(item.getSummaryZh())
                .summaryEn(item.getSummaryEn())
                .category(item.getCategory())
                .publishTime(item.getPublishTime())
                .heat(item.getHeat())
                .source(toSourceMetaVO(source))
                .build();
    }

    private NewsSourceMetaVO toSourceMetaVO(NewsSourceDO source) {
        if (source == null) {
            return null;
        }
        return NewsSourceMetaVO.builder()
                .sourceKey(source.getSourceKey())
                .platform(source.getPlatform())
                .official(source.getOfficial())
                .displayName(source.getDisplayName())
                .displayNameEn(source.getDisplayNameEn())
                .build();
    }

    /**
     * 信源批量查回（注册表行级量级，信源行被删时条目 source 落 null，卡片端按缺失渲染）
     */
    private Map<Long, NewsSourceDO> sourceMapByIds(Collection<Long> sourceIds) {
        Set<Long> nonNullIds = sourceIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (nonNullIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return newsSourceMapper.selectBatchIds(nonNullIds).stream()
                .collect(Collectors.toMap(NewsSourceDO::getId, Function.identity()));
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
