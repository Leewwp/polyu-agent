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
import com.nageoffer.ai.ragent.news.service.NewsSeoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * {@link NewsSeoService} 实现：feed.xml / sitemap.xml 确定性渲染。
 *
 * <p>可见性=委托既有公开查询面（listPublished/listCuratedTopics/listRecent），
 * 本类不持有任何 Mapper——「出口用哪套可见性」由装配关系结构性保证（隐藏条目
 * 在服务层即被过滤，NewsQueryServiceTests 的 hidden 对照行覆盖该层）。
 *
 * <p>资讯段以 {@code rag.news.enabled} 为条件（sitemap 本体站点级常开，flag 关时
 * 只含静态路由——关闭态不残留在售内容入口）。
 */
@Slf4j
@Service
public class NewsSeoServiceImpl implements NewsSeoService {

    /**
     * feed 条目默认上限（调用方 Controller 钳制 [1,100]，此处再防一层）
     */
    static final int FEED_MAX_LIMIT = 100;

    /**
     * sitemap 资讯详情页收录上限：近 N 条公开条目（页面详情页是长尾入口，
     * 全量进 sitemap 无收益且稀释抓取预算）
     */
    static final int SITEMAP_NEWS_ITEMS = 200;

    /**
     * sitemap 主题页收录上限（curated 主题目录页）
     */
    static final int SITEMAP_TOPICS = 100;

