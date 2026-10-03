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
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.news.controller.vo.NewsHotRankEntryVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsItemVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsPageVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsSourceMetaVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsTopicDetailVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsTopicVO;
import com.nageoffer.ai.ragent.news.dao.dto.TopicPublishedCountDTO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemTopicDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsTopicDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemTopicMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsTopicMapper;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import com.nageoffer.ai.ragent.news.heat.NewsHeatProperties;
import com.nageoffer.ai.ragent.news.heat.NewsHeatService;
import com.nageoffer.ai.ragent.news.heat.NewsStoryAssembler;
import com.nageoffer.ai.ragent.news.heat.NewsStoryCluster;
import com.nageoffer.ai.ragent.news.heat.NewsStoryClusterer;
import com.nageoffer.ai.ragent.news.heat.NewsStoryItem;
import com.nageoffer.ai.ragent.news.service.NewsQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 公开资讯查询服务实现（接热度真值与故事线聚簇）
 *
 * <p>量级前提：~10–40 条/日 × 90 天保留 ≈ 数千行，PG 足够、不建 ES。
 * 信源元数据以批量查回后内存 join（信源注册表 ≤10 行级，无 N+1）。
 *
 * <p><b>统一公开资格</b>（#185）：列表/详情/主题/检索/热点/徽章全部走同一条
 * 可见性规则——{@code status='published' AND (eligible_time IS NULL OR
 * eligible_time <= now - 180s)}（发布门从资格就绪起算，pending/archived/expired/
 * hidden 与未过门的 published 一律不可见；NULL 为 #185 前历史行）。本类是该
 * 规则的 wrapper 构建入口，主题面注解 SQL（NewsItemTopicMapper）与热度装配面
 * （NewsStoryAssembler#loadVisible）同口径——后续 RSS/日报/MCP 出口（#182）
 * 复用本入口，不得另写可见性判据。
 *
 * <p>热度接入后：热点榜=故事线粒度（一簇一条，标签与信源名单随簇）；列表卡带
 * 「另有 N 个来源」徽章。热度排序读已持久化 heat（抓取轮末重算，允许 ≤ 一个
 * 抓取轮的陈旧）；簇与标签在查询侧对重算窗口内条目即时计算，窗口=热度窗口
 * （4 天）——窗口外条目热度已归零、不参与榜单与徽章。
 */
@Service
public class NewsQueryServiceImpl implements NewsQueryService {

    private static final String STATUS_PUBLISHED = "published";
    private static final String STATUS_ACTIVE = "active";
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_HOT_LIMIT = 50;

    /** 「只看关注」多主题过滤的 slug 数量上限（关注集远小于目录规模，防参数滥用） */
    private static final int MAX_TOPIC_FILTER_SLUGS = 20;
    private static final int MIN_PAGE = 1;
    /** 检索排序档：relevance=标题命中优先；其余取值（含 time）走时间倒序 */
    private static final String SORT_RELEVANCE = "relevance";

    private final NewsItemMapper newsItemMapper;
    private final NewsSourceMapper newsSourceMapper;
    private final NewsTopicMapper newsTopicMapper;
    private final NewsItemTopicMapper newsItemTopicMapper;
    private final NewsStoryAssembler storyAssembler;
    private final NewsStoryClusterer storyClusterer;
    private final NewsHeatProperties heatProperties;
    private final NewsFetchProperties fetchProperties;
    private final Supplier<Date> nowSupplier;

    @Value("${rag.news.page-size-default:20}")
    private int pageSizeDefault;

    @Autowired
    public NewsQueryServiceImpl(NewsItemMapper newsItemMapper,
                                NewsSourceMapper newsSourceMapper,
                                NewsTopicMapper newsTopicMapper,
                                NewsItemTopicMapper newsItemTopicMapper,
                                NewsStoryAssembler storyAssembler,
                                NewsStoryClusterer storyClusterer,
                                NewsHeatProperties heatProperties,
                                NewsFetchProperties fetchProperties) {
        this(newsItemMapper, newsSourceMapper, newsTopicMapper, newsItemTopicMapper,
                storyAssembler, storyClusterer, heatProperties, fetchProperties, Date::new);
    }

