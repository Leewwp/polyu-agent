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

import com.nageoffer.ai.ragent.infra.model.LlmBudgetExhaustedException;
import com.nageoffer.ai.ragent.news.dao.entity.NewsDailyDigestDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemTopicDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsTopicDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemTopicMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsTopicMapper;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import com.nageoffer.ai.ragent.news.service.NewsDailyDigestService;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 日报生成服务测试（#212 验收面）：窗口边界/迟到数据/幂等重跑/空刊/
 * 预算耗尽出刊/导语回退/统一公开资格候选/快照字段。
 *
 * <p>Mapper 走内存 fake（行为语义）；预算服务与提示词加载器 mock——
 * 真实 SQL（唯一约束/级联/DATE 绑定）由 PG 环境保障。
 */
class NewsDailyDigestServiceTests {

    private static final ZoneId HKT = ZoneId.of("Asia/Hong_Kong");
    /** 生成时刻=2026-10-03 09:00 HKT（08:40 调度之后） */
    private static final Date NOW = Date.from(LocalDateTime.of(2026, 10, 3, 9, 0)
            .atZone(HKT).toInstant());

    private static final LocalDate DIGEST_DATE = LocalDate.of(2026, 10, 3);

    private FakeDailyDigestStore digestStore;
    private FakeDailyDigestNewsItemStore itemStore;
    private NewsLlmBudgetService budgetService;
    private PromptTemplateLoader promptLoader;
    private NewsDailyDigestService service;

