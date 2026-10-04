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
 * XML 转义覆盖全部文本节点，pubDate 用 RFC-822（HKT 偏移）。期级 feed
 * （#240 renderIssuesRss）同一组装/转义约定，批量复检一次 IN 查询覆盖
 * 全部期（不逐期 getDetail）。
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
     * 期级 RSS 收录期数（#240 票面：最近 30 期；固定值不出参——feed 形态对
     * 订阅者稳定，上限走 SQL LIMIT 与目录同款）
     */
    private static final int ISSUES_FEED_LIMIT = 30;

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
        List<NewsDailyDigestDO> headers = digestMapper.selectList(new LambdaQueryWrapper<NewsDailyDigestDO>()
                .orderByDesc(NewsDailyDigestDO::getDigestDate)
                .last("LIMIT " + bounded));
        // firstTitle 批量口径（#240）：一次 IN 查询取各期可见集 seq 首条（与详情页
        // 头条同源同值——seq=1 恰被下架的期落到下一可见条，空期/全失格=null）
        Map<Long, VisibleFace> faces = batchVisibleFaces(headers);
        return headers.stream()
                .map(header -> {
                    NewsDailyDigestItemDO first = faces.get(header.getId()).firstVisible();
                    return NewsDailyDigestSummaryVO.builder()
                            .digestDate(header.getDigestDate())
                            .itemCount(header.getItemCount())
                            .introSource(header.getIntroSource())
                            .buildTime(header.getBuildTime())
                            .firstTitleZh(first == null ? null : first.getTitleZh())
                            .firstTitleEn(first == null ? null : first.getTitleEn())
                            .build();
                })
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
        EffectiveIntro intro = effectiveIntro(header, visible.size(), disqualified);
        if (disqualified > 0) {
            log.info("[news][daily] 日报 {} 读取期复检：{} 条快照失格（主动下架），导语已回退模板（零调用）",
                    digestDate, disqualified);
        }
        return NewsDailyDigestVO.builder()
                .digestDate(header.getDigestDate())
                .windowStart(header.getWindowStart())
                .windowEnd(header.getWindowEnd())
                .introZh(intro.zh())
                .introEn(intro.en())
                .storedIntroSource(header.getIntroSource())
                .introDegraded(disqualified > 0)
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

    /**
     * 期级 RSS 渲染（#240，Q10——零 LLM）：订阅对象是「日报」这份连续刊物，
     * 每期一条 item（最近 30 期、日期倒序）。link=站内 /daily/{date} 绝对
     * canonical（基准 URL 用站点级 siteBaseUrl——与 sitemap /daily/{date} 条目
     * 同源，出口一致；单刊 feed 的 digestRssSiteUrl 键是 #212 专有不并键）；
     * guid=期日期（非 URL，isPermaLink=false）；空期条目保留并附休刊说明文案。
     * 批量复检（快照与源行各一次 IN 查询）覆盖全部期，不逐期 getDetail。
     */
    @Override
    public String renderIssuesRss() {
        String siteUrl = properties.effectiveSiteBaseUrl();
        List<NewsDailyDigestDO> headers = digestMapper.selectList(new LambdaQueryWrapper<NewsDailyDigestDO>()
                .orderByDesc(NewsDailyDigestDO::getDigestDate)
                .last("LIMIT " + ISSUES_FEED_LIMIT));
        Map<Long, VisibleFace> faces = batchVisibleFaces(headers);
        int degradedIssues = 0;
        for (NewsDailyDigestDO header : headers) {
            if (faces.get(header.getId()).disqualified() > 0) {
                degradedIssues++;
            }
        }
        if (degradedIssues > 0) {
            log.info("[news][daily] 期级 RSS 读取期复检：{}/{} 期存在失格快照（主动下架），相应期导语已回退模板（零调用）",
                    degradedIssues, headers.size());
        }
        StringBuilder xml = new StringBuilder(8192);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<rss version=\"2.0\">\n");
        xml.append("  <channel>\n");
        xml.append("    <title>").append(xmlEscape("理大资讯日报 | PolyU Daily Digest")).append("</title>\n");
        xml.append("    <link>").append(xmlEscape(siteUrl + "/daily")).append("</link>\n");
        xml.append("    <description>").append(xmlEscape(
                        "香港理工大学公开动态日报：每日一期，条目为下架复检后的可见快照。 "
                                + "One issue per day of aggregated PolyU public updates."))
                .append("</description>\n");
        xml.append("    <language>zh-cn</language>\n");
        xml.append("    <lastBuildDate>").append(rfc822(new Date())).append("</lastBuildDate>\n");
        for (NewsDailyDigestDO header : headers) {
            VisibleFace face = faces.get(header.getId());
            List<NewsDailyDigestItemDO> visible = face.visible();
            EffectiveIntro intro = effectiveIntro(header, visible.size(), face.disqualified());
            String date = header.getDigestDate().toString();
            xml.append("    <item>\n");
            xml.append("      <title>").append(xmlEscape(issueTitle(date, visible))).append("</title>\n");
            xml.append("      <link>").append(xmlEscape(siteUrl + "/daily/" + date)).append("</link>\n");
            xml.append("      <guid isPermaLink=\"false\">").append(xmlEscape(date)).append("</guid>\n");
            xml.append("      <pubDate>").append(rfc822(header.getBuildTime())).append("</pubDate>\n");
            xml.append("      <description>").append(
                    xmlEscape(issueDescription(intro.zh(), visible))).append("</description>\n");
            xml.append("    </item>\n");
        }
        xml.append("  </channel>\n");
        xml.append("</rss>\n");
        return xml.toString();
    }

    /**
     * 期条目标题：理大资讯日报 · 日期 + 头条标题（可见集 seq 首条，中文优先英文
     * 兜底）；空期只有日期前缀——头条后缀缺位由 description 的休刊文案补义
     */
    private static String issueTitle(String date, List<NewsDailyDigestItemDO> visible) {
        if (visible.isEmpty()) {
            return "理大资讯日报 · " + date;
        }
        NewsDailyDigestItemDO headline = visible.get(0);
        return "理大资讯日报 · " + date + "：" + firstNonBlank(headline.getTitleZh(), headline.getTitleEn());
    }

    /**
     * 期条目描述：生效导语（zh 口径）+可见条目标题简表；空期=导语+休刊说明
     * （每日 URL 可预期是特性，空期保留条目不跳期）
     */
    private static String issueDescription(String introZh, List<NewsDailyDigestItemDO> visible) {
        StringBuilder text = new StringBuilder(introZh);
        if (visible.isEmpty()) {
            return text.append("\n本期休刊——该日无可见公开动态。").toString();
        }
        for (NewsDailyDigestItemDO item : visible) {
            text.append("\n· ").append(firstNonBlank(item.getTitleZh(), item.getTitleEn()));
        }
        return text.toString();
    }

    /**
     * 批量读取期复检（#240）：一次 IN 查询取全部期的快照+一次 IN 查询回查源行
     * status，逐期得出可见集（seq 升序，首条=详情页头条）与失格数——判据与
     * {@link #getDetail} 完全一致（源行仍存在且 status!=published → 失格；
     * 源行已清理 → 保留），保证目录 firstTitle 与详情页头条同值
     */
    private Map<Long, VisibleFace> batchVisibleFaces(List<NewsDailyDigestDO> headers) {
        if (headers.isEmpty()) {
            return Map.of();
        }
        Set<Long> digestIds = headers.stream()
                .map(NewsDailyDigestDO::getId).collect(Collectors.toSet());
        List<NewsDailyDigestItemDO> snapshots = digestItemMapper.selectList(
                new LambdaQueryWrapper<NewsDailyDigestItemDO>()
                        .in(NewsDailyDigestItemDO::getDigestId, digestIds)
                        .orderByAsc(NewsDailyDigestItemDO::getSeq));
        Set<Long> sourceItemIds = snapshots.stream()
                .map(NewsDailyDigestItemDO::getItemId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> liveStatus = new HashMap<>();
        if (!sourceItemIds.isEmpty()) {
            for (NewsItemDO live : itemMapper.selectList(new LambdaQueryWrapper<NewsItemDO>()
                    .in(NewsItemDO::getId, sourceItemIds))) {
                liveStatus.put(live.getId(), live.getStatus());
            }
        }
        Map<Long, List<NewsDailyDigestItemDO>> visibleByDigest = new HashMap<>();
        Map<Long, Integer> totalByDigest = new HashMap<>();
        for (NewsDailyDigestItemDO snapshot : snapshots) {
            totalByDigest.merge(snapshot.getDigestId(), 1, Integer::sum);
            String status = liveStatus.get(snapshot.getItemId());
            if (status != null && !"published".equals(status)) {
                continue;
            }
            visibleByDigest.computeIfAbsent(snapshot.getDigestId(), key -> new ArrayList<>()).add(snapshot);
        }
        Map<Long, VisibleFace> faces = new HashMap<>();
        for (NewsDailyDigestDO header : headers) {
            List<NewsDailyDigestItemDO> visible = visibleByDigest.getOrDefault(header.getId(), List.of());
            faces.put(header.getId(), new VisibleFace(visible,
                    totalByDigest.getOrDefault(header.getId(), 0) - visible.size()));
        }
        return faces;
    }

    /**
     * 生效导语（getDetail 与期级 RSS 共用口径）：无失格=刊头原导语；
     * 有失格=固定模板（可见&gt;0 计数模板 / =0 空刊模板），零模型调用
     */
    private static EffectiveIntro effectiveIntro(NewsDailyDigestDO header, int visibleCount, int disqualified) {
        String zh = header.getIntroZh();
        String en = header.getIntroEn();
        if (disqualified > 0) {
            if (visibleCount == 0) {
                zh = NewsDailyDigestTemplates.emptyIntroZh(header.getDigestDate());
                en = NewsDailyDigestTemplates.emptyIntroEn(header.getDigestDate());
            } else {
                zh = NewsDailyDigestTemplates.fallbackIntroZh(header.getDigestDate(), visibleCount);
                en = NewsDailyDigestTemplates.fallbackIntroEn(header.getDigestDate(), visibleCount);
            }
        }
        return new EffectiveIntro(zh, en);
    }

    /**
     * 一期读取期复检结果：可见快照集（seq 升序）+失格条数
     */
    private record VisibleFace(List<NewsDailyDigestItemDO> visible, int disqualified) {

        NewsDailyDigestItemDO firstVisible() {
            return visible.isEmpty() ? null : visible.get(0);
        }
    }

    /**
     * 生效导语双语对（读取期零调用口径，见 effectiveIntro）
     */
    private record EffectiveIntro(String zh, String en) {
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