    /**
     * 全参构造器（测试注入时钟，FakeWindowCounter 时间旅行先例同源）
     */
    NewsQueryServiceImpl(NewsItemMapper newsItemMapper,
                         NewsSourceMapper newsSourceMapper,
                         NewsTopicMapper newsTopicMapper,
                         NewsItemTopicMapper newsItemTopicMapper,
                         NewsStoryAssembler storyAssembler,
                         NewsStoryClusterer storyClusterer,
                         NewsHeatProperties heatProperties,
                         NewsFetchProperties fetchProperties,
                         Supplier<Date> nowSupplier) {
        this.newsItemMapper = newsItemMapper;
        this.newsSourceMapper = newsSourceMapper;
        this.newsTopicMapper = newsTopicMapper;
        this.newsItemTopicMapper = newsItemTopicMapper;
        this.storyAssembler = storyAssembler;
        this.storyClusterer = storyClusterer;
        this.heatProperties = heatProperties;
        this.fetchProperties = fetchProperties;
        this.nowSupplier = nowSupplier;
    }

    /**
     * 发布门下界：now - publish-gate-seconds（资格就绪 180s 后过门）
     */
    private Date gateFloor() {
        Date now = nowSupplier.get();
        return new Date(now.getTime() - fetchProperties.effectivePublishGateSeconds() * 1000L);
    }

    /**
     * 统一公开资格（wrapper 面）：status=published 且发布门已开启——
     * 类 javadoc 合同的唯一构建点
     */
    private LambdaQueryWrapper<NewsItemDO> visibleItems() {
        Date gateFloor = gateFloor();
        return new LambdaQueryWrapper<NewsItemDO>()
                .eq(NewsItemDO::getStatus, STATUS_PUBLISHED)
                .and(w -> w.isNull(NewsItemDO::getEligibleTime).or().le(NewsItemDO::getEligibleTime, gateFloor));
    }

    @Override
    public NewsPageVO listPublished(String category, int page, int size) {
        return listPublished(category, null, page, size);
    }

    @Override
    public NewsPageVO listPublished(String category, List<String> topicSlugs, int page, int size) {
        List<Long> topicIds = resolveCuratedTopicIds(topicSlugs);
        if (topicIds != null && topicIds.isEmpty()) {
            // 请求了主题过滤但全部未命中 curated 目录（如转正后被下架）——契约空页，
            // 不落条目查询（buildPageVO 空页三态之一）
            return buildPageVO(List.of(), 0L, normalizePage(page), normalizeSize(size), false);
        }
        // 急切求值防线（同 promote() 条件 set 判例）：apply 的 sql 实参先于 condition
        // 求值，topicIds==null 时不得触碰 topicExistsSql——判空后单独注入
        LambdaQueryWrapper<NewsItemDO> wrapper = visibleItems()
                .eq(!isBlank(category), NewsItemDO::getCategory, category);
        if (topicIds != null) {
            wrapper.apply(topicExistsSql(topicIds));
        }
        Page<NewsItemDO> pager = newsItemMapper.selectPage(
                new Page<>(normalizePage(page), normalizeSize(size)),
                wrapper
                        .orderByDesc(NewsItemDO::getPublishTime)
                        .orderByDesc(NewsItemDO::getId));
        NewsPageVO pageVO = toPageVO(pager, pager.getRecords());
        fillClusterBadges(pager.getRecords(), pageVO.getRecords());
        return pageVO;
    }

