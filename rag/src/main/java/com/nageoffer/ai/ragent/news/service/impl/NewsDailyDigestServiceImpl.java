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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.calendar.KeyDateSemantics;
import com.nageoffer.ai.ragent.calendar.dao.entity.KeyDateDO;
import com.nageoffer.ai.ragent.calendar.dao.mapper.KeyDateMapper;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.infra.model.LlmBudgetExhaustedException;
import com.nageoffer.ai.ragent.news.dao.entity.NewsDailyDigestActivityDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsDailyDigestDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsDailyDigestItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsDailyDigestKeyDateDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemTopicDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsTopicDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsDailyDigestActivityMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsDailyDigestItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsDailyDigestKeyDateMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsDailyDigestMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemTopicMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsTopicMapper;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import com.nageoffer.ai.ragent.news.service.NewsActivityQueryService;
import com.nageoffer.ai.ragent.news.service.NewsDailyDigestService;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import com.nageoffer.ai.ragent.news.fetch.PublishTimePrecision;

/**
 * 资讯日报生成服务实现（#212，父票 #182 r3 §日报——P2-a 出口）
 *
 * <p><b>窗口合同（冻结）</b>：一刊=D 的 HKT 日，窗口=[D-1 08:00, D 08:00)
 * <b>左闭右开</b>；publish_time 恰落 08:00:00.000 的条目归属<b>下一期</b>
 * （窗口起点侧）。默认调度 08:40（rag.news.digest-cron）——08:00 采集轮
 * 已收尾、与 08:30 探活错峰，生成时读到的都是已完成行。
 *
 * <p><b>不抢读未完成行</b>：候选只取<b>统一公开资格</b>条目（status=published
 * 且过发布门——pending/expired/archived/hidden 与未过门条目一律不可见），
 * 与 NewsQueryServiceImpl.visibleItems 同一判据（#180 R4 可见性合同）；
 * 08:00 轮新入库的 pending 行在 08:40 时若仍未富化，自然不在候选内，
 * 不存在抢读。
 *
 * <p><b>迟到数据规则（冻结）</b>：条目归属只看 publish_time 与窗口的关系，
 * 与抓取/富化时刻无关——发布于 07:50、13:00 轮才富化过门的条目属当日刊的
 * 迟到数据，当日 08:40 生成时<b>尚未</b>过门 → 不进当期（不回捞）；
 * 显式 rebuildForDate(D) 重跑时若已过门 → 并入 D 刊（窗口边界不变，
 * 重跑=按冻结窗口全量重算）。漏跑由调度按 digest-backfill-days 补齐
 * （已存在的刊不自动重建）。
 *
 * <p><b>确定性选材（冻结，无评分）</b>：交付「全部动态」口径——窗口内全部
 * 公开资格条目全量入选，刊内序=publish_time DESC, id DESC（与资讯流列表
 * 同一确定性排序）。不引入 tier/分类配额/top-N 精选（t_news_source 无
 * tier 序、无冻结配额依据；票面明示无规则时只交付全部动态口径，不恢复
 * 评分流水线）。digest-max-items（默认 200）只是防御性容量上界（容量合同
 * ≤60 条/日新准入，200 已远超日常量级），<b>不是选材过滤器</b>。
 *
 * <p><b>导语（唯一 LLM 触点）</b>：单次调用走 {@link NewsLlmBudgetService}
 * （Tier.FAST、与摘要共用资讯 ¥10 月额度、t_news_llm_receipt 记账），
 * 提示词外置 prompt/news-digest-intro.st；调用失败/预算耗尽/解析无效
 * → 固定模板导语（{@link NewsDailyDigestTemplates}，零新增调用）照常出刊。
 * 空刊零调用直接落空刊模板。读取面（页面/RSS）零 LLM——见
 * {@link com.nageoffer.ai.ragent.news.service.NewsDailyDigestQueryService}。
 *
 * <p><b>校历关键日期栏目（#316，总纲 #315 线一 L1）</b>：每刊额外装配
 * 「未来 N 天关键日期与截止提醒」栏目快照行（t_news_daily_digest_key_date）
 * ——纯数据、零 LLM，供给与资讯量彻底解耦（空刊保底有内容）；数据源=
 * t_key_date 只读（#192 铁律），as-of=刊日生成期冻结，窗口/容量入
 * rag.news 配置（digest-key-date-window-days 默认 14 / -max-entries 默认 8）。
 *
 * <p><b>校园活动版面（#330，父票 #317——总纲 #315 线一 L2）</b>：每刊额外
 * 装配「进行中/即将来临」两组的活动版面快照行（t_news_daily_digest_activity）
 * ——纯数据、零 LLM，按<b>活动实体日期</b>（publish_time=活动开始 →
 * activity_end_time=活动结束，非资讯流 publish_time 窗口）组织；数据源=
 * {@link NewsActivityQueryService} 活动实体投影（#323 模型——events 现为唯一
 * 活跃源，CPEO/SAO 入库后自动汇入<b>无需改版面</b>，装配层只面向投影编程
 * 不感知源适配）。as-of=刊日生成期冻结（ongoing 不随读取时刻漂移），
 * 窗口/容量入 rag.news 配置（digest-activity-window-days 默认 56=8 周 /
 * -max-entries 默认 10）；过期活动（结束日早于刊日）经投影窗口合同自动
 * 移出，窗口零活动=零快照行（读取面整段隐藏）。
 *
 * <p><b>幂等</b>：digest_date 库级唯一；重建=按日期先删后插（快照行经
 * digest_id 外键 ON DELETE CASCADE 随旧刊头带走），同日期重跑只产一刊。
 * 单事务包住删+插，中途失败不产生半刊（下一轮重跑再建）。
 */
