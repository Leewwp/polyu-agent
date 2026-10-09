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

import com.nageoffer.ai.ragent.news.controller.vo.NewsDailyDigestActivityVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsDailyDigestKeyDateVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsDailyDigestSummaryVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsDailyDigestVO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsDailyDigestDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsDailyDigestItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsDailyDigestActivityDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsDailyDigestKeyDateDO;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日报公开读取面测试（#212 验收面）：hide 失格过滤/导语失格回退/快照独立性
 * （删源行后日报可读）/RSS 格式合法性/目录排序/<b>零 LLM 结构证明</b>；
 * #240 增目录 firstTitle 映射（批量可见集 seq 首条）与期级 RSS 三态组装。
 */
class NewsDailyDigestQueryServiceTests {

    private static final ZoneId HKT = ZoneId.of("Asia/Hong_Kong");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 3);

    private FakeDailyDigestStore digestStore;
    private FakeDailyDigestNewsItemStore itemStore;
    private NewsDailyDigestQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        digestStore = new FakeDailyDigestStore();
        itemStore = new FakeDailyDigestNewsItemStore();
        service = new NewsDailyDigestQueryServiceImpl(digestStore.digestMapper,
                digestStore.digestItemMapper, digestStore.digestKeyDateMapper,
                digestStore.digestActivityMapper, itemStore.mapper, new NewsFetchProperties());
    }

    // ==================== 装配（真刊+真快照） ====================

    private void seedDigest(String introZh, String introSource, long... liveItemIds) {
        Date windowStart = Date.from(LocalDateTime.of(2026, 10, 2, 8, 0).atZone(HKT).toInstant());
        Date windowEnd = Date.from(LocalDateTime.of(2026, 10, 3, 8, 0).atZone(HKT).toInstant());
        NewsDailyDigestDO header = NewsDailyDigestDO.builder()
                .digestDate(DATE)
                .windowStart(windowStart)
                .windowEnd(windowEnd)
                .introZh(introZh)
                .introEn("English intro of llm")
                .introSource(introSource)
                .itemCount(liveItemIds.length)
                .status(NewsDailyDigestDO.STATUS_PUBLISHED)
                .buildTime(Date.from(LocalDateTime.of(2026, 10, 3, 8, 40).atZone(HKT).toInstant()))
                .build();
        digestStore.digestMapper.insert(header);
        int seq = 0;
        for (long itemId : liveItemIds) {
            digestStore.digestItemMapper.insert(NewsDailyDigestItemDO.builder()
                    .digestId(header.getId())
                    .itemId(itemId)
                    .seq(++seq)
                    .url("https://www.polyu.edu.hk/item/" + itemId)
                    .urlHash("hash-" + itemId)
                    .titleZh("条目" + itemId)
                    .titleEn("Item " + itemId)
                    .summaryZh("摘要" + itemId)
                    .summaryEn("Summary " + itemId)
                    .category("campus")
                    .topicSlugs("research,ai")
                    .sourceId(7L)
                    .sourceKey("news-sitemap")
                    .sourcePlatform("official")
                    .sourceOfficial(true)
                    .sourceDisplayName("理大官网")
                    .sourceDisplayNameEn("PolyU official")
                    .publishTime(Date.from(LocalDateTime.of(2026, 10, 2, 9, 0).atZone(HKT).toInstant()))
                    .build());
        }
    }

    private void seedLiveItem(long id, String status) {
        itemStore.seed(com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO.builder()
                .id(id).status(status).url("https://www.polyu.edu.hk/item/" + id)
                .urlHash("hash-" + id).titleZh("条目" + id).titleEn("Item " + id)
                .category("campus")
                .publishTime(Date.from(LocalDateTime.of(2026, 10, 2, 9, 0).atZone(HKT).toInstant()))
                .build());
    }

    /** 指定日期的休刊期（零快照、empty 模板导语——生成期空刊形态） */
    private void seedEmptyDigest(LocalDate date) {
        digestStore.digestMapper.insert(NewsDailyDigestDO.builder()
                .digestDate(date)
                .windowStart(Date.from(date.minusDays(1).atTime(8, 0).atZone(HKT).toInstant()))
                .windowEnd(Date.from(date.atTime(8, 0).atZone(HKT).toInstant()))
                .introZh(NewsDailyDigestTemplates.emptyIntroZh(date))
                .introEn(NewsDailyDigestTemplates.emptyIntroEn(date))
                .introSource(NewsDailyDigestDO.INTRO_SOURCE_EMPTY)
                .itemCount(0).status(NewsDailyDigestDO.STATUS_PUBLISHED)
                .buildTime(Date.from(date.atTime(8, 40).atZone(HKT).toInstant()))
                .build());
    }

    // ==================== 零 LLM 结构证明 ====================

    /**
     * 页面/RSS 请求不触发 LLM 的结构证明：读取服务声明字段不包含任何
     * 付费/生成组件（LLMService/NewsLlmBudgetService/ModelSelector/
     * PromptTemplateLoader/生成服务）——请求路径上不存在可触发的模型调用
     */
    @Test
    void zeroLlmStructure() {
        List<String> forbidden = List.of(
                "com.nageoffer.ai.ragent.infra.chat.LLMService",
                "com.nageoffer.ai.ragent.news.service.impl.NewsLlmBudgetService",
                "com.nageoffer.ai.ragent.infra.model.ModelSelector",
                "com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader",
                "com.nageoffer.ai.ragent.news.service.NewsDailyDigestService",
                "com.nageoffer.ai.ragent.news.service.impl.NewsEnrichService");
        for (Field field : NewsDailyDigestQueryServiceImpl.class.getDeclaredFields()) {
            String type = field.getType().getName();
            assertFalse(forbidden.contains(type), "读取服务不得依赖付费/生成组件，实际字段=" + type);
        }
    }

    // ==================== 校历关键日期栏目透出（#316 L1） ====================

    @Test
    void detailCarriesKeyDateSectionSnapshotOrderedBySeq() {
        seedDigest("本期导语", NewsDailyDigestDO.INTRO_SOURCE_LLM, 1L);
        seedLiveItem(1L, "published");
        Long digestId = digestStore.headers().get(0).getId();
        digestStore.digestKeyDateMapper.insert(NewsDailyDigestKeyDateDO.builder()
                .digestId(digestId).keyDateId(101L).seq(1).uid("uid-101")
                .titleZh("考试周").titleEn("Examination period").audienceText("全体学生")
                .precision("exact-range").dateStart(LocalDate.of(2026, 12, 7))
                .dateEnd(LocalDate.of(2026, 12, 19)).ongoing(true).daysUntil(-2).build());
        digestStore.digestKeyDateMapper.insert(NewsDailyDigestKeyDateDO.builder()
                .digestId(digestId).keyDateId(102L).seq(2).uid("uid-102")
                .titleZh(null).titleEn("Add/drop deadline").precision("exact-day")
                .dateStart(LocalDate.of(2026, 10, 12)).ongoing(false).daysUntil(9).build());
        NewsDailyDigestVO detail = service.getDetail(DATE);
        List<NewsDailyDigestKeyDateVO> keyDates = detail.getKeyDates();
        assertEquals(2, keyDates.size());
        assertEquals(List.of(1, 2), keyDates.stream().map(NewsDailyDigestKeyDateVO::getSeq).toList());
        NewsDailyDigestKeyDateVO ongoing = keyDates.get(0);
        assertEquals("uid-101", ongoing.getUid());
        assertEquals("考试周", ongoing.getTitleZh());
        assertEquals("全体学生", ongoing.getAudienceText());
        assertEquals(Boolean.TRUE, ongoing.getOngoing());
        assertEquals(-2, ongoing.getDaysUntil());
        assertEquals(LocalDate.of(2026, 10, 12), keyDates.get(1).getDateStart());
        assertNull(keyDates.get(1).getTitleZh(), "词表缺词 null 直映——前端回退英文");
    }

    @Test
    void detailWithoutKeyDateRowsYieldsEmptySectionList() {
        seedDigest("本期导语", NewsDailyDigestDO.INTRO_SOURCE_LLM, 1L);
        seedLiveItem(1L, "published");
        NewsDailyDigestVO detail = service.getDetail(DATE);
        assertNotNull(detail.getKeyDates());
        assertTrue(detail.getKeyDates().isEmpty(), "无栏目快照行=空列表（前端整段隐藏，不渲染空壳）");
    }

    // ==================== 校园活动版面透出（#330 L2） ====================

    @Test
    void detailCarriesActivitySectionSnapshotOrderedBySeq() {
        seedDigest("本期导语", NewsDailyDigestDO.INTRO_SOURCE_LLM, 1L);
        seedLiveItem(1L, "published");
        Long digestId = digestStore.headers().get(0).getId();
        digestStore.digestActivityMapper.insert(NewsDailyDigestActivityDO.builder()
                .digestId(digestId).itemId(201L).seq(1)
                .titleZh("第32届毕业典礼").titleEn("32nd Congregation")
                .url("https://www.polyu.edu.hk/en/events/congregation")
                .dateStart(LocalDate.of(2026, 10, 1)).dateEnd(LocalDate.of(2026, 11, 21))
                .ongoing(true).build());
        digestStore.digestActivityMapper.insert(NewsDailyDigestActivityDO.builder()
                .digestId(digestId).itemId(202L).seq(2)
                .titleZh(null).titleEn("Information Day 2026")
                .url("https://www.polyu.edu.hk/en/events/infoday")
                .dateStart(LocalDate.of(2026, 10, 10)).dateEnd(LocalDate.of(2026, 10, 10))
                .ongoing(false).build());
        NewsDailyDigestVO detail = service.getDetail(DATE);
        List<NewsDailyDigestActivityVO> activities = detail.getActivities();
        assertEquals(2, activities.size());
        assertEquals(List.of(1, 2), activities.stream().map(NewsDailyDigestActivityVO::getSeq).toList());
        NewsDailyDigestActivityVO ongoing = activities.get(0);
        assertEquals(201L, ongoing.getItemId());
        assertEquals("第32届毕业典礼", ongoing.getTitleZh());
        assertEquals("https://www.polyu.edu.hk/en/events/congregation", ongoing.getUrl());
        assertEquals(LocalDate.of(2026, 10, 1), ongoing.getDateStart());
        assertEquals(LocalDate.of(2026, 11, 21), ongoing.getDateEnd());
        assertEquals(Boolean.TRUE, ongoing.getOngoing());
        assertNull(activities.get(1).getTitleZh(), "标题缺词 null 直映——前端回退英文");
        assertEquals(Boolean.FALSE, activities.get(1).getOngoing());
    }

    @Test
    void detailWithoutActivityRowsYieldsEmptySectionList() {
        seedDigest("本期导语", NewsDailyDigestDO.INTRO_SOURCE_LLM, 1L);
        seedLiveItem(1L, "published");
        NewsDailyDigestVO detail = service.getDetail(DATE);
        assertNotNull(detail.getActivities());
        assertTrue(detail.getActivities().isEmpty(), "无版面快照行=空列表（前端整段隐藏，不渲染空壳）");
    }

    // ==================== hide 失格与导语回退 ====================

    @Test
    void hiddenLiveItemDisqualifiesSnapshotAndDegradesIntro() {
        seedDigest("本期导语提及条目1与条目2", NewsDailyDigestDO.INTRO_SOURCE_LLM, 1L, 2L);
        seedLiveItem(1L, "published");
        seedLiveItem(2L, "hidden"); // 人工下架
        NewsDailyDigestVO detail = service.getDetail(DATE);
        assertEquals(1, detail.getVisibleCount());
        assertEquals(1, detail.getDisqualifiedCount());
        assertEquals(List.of(1L), detail.getItems().stream().map(i -> i.getItemId()).toList());
        assertTrue(detail.getIntroDegraded(), "有失格条目→导语失格");
        assertTrue(detail.getIntroZh().contains("1 条"), "回退模板导语（零调用）");
        assertFalse(detail.getIntroZh().contains("条目1"), "被下架内容不得残留于导语");
        assertFalse(detail.getIntroZh().contains("本期导语提及"), "LLM 导语已整体替换");
    }

    @Test
    void allItemsHiddenYieldsEmptyDigestTemplate() {
        seedDigest("LLM 导语", NewsDailyDigestDO.INTRO_SOURCE_LLM, 1L, 2L);
        seedLiveItem(1L, "hidden");
        seedLiveItem(2L, "expired");
        NewsDailyDigestVO detail = service.getDetail(DATE);
        assertEquals(0, detail.getVisibleCount(), "全部失格→出口无内容");
        assertEquals(2, detail.getDisqualifiedCount());
        assertTrue(detail.getItems().isEmpty());
        assertTrue(detail.getIntroZh().contains("暂无公开动态"), "空刊模板导语（零调用）");
        assertFalse(detail.getIntroZh().contains("LLM 导语"), "导语无残留");
    }

    @Test
    void purgedSourceRowKeepsSnapshotReadable() {
        // 快照独立性验收：正常清理后日报可读——源行被 90 天保留清理删除（回查不存在）→ 快照保留
        seedDigest("正常导语", NewsDailyDigestDO.INTRO_SOURCE_LLM, 1L, 2L);
        // 不 seed 任何 live 行=源行已全部被清理
        NewsDailyDigestVO detail = service.getDetail(DATE);
        assertEquals(2, detail.getVisibleCount(), "源行缺失=保留清理常态，不算失格");
        assertEquals(0, detail.getDisqualifiedCount());
        assertFalse(detail.getIntroDegraded());
        assertEquals("正常导语", detail.getIntroZh(), "无失格→刊头原导语保留");
        assertEquals("条目1", detail.getItems().get(0).getTitleZh());
        assertEquals("理大官网", detail.getItems().get(0).getSource().getDisplayName());
        assertEquals(List.of("research", "ai"), detail.getItems().get(0).getTopics());
    }

    @Test
    void intactDigestReturnsStoredIntroAndFullItems() {
        seedDigest("完整刊导语", NewsDailyDigestDO.INTRO_SOURCE_LLM, 1L);
        seedLiveItem(1L, "published");
        NewsDailyDigestVO detail = service.getDetail(DATE);
        assertEquals(1, detail.getVisibleCount());
        assertFalse(detail.getIntroDegraded());
        assertEquals("完整刊导语", detail.getIntroZh());
        assertEquals("llm", detail.getStoredIntroSource());
        assertEquals(1, detail.getItems().get(0).getSeq(), "刊内序透出");
    }

    @Test
    void missingDigestReturnsNull() {
        assertNull(service.getDetail(DATE));
    }

    // ==================== 目录 ====================

    @Test
    void listRecentOrdersByDateDesc() {
        seedDigest("A", NewsDailyDigestDO.INTRO_SOURCE_LLM, 1L);
        digestStore.digestMapper.insert(NewsDailyDigestDO.builder()
                .digestDate(DATE.plusDays(1))
                .windowStart(new Date(0)).windowEnd(new Date(1))
                .introZh("B").introEn("B").introSource(NewsDailyDigestDO.INTRO_SOURCE_FALLBACK)
                .itemCount(0).status(NewsDailyDigestDO.STATUS_PUBLISHED).buildTime(new Date())
                .build());
        List<NewsDailyDigestSummaryVO> list = service.listRecent(10);
        assertEquals(2, list.size());
        assertEquals(DATE.plusDays(1), list.get(0).getDigestDate(), "日期倒序");
        assertEquals(DATE, list.get(1).getDigestDate());
    }

    // ==================== 目录 firstTitle（#240） ====================

    /**
     * #240 验收：seq=1 恰被下架的期，firstTitle 落到 published 可见集中 seq 最小条
     * （不是「seq=1 且 published 才返回」），且与详情页头条同值（含「部分内容已下架」期）
     */
    @Test
    void listRecentFirstTitleFallsToNextVisibleWhenSeqOneHidden() {
        seedDigest("导语", NewsDailyDigestDO.INTRO_SOURCE_LLM, 21L, 22L, 23L);
        seedLiveItem(21L, "hidden");      // seq=1 恰被人工下架
        seedLiveItem(22L, "published");   // seq=2 → 可见集 seq 首条
        // 23 无 live 行=保留清理常态，保留展示
        List<NewsDailyDigestSummaryVO> list = service.listRecent(10);
        assertEquals(1, list.size());
        assertEquals("条目22", list.get(0).getFirstTitleZh(), "seq=1 失格→落到 seq 最小可见条");
        assertEquals("Item 22", list.get(0).getFirstTitleEn());
        NewsDailyDigestVO detail = service.getDetail(DATE);
        assertEquals(detail.getItems().get(0).getTitleZh(), list.get(0).getFirstTitleZh(),
                "与详情页头条一致（验收口径）");
        assertEquals(detail.getItems().get(0).getTitleEn(), list.get(0).getFirstTitleEn());
    }

    @Test
    void listRecentEmptyOrFullyDisqualifiedDigestHasNullFirstTitles() {
        seedDigest("LLM 导语", NewsDailyDigestDO.INTRO_SOURCE_LLM, 31L, 32L);
        seedLiveItem(31L, "hidden");
        seedLiveItem(32L, "expired");     // 全失格=读取期空可见集
        seedEmptyDigest(DATE.plusDays(1)); // 生成期休刊
        List<NewsDailyDigestSummaryVO> list = service.listRecent(10);
        assertEquals(2, list.size());
        assertEquals(DATE.plusDays(1), list.get(0).getDigestDate());
        assertNull(list.get(0).getFirstTitleZh(), "休刊期两字段 null");
        assertNull(list.get(0).getFirstTitleEn());
        assertNull(list.get(1).getFirstTitleZh(), "全失格期可见集为空→null");
        assertNull(list.get(1).getFirstTitleEn());
    }

    // ==================== RSS 合法性 ====================

    @Test
    void rssIsWellFormedXmlWithRequiredElements() throws Exception {
        seedDigest("RSS 导语 <测试>", NewsDailyDigestDO.INTRO_SOURCE_LLM, 1L, 2L);
        NewsDailyDigestVO detail = service.getDetail(DATE);
        String rss = service.renderRss(detail);
        // 基本结构+必要元素：可被标准解析器解析为 XML
        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new ByteArrayInputStream(rss.getBytes(StandardCharsets.UTF_8)));
        assertEquals("rss", doc.getDocumentElement().getTagName());
        assertEquals("2.0", doc.getDocumentElement().getAttribute("version"));
        Element channel = (Element) doc.getElementsByTagName("channel").item(0);
        assertNotNull(channel, "RSS 2.0 必需 channel");
        assertNotNull(channel.getElementsByTagName("title").item(0), "channel 必需 title");
        assertNotNull(channel.getElementsByTagName("link").item(0), "channel 必需 link");
        assertNotNull(channel.getElementsByTagName("description").item(0), "channel 必需 description");
        NodeList items = channel.getElementsByTagName("item");
        assertEquals(2, items.getLength(), "条目数与可见快照一致");
        Element first = (Element) items.item(0);
        assertNotNull(first.getElementsByTagName("title").item(0), "item 必需 title");
        assertNotNull(first.getElementsByTagName("link").item(0), "item 必需 link");
        assertNotNull(first.getElementsByTagName("description").item(0), "item 必需 description");
        assertNotNull(first.getElementsByTagName("guid").item(0));
        assertTrue(first.getElementsByTagName("link").item(0).getTextContent()
                .startsWith("https://www.polyu.edu.hk/item/"));
        // pubDate RFC-822 形状（周几, 日 月 年 时:分:秒 +0800）
        String pubDate = ((Element) channel.getElementsByTagName("pubDate").item(0)).getTextContent();
        assertTrue(pubDate.matches("[A-Za-z]{3}, \\d{2} [A-Za-z]{3} \\d{4} \\d{2}:\\d{2}:\\d{2} \\+0800"),
                "RFC-822 HKT 形状，实际=" + pubDate);
        // 转义回归：导语含 < > 时不破坏结构
        assertEquals("RSS 导语 <测试>",
                channel.getElementsByTagName("description").item(0).getTextContent());
    }

    @Test
    void rssEscapesXmlSpecialsInItemText() throws Exception {
        Date windowStart = Date.from(LocalDateTime.of(2026, 10, 2, 8, 0).atZone(HKT).toInstant());
        NewsDailyDigestDO header = NewsDailyDigestDO.builder()
                .digestDate(DATE).windowStart(windowStart)
                .windowEnd(Date.from(LocalDateTime.of(2026, 10, 3, 8, 0).atZone(HKT).toInstant()))
                .introZh("导语").introEn("Intro")
                .introSource(NewsDailyDigestDO.INTRO_SOURCE_LLM)
                .itemCount(1).status(NewsDailyDigestDO.STATUS_PUBLISHED)
                .buildTime(new Date())
                .build();
        digestStore.digestMapper.insert(header);
        digestStore.digestItemMapper.insert(NewsDailyDigestItemDO.builder()
                .digestId(header.getId()).itemId(9L).seq(1)
                .url("https://example.com/a?x=1&y=2")
                .titleZh("含 <标签> & \"引号\" 的标题")
                .titleEn(null).summaryZh(null).summaryEn(null)
                .category("other").publishTime(new Date())
                .build());
        NewsDailyDigestVO detail = service.getDetail(DATE);
        String rss = service.renderRss(detail);
        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new ByteArrayInputStream(rss.getBytes(StandardCharsets.UTF_8)));
        Element item = (Element) doc.getElementsByTagName("item").item(0);
        assertEquals("含 <标签> & \"引号\" 的标题",
                item.getElementsByTagName("title").item(0).getTextContent(), "特殊字符经实体转义后还原");
        assertEquals("https://example.com/a?x=1&y=2",
                item.getElementsByTagName("link").item(0).getTextContent());
    }

    // ==================== 期级 RSS（#240） ====================

    /**
     * #240 三态组装（正常期/空期）+结构面：每期一条 item、日期倒序、
     * title=理大资讯日报 · 日期+头条、link=站内 canonical、guid=期日期、
     * 空期条目保留并附休刊说明文案
     */
    @Test
    void issuesRssOneItemPerIssueWithEmptyState() throws Exception {
        seedDigest("正常导语 <测试>", NewsDailyDigestDO.INTRO_SOURCE_LLM, 1L, 2L);
        seedLiveItem(1L, "published");
        seedLiveItem(2L, "published");
        seedEmptyDigest(DATE.plusDays(1)); // 更近的休刊期——倒序应排在前
        String rss = service.renderIssuesRss();
        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new ByteArrayInputStream(rss.getBytes(StandardCharsets.UTF_8)));
        assertEquals("rss", doc.getDocumentElement().getTagName());
        assertEquals("2.0", doc.getDocumentElement().getAttribute("version"));
        Element channel = (Element) doc.getElementsByTagName("channel").item(0);
        assertNotNull(channel.getElementsByTagName("title").item(0), "channel 必需 title");
        assertNotNull(channel.getElementsByTagName("link").item(0), "channel 必需 link");
        assertNotNull(channel.getElementsByTagName("description").item(0), "channel 必需 description");
        NodeList items = channel.getElementsByTagName("item");
        assertEquals(2, items.getLength(), "每期一条 item（空期也保留）");
        // 日期倒序：更近的休刊期在前
        Element emptyIssue = (Element) items.item(0);
        assertEquals("理大资讯日报 · " + DATE.plusDays(1),
                emptyIssue.getElementsByTagName("title").item(0).getTextContent(), "空期无头条后缀");
        assertEquals("https://polyuguide.com/daily/" + DATE.plusDays(1),
                emptyIssue.getElementsByTagName("link").item(0).getTextContent(), "link=站内 canonical");
        Element emptyGuid = (Element) emptyIssue.getElementsByTagName("guid").item(0);
        assertEquals(DATE.plusDays(1).toString(), emptyGuid.getTextContent(), "guid=期日期");
        assertEquals("false", emptyGuid.getAttribute("isPermaLink"), "非 URL guid 须显式 isPermaLink=false");
        String emptyDesc = emptyIssue.getElementsByTagName("description").item(0).getTextContent();
        assertTrue(emptyDesc.contains("休刊"), "空期条目附休刊说明文案，实际=" + emptyDesc);
        assertTrue(emptyIssue.getElementsByTagName("pubDate").item(0).getTextContent()
                .matches("[A-Za-z]{3}, \\d{2} [A-Za-z]{3} \\d{4} \\d{2}:\\d{2}:\\d{2} \\+0800"),
                "pubDate=生成时刻 RFC-822 HKT");
        // 正常期：头条入题、导语+条目简表入描述、转义还原
        Element normalIssue = (Element) items.item(1);
        assertEquals("理大资讯日报 · " + DATE + "：条目1",
                normalIssue.getElementsByTagName("title").item(0).getTextContent());
        assertEquals("https://polyuguide.com/daily/" + DATE,
                normalIssue.getElementsByTagName("link").item(0).getTextContent());
        assertEquals(DATE.toString(),
                normalIssue.getElementsByTagName("guid").item(0).getTextContent());
        String desc = normalIssue.getElementsByTagName("description").item(0).getTextContent();
        assertTrue(desc.contains("正常导语 <测试>"), "生效导语（zh 口径）入描述，实际=" + desc);
        assertTrue(desc.contains("条目1") && desc.contains("条目2"), "可见条目标题简表，实际=" + desc);
    }

    /** 下架复检期：头条落到下一可见条、导语回退模板（与单刊 feed 同口径） */
    @Test
    void issuesRssDegradedIssueFallsToNextHeadlineAndTemplateIntro() throws Exception {
        seedDigest("导语提及条目1", NewsDailyDigestDO.INTRO_SOURCE_LLM, 1L, 2L);
        seedLiveItem(1L, "hidden");
        seedLiveItem(2L, "published");
        String rss = service.renderIssuesRss();
        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new ByteArrayInputStream(rss.getBytes(StandardCharsets.UTF_8)));
        Element item = (Element) doc.getElementsByTagName("item").item(0);
        String title = item.getElementsByTagName("title").item(0).getTextContent();
        assertEquals("理大资讯日报 · " + DATE + "：条目2", title, "seq=1 失格→头条=seq 最小可见条");
        String desc = item.getElementsByTagName("description").item(0).getTextContent();
        assertTrue(desc.contains("1 条"), "导语回退计数模板（零调用），实际=" + desc);
        assertTrue(desc.contains("条目2"), "可见条目入简表，实际=" + desc);
        assertFalse(desc.contains("导语提及条目1"), "被下架内容不得残留于导语，实际=" + desc);
        assertFalse(desc.contains("条目1"), "失格条目不入简表，实际=" + desc);
    }

    /** 最近 30 期封顶：第 31 期（最旧）被 LIMIT 截掉 */
    @Test
    void issuesRssCapsAtThirtyMostRecentIssues() throws Exception {
        for (int i = 0; i < 31; i++) {
            seedEmptyDigest(DATE.plusDays(i));
        }
        String rss = service.renderIssuesRss();
        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new ByteArrayInputStream(rss.getBytes(StandardCharsets.UTF_8)));
        NodeList items = doc.getElementsByTagName("item");
        assertEquals(30, items.getLength(), "最近 30 期封顶");
        assertEquals(DATE.plusDays(30).toString(),
                ((Element) items.item(0)).getElementsByTagName("guid").item(0).getTextContent(), "最新期在前");
        assertEquals(DATE.plusDays(1).toString(),
                ((Element) items.item(29)).getElementsByTagName("guid").item(0).getTextContent(),
                "最旧一期（DATE 本体）被截掉");
    }
}