    /**
     * 「只看关注」主题 slug → curated+active 主题 id 解析（#215 调整）：
     * 规范化（trim/小写/去重）+ 数量上限截断（防参数滥用）；null 入参=未启用
     * 过滤返回 null（与「解析结果为空」区分——后者=契约空页）
     */
    private List<Long> resolveCuratedTopicIds(List<String> topicSlugs) {
        if (topicSlugs == null || topicSlugs.isEmpty()) {
            return null;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String slug : topicSlugs) {
            String value = slug == null ? "" : slug.strip().toLowerCase(Locale.ROOT);
            if (!value.isEmpty()) {
                normalized.add(value);
                if (normalized.size() >= MAX_TOPIC_FILTER_SLUGS) {
                    break;
                }
            }
        }
        if (normalized.isEmpty()) {
            return null;
        }
        return newsTopicMapper.selectList(new LambdaQueryWrapper<NewsTopicDO>()
                        .in(NewsTopicDO::getSlug, normalized)
                        .eq(NewsTopicDO::getCurated, true)
                        .eq(NewsTopicDO::getStatus, STATUS_ACTIVE))
                .stream().map(NewsTopicDO::getId).toList();
    }

    /** 主题成员 EXISTS 判据（id 列表为库内 Long 字面量拼接，无注入面） */
    private static String topicExistsSql(List<Long> topicIds) {
        String joined = topicIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        return "EXISTS (SELECT 1 FROM t_news_item_topic nit WHERE nit.item_id = t_news_item.id"
                + " AND nit.topic_id IN (" + joined + "))";
    }

