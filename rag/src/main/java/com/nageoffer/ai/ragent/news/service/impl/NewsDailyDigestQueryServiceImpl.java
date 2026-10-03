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
import com.nageoffer.ai.ragent.news.controller.vo.NewsDailyDigestItemVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsDailyDigestSummaryVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsDailyDigestVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsSourceMetaVO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsDailyDigestDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsDailyDigestItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsDailyDigestItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsDailyDigestMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import com.nageoffer.ai.ragent.news.service.NewsDailyDigestQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 资讯日报公开读取服务实现（#212）——<b>零 LLM 结构保证</b>
 *
 * <p>本类依赖只有三张表的 Mapper 与配置（digest/digest_item/item 的只读面），
 * <b>不持有</b> LLMService/NewsLlmBudgetService/ModelSelector/PromptTemplateLoader
 * 等任何付费组件——页面与 RSS 请求路径上不存在可触发的模型调用
 * （NewsDailyDigestQueryServiceTests.zeroLlmStructure 以反射断言此结构）。
 *
 * <p><b>主动下架复检（读取期，零调用）</b>：快照条目按 item_id 回查
 * t_news_item——源行<b>仍存在且 status 不为 published</b>（hidden/expired/
 * archived）→ 快照失格过滤；源行<b>已不存在</b>（90 天保留清理正常删除）→
 * 快照保留展示（快照独立性验收：正常清理后日报可读）。不再复检发布门：
 * eligible_time 只在资格就绪时一次性落定不回拨，生成期已过门的条目读取期
 * 仍过门；会回退的只有 status（published→hidden 人工下架）。
 *
 * <p><b>导语失格回退</b>：任一条目失格 → 生效导语回退固定模板
 * （{@link NewsDailyDigestTemplates}：可见&gt;0 回退计数模板、=0 回退空刊
 * 模板）——下架内容不残留于导语，且零新增模型调用（票面默认回退路径）。
 *
 * <p><b>RSS 渲染</b>：RSS 2.0 文本端点（沿 PublicKeyDateIcsController 的
 * 文本 feed 先例：原文返回+Cache-Control 卫生值），从详情 VO 确定性渲染，
 * XML 转义覆盖全部文本节点，pubDate 用 RFC-822（HKT 偏移）。
 */
@Slf4j
@Service
public class NewsDailyDigestQueryServiceImpl implements NewsDailyDigestQueryService {

    /**
     * 目录条数上限（钳制上界：一刊一行，90 天保留面 90 期封顶）
     */
    private static final int MAX_LIST_LIMIT = 90;

    /**
     * 目录默认条数
     */
    private static final int DEFAULT_LIST_LIMIT = 30;