    /**
     * RSS pubDate/lastBuildDate（RFC-822，HKT +0800，与日报 RSS 同格式）
     */
    private static final DateTimeFormatter RFC822_FORMAT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH)
                    .withZone(ZoneId.of("Asia/Hong_Kong"));

    /**
     * sitemap lastmod（W3C Datetime——sitemap 协议要求，非 RFC-822，两条口径不合并）
     */
    private static final DateTimeFormatter W3C_DATETIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ENGLISH)
                    .withZone(ZoneId.of("Asia/Hong_Kong"));

    private final NewsQueryService newsQueryService;
    private final NewsDailyDigestQueryService digestQueryService;
    private final NewsFetchProperties properties;
    private final boolean newsEnabled;

    @Autowired
    public NewsSeoServiceImpl(NewsQueryService newsQueryService,
                              NewsDailyDigestQueryService digestQueryService,
                              NewsFetchProperties properties,
                              @Value("${rag.news.enabled:false}") boolean newsEnabled) {
        this.newsQueryService = newsQueryService;
        this.digestQueryService = digestQueryService;
        this.properties = properties;
        this.newsEnabled = newsEnabled;
    }

    @Override
    public String renderNewsFeed(int limit) {
        int bounded = Math.max(1, Math.min(limit, FEED_MAX_LIMIT));
        String site = properties.effectiveSiteBaseUrl();
        NewsPageVO page = newsQueryService.listPublished(null, 1, bounded);
        List<NewsItemVO> items = page == null || page.getRecords() == null
                ? List.of() : page.getRecords();
        StringBuilder xml = new StringBuilder(4096);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<rss version=\"2.0\">\n");
        xml.append("  <channel>\n");
        xml.append("    <title>").append(xmlEscape("理大资讯 PolyU News")).append("</title>\n");
        xml.append("    <link>").append(xmlEscape(site + "/hot")).append("</link>\n");
        xml.append("    <description>").append(xmlEscape(
                "香港理工大学官方渠道资讯聚合：双语 AI 摘要 + 主题分类。 "
                        + "Aggregated PolyU official news with bilingual AI summaries."))
                .append("</description>\n");
        xml.append("    <language>zh-cn</language>\n");
        xml.append("    <lastBuildDate>").append(rfc822(new Date())).append("</lastBuildDate>\n");
        for (NewsItemVO item : items) {
            String canonical = site + "/news/" + item.getId();
            String title = firstNonBlank(item.getTitleZh(), item.getTitleEn());
            String summary = firstNonBlank(item.getSummaryZh(), item.getSummaryEn());
            String description = summary
                    + (isBlank(item.getUrl()) ? "" : "\n原文 Original: " + item.getUrl());
            xml.append("    <item>\n");
            xml.append("      <title>").append(xmlEscape(title)).append("</title>\n");
            xml.append("      <link>").append(xmlEscape(canonical)).append("</link>\n");
            xml.append("      <guid isPermaLink=\"true\">").append(xmlEscape(canonical)).append("</guid>\n");
            if (item.getPublishTime() != null) {
                xml.append("      <pubDate>").append(rfc822(item.getPublishTime())).append("</pubDate>\n");
            }
            xml.append("      <description>").append(xmlEscape(description)).append("</description>\n");
            if (!isBlank(item.getCategory())) {
                xml.append("      <category>").append(xmlEscape(item.getCategory())).append("</category>\n");
            }
            xml.append("    </item>\n");
        }
        xml.append("  </channel>\n");
        xml.append("</rss>\n");
        return xml.toString();
    }

    @Override
    public String renderSitemap() {
        String site = properties.effectiveSiteBaseUrl();
        StringBuilder xml = new StringBuilder(8192);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        // 静态公共路由（无自然 lastmod，规范允许省略——不伪造）
        appendUrl(xml, site + "/");
        appendUrl(xml, site + "/hot");
        appendUrl(xml, site + "/topics");
        appendUrl(xml, site + "/key-dates");
        if (newsEnabled) {
            appendUrl(xml, site + "/daily");
            appendTopics(xml, site);
            appendRecentItems(xml, site);
            appendRecentDigests(xml, site);
        }
        xml.append("</urlset>\n");
        return xml.toString();
    }

    private void appendTopics(StringBuilder xml, String site) {
        List<NewsTopicVO> topics = newsQueryService.listCuratedTopics();
        if (topics == null) {
            return;
        }
        topics.stream()
                .filter(topic -> topic != null && !isBlank(topic.getSlug()))
                .limit(SITEMAP_TOPICS)
                .forEach(topic -> appendUrl(xml, site + "/topics/" + topic.getSlug()));
    }

    private void appendRecentItems(StringBuilder xml, String site) {
        NewsPageVO page = newsQueryService.listPublished(null, 1, SITEMAP_NEWS_ITEMS);
        List<NewsItemVO> items = page == null || page.getRecords() == null
                ? List.of() : page.getRecords();
        for (NewsItemVO item : items) {
            if (item == null || item.getId() == null) {
                continue;
            }
            appendUrl(xml, site + "/news/" + item.getId(),
                    item.getPublishTime() == null ? null : w3cDatetime(item.getPublishTime()));
        }
    }

    private void appendRecentDigests(StringBuilder xml, String site) {
        List<NewsDailyDigestSummaryVO> digests = digestQueryService.listRecent(30);
        if (digests == null) {
            return;
        }
        digests.stream()
                .filter(digest -> digest != null && digest.getDigestDate() != null)
                .forEach(digest -> appendUrl(xml, site + "/daily/" + digest.getDigestDate(),
                        digest.getBuildTime() == null ? null : w3cDatetime(digest.getBuildTime())));
    }

    private static void appendUrl(StringBuilder xml, String loc) {
        appendUrl(xml, loc, null);
    }

    private static void appendUrl(StringBuilder xml, String loc, String lastmod) {
        xml.append("  <url>\n    <loc>").append(xmlEscape(loc)).append("</loc>\n");
        if (lastmod != null) {
            xml.append("    <lastmod>").append(lastmod).append("</lastmod>\n");
        }
        xml.append("  </url>\n");
    }

    /**
     * sitemap loc 规范要求转义（URI 里的 & 等保留字符）；主题 slug 经
     * {@link URI} 规范化校验后再放行，非法值跳过不整包失败
     */
    private static String xmlEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '&' -> escaped.append("&amp;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&apos;");
                default -> escaped.append(ch);
            }
        }
        return escaped.toString();
    }

    private static String rfc822(Date time) {
        return time == null ? "" : RFC822_FORMAT.format(time.toInstant());
    }

    private static String w3cDatetime(Date time) {
        return time == null ? "" : W3C_DATETIME_FORMAT.format(time.toInstant());
    }

    private static String firstNonBlank(String first, String second) {
        return !isBlank(first) ? first : (second == null ? "" : second);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
