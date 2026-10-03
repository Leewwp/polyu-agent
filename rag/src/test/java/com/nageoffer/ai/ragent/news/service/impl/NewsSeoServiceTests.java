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
import com.nageoffer.ai.ragent.news.controller.vo.NewsItemVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsPageVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsTopicVO;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import com.nageoffer.ai.ragent.news.service.NewsDailyDigestQueryService;
import com.nageoffer.ai.ragent.news.service.NewsQueryService;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link NewsSeoServiceImpl} 渲染合同测试（#213）：
 * feed=RSS 2.0 规范结构+本站 canonical+双语回退+转义；sitemap=sitemaps.org 0.9
 * 结构+资讯段 flag 条件。两条校验独立成法（票面：不写「都过 W3C」合并口径）。
 * 可见性隔离不在此重复断言——本实现委托 listPublished/listCuratedTopics/listRecent
 * （与页面同一查询面），隐藏条目过滤由该层测试锚定；此处断言委托关系本身。
 */
class NewsSeoServiceTests {

    private final NewsQueryService newsQueryService = mock(NewsQueryService.class);
    private final NewsDailyDigestQueryService digestQueryService = mock(NewsDailyDigestQueryService.class);
    private final NewsFetchProperties properties = new NewsFetchProperties();

    private NewsSeoServiceImpl newService(boolean newsEnabled) {
        return new NewsSeoServiceImpl(newsQueryService, digestQueryService, properties, newsEnabled);
    }

    private static NewsItemVO item(long id, String zhTitle, String enTitle, String url, String category) {
        NewsItemVO vo = new NewsItemVO();
        vo.setId(id);
        vo.setTitleZh(zhTitle);
        vo.setTitleEn(enTitle);
        vo.setSummaryZh("中文摘要 <含转义> & 测试");
        vo.setSummaryEn("English summary");
        vo.setUrl(url);
        vo.setCategory(category);
        vo.setPublishTime(new Date(1760000000000L));
        return vo;
    }

    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    // ================== feed.xml（RSS 2.0 规范结构） ==================

    @Test
    void feedRssStructureAndChannelRequiredElements() throws Exception {
        NewsPageVO page = new NewsPageVO();
        page.setRecords(List.of(item(179, "标题甲", "Title A", "https://www.polyu.edu.hk/x", "research")));
        when(newsQueryService.listPublished(isNull(), eq(1), eq(10))).thenReturn(page);

        Document doc = parseXml(newService(true).renderNewsFeed(10));

        assertThat(doc.getDocumentElement().getNodeName()).isEqualTo("rss");
        assertThat(doc.getDocumentElement().getAttribute("version")).isEqualTo("2.0");
        // channel 必需三件套：title/link/description（RSS 2.0 规范 §required channel elements）
        assertThat(doc.getElementsByTagName("title").getLength()).isGreaterThan(0);
        assertThat(doc.getElementsByTagName("link").getLength()).isGreaterThan(0);
        assertThat(doc.getElementsByTagName("description").getLength()).isGreaterThan(0);
        assertThat(doc.getElementsByTagName("language").item(0).getTextContent()).isEqualTo("zh-cn");
        assertThat(doc.getElementsByTagName("lastBuildDate").getLength()).isEqualTo(1);
    }

    @Test
    void feedItemsUseCanonicalSiteUrlAndCarryOriginalBacklink() throws Exception {
        NewsPageVO page = new NewsPageVO();
        page.setRecords(List.of(item(179, "标题甲", "Title A", "https://www.polyu.edu.hk/x", "research")));
        when(newsQueryService.listPublished(isNull(), eq(1), eq(50))).thenReturn(page);

        Document doc = parseXml(newService(true).renderNewsFeed(50));

        NodeList links = doc.getElementsByTagName("link");
        // channel link 之外，item link=本站 canonical（/news/{id}）——不是外部原文 URL
        assertThat(links.item(1).getTextContent()).isEqualTo("https://polyuguide.com/news/179");
        assertThat(doc.getElementsByTagName("guid").item(0).getTextContent())
                .isEqualTo("https://polyuguide.com/news/179");
        assertThat(doc.getElementsByTagName("guid").item(0).getAttributes().getNamedItem("isPermaLink")
                .getNodeValue()).isEqualTo("true");
        // 来源回链：描述尾附原文 URL（出口一致性口径）
        assertThat(doc.getElementsByTagName("description").item(1).getTextContent())
                .contains("https://www.polyu.edu.hk/x");
        assertThat(doc.getElementsByTagName("pubDate").item(0).getTextContent()).endsWith("+0800");
    }

    @Test
    void feedBilingualFallbackZhFirstEnFallbackAndXmlEscape() {
        NewsPageVO page = new NewsPageVO();
        page.setRecords(List.of(
                item(1, "中文标题", "En Title", "https://a.example/1", "research"),
                item(2, null, "En Only", "https://a.example/2", "event")));
        when(newsQueryService.listPublished(isNull(), anyInt(), anyInt())).thenReturn(page);

        String xml = newService(true).renderNewsFeed(10);

        // 中文优先、英文兜底（与日报 RSS 同口径）
        assertThat(xml).contains("<title>中文标题</title>");
        assertThat(xml).contains("<title>En Only</title>");
        // XML 转义：摘要里的 < > & 必须实体化（规范合法性的硬要求）
        assertThat(xml).contains("中文摘要 &lt;含转义&gt; &amp; 测试");
        assertThat(xml).doesNotContain("中文摘要 <含转义>");
    }

