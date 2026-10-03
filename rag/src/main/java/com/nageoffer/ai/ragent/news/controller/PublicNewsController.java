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

package com.nageoffer.ai.ragent.news.controller;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.news.controller.vo.NewsDailyDigestSummaryVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsDailyDigestVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsHotRankEntryVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsItemVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsPageVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsTopicDetailVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsTopicVO;
import com.nageoffer.ai.ragent.news.service.NewsDailyDigestQueryService;
import com.nageoffer.ai.ragent.news.service.NewsSeoService;
import com.nageoffer.ai.ragent.news.service.NewsQueryService;
import cn.hutool.core.lang.Assert;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;

/**
 * 公开资讯只读控制器
 *
 * <p>路径在 SaTokenConfig 登录拦截白名单（/public/news/**）；资讯浏览永久免登录，
 * 与游客 Agent 对话配额（3 次/日）互不占用。孪生兜底见 PublicNewsDisabledController。
 */
@RestController
@RequestMapping("/public/news")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.news.enabled", havingValue = "true")
public class PublicNewsController {

    private final NewsQueryService newsQueryService;
    private final NewsDailyDigestQueryService dailyDigestQueryService;
    private final NewsSeoService newsSeoService;

    /**
     * 资讯流列表：category 过滤（固定 8 类，空=全部资讯态）+ 分页；
     * topics=逗号分隔主题 slug 集（「只看关注」多主题过滤，空=不过滤——服务端过滤
     * 保分页正确性，客户端过滤全局流有页内漏配判例）
     */
    @GetMapping("/list")
    public Result<NewsPageVO> list(@RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "topics", required = false) String topics,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return Results.success(newsQueryService.listPublished(category, parseTopicSlugs(topics), page, size));
    }

    /** topics 参数解析：逗号分隔 slug，trim 后滤空；全空/null 入参=不过滤（null） */
    private static List<String> parseTopicSlugs(String topics) {
        if (topics == null || topics.isBlank()) {
            return null;
        }
        List<String> slugs = Arrays.stream(topics.split(","))
                .map(String::strip)
                .filter(slug -> !slug.isEmpty())
                .toList();
        return slugs.isEmpty() ? null : slugs;
    }

    /**
     * 热点榜 Top N（故事线粒度：热度倒序+标签+信源名单，接热度模型真值；
     * 默认条数走 rag.news.hot-limit 配置）
     */
    @GetMapping("/hot")
    public Result<List<NewsHotRankEntryVO>> hot(
            @RequestParam(value = "limit", defaultValue = "${rag.news.hot-limit:10}") int limit) {
        return Results.success(newsQueryService.listHot(limit));
    }

    /**
     * 资讯详情单条：分享/直链场景可达；复用 NewsItemVO+topics 装配，
     * 仅 published 可见（下架/不存在=ClientException「资讯不存在」）
     */
    @GetMapping("/detail")
    public Result<NewsItemVO> detail(@RequestParam("id") long id) {
        return Results.success(newsQueryService.getPublishedDetail(id));
    }

    /**
     * 全局检索：标题+摘要四列 ILIKE（正文未存储不参与）；
     * sort=time 默认/relevance=标题命中优先；order=asc|desc 默认 desc（T21 两分支贯通）；
     * category 可选（T21 关键词×分类互通）；分页语义与 list 一致
     */
    @GetMapping("/search")
    public Result<NewsPageVO> search(@RequestParam("q") String q,
            @RequestParam(value = "sort", defaultValue = "time") String sort,
            @RequestParam(value = "order", defaultValue = "desc") String order,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return Results.success(newsQueryService.searchPublished(q, sort, order, category, page, size));
    }

    /**
     * MCP 出口受限检索（#182 r3 §MCP / #214）：关键词 × 主题 slug × 时间窗三元过滤，
     * 发布时间倒序、分页钳制同 list（size ≤50）。mcp-server 无数据库层（#164 裁剪），
     * search_news 工具的数据全走本口；可见性=统一公开资格（#180 R4），下架/隐藏/
     * 未过门条目隔离。三个过滤维度均可缺省，全缺省语义同 /list 首页。
     */
    @GetMapping("/mcp-search")
    public Result<NewsPageVO> mcpSearch(@RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "topic", required = false) String topic,
            @RequestParam(value = "from", required = false) LocalDate from,
            @RequestParam(value = "to", required = false) LocalDate to,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return Results.success(newsQueryService.searchPublishedForMcp(q, topic, from, to, page, size));
    }

    /**
     * 主题目录：三维分组（curated 词表，AI 提案不进目录）
     */
    @GetMapping("/topics")
    public Result<List<NewsTopicVO>> topics() {
        return Results.success(newsQueryService.listCuratedTopics());
    }

    /**
     * 主题详情：元数据+条数·更新时间统计+条目分页（近期焦点由前端按 heat 取 Top2）
     */
    @GetMapping("/topic/{slug}")
    public Result<NewsTopicDetailVO> topicDetail(@PathVariable String slug,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return Results.success(newsQueryService.getTopicDetail(slug, page, size));
    }

    // ==================== 日报出口（#212，父票 #182 r3 §日报） ====================

    /**
     * 日报目录：近 N 期（digest_date 倒序，日期+条数+导语产出方式；不携带导语
     * 正文）。零 LLM——读取面只见快照表（页面请求不触发模型调用的结构保证）
     */
    @GetMapping("/daily")
    public Result<List<NewsDailyDigestSummaryVO>> dailyList(
            @RequestParam(value = "limit", defaultValue = "30") int limit) {
        return Results.success(dailyDigestQueryService.listRecent(limit));
    }

    /**
     * 日报详情：刊头+生效导语+快照条目（读取期主动下架复检后）。零 LLM；
     * 不存在/已清理同形「日报不存在」（不泄漏存在性）
     */
    @GetMapping("/daily/{date}")
    public Result<NewsDailyDigestVO> dailyDetail(@PathVariable String date) {
        return Results.success(dailyDigestQueryService.getDetail(parseDigestDate(date)));
    }

    /**
     * 日报 RSS feed（#212 §独立 RSS feed）：RSS 2.0 原文返回（无 JSON Result
     * 包裹，沿 PublicKeyDateIcsController 文本 feed 先例）；Cache-Control 一小时
     * 是客户端节流卫生值。零 LLM——渲染从详情 VO 确定性生成
     */
    @GetMapping(value = "/daily/{date}/rss", produces = "application/rss+xml;charset=UTF-8")
    public ResponseEntity<String> dailyRss(@PathVariable String date) {
        NewsDailyDigestVO detail = dailyDigestQueryService.getDetail(parseDigestDate(date));
        Assert.notNull(detail, () -> new ClientException("日报不存在"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/rss+xml;charset=" + StandardCharsets.UTF_8))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(dailyDigestQueryService.renderRss(detail));
    }

    /**
     * 日报日期解析：YYYY-MM-DD；非法格式同形「日报不存在」（与详情缺失同口径，
     * 不向匿名访问者区分「格式错」与「无此刊」）
     */
    private static LocalDate parseDigestDate(String date) {
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new ClientException("日报不存在");
        }
    }

    /**
     * 站点级 news RSS feed（#213，feed.xml）：最近公开条目，item link=本站详情页
     * canonical URL（描述尾附原文回链）。可见性=listPublished 同页面查询面（#180 R4：
     * 隐藏条目查询层即隔离）；零 LLM。网关把根路径 /feed.xml 精确反代到本端点。
     */
    @GetMapping(value = "/feed.xml", produces = "application/rss+xml;charset=UTF-8")
    public ResponseEntity<String> newsFeed(
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/rss+xml;charset=" + StandardCharsets.UTF_8))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(newsSeoService.renderNewsFeed(Math.max(1, Math.min(limit, 100))));
    }
}