@Slf4j
@Service
public class NewsDailyDigestServiceImpl implements NewsDailyDigestService {

    /**
     * 导语提示词模板（外置，二开纪律）
     */
    static final String INTRO_PROMPT_PATH = "prompt/news-digest-intro.st";

    /**
     * 导语提示词携带的条目行上限（输入体积防线：40 行×60 字符≈2400 CJK 字符
     * ≈2400 tokens，在 max-input-tokens 4000 的正文预算内；超限截断只影响
     * 导语概括面，不影响快照全量——快照始终按窗口全量落库）
     */
    static final int INTRO_PROMPT_MAX_ITEMS = 40;

    /**
     * 导语提示词单行标题截断（字符）
     */
    static final int INTRO_PROMPT_TITLE_MAX_CHARS = 60;

    private static final String STATUS_PUBLISHED = "published";

    private final NewsDailyDigestMapper digestMapper;
    private final NewsDailyDigestItemMapper digestItemMapper;
    private final NewsDailyDigestKeyDateMapper digestKeyDateMapper;
    private final NewsDailyDigestActivityMapper digestActivityMapper;
    private final NewsItemMapper itemMapper;
    private final NewsItemTopicMapper itemTopicMapper;
    private final NewsTopicMapper topicMapper;
    private final NewsSourceMapper sourceMapper;
    private final KeyDateMapper keyDateMapper;
    private final NewsActivityQueryService activityQueryService;
    private final NewsLlmBudgetService llmBudgetService;
    private final PromptTemplateLoader promptTemplateLoader;
    private final NewsFetchProperties properties;
    private final ObjectMapper objectMapper;
    private final Supplier<Date> nowSupplier;