    @Override
    public List<NewsHotRankEntryVO> listHot(int limit) {
        int bounded = Math.min(Math.max(limit, 1), MAX_HOT_LIMIT);
        Date now = nowSupplier.get();
        Date windowFloor = new Date(now.getTime() - NewsHeatService.HEAT_WINDOW_DAYS * 24L * 3600L * 1000L);
        NewsStoryAssembler.NewsStoryWindow window = storyAssembler.loadVisible(windowFloor, gateFloor());
        List<NewsStoryItem> storyItems = window.items();
        Map<Long, NewsSourceDO> sourcesById = window.sourcesById();
        if (storyItems.isEmpty()) {
            // 冷却期兜底：窗口内无条目时回退最新 bounded 条（热度全 0 的时间序语义沿骨架版）
            storyItems = latestPublishedAsStoryItems(bounded);
            sourcesById = sourceMapByIds(storyItems.stream()
                    .map(NewsStoryItem::sourceId).collect(Collectors.toSet()));
        }
        if (storyItems.isEmpty()) {
            return Collections.emptyList();
        }
        List<NewsStoryCluster> clusters = storyClusterer.cluster(storyItems, heatProperties.isStoryMergeEnabled());
        List<HotEntry> entries = new ArrayList<>(clusters.size());
        for (NewsStoryCluster cluster : clusters) {
            entries.add(new HotEntry(cluster, maxStoredHeat(cluster),
                    storyClusterer.tags(cluster, now), sourceNames(cluster, sourcesById)));
        }
        entries.sort(Comparator.comparingInt(HotEntry::heat).reversed()
                .thenComparing(hotEntry -> hotEntry.cluster.latestPublish(),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(hotEntry -> hotEntry.cluster.representative().id(),
                        Comparator.nullsFirst(Comparator.naturalOrder())));
        return entries.stream()
                .limit(bounded)
                .map(entry -> {
                    NewsStoryItem rep = entry.cluster.representative();
                    return NewsHotRankEntryVO.builder()
                            .itemId(rep.id())
                            .titleZh(firstNonBlank(entry.cluster.members(), NewsStoryItem::titleZh))
                            .titleEn(firstNonBlank(entry.cluster.members(), NewsStoryItem::titleEn))
                            .heat(entry.heat())
                            .tags(entry.tags())
                            .sources(entry.sourceNames())
                            .build();
                })
                .toList();
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
        Map<Long, Long> counts = visibleCountByTopic();
        return topics.stream()
                .map(topic -> toTopicVO(topic, counts.getOrDefault(topic.getId(), 0L)))
                .toList();
    }

    @Override
    public NewsTopicDetailVO getTopicDetail(String slug, int page, int size) {
        Assert.notBlank(slug, () -> new ClientException("主题不存在"));
        NewsTopicDO topic = findCuratedTopic(slug);
        Map<Long, Long> counts = visibleCountByTopic();
        IPage<NewsItemDO> items = newsItemTopicMapper.selectVisiblePageByTopic(
                new Page<>(normalizePage(page), normalizeSize(size)), topic.getId(), gateFloor());
        return NewsTopicDetailVO.builder()
                .topic(toTopicVO(topic, counts.getOrDefault(topic.getId(), 0L)))
                .lastPublishTime(newsItemTopicMapper.selectLastVisiblePublishTime(topic.getId(), gateFloor()))
                .items(toPageVO(items, items.getRecords()))
                .build();
    }

    @Override
    public NewsItemVO getPublishedDetail(long id) {
        Assert.isTrue(id > 0, () -> new ClientException("资讯不存在"));
        NewsItemDO item = newsItemMapper.selectOne(
                visibleItems()
                        .eq(NewsItemDO::getId, id)
                        .last("LIMIT 1"));
        // 下架/不存在/未过门同形文案：不向匿名访问者泄漏条目存在性（与「功能未部署」404 同口径）
        Assert.notNull(item, () -> new ClientException("资讯不存在"));
        return toItemVOs(List.of(item)).get(0);
    }

    @Override
    public NewsPageVO searchPublished(String q, String sort, String order, String category, int page, int size) {
        if (isBlank(q)) {
            return emptyPage(page, size);
        }
        String needle = q.trim();
        String pattern = "%" + escapeLike(needle) + "%";
        // 检索面同走统一公开资格（QueryWrapper 形态：与 visibleItems 同一判据的 SQL 面）
        QueryWrapper<NewsItemDO> match = new QueryWrapper<NewsItemDO>()
                .eq("status", STATUS_PUBLISHED)
                .apply("(eligible_time IS NULL OR eligible_time <= {0})", gateFloor())
                .apply("(title_zh ILIKE {0} OR title_en ILIKE {0} OR summary_zh ILIKE {0} OR summary_en ILIKE {0})",
                        pattern);
        // T21：关键词×分类互通——检索可限定分类范围（eq 谓词同 list 分支）
        if (!isBlank(category)) {
            match.eq("category", category.trim());
        }
        long pageNo = normalizePage(page);
        int pageSize = normalizeSize(size);
        boolean ascending = "asc".equalsIgnoreCase(order == null ? "" : order.trim());
        if (SORT_RELEVANCE.equals(sort)) {
            // relevance：标题命中优先、摘要命中次之、同分按时间——匹配集整取内存排序
            // 后切片（量级前提=90 天保留数千行），避免 SQL 字面量拼装引入注入面
            // T21：order 贯通两向——asc 时桶序翻转（非标题命中在前）+桶内时间正序
            List<NewsItemDO> matched = newsItemMapper.selectList(match);
            Comparator<NewsItemDO> bucket = Comparator
                    .comparingInt((NewsItemDO item) -> titleHit(item, needle) ? 0 : 1);
            Comparator<NewsItemDO> byTime = Comparator.comparing(NewsItemDO::getPublishTime,
                    ascending
                            ? Comparator.nullsLast(Comparator.<Date>naturalOrder())
                            : Comparator.nullsLast(Comparator.<Date>reverseOrder()));
            Comparator<NewsItemDO> byId = Comparator.comparing(NewsItemDO::getId,
                    ascending ? Comparator.naturalOrder() : Comparator.reverseOrder());
            matched.sort(ascending ? bucket.reversed().thenComparing(byTime).thenComparing(byId)
                    : bucket.thenComparing(byTime).thenComparing(byId));
            int from = (int) Math.min((pageNo - 1) * pageSize, matched.size());
            int to = (int) Math.min(from + pageSize, matched.size());
            return buildPageVO(matched.subList(from, to), matched.size(), pageNo, pageSize, to < matched.size());
        }
        // time（默认）与未知取值：按发布时间两向（T21 默认 desc=最新在前），与 list 同序——SQL 侧分页
        IPage<NewsItemDO> pager = newsItemMapper.selectPage(new Page<>(pageNo, pageSize),
                ascending
                        ? match.orderByAsc("publish_time").orderByAsc("id")
                        : match.orderByDesc("publish_time").orderByDesc("id"));
        return toPageVO(pager, pager.getRecords());
    }

    /**
     * MCP 出口受限检索（#214）：关键词 × 主题 × 时间窗三元可组合过滤，发布时间倒序。
     * <p>
     * 可见性与 searchPublished 同一条统一公开资格（QueryWrapper 面：status=published +
     * 发布门），下架/未过门条目隔离；主题过滤用 EXISTS 半连接（与
     * NewsItemTopicMapper#selectVisiblePageByTopic 同一关联语义，可见性判据仍由本 wrapper
     * 独占，不在关联表 SQL 里另写一份）。时间窗端点含当日：上界取「次日零点」开区间，
     * 不枚举时分秒；Timestamp.valueOf 走墙钟语义，不受 JVM/DB 时区漂移影响。
     */
    @Override
    public NewsPageVO searchPublishedForMcp(String q, String topicSlug, LocalDate dateFrom, LocalDate dateTo,
                                            int page, int size) {
        Long topicId = isBlank(topicSlug) ? null : findCuratedTopic(topicSlug).getId();
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new ClientException("时间窗不合法：起始日期晚于结束日期");
        }
        QueryWrapper<NewsItemDO> match = new QueryWrapper<NewsItemDO>()
                .eq("status", STATUS_PUBLISHED)
                .apply("(eligible_time IS NULL OR eligible_time <= {0})", gateFloor());
        if (!isBlank(q)) {
            String pattern = "%" + escapeLike(q.trim()) + "%";
            match.apply("(title_zh ILIKE {0} OR title_en ILIKE {0} OR summary_zh ILIKE {0} OR summary_en ILIKE {0})",
                    pattern);
        }
        if (topicId != null) {
            match.apply("EXISTS (SELECT 1 FROM t_news_item_topic iit "
                    + "WHERE iit.item_id = t_news_item.id AND iit.topic_id = {0})", topicId);
        }
        if (dateFrom != null) {
            match.ge("publish_time", Timestamp.valueOf(dateFrom.atStartOfDay()));
        }
        if (dateTo != null) {
            match.lt("publish_time", Timestamp.valueOf(dateTo.plusDays(1).atStartOfDay()));
        }
        IPage<NewsItemDO> pager = newsItemMapper.selectPage(new Page<>(normalizePage(page), normalizeSize(size)),
                match.orderByDesc("publish_time").orderByDesc("id"));
        return toPageVO(pager, pager.getRecords());
    }

