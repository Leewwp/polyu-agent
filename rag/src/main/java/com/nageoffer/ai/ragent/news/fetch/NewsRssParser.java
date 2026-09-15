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

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * YouTube 频道 RSS（Atom）解析器（RSS 型）
 *
 * <p>YouTube 官方 RSS 标准：{@code https://www.youtube.com/feeds/videos.xml?channel_id=…}，
 * Atom 格式默认返回最近 15 条；单条即含标题+watch 链接+发布时间+media:description，
 * 无需再抓详情。本机 DNS 污染未能实测端点（已知遗留），解析器按 Atom+media RSS
 * 公开规范实现，生产香港服务器侧 curl 复验列入后续待办。
 */
public final class NewsRssParser {

    /**
     * Atom 1.0 与 media RSS 命名空间
     */
    static final String NS_ATOM = "http://www.w3.org/2005/Atom";
    static final String NS_MEDIA = "http://search.yahoo.com/mrss/";

    private NewsRssParser() {
    }

    /**
     * 解析 feed 字节流：Atom 1.0（YouTube）优先，零条目时回落 RSS 2.0（Google News）
     *
     * @throws NewsFetchException 零条目或 XML 不合法（防静默空结果）
     */
    public static List<RssEntry> parse(byte[] xml) {
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);
            document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new NewsFetchException("RSS XML 解析失败: " + e.getMessage(), false, e);
        }
        List<RssEntry> parsed = parseAtomEntries(document);
        if (parsed.isEmpty()) {
            parsed = parseRss2Items(document);
        }
        if (parsed.isEmpty()) {
            throw new NewsFetchException("RSS 解析零条目（结构变更嫌疑，fail-closed）", false);
        }
        return parsed;
    }

    private static List<RssEntry> parseAtomEntries(Document document) {
        NodeList entries = document.getElementsByTagNameNS(NS_ATOM, "entry");
        List<RssEntry> parsed = new ArrayList<>();
        for (int i = 0; i < entries.getLength(); i++) {
            Element entry = (Element) entries.item(i);
            String title = textOfFirst(entry, NS_ATOM, "title");
            String link = linkHref(entry);
            String published = textOfFirst(entry, NS_ATOM, "published");
            String updated = textOfFirst(entry, NS_ATOM, "updated");
            String description = textOfFirst(entry, NS_MEDIA, "description");
            if (link == null || link.isBlank()) {
                continue;
            }
            Date publishTime = parseInstant(published);
            if (publishTime == null) {
                publishTime = parseInstant(updated);
            }
            parsed.add(new RssEntry(link.trim(), title == null ? null : title.trim(), publishTime,
                    description == null ? null : description.trim(), null));
        }
        return parsed;
    }

    /**
     * RSS 2.0 回落分支（Google News）：item = title + link（文本元素，
     * 非 Atom href 属性）+ pubDate（RFC 1123）+ description + source（媒体名，
     * 供「标题 - 媒体名」后缀清洗）
     */
    private static List<RssEntry> parseRss2Items(Document document) {
        NodeList items = document.getElementsByTagName("item");
        List<RssEntry> parsed = new ArrayList<>();
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String title = childText(item, "title");
            String link = childText(item, "link");
            String pubDate = childText(item, "pubDate");
            String description = childText(item, "description");
            String source = childText(item, "source");
            if (link == null || link.isBlank()) {
                continue;
            }
            parsed.add(new RssEntry(link.trim(), title == null ? null : title.trim(),
                    parseRfc1123(pubDate), description == null ? null : description.trim(),
                    source == null || source.isBlank() ? null : source.trim()));
        }
        return parsed;
    }

    private static String childText(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagName(localName);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
    }

    private static Date parseRfc1123(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Date.from(java.time.ZonedDateTime
                    .parse(value.trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String linkHref(Element entry) {
        NodeList links = entry.getElementsByTagNameNS(NS_ATOM, "link");
        for (int i = 0; i < links.getLength(); i++) {
            Element link = (Element) links.item(i);
            String rel = link.getAttribute("rel");
            if (rel.isBlank() || "alternate".equals(rel)) {
                return link.getAttribute("href");
            }
        }
        return null;
    }

    private static Date parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Date.from(OffsetDateTime.parse(value.trim()).toInstant());
        } catch (Exception ignore) {
            return null;
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
     * RSS 条目：链接 + 标题 + 发布时间 + 描述（作 LLM 摘要输入的原文）+
     * 媒体名（RSS 2.0 source 元素；Atom/YouTube 恒 null，供 GNews 后缀清洗）
     */
    public record RssEntry(String link, String title, Date publishTime, String description,
                           String sourcePublisher) {
    }
}
