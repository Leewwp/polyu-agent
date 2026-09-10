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

package com.nageoffer.ai.ragent.news.fetch;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Google News 格式 sitemap 解析器（U12-A A3 主引擎，doc 19 §2）
 *
 * <p>每条 {@code <url>} 携带：loc（规范 URL）+ news:title + news:publication_date
 * （秒级、带 +08:00）+ xhtml:link rel=alternate 的 en/zh-Hant/zh-Hans 三语变体
 * （同 slug 换 / /tc/ /sc/ 前缀）——增量发现、精确发布时间、三语对齐一文件全解决。
 * 解析产出 {@link SitemapEntry}；hreflang 变体 map 供 A5 双语详情抓取用，
 * A3 落库只取 loc 与 news:title。
 *
 * <p>格式变更设监控（研究票工程注意）：解析零条目即抛异常而非静默空结果。
 */
public final class NewsSitemapParser {

    /**
     * sitemap 0.9 / Google News / xhtml 命名空间
     */
    static final String NS_SITEMAP = "http://www.sitemaps.org/schemas/sitemap/0.9";
    static final String NS_NEWS = "http://www.google.com/schemas/sitemap-news/0.9";
    static final String NS_XHTML = "http://www.w3.org/1999/xhtml";

    private NewsSitemapParser() {
    }

    /**
     * 解析 news-sitemap.xml 字节流
     *
     * @throws NewsFetchException 零条目或 XML 不合法（防结构改版静默空结果）
     */
    public static List<SitemapEntry> parse(byte[] xml) {
        Document document = parseXml(xml);
        NodeList urlNodes = document.getElementsByTagNameNS(NS_SITEMAP, "url");
        List<SitemapEntry> entries = new ArrayList<>();
        for (int i = 0; i < urlNodes.getLength(); i++) {
            Element urlElement = (Element) urlNodes.item(i);
            String loc = textOfFirst(urlElement, NS_SITEMAP, "loc");
            if (loc == null || loc.isBlank()) {
                continue;
            }
            String title = textOfFirst(urlElement, NS_NEWS, "title");
            String publicationDate = textOfFirst(urlElement, NS_NEWS, "publication_date");
            String language = textOfFirst(urlElement, NS_NEWS, "language");
            Map<String, String> alternates = new LinkedHashMap<>();
            NodeList links = urlElement.getElementsByTagNameNS(NS_XHTML, "link");
            for (int j = 0; j < links.getLength(); j++) {
                Element link = (Element) links.item(j);
                String rel = link.getAttribute("rel");
                String hreflang = link.getAttribute("hreflang");
                String href = link.getAttribute("href");
                if ("alternate".equals(rel) && !hreflang.isBlank() && !href.isBlank()) {
                    alternates.putIfAbsent(hreflang, href);
                }
            }
            Date publishTime = null;
            if (publicationDate != null && !publicationDate.isBlank()) {
                try {
                    publishTime = Date.from(OffsetDateTime.parse(publicationDate.trim()).toInstant());
                } catch (Exception ignore) {
                    // 时间戳不合法的条目仍保留（publishTime 为 null 由上层窗口过滤兜底）
                }
            }
            entries.add(new SitemapEntry(loc.trim(), title == null ? null : title.trim(),
                    language == null ? null : language.trim(), publishTime, Map.copyOf(alternates)));
        }
        if (entries.isEmpty()) {
            throw new NewsFetchException("news-sitemap 解析零条目（结构改版嫌疑，fail-closed）", false);
        }
        return entries;
    }

    private static Document parseXml(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // XXE 加固：外部实体一律禁用（内容来自外部站点）
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new NewsFetchException("news-sitemap XML 解析失败: " + e.getMessage(), false, e);
        }
    }

    private static String textOfFirst(Element parent, String namespace, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS(namespace, localName);
        if (nodes.getLength() == 0) {
            return null;
        }
        String text = nodes.item(0).getTextContent();
        return text == null ? null : text.trim();
    }

    /**
     * sitemap 条目：loc + 标题 + 发布时间 + 三语 hreflang 变体
     *
     * @param alternates hreflang（en/zh-Hant/zh-Hans/x-default）→ URL
     */
    public record SitemapEntry(String loc,
                               String title,
                               String language,
                               Date publishTime,
                               Map<String, String> alternates) {
    }
}