    /**
     * 策展主题按 slug 解析（active + curated）：不存在/已合并/已弃/AI 提案一律同形
     * 「主题不存在」——getTopicDetail 与 MCP 出口检索共用，不泄漏存在性
     */
    private NewsTopicDO findCuratedTopic(String slug) {
        NewsTopicDO topic = newsTopicMapper.selectOne(
                new LambdaQueryWrapper<NewsTopicDO>()
                        .eq(NewsTopicDO::getSlug, slug.trim())
                        .eq(NewsTopicDO::getStatus, STATUS_ACTIVE)
                        .eq(NewsTopicDO::getCurated, true)
                        .last("LIMIT 1"));
        Assert.notNull(topic, () -> new ClientException("主题不存在"));
        return topic;
    }

    /**
     * 列表卡聚簇徽章：「另有 N 个来源」=簇覆盖信源数-1（≥2 源才显）。
     * 簇按热度窗口即时计算（#185：公开面装配走 loadVisible，未过门条目不进徽章视野）；
     * 窗口外/降级态/单源条目不设徽章（VO 字段保持 null）。
     */
    private void fillClusterBadges(List<NewsItemDO> records, List<NewsItemVO> vos) {
        if (records.isEmpty()) {
            return;
        }
        Date now = nowSupplier.get();
        Date windowFloor = new Date(now.getTime() - NewsHeatService.HEAT_WINDOW_DAYS * 24L * 3600L * 1000L);
        NewsStoryAssembler.NewsStoryWindow window = storyAssembler.loadVisible(windowFloor, gateFloor());
        if (window.items().isEmpty()) {
            return;
        }
        Map<Long, Integer> badgeByItemId = new HashMap<>();
        for (NewsStoryCluster cluster : storyClusterer.cluster(window.items(), heatProperties.isStoryMergeEnabled())) {
            long sources = cluster.distinctSourceCount();
            if (sources >= 2) {
                int others = (int) sources - 1;
                for (NewsStoryItem member : cluster.members()) {
                    badgeByItemId.put(member.id(), others);
                }
            }
        }
        if (badgeByItemId.isEmpty()) {
            return;
        }
        for (int i = 0; i < records.size(); i++) {
            Integer badge = badgeByItemId.get(records.get(i).getId());
            if (badge != null) {
                vos.get(i).setClusterSourceCount(badge);
            }
        }
    }

