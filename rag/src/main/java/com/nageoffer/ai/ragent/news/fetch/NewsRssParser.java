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
 * YouTube 频道 RSS（Atom）解析器（U12-A A3，doc 19 §2 RSS 型）
 *
 * <p>YouTube 官方 RSS 标准：{@code https://www.youtube.com/feeds/videos.xml?channel_id=…}，
 * Atom 格式默认返回最近 15 条；单条即含标题+watch 链接+发布时间+media:description，
 * 无需再抓详情。本机 DNS 污染未能实测端点（T1 遗留），解析器按 Atom+media RSS
 * 公开规范实现，生产香港服务器侧 curl 复验挂 HANDOFF 待办。
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
     * 解析 Atom feed 字节流
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
                    description == null ? null : description.trim()));
        }
        if (parsed.isEmpty()) {
            throw new NewsFetchException("RSS 解析零条目（结构变更嫌疑，fail-closed）", false);
        }
        return parsed;
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
     * RSS 条目：watch 链接 + 标题 + 发布时间 + 描述（作 A5 摘要输入的原文）
     */
    public record RssEntry(String link, String title, Date publishTime, String description) {
    }
}