    @Autowired
    public NewsDailyDigestServiceImpl(NewsDailyDigestMapper digestMapper,
                                      NewsDailyDigestItemMapper digestItemMapper,
                                      NewsDailyDigestKeyDateMapper digestKeyDateMapper,
                                      NewsDailyDigestActivityMapper digestActivityMapper,
                                      NewsItemMapper itemMapper,
                                      NewsItemTopicMapper itemTopicMapper,
                                      NewsTopicMapper topicMapper,
                                      NewsSourceMapper sourceMapper,
                                      KeyDateMapper keyDateMapper,
                                      NewsActivityQueryService activityQueryService,
                                      NewsLlmBudgetService llmBudgetService,
                                      PromptTemplateLoader promptTemplateLoader,
                                      NewsFetchProperties properties) {
        this(digestMapper, digestItemMapper, digestKeyDateMapper, digestActivityMapper,
                itemMapper, itemTopicMapper, topicMapper, sourceMapper, keyDateMapper,
                activityQueryService, llmBudgetService, promptTemplateLoader, properties,
                new ObjectMapper(), Date::new);
    }

    /**
     * 全参构造器（测试注入时钟与 ObjectMapper，NewsFetchJob 先例同源）
     */
    NewsDailyDigestServiceImpl(NewsDailyDigestMapper digestMapper,
                               NewsDailyDigestItemMapper digestItemMapper,
                               NewsDailyDigestKeyDateMapper digestKeyDateMapper,
                               NewsDailyDigestActivityMapper digestActivityMapper,
                               NewsItemMapper itemMapper,
                               NewsItemTopicMapper itemTopicMapper,
                               NewsTopicMapper topicMapper,
                               NewsSourceMapper sourceMapper,
                               KeyDateMapper keyDateMapper,
                               NewsActivityQueryService activityQueryService,
                               NewsLlmBudgetService llmBudgetService,
                               PromptTemplateLoader promptTemplateLoader,
                               NewsFetchProperties properties,
                               ObjectMapper objectMapper,
                               Supplier<Date> nowSupplier) {
        this.digestMapper = digestMapper;
        this.digestItemMapper = digestItemMapper;
        this.digestKeyDateMapper = digestKeyDateMapper;
        this.digestActivityMapper = digestActivityMapper;
        this.itemMapper = itemMapper;
        this.itemTopicMapper = itemTopicMapper;
        this.topicMapper = topicMapper;
        this.sourceMapper = sourceMapper;
        this.keyDateMapper = keyDateMapper;
        this.activityQueryService = activityQueryService;
        this.llmBudgetService = llmBudgetService;
        this.promptTemplateLoader = promptTemplateLoader;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.nowSupplier = nowSupplier;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DigestBuildResult rebuildForDate(LocalDate digestDate) {
        Objects.requireNonNull(digestDate, "digestDate");
        ZonedDateTime windowEnd = digestDate.atTime(8, 0).atZone(NewsDailyDigestTemplates.HKT_ZONE);
        Date windowStart = Date.from(windowEnd.minusDays(1).toInstant());
        Date windowEndDate = Date.from(windowEnd.toInstant());
        // 候选=窗口内统一公开资格条目（确定性序：publish_time DESC, id DESC；上限=防御容量界）
        List<NewsItemDO> candidates = selectWindowCandidates(windowStart, windowEndDate);
        List<NewsDailyDigestItemDO> snapshots = buildSnapshots(candidates);
        // 导语：空刊零调用；非空单次 LLM（可失败回退模板）
        String introSource;
        String introZh;
        String introEn;
        if (snapshots.isEmpty()) {
            introSource = NewsDailyDigestDO.INTRO_SOURCE_EMPTY;
            introZh = NewsDailyDigestTemplates.emptyIntroZh(digestDate);
            introEn = NewsDailyDigestTemplates.emptyIntroEn(digestDate);
        } else {
            IntroOutcome intro = generateIntro(digestDate, windowStart, windowEndDate, candidates);
            introSource = intro.source();
            introZh = intro.zh();
            introEn = intro.en();
        }
        // 幂等重建：先删后插（同一事务；快照行经 digest_id 外键级联随旧刊头带走）
        digestMapper.delete(new LambdaQueryWrapper<NewsDailyDigestDO>()
                .eq(NewsDailyDigestDO::getDigestDate, digestDate));
        NewsDailyDigestDO header = NewsDailyDigestDO.builder()
                .digestDate(digestDate)
                .windowStart(windowStart)
                .windowEnd(windowEndDate)
                .introZh(introZh)
                .introEn(introEn)
                .introSource(introSource)
                .itemCount(snapshots.size())
                .status(NewsDailyDigestDO.STATUS_PUBLISHED)
                .buildTime(nowSupplier.get())
                .build();
        digestMapper.insert(header);
        int seq = 0;
        for (NewsDailyDigestItemDO snapshot : snapshots) {
            snapshot.setDigestId(header.getId());
            snapshot.setSeq(++seq);
            digestItemMapper.insert(snapshot);
        }
        // 校历关键日期栏目（#316 L1）：纯数据零 LLM，独立于资讯量——空刊也照常
        // 落栏目行（降级版式保底）；快照行经 digest_id 级联随重建带走
        List<NewsDailyDigestKeyDateDO> keyDateSnapshots = buildKeyDateSnapshots(digestDate);
        int keyDateSeq = 0;
        for (NewsDailyDigestKeyDateDO snapshot : keyDateSnapshots) {
            snapshot.setDigestId(header.getId());
            snapshot.setSeq(++keyDateSeq);
            digestKeyDateMapper.insert(snapshot);
        }
        // 校园活动版面（#330 L2）：纯数据零 LLM，独立于资讯量——空刊也照常落
        // 版面行；快照行经 digest_id 级联随重建带走
        List<NewsDailyDigestActivityDO> activitySnapshots = buildActivitySnapshots(digestDate);
        int activitySeq = 0;
        for (NewsDailyDigestActivityDO snapshot : activitySnapshots) {
            snapshot.setDigestId(header.getId());
            snapshot.setSeq(++activitySeq);
            digestActivityMapper.insert(snapshot);
        }
        log.info("[news][daily] 日报 {} 生成完成：快照 {} 条，导语产出={}，校历关键日期 {} 条（窗口 [{},{}]），校园活动 {} 项（窗口 [{},{}]）",
                digestDate, snapshots.size(), introSource, keyDateSnapshots.size(),
                digestDate, digestDate.plusDays(properties.effectiveDigestKeyDateWindowDays() - 1L),
                activitySnapshots.size(),
                digestDate, digestDate.plusDays(properties.effectiveDigestActivityWindowDays() - 1L));
        return new DigestBuildResult(digestDate, snapshots.size(), introSource, true);
    }

    /**
     * 本方法同样标 {@code @Transactional}（#213 收编窗复核补修）：生产路径=Job 经代理
     * 调本方法后<strong>自调用</strong> {@link #rebuildForDate}——Spring 代理不拦截
     * this 调用，仅 rebuildForDate 持注解时事务被旁路，「同一事务先删后插」失效
     * （崩溃窗内可能留半刊且跳过逻辑不自愈）。注解上提到代理入口后内层 this 调用
     * 运行于外层事务内，语义与冻结设计一致。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean generateIfMissing(LocalDate digestDate) {
        Long existing = digestMapper.selectCount(new LambdaQueryWrapper<NewsDailyDigestDO>()
                .eq(NewsDailyDigestDO::getDigestDate, digestDate));
        if (existing != null && existing > 0) {
            log.debug("[news][daily] 日报 {} 已存在，跳过（已存在的刊不自动重建）", digestDate);
            return false;
        }
        rebuildForDate(digestDate);
        return true;
    }

    // ==================== 候选选材（统一公开资格，冻结口径） ====================

    /**
     * 窗口候选：status=published + 过发布门 + publish_time∈[start,end) 左闭右开，
     * 序=publish_time DESC, id DESC——与 NewsQueryServiceImpl.visibleItems 同一
     * 可见性判据（#180 R4），pending/未过门/终态行一律不进候选（不抢读未完成行）
     */
    private List<NewsItemDO> selectWindowCandidates(Date windowStart, Date windowEnd) {
        Date gateFloor = new Date(nowSupplier.get().getTime()
                - properties.effectivePublishGateSeconds() * 1000L);
        return itemMapper.selectList(new LambdaQueryWrapper<NewsItemDO>()
                .eq(NewsItemDO::getStatus, STATUS_PUBLISHED)
                .and(w -> w.isNull(NewsItemDO::getEligibleTime)
                        .or().le(NewsItemDO::getEligibleTime, gateFloor))
                .isNotNull(NewsItemDO::getPublishTime)
                .ge(NewsItemDO::getPublishTime, windowStart)
                .lt(NewsItemDO::getPublishTime, windowEnd)
                .orderByDesc(NewsItemDO::getPublishTime)
                .orderByDesc(NewsItemDO::getId)
                .last("LIMIT " + properties.effectiveDigestMaxItems()));
    }

    /**
     * 快照构建：条目展示字段全冗余（标题/摘要/URL/分类/主题/信源元数据），
     * 源行后续被 90 天保留清理删除不影响快照可读性
     */
    private List<NewsDailyDigestItemDO> buildSnapshots(List<NewsItemDO> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<Long, List<String>> topicSlugs = topicSlugsByItemIds(
                candidates.stream().map(NewsItemDO::getId).collect(Collectors.toSet()));
        Map<Long, NewsSourceDO> sources = sourceMapByIds(
                candidates.stream().map(NewsItemDO::getSourceId).collect(Collectors.toSet()));
        List<NewsDailyDigestItemDO> snapshots = new ArrayList<>(candidates.size());
        for (NewsItemDO item : candidates) {
            NewsSourceDO source = item.getSourceId() == null ? null : sources.get(item.getSourceId());
            List<String> slugs = topicSlugs.getOrDefault(item.getId(), List.of());
            snapshots.add(NewsDailyDigestItemDO.builder()
                    .itemId(item.getId())
                    .url(item.getUrl())
                    .urlHash(item.getUrlHash())
                    .titleZh(item.getTitleZh())
                    .titleEn(item.getTitleEn())
                    .summaryZh(item.getSummaryZh())
                    .summaryEn(item.getSummaryEn())
                    .category(item.getCategory())
                    .topicSlugs(slugs.isEmpty() ? null : String.join(",", slugs))
                    .sourceId(item.getSourceId())
                    .sourceKey(source == null ? null : source.getSourceKey())
                    .sourcePlatform(source == null ? null : source.getPlatform())
                    .sourceOfficial(source == null ? null : source.getOfficial())
                    .sourceDisplayName(source == null ? null : source.getDisplayName())
                    .sourceDisplayNameEn(source == null ? null : source.getDisplayNameEn())
                    .publishTime(item.getPublishTime())
                    .publishTimePrecision(PublishTimePrecision.orUnknown(item.getPublishTimePrecision()))
                    .build());
        }
        return snapshots;
    }

    // ==================== 校历关键日期栏目（#316 L1，纯数据零 LLM） ====================

    /**
     * 关键日期栏目快照构建（生成期冻结，as-of=刊日 D）：
     * <ul>
     * <li>窗口=[D, D+N-1] 含端共 N 个历日（N={@code rag.news.digest-key-date-window-days}，
     *     默认 14）；status=published 且 date_start 或 date_end 落窗即入选
     *     （fuzzy 行 date_* 全 NULL 天然不落窗；过期条目=两端均在 D 前不出现）；</li>
     * <li>序=date_start 升序+uid 兜底（board currentAndUpcoming 同构：进行中
     *     已开行者自然在前），容量截断=超限取最近；</li>
     * <li>ongoing=已开始（date_start &lt; D）未结束（有效结束日 date_end??date_start
     *     &gt;= D）；days_until=D→date_start 天数，倒计时门=仅 exact-day/exact-range
     *     （onwards 不伪造截止语义恒 null——KeyDateSemantics 单一源，与看板同口径）；</li>
     * <li>窗口零条目=空列表（读取面整段隐藏，不渲染空壳）。</li>
     * </ul>
     * t_key_date 只读（#192 合同铁律），本方法零写入；展示字段全冗余快照，
     * withdrawn/归档不连带。
     */
    private List<NewsDailyDigestKeyDateDO> buildKeyDateSnapshots(LocalDate digestDate) {
        LocalDate windowEndInclusive = digestDate.plusDays(properties.effectiveDigestKeyDateWindowDays() - 1L);
        // 74 行量级全取内存过滤（SQL 侧只限定 published；防御性双保险=Java 侧
        // 再滤一次 status——KeyDateQueryServiceImpl.board 先例，mock 直测时
        // wrapper 过滤不可达）
        List<KeyDateDO> candidates = keyDateMapper.selectList(new LambdaQueryWrapper<KeyDateDO>()
                .eq(KeyDateDO::getStatus, STATUS_PUBLISHED));
        return candidates.stream()
                .filter(row -> STATUS_PUBLISHED.equals(row.getStatus()))
                .filter(row -> inWindow(row.getDateStart(), digestDate, windowEndInclusive)
                        || inWindow(row.getDateEnd(), digestDate, windowEndInclusive))
                .sorted(Comparator.comparing(KeyDateDO::getDateStart)
                        .thenComparing(KeyDateDO::getUid))
                .limit(properties.effectiveDigestKeyDateMaxEntries())
                .map(row -> toKeyDateSnapshot(row, digestDate))
                .toList();
    }

    /** 日期落窗判定（null=不落窗；含端） */
    private static boolean inWindow(LocalDate date, LocalDate windowStart, LocalDate windowEndInclusive) {
        return date != null && !date.isBefore(windowStart) && !date.isAfter(windowEndInclusive);
    }

    /**
     * 关键日期行→栏目快照：ongoing/days_until 按刊日冻结；倒计时门=仅
     * exact-day/exact-range（onwards 恒 null，负值=已开始区间留给 ongoing 徽章）
     * ——语义三件套口径单一源 {@link KeyDateSemantics}
     */
    private static NewsDailyDigestKeyDateDO toKeyDateSnapshot(KeyDateDO row, LocalDate asOf) {
        boolean ongoing = KeyDateSemantics.isOngoing(row, asOf);
        Integer daysUntil = KeyDateSemantics.daysUntil(row, asOf);
        return NewsDailyDigestKeyDateDO.builder()
                .keyDateId(row.getId())
                .uid(row.getUid())
                .titleZh(row.getTitleZh())
                .titleEn(row.getTitleEn())
                .audienceText(row.getAudienceText())
                .precision(row.getPrecision())
                .dateStart(row.getDateStart())
                .dateEnd(row.getDateEnd())
                .fuzzyHint(row.getFuzzyHint())
                .ongoing(ongoing)
                .daysUntil(daysUntil)
                .build();
    }

    // ==================== 校园活动版面（#330 L2，纯数据零 LLM） ====================

    /**
     * 校园活动版面快照构建（生成期冻结，as-of=刊日 D）：
     * <ul>
     * <li>数据源={@link NewsActivityQueryService#campusActivities}（#323 活动
     *     实体投影）：窗口=[D, D+N-1] 含端（N={@code rag.news.digest-activity-
     *     window-days}，默认 56=8 周，与 events 抓取扩窗同口径）；投影已按
     *     「活动区间与窗口任一历日重叠」选材并按 startDate 升序+itemId 兜底
     *     排序——过期（结束日早于 D）与远未开始天然落窗外，装配层不重复
     *     实现窗口语义（装配只面向投影编程，不感知 events/CPEO/SAO 源适配）；</li>
     * <li>容量截断=超限取最近（{@code rag.news.digest-activity-max-entries}，
     *     默认 10，startDate 升序截断——进行中的开始日早自然在前）；</li>
     * <li>ongoing=已开始（startDate &lt; D）未结束（endDate &gt;= D）——
     *     「进行中」组；当日开始（startDate = D）归「即将来临」组（与 L1
     *     关键日期「当日开始不标进行中」同口径），生成期冻结不随读取漂移；</li>
     * <li>窗口零活动=空列表（读取面整段隐藏，不渲染空壳）；展示字段全冗余
     *     快照（item_id 只作溯源，90 天保留清理删除源行不连带）。</li>
     * </ul>
     */
    private List<NewsDailyDigestActivityDO> buildActivitySnapshots(LocalDate digestDate) {
        return activityQueryService
                .campusActivities(digestDate, properties.effectiveDigestActivityWindowDays())
                .stream()
                .limit(properties.effectiveDigestActivityMaxEntries())
                .map(activity -> toActivitySnapshot(activity, digestDate))
                .toList();
    }

    /** 活动实体→版面快照：ongoing 按刊日冻结（当日开始归即将来临） */
    private static NewsDailyDigestActivityDO toActivitySnapshot(
            NewsActivityQueryService.CampusActivity activity, LocalDate asOf) {
        return NewsDailyDigestActivityDO.builder()
                .itemId(activity.itemId())
                .titleZh(activity.titleZh())
                .titleEn(activity.titleEn())
                .url(activity.url())
                .dateStart(activity.startDate())
                .dateEnd(activity.endDate())
                .ongoing(activity.startDate().isBefore(asOf))
                .build();
    }

    // ==================== 导语（唯一 LLM 触点，可失败回退） ====================

    /**
     * 导语生成：渲染外置提示词（携带日期+窗口+条目行，截断上限见常量）→
     * 预算护栏内单次调用（同指纹重跑复用回执不重复付费）→ JSON 解析；
     * 预算耗尽（LlmBudgetExhaustedException）/回执终态/解析无效/其它异常
     * 一律回退固定模板（零新增调用），digest 照常出刊
     */
    private IntroOutcome generateIntro(LocalDate digestDate, Date windowStart, Date windowEnd,
                                       List<NewsItemDO> candidates) {
        try {
            String prompt = promptTemplateLoader.render(INTRO_PROMPT_PATH, Map.of(
                    "digest_date", digestDate.toString(),
                    "window_label", windowStart + " ~ " + windowEnd + " (HKT)",
                    "item_lines", introItemLines(candidates)));
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(ChatMessage.user(prompt)))
                    .temperature(0.2D)
                    .topP(0.3D)
                    .thinking(false)
                    .maxTokens(properties.effectiveSummaryMaxTokens())
                    .build();
            NewsLlmBudgetService.LlmCall call = llmBudgetService.call(request, Tier.FAST);
            try {
                JsonNode node = parseIntroJson(call.content());
                String zh = node.path("intro_zh").asText("").strip();
                String en = node.path("intro_en").asText("").strip();
                if (!zh.isEmpty() && !en.isEmpty()) {
                    return new IntroOutcome(NewsDailyDigestDO.INTRO_SOURCE_LLM, zh, en);
                }
                call.reportInvalid();
                log.warn("[news][daily] 日报 {} 导语响应字段缺失（zh/en 任一为空），回退模板", digestDate);
            } catch (RuntimeException parseFailure) {
                // 无效响应回报：新鲜响应保留一次下轮复用（已付费），复用响应隔离
                call.reportInvalid();
                log.warn("[news][daily] 日报 {} 导语响应解析失败，回退模板：{}", digestDate, parseFailure.getMessage());
            }
        } catch (LlmBudgetExhaustedException e) {
            // 预算耗尽：照常出刊，模板导语（回执 DEGRADED 已由预算服务落账）
            log.warn("[news][daily] 日报 {} 导语预算耗尽，模板出刊：{}", digestDate, e.getMessage());
        } catch (Exception e) {
            // 回执终态/模板缺失/其它异常：模板兜底，不让导语失败阻断出刊
            log.warn("[news][daily] 日报 {} 导语生成失败，模板出刊：{}", digestDate, e.getMessage());
        }
        return new IntroOutcome(NewsDailyDigestDO.INTRO_SOURCE_FALLBACK,
                NewsDailyDigestTemplates.fallbackIntroZh(digestDate, candidates.size()),
                NewsDailyDigestTemplates.fallbackIntroEn(digestDate, candidates.size()));
    }