    /**
     * 冷却期兜底：最新 bounded 条公开可见条目（窗口外旧条目，无主题关联形态）——
     * 同走统一公开资格（未过门条目不冒头）
     */
    private List<NewsStoryItem> latestPublishedAsStoryItems(int bounded) {
        return newsItemMapper.selectList(visibleItems()
                        .orderByDesc(NewsItemDO::getPublishTime)
                        .orderByDesc(NewsItemDO::getId)
                        .last("LIMIT " + bounded))
                .stream()
                .map(item -> new NewsStoryItem(item.getId(), item.getTitleZh(), item.getTitleEn(),
                        item.getCategory(), item.getSourceId(), item.getPublishTime(), Set.of(), item.getHeat(),
                        item.getSummaryZh(), item.getSummarySource()))
                .toList();
    }

    /**
     * 簇展示热度：成员已持久化热度的最大值（同簇写入同值，此处的 max 兜底陈旧窗口内更新不一致）
     */
    private int maxStoredHeat(NewsStoryCluster cluster) {
        int max = 0;
        for (NewsStoryItem member : cluster.members()) {
            if (member.heat() != null && member.heat() > max) {
                max = member.heat();
            }
        }
        return max;
    }

    /**
     * 簇信源展示名：权重高者先（官网主站高、补充源低），同权重按 sourceKey 稳定序
     */
    private List<String> sourceNames(NewsStoryCluster cluster, Map<Long, NewsSourceDO> sourcesById) {
        return cluster.distinctSourceIds().stream()
                .map(sourcesById::get)
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparingInt(this::sourceWeight).reversed()
                        .thenComparing(NewsSourceDO::getSourceKey,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::sourceDisplayName)
                .toList();
    }

    private int sourceWeight(NewsSourceDO source) {
        return source.getSourceKey() == null ? 0
                : heatProperties.getSourceWeights().getOrDefault(source.getSourceKey(), 0);
    }

    private String sourceDisplayName(NewsSourceDO source) {
        if (source.getDisplayName() != null) {
            return source.getDisplayName();
        }
        if (source.getDisplayNameEn() != null) {
            return source.getDisplayNameEn();
        }
        return source.getSourceKey();
    }