    @BeforeEach
    void setUp() {
        // LambdaQueryWrapper（NewsItemTopicDO join 查询）需要表元数据缓存（FakeNewsItemStore 先例）
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,
                com.nageoffer.ai.ragent.news.dao.entity.NewsItemTopicDO.class);
        digestStore = new FakeDailyDigestStore();
        itemStore = new FakeDailyDigestNewsItemStore();
        budgetService = mock(NewsLlmBudgetService.class);
        promptLoader = mock(PromptTemplateLoader.class);
        NewsItemTopicMapper itemTopicMapper = mock(NewsItemTopicMapper.class);
        NewsTopicMapper topicMapper = mock(NewsTopicMapper.class);
        NewsSourceMapper sourceMapper = mock(NewsSourceMapper.class);
        when(promptLoader.render(any(), any())).thenReturn("rendered-prompt");
        service = new NewsDailyDigestServiceImpl(digestStore.digestMapper, digestStore.digestItemMapper,
                itemStore.mapper, itemTopicMapper, topicMapper, sourceMapper,
                budgetService, promptLoader, new NewsFetchProperties(), new ObjectMapper(), () -> NOW);
    }

    // ==================== 条目工厂 ====================

    private NewsItemDO.NewsItemDOBuilder visibleItem() {
        return NewsItemDO.builder()
                .status("published")
                // 历史行口径：eligible_time NULL=早已过门（#185）；需要测发布门时显式覆盖
                .eligibleTime(null)
                .category("research")
                .titleZh("研究动态")
                .titleEn("Research update")
                .summaryZh("摘要中文")
                .summaryEn("Summary en")
                .url("https://www.polyu.edu.hk/x")
                .urlHash("hash-x");
    }

    private static Date hkt(int year, int month, int day, int hour, int minute) {
        return Date.from(LocalDateTime.of(year, month, day, hour, minute).atZone(HKT).toInstant());
    }

    private void stubLlmSuccess(String zh, String en) {
        when(budgetService.call(any(), any())).thenReturn(new NewsLlmBudgetService.LlmCall(
                "{\"intro_zh\":\"" + zh + "\",\"intro_en\":\"" + en + "\"}", false, () -> { }));
    }

    // ==================== 窗口边界（左闭右开） ====================

    @Test
    void windowBoundariesLeftClosedRightOpen() {
        // 窗口 [10-02 08:00, 10-03 08:0)：起点侧含 08:00 整点，闭端侧 08:00 整点归下一期
        itemStore.seed(visibleItem().id(1L).publishTime(hkt(2026, 10, 2, 8, 0)).build());     // 起点整点=含
        itemStore.seed(visibleItem().id(2L).publishTime(hkt(2026, 10, 2, 12, 0)).build());    // 窗内
        itemStore.seed(visibleItem().id(3L).publishTime(hkt(2026, 10, 3, 7, 59)).build()); // 窗内末尾
        itemStore.seed(visibleItem().id(4L).publishTime(hkt(2026, 10, 3, 8, 0)).build());     // 闭端整点=不含
        itemStore.seed(visibleItem().id(5L).publishTime(hkt(2026, 10, 2, 7, 59)).build()); // 起点前一毫秒级=不含
        itemStore.seed(visibleItem().id(6L).publishTime(hkt(2026, 10, 1, 23, 0)).build());    // 窗外前日
        stubLlmSuccess("今日看点", "Today's focus");
        service.rebuildForDate(DIGEST_DATE);
        List<Long> ids = digestStore.items().stream().map(i -> i.getItemId()).toList();
        assertEquals(List.of(3L, 2L, 1L), ids, "窗口左闭右开+发布时间倒序：闭端/窗外条目排除");
        // 闭端整点条目归属下一期
        itemStore.seed(visibleItem().id(7L).publishTime(hkt(2026, 10, 3, 20, 0)).build());
        service.rebuildForDate(DIGEST_DATE.plusDays(1));
        Long nextHeaderId = digestStore.headers().stream()
                .filter(h -> DIGEST_DATE.plusDays(1).equals(h.getDigestDate()))
                .findFirst().orElseThrow().getId();
        List<Long> nextIds = digestStore.items().stream()
                .filter(i -> i.getDigestId().equals(nextHeaderId))
                .map(i -> i.getItemId()).toList();
        assertTrue(nextIds.contains(4L), "publish_time 恰落闭端 08:00:00 归属下一期");
    }

    // ==================== 迟到数据规则 ====================

    @Test
    void lateArrivingItemJoinsOnlyOnExplicitRebuild() {
        // 条目发布于窗内 10-02 15:00，但生成时刻仍 pending（08:00 轮未富化完）→ 不进当期
        itemStore.seed(visibleItem().id(10L).status("pending")
                .publishTime(hkt(2026, 10, 2, 15, 0)).build());
        NewsDailyDigestService.DigestBuildResult first = service.rebuildForDate(DIGEST_DATE);
        assertEquals(0, first.itemCount(), "pending（未获公开资格）不进当期——不抢读未完成行");
        assertEquals(NewsDailyDigestDO.INTRO_SOURCE_EMPTY, first.introSource());
        // 迟到富化完成：status=published+eligible_time 就绪（但已过发布门等待期）
        itemStore.publishWithEligibleTime(10L, new Date(NOW.getTime() - 3600_000L));
        assertFalse(service.generateIfMissing(DIGEST_DATE), "已存在的刊不自动重建");
        NewsDailyDigestService.DigestBuildResult rebuilt = service.rebuildForDate(DIGEST_DATE);
        assertEquals(1, rebuilt.itemCount(), "显式重跑按冻结窗口全量重算：迟到条目并入");
    }

    @Test
    void ungatedPublishedItemExcludedByVisibilityGate() {
        // published 但 eligible_time 未过 180s 发布门 → 不可见（统一公开资格）
        itemStore.seed(visibleItem().id(11L)
                .eligibleTime(new Date(NOW.getTime() - 60_000L)) // 60s 前就绪=未过门
                .publishTime(hkt(2026, 10, 2, 15, 0)).build());
        itemStore.seed(visibleItem().id(12L)
                .eligibleTime(new Date(NOW.getTime() - 400_000L)) // 400s 前就绪=已过门
                .publishTime(hkt(2026, 10, 2, 16, 0)).build());
        // 终态行同样不可见
        itemStore.seed(visibleItem().id(13L).status("hidden").publishTime(hkt(2026, 10, 2, 17, 0)).build());
        itemStore.seed(visibleItem().id(14L).status("expired").publishTime(hkt(2026, 10, 2, 18, 0)).build());
        itemStore.seed(visibleItem().id(15L).status("archived").publishTime(hkt(2026, 10, 2, 19, 0)).build());
        NewsDailyDigestService.DigestBuildResult result = service.rebuildForDate(DIGEST_DATE);
        assertEquals(1, result.itemCount(), "只有过门 published 进候选；hidden/expired/archived/未过门一律隔离");
        assertEquals(12L, digestStore.items().get(0).getItemId());
    }

    // ==================== 幂等重跑 ====================

    @Test
    void rerunProducesSingleDigestWithFreshSnapshots() {
        itemStore.seed(visibleItem().id(20L).publishTime(hkt(2026, 10, 2, 9, 0)).build());
        stubLlmSuccess("第一刊导语", "First intro");
        service.rebuildForDate(DIGEST_DATE);
        assertEquals(1, digestStore.headers().size());
        assertEquals(1, digestStore.items().size());
        Long firstHeaderId = digestStore.headers().get(0).getId();
        // 新条目到位后重跑：仍是同一日期一刊，快照按冻结窗口全量重算
        itemStore.seed(visibleItem().id(21L).publishTime(hkt(2026, 10, 2, 10, 0)).build());
        service.rebuildForDate(DIGEST_DATE);
        assertEquals(1, digestStore.headers().size(), "同日期重跑只产一刊（先删后插）");
        assertEquals(2, digestStore.items().size(), "旧快照行经级联带走，无残行");
        Long secondHeaderId = digestStore.headers().get(0).getId();
        assertTrue(secondHeaderId > firstHeaderId || !secondHeaderId.equals(firstHeaderId),
                "重建产生新刊头行");
        assertEquals(2, digestStore.headers().get(0).getItemCount());
    }

    // ==================== 空刊 ====================

    @Test
    void emptyWindowStillPersistsEmptyDigestWithZeroLlm() {
        NewsDailyDigestService.DigestBuildResult result = service.rebuildForDate(DIGEST_DATE);
        assertEquals(0, result.itemCount());
        assertEquals(NewsDailyDigestDO.INTRO_SOURCE_EMPTY, result.introSource());
        assertEquals(1, digestStore.headers().size(), "空刊也落一行");
        NewsDailyDigestDO header = digestStore.headers().get(0);
        assertNotNull(header.getIntroZh());
        assertNotNull(header.getIntroEn());
        assertTrue(header.getIntroZh().contains("暂无公开动态"));
        assertEquals(Date.from(LocalDateTime.of(2026, 10, 2, 8, 0).atZone(HKT).toInstant()),
                header.getWindowStart());
        assertEquals(Date.from(LocalDateTime.of(2026, 10, 3, 8, 0).atZone(HKT).toInstant()),
                header.getWindowEnd());
        verify(budgetService, never()).call(any(), any());
    }

    // ==================== 预算耗尽/失败回退 ====================

    @Test
    void budgetExhaustedStillPublishesWithTemplateIntro() {
        itemStore.seed(visibleItem().id(30L).publishTime(hkt(2026, 10, 2, 9, 0)).build());
        itemStore.seed(visibleItem().id(31L).publishTime(hkt(2026, 10, 2, 10, 0)).build());
        when(budgetService.call(any(), any()))
                .thenThrow(new LlmBudgetExhaustedException("资讯 LLM 预算耗尽"));
        NewsDailyDigestService.DigestBuildResult result = service.rebuildForDate(DIGEST_DATE);
        assertEquals(2, result.itemCount(), "预算耗尽照常出刊");
        assertEquals(NewsDailyDigestDO.INTRO_SOURCE_FALLBACK, result.introSource());
        NewsDailyDigestDO header = digestStore.headers().get(0);
        assertTrue(header.getIntroZh().contains("2 条"), "模板导语携带可见计数");
        assertFalse(header.getIntroZh().contains("第一刊"), "模板导语无 LLM 内容残留");
    }

    @Test
    void llmTerminalFailureFallsBackToTemplate() {
        itemStore.seed(visibleItem().id(32L).publishTime(hkt(2026, 10, 2, 9, 0)).build());
        when(budgetService.call(any(), any()))
                .thenThrow(new IllegalStateException("回执终态：同指纹重试预算已耗尽"));
        NewsDailyDigestService.DigestBuildResult result = service.rebuildForDate(DIGEST_DATE);
        assertEquals(NewsDailyDigestDO.INTRO_SOURCE_FALLBACK, result.introSource());
        assertEquals(1, result.itemCount());
    }

    @Test
    void invalidLlmPayloadFallsBackAndReportsInvalid() {
        itemStore.seed(visibleItem().id(33L).publishTime(hkt(2026, 10, 2, 9, 0)).build());
        when(budgetService.call(any(), any())).thenReturn(new NewsLlmBudgetService.LlmCall(
                "not-json", false, () -> { }));
        NewsDailyDigestService.DigestBuildResult result = service.rebuildForDate(DIGEST_DATE);
        assertEquals(NewsDailyDigestDO.INTRO_SOURCE_FALLBACK, result.introSource());
    }

    @Test
    void llmSuccessStoresIntro() {
        itemStore.seed(visibleItem().id(34L).publishTime(hkt(2026, 10, 2, 9, 0)).build());
        stubLlmSuccess("理大研究取得突破", "PolyU research breakthrough");
        NewsDailyDigestService.DigestBuildResult result = service.rebuildForDate(DIGEST_DATE);
        assertEquals(NewsDailyDigestDO.INTRO_SOURCE_LLM, result.introSource());
        assertEquals("理大研究取得突破", digestStore.headers().get(0).getIntroZh());
        assertEquals("PolyU research breakthrough", digestStore.headers().get(0).getIntroEn());
    }

    // ==================== 快照字段冗余 ====================

    @Test
    void snapshotCarriesFullDisplayFieldsIndependentOfSourceRow() {
        itemStore.seed(visibleItem().id(40L).sourceId(7L)
                .publishTime(hkt(2026, 10, 2, 9, 0)).build());
        stubLlmSuccess("导", "Intro");
        service.rebuildForDate(DIGEST_DATE);
        var snapshot = digestStore.items().get(0);
        assertEquals("https://www.polyu.edu.hk/x", snapshot.getUrl());
        assertEquals("研究动态", snapshot.getTitleZh());
        assertEquals("摘要中文", snapshot.getSummaryZh());
        assertEquals("research", snapshot.getCategory());
        assertEquals(7L, snapshot.getSourceId());
        // 删除源行（模拟 90 天保留清理）：快照字段完整保留
        itemStore.purgeById(40L);
        assertEquals("研究动态", digestStore.items().get(0).getTitleZh(),
                "源行清理后快照仍完整（快照独立性）");
    }

    // ==================== 提示词与请求形状 ====================

    @Test
    void introPromptCarriesDateAndBoundedItemLines() {
        for (int i = 1; i <= 50; i++) {
            itemStore.seed(visibleItem().id(100L + i)
                    .titleZh("标题" + i)
                    .publishTime(hkt(2026, 10, 2, 9, 0)).build());
        }
        stubLlmSuccess("导", "Intro");
        service.rebuildForDate(DIGEST_DATE);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, String>> slots = ArgumentCaptor.forClass(java.util.Map.class);
        verify(promptLoader).render(eq("prompt/news-digest-intro.st"), slots.capture());
        String lines = slots.getValue().get("item_lines");
        assertTrue(lines.contains("[research] 标题1"));
        assertTrue(lines.contains("其余"), "超上限截断提示");
        long lineCount = lines.lines().count();
        assertTrue(lineCount <= NewsDailyDigestServiceImpl.INTRO_PROMPT_MAX_ITEMS + 1,
                "提示词条目行受截断上限约束");
        assertEquals("2026-10-03", slots.getValue().get("digest_date"));
    }
}