    /**
     * RSS pubDate/lastBuildDate 格式（RFC-822，HKT +0800）
     */
    private static final DateTimeFormatter RFC822_FORMAT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH)
                    .withZone(ZoneId.of("Asia/Hong_Kong"));

    private final NewsDailyDigestMapper digestMapper;
    private final NewsDailyDigestItemMapper digestItemMapper;
    private final NewsItemMapper itemMapper;
    private final NewsFetchProperties properties;

    @Autowired
    public NewsDailyDigestQueryServiceImpl(NewsDailyDigestMapper digestMapper,
                                           NewsDailyDigestItemMapper digestItemMapper,
                                           NewsItemMapper itemMapper,
                                           NewsFetchProperties properties) {
        this.digestMapper = digestMapper;
        this.digestItemMapper = digestItemMapper;
        this.itemMapper = itemMapper;
        this.properties = properties;
    }

    @Override
    public List<NewsDailyDigestSummaryVO> listRecent(int limit) {
        int bounded = limit <= 0 ? DEFAULT_LIST_LIMIT : Math.min(limit, MAX_LIST_LIMIT);
        return digestMapper.selectList(new LambdaQueryWrapper<NewsDailyDigestDO>()
                        .orderByDesc(NewsDailyDigestDO::getDigestDate)
                        .last("LIMIT " + bounded))
                .stream()
                .map(header -> NewsDailyDigestSummaryVO.builder()
                        .digestDate(header.getDigestDate())
                        .itemCount(header.getItemCount())
                        .introSource(header.getIntroSource())
                        .buildTime(header.getBuildTime())
                        .build())
                .toList();
    }

    @Override
    public NewsDailyDigestVO getDetail(LocalDate digestDate) {
        NewsDailyDigestDO header = digestMapper.selectOne(new LambdaQueryWrapper<NewsDailyDigestDO>()
                .eq(NewsDailyDigestDO::getDigestDate, digestDate)
                .last("LIMIT 1"));
        if (header == null) {
            return null;
        }
        List<NewsDailyDigestItemDO> snapshots = digestItemMapper.selectList(
                new LambdaQueryWrapper<NewsDailyDigestItemDO>()
                        .eq(NewsDailyDigestItemDO::getDigestId, header.getId())
                        .orderByAsc(NewsDailyDigestItemDO::getSeq));
        // 主动下架复检：源行仍存在且 status!=published → 失格；源行已清理 → 保留
        Set<Long> sourceItemIds = snapshots.stream()
                .map(NewsDailyDigestItemDO::getItemId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> liveStatus = new HashMap<>();
        if (!sourceItemIds.isEmpty()) {
            for (NewsItemDO live : itemMapper.selectList(new LambdaQueryWrapper<NewsItemDO>()
                    .in(NewsItemDO::getId, sourceItemIds))) {
                liveStatus.put(live.getId(), live.getStatus());
            }
        }
        List<NewsDailyDigestItemVO> visible = new ArrayList<>(snapshots.size());
        int disqualified = 0;
        for (NewsDailyDigestItemDO snapshot : snapshots) {
            String status = liveStatus.get(snapshot.getItemId());
            if (status != null && !"published".equals(status)) {
                disqualified++;
                continue;
            }
            visible.add(toItemVO(snapshot));
        }
        // 导语失格回退（零调用）：有失格条目即回退模板（可见>0 计数模板 / =0 空刊模板）
        String introZh = header.getIntroZh();
        String introEn = header.getIntroEn();
        boolean degraded = disqualified > 0;
        if (degraded) {
            if (visible.isEmpty()) {
                introZh = NewsDailyDigestTemplates.emptyIntroZh(digestDate);
                introEn = NewsDailyDigestTemplates.emptyIntroEn(digestDate);
            } else {
                introZh = NewsDailyDigestTemplates.fallbackIntroZh(digestDate, visible.size());
                introEn = NewsDailyDigestTemplates.fallbackIntroEn(digestDate, visible.size());
            }
            log.info("[news][daily] 日报 {} 读取期复检：{} 条快照失格（主动下架），导语已回退模板（零调用）",
                    digestDate, disqualified);
        }
        return NewsDailyDigestVO.builder()
                .digestDate(header.getDigestDate())
                .windowStart(header.getWindowStart())
                .windowEnd(header.getWindowEnd())
                .introZh(introZh)
                .introEn(introEn)
                .storedIntroSource(header.getIntroSource())
                .introDegraded(degraded)
                .itemCount(header.getItemCount())
                .visibleCount(visible.size())
                .disqualifiedCount(disqualified)
                .items(visible)
                .buildTime(header.getBuildTime())
                .build();
    }

    /**
     * RSS 2.0 渲染（#212 §独立 RSS feed）：从详情 VO 确定性生成——文本节点全部
     * 经 {@link #xmlEscape} 转义；channel 必需三件套（title/link/description）
     * + item（title/link/guid/pubDate/description/category）；标题/摘要中英
     * 取「中文优先、英文兜底」（RSS 单语惯例，双语快照两列都留存）
     */
    @Override
    public String renderRss(NewsDailyDigestVO detail) {
        String siteUrl = properties.effectiveDigestRssSiteUrl();
        String date = detail.getDigestDate().toString();
        StringBuilder xml = new StringBuilder(4096);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<rss version=\"2.0\">\n");
        xml.append("  <channel>\n");
        xml.append("    <title>").append(xmlEscape("理大资讯日报 " + date + " | PolyU Daily Digest " + date))
                .append("</title>\n");
        xml.append("    <link>").append(xmlEscape(siteUrl)).append("/daily</link>\n");
        xml.append("    <description>").append(xmlEscape(firstNonBlank(detail.getIntroZh(), detail.getIntroEn())))
                .append("</description>\n");
        xml.append("    <language>zh-cn</language>\n");
        xml.append("    <pubDate>").append(rfc822(detail.getBuildTime())).append("</pubDate>\n");
        xml.append("    <lastBuildDate>").append(rfc822(detail.getBuildTime())).append("</lastBuildDate>\n");
        for (NewsDailyDigestItemVO item : detail.getItems()) {
            String title = firstNonBlank(item.getTitleZh(), item.getTitleEn());
            String summary = firstNonBlank(item.getSummaryZh(), item.getSummaryEn());
            xml.append("    <item>\n");
            xml.append("      <title>").append(xmlEscape(title)).append("</title>\n");
            xml.append("      <link>").append(xmlEscape(item.getUrl())).append("</link>\n");
            xml.append("      <guid>").append(xmlEscape(item.getUrl())).append("</guid>\n");
            if (item.getPublishTime() != null) {
                xml.append("      <pubDate>").append(rfc822(item.getPublishTime())).append("</pubDate>\n");
            }
            xml.append("      <description>").append(xmlEscape(summary)).append("</description>\n");
            if (item.getCategory() != null) {
                xml.append("      <category>").append(xmlEscape(item.getCategory())).append("</category>\n");
            }
            xml.append("    </item>\n");
        }
        xml.append("  </channel>\n");
        xml.append("</rss>\n");
        return xml.toString();
    }

    private NewsDailyDigestItemVO toItemVO(NewsDailyDigestItemDO snapshot) {
        NewsSourceMetaVO source = null;
        if (snapshot.getSourceKey() != null) {
            source = NewsSourceMetaVO.builder()
                    .sourceKey(snapshot.getSourceKey())
                    .platform(snapshot.getSourcePlatform())
                    .official(snapshot.getSourceOfficial())
                    .displayName(snapshot.getSourceDisplayName())
                    .displayNameEn(snapshot.getSourceDisplayNameEn())
                    .build();
        }
        List<String> topics = snapshot.getTopicSlugs() == null || snapshot.getTopicSlugs().isBlank()
                ? List.of() : Arrays.asList(snapshot.getTopicSlugs().split(","));
        return NewsDailyDigestItemVO.builder()
                .itemId(snapshot.getItemId())
                .seq(snapshot.getSeq())
                .url(snapshot.getUrl())
                .titleZh(snapshot.getTitleZh())
                .titleEn(snapshot.getTitleEn())
                .summaryZh(snapshot.getSummaryZh())
                .summaryEn(snapshot.getSummaryEn())
                .category(snapshot.getCategory())
                .topics(topics)
                .publishTime(snapshot.getPublishTime())
                .source(source)
                .build();
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback == null ? "" : fallback;
    }

    /**
     * XML 文本节点转义（五个预定义实体；控制字符剔除——RSS 文本不承载）
     */
    private static String xmlEscape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> {
                    if (ch >= 0x20 || ch == 0x9 || ch == 0xA || ch == 0xD) {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }

    private static String rfc822(Date time) {
        return time == null ? "" : RFC822_FORMAT.format(time.toInstant());
    }
}