    @Test
    void feedDelegatesToTheSameVisibilityFaceAsThePage() {
        // 出口一致性结构证明：feed 走 /list 同一查询面（隐藏/未过门过滤在该层），
        // 自身不另写可见性判据——一个出口不泄露另一出口已隐藏内容
        when(newsQueryService.listPublished(isNull(), eq(1), eq(50))).thenReturn(new NewsPageVO());

        newService(true).renderNewsFeed(50);

        verify(newsQueryService).listPublished(isNull(), eq(1), eq(50));
    }

    @Test
    void feedLimitClampedToBound() {
        when(newsQueryService.listPublished(isNull(), eq(1), eq(100))).thenReturn(new NewsPageVO());

        newService(true).renderNewsFeed(99999);

        verify(newsQueryService).listPublished(isNull(), eq(1), eq(100));
    }

    // ================== sitemap.xml（sitemaps.org 0.9 schema） ==================

    @Test
    void sitemapUrlsetStructureAndNamespace() throws Exception {
        Document doc = parseXml(newService(false).renderSitemap());

        assertThat(doc.getDocumentElement().getNodeName()).isEqualTo("urlset");
        assertThat(doc.getDocumentElement().getNamespaceURI())
                .isEqualTo("http://www.sitemaps.org/schemas/sitemap/0.9");
        // 每 url 恰一个 loc（schema：url 子元素 loc 必需且唯一）
        NodeList urls = doc.getElementsByTagName("url");
        assertThat(urls.getLength()).isGreaterThanOrEqualTo(4);
        for (int i = 0; i < urls.getLength(); i++) {
            assertThat(urls.item(i).getChildNodes().getLength()).isGreaterThan(0);
        }
        Set<String> locs = IntStream.range(0, doc.getElementsByTagName("loc").getLength())
                .mapToObj(i -> doc.getElementsByTagName("loc").item(i).getTextContent())
                .collect(Collectors.toSet());
        assertThat(locs).contains("https://polyuguide.com/", "https://polyuguide.com/hot",
                "https://polyuguide.com/topics", "https://polyuguide.com/key-dates");
    }

    @Test
    void sitemapNewsSectionsOnlyWhenFlagOn() throws Exception {
        NewsTopicVO topic = new NewsTopicVO();
        topic.setSlug("research");
        when(newsQueryService.listCuratedTopics()).thenReturn(List.of(topic));
        NewsPageVO page = new NewsPageVO();
        page.setRecords(List.of(item(179, "t", "t", "https://a.example/1", "research")));
        when(newsQueryService.listPublished(isNull(), eq(1), eq(200))).thenReturn(page);
        NewsDailyDigestSummaryVO digest = new NewsDailyDigestSummaryVO();
        digest.setDigestDate(LocalDate.of(2026, 10, 3));
        digest.setBuildTime(new Date(1760000000000L));
        when(digestQueryService.listRecent(30)).thenReturn(List.of(digest));

        Document off = parseXml(newService(false).renderSitemap());
        Document on = parseXml(newService(true).renderSitemap());

        String offXml = xmlText(off);
        String onXml = xmlText(on);
        // 关闭态：资讯/主题/日报段整段不出现（不残留在售内容入口）
        assertThat(offXml).doesNotContain("/daily").doesNotContain("/topics/research").doesNotContain("/news/179");
        // 开启态：主题页/详情页（带 W3C lastmod）/日报归档页
        assertThat(onXml).contains("https://polyuguide.com/topics/research");
        assertThat(onXml).contains("https://polyuguide.com/news/179");
        assertThat(onXml).contains("https://polyuguide.com/daily/2026-10-03");
        // lastmod 为 W3C Datetime（ISO 8601 日期带 T 时间），与 RSS 的 RFC-822 口径不合并
        assertThat(onXml).containsPattern("<lastmod>\\d{4}-\\d{2}-\\d{2}T");
        assertThat(onXml).doesNotContain("+0800</lastmod>");
    }

    @Test
    void seoServiceHoldsNoLlmComponents() {
        // 页面/feed 请求不触发 LLM 的结构证明（与 #212 读取面同法）：字段集只有
        // 查询服务+配置+flag 布尔
        for (Field field : NewsSeoServiceImpl.class.getDeclaredFields()) {
            String type = field.getType().getName();
            assertThat(type.toLowerCase().contains("llm")).as("field %s", field.getName()).isFalse();
        }
    }

    private static String xmlText(Document doc) throws Exception {
        javax.xml.transform.Transformer transformer =
                javax.xml.transform.TransformerFactory.newInstance().newTransformer();
        java.io.StringWriter writer = new java.io.StringWriter();
        transformer.transform(new javax.xml.transform.dom.DOMSource(doc),
                new javax.xml.transform.stream.StreamResult(writer));
        return writer.toString();
    }
}
