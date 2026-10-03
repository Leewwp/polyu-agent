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

import com.nageoffer.ai.ragent.news.controller.vo.NewsDailyDigestSummaryVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsDailyDigestVO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsDailyDigestDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsDailyDigestItemDO;
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
 * （删源行后日报可读）/RSS 格式合法性/目录排序/<b>零 LLM 结构证明</b>。
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
                digestStore.digestItemMapper, itemStore.mapper, new NewsFetchProperties());
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
}