    /**
     * 导语提示词条目行（截断上限 INTRO_PROMPT_MAX_ITEMS/TITLE_MAX_CHARS——
     * 只影响概括面不影响快照全量）
     */
    private String introItemLines(List<NewsItemDO> candidates) {
        StringBuilder lines = new StringBuilder();
        int count = 0;
        for (NewsItemDO item : candidates) {
            if (count >= INTRO_PROMPT_MAX_ITEMS) {
                lines.append("（其余 ").append(candidates.size() - count).append(" 条略）");
                break;
            }
            String title = item.getTitleZh() != null && !item.getTitleZh().isBlank()
                    ? item.getTitleZh() : item.getTitleEn();
            String trimmed = title == null ? "" : (title.length() > INTRO_PROMPT_TITLE_MAX_CHARS
                    ? title.substring(0, INTRO_PROMPT_TITLE_MAX_CHARS) + "…" : title);
            lines.append(count + 1).append(". [").append(item.getCategory()).append("] ")
                    .append(trimmed).append('\n');
            count++;
        }
        return lines.toString().stripTrailing();
    }

    /**
     * 导语 JSON 解析：剥代码围栏后取首尾大括号间内容（沿 NewsEnrichService
     * parsePayload 的宽松口径；严格校验归守卫——导语无守卫，字段级空值即回退）
     */
    private JsonNode parseIntroJson(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.startsWith("```")) {
            int firstBrace = text.indexOf('{');
            int lastBrace = text.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                text = text.substring(firstBrace, lastBrace + 1);
            }
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException("导语响应不是合法 JSON", e);
        }
    }

    // ==================== 主题/信源批量装配（沿 NewsQueryServiceImpl 同构） ====================

    private Map<Long, List<String>> topicSlugsByItemIds(Set<Long> itemIds) {
        Set<Long> nonNullIds = itemIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (nonNullIds.isEmpty()) {
            return Map.of();
        }
        List<NewsItemTopicDO> links = itemTopicMapper.selectList(
                new LambdaQueryWrapper<NewsItemTopicDO>().in(NewsItemTopicDO::getItemId, nonNullIds));
        if (links.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> slugByTopicId = topicMapper.selectBatchIds(
                        links.stream().map(NewsItemTopicDO::getTopicId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(NewsTopicDO::getId, NewsTopicDO::getSlug));
        Map<Long, List<String>> result = new LinkedHashMap<>();
        for (NewsItemTopicDO link : links) {
            String slug = slugByTopicId.get(link.getTopicId());
            if (slug != null) {
                result.computeIfAbsent(link.getItemId(), key -> new ArrayList<>()).add(slug);
            }
        }
        return result;
    }

    private Map<Long, NewsSourceDO> sourceMapByIds(Set<Long> sourceIds) {
        Set<Long> nonNullIds = sourceIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (nonNullIds.isEmpty()) {
            return Map.of();
        }
        return sourceMapper.selectBatchIds(nonNullIds).stream()
                .collect(Collectors.toMap(NewsSourceDO::getId, Function.identity()));
    }

    /**
     * 导语产出载荷（内部中转）
     */
    private record IntroOutcome(String source, String zh, String en) {
    }
}