    private String firstNonBlank(List<NewsStoryItem> members, Function<NewsStoryItem, String> field) {
        for (NewsStoryItem member : members) {
            String value = field.apply(member);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * 榜单内部排序载荷（簇+热度+标签+来源名的临时绑定）
     */
    private record HotEntry(NewsStoryCluster cluster, int heat, List<String> tags, List<String> sourceNames) {
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

    private Map<Long, Long> visibleCountByTopic() {
        return newsItemTopicMapper.countVisibleByTopic(gateFloor()).stream()
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
        return buildPageVO(records, pager.getTotal(), pager.getCurrent(), pager.getSize(),
                pager.getCurrent() * pager.getSize() < pager.getTotal());
    }

    /** 分页 VO 单点构造（list SQL 分页/检索内存切片/契约空页三态共用） */
    private NewsPageVO buildPageVO(List<NewsItemDO> records, long total, long page, long size, boolean hasMore) {
        return NewsPageVO.builder()
                .records(toItemVOs(records))
                .total(total)
                .page(page)
                .size(size)
                .hasMore(hasMore)
                .build();
    }

    private List<NewsItemVO> toItemVOs(List<NewsItemDO> items) {
        if (items.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, NewsSourceDO> sources = sourceMapByIds(
                items.stream().map(NewsItemDO::getSourceId).collect(Collectors.toSet()));
        Map<Long, List<String>> topicSlugs = topicSlugsByItemIds(
                items.stream().map(NewsItemDO::getId).collect(Collectors.toSet()));
        return items.stream()
                .map(item -> toItemVO(item, sources, topicSlugs.getOrDefault(item.getId(), List.of())))
                .toList();
    }

    private NewsItemVO toItemVO(NewsItemDO item, Map<Long, NewsSourceDO> sources, List<String> topics) {
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
                .topics(topics)
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

    /**
     * 条目主题 slug 批量查回（真数据接线）：链接行 IN 批查 + 主题注册表一次查回内存 join，
     * 无 N+1；条目无主题落空列表（前端 TopicDetail 按 slug 过滤、卡片主题章消费）。
     */
    private Map<Long, List<String>> topicSlugsByItemIds(Collection<Long> itemIds) {
        Set<Long> nonNullIds = itemIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (nonNullIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<NewsItemTopicDO> links = newsItemTopicMapper.selectList(
                new LambdaQueryWrapper<NewsItemTopicDO>().in(NewsItemTopicDO::getItemId, nonNullIds));
        if (links.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> slugByTopicId = newsTopicMapper.selectBatchIds(
                        links.stream().map(NewsItemTopicDO::getTopicId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(NewsTopicDO::getId, NewsTopicDO::getSlug));
        Map<Long, List<String>> result = new HashMap<>();
        for (NewsItemTopicDO link : links) {
            String slug = slugByTopicId.get(link.getTopicId());
            if (slug != null) {
                result.computeIfAbsent(link.getItemId(), key -> new ArrayList<>()).add(slug);
            }
        }
        return result;
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /**
     * 检索 LIKE 转义：用户输入按字面匹配——反斜杠/%/_ 转义后交 PG
     * 默认转义符（反斜杠）消解通配符注入；参数化由 apply({0}) 保底
     */
    private String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** relevance 档排名判定：任一标题列命中（大小写不敏感子串）即标题命中 */
    private boolean titleHit(NewsItemDO item, String needle) {
        return containsIgnoreCase(item.getTitleZh(), needle) || containsIgnoreCase(item.getTitleEn(), needle);
    }

    private static boolean containsIgnoreCase(String text, String needle) {
        return text != null && text.toLowerCase().contains(needle.toLowerCase());
    }

    /** 检索契约兜底：blank q 直接空页（前端 q 空时不进检索态，不会走到此分支的 UI） */
    private NewsPageVO emptyPage(int page, int size) {
        return buildPageVO(Collections.emptyList(), 0L, normalizePage(page), normalizeSize(size), false);
    }
}
