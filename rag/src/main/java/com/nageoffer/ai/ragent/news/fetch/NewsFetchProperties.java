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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 资讯抓取与预算参数（抓取序列的窗口/批上限+LLM 摘要预算护栏外置，#184）
 *
 * <p>绑定 {@code rag.news.*} 节，默认值=日常运营口径（14 天窗口、
 * 单源单轮 50 条、HTML_LIST 仅首页、events 未来 8 周滚动窗口按月取数拼窗，
 * #323）。历史回灌（近 3 个月一次性补齐）通过命令行参数临时调大跑完即还原，
 * 日常不改动：{@code --rag.news.backfill-days=95 --rag.news.max-items-per-source=500
 * --rag.news.fetch-pages-max=15 --rag.news.events-past-months=3}。
 *
 * <p>预算护栏（{@code rag.news.budget-*}）口径与默认值：
 * <ul>
 * <li><b>额度归属</b>：本额度为<b>资讯 LLM 调用专用独立额度</b>；全项目成本口径
 * （资讯+主链 RAG 等）另行统计，<b>不与本额度混算</b>（维护者 2026-09-29 指定）。</li>
 * <li>日 ¥1.0 / 月 <b>¥10</b>（月度=维护者 2026-09-29 指定保守默认，非红线+缓冲）。</li>
 * <li>单次发出成本=按 Tier.FAST <b>完整候选链</b>（含 fallback，配置来源
 * {@code ai.chat.tiers.fast.candidates}，现行 [qwen-flash, qwen-plus]）中<b>最贵候选</b>
 * ×完整请求限额的保守上界：输入限额=maxInputTokens(4000)+提示词开销(2000)=6000、
 * 输出限额=summaryMaxTokens(1024)；单价取百炼北京区列表价非思考档
 * （qwen-plus 0.8/2.0 元每百万 tokens）——上界=6000×0.8+1024×2 per 1M ≈ <b>¥0.006848/次</b>。</li>
 * <li>attempts=真实发出次数（含 fallback/重试）、PG 持久化重启不清零；
 * 超额当日降级=仅入库不富化，次日按剩余配额自然补偿（待补条目
 * summary_en IS NULL 常驻查询）。</li>
 * </ul>
 */
@Data
@Configuration
@ConfigurationProperties("rag.news")
public class NewsFetchProperties {

    /**
     * 回灌窗口天数：早于（now - backfillDays）的条目不入库
     */
    private int backfillDays = 14;

    /**
     * 单源单轮入库上限（批上限防长事务）
     */
    private int maxItemsPerSource = 50;

    /**
     * HTML_LIST 型抓取页深（1=仅 fetch_endpoint 首页，即现行口径；>1 时逐页
     * 翻取，空页/零有效条目自动到头停止，首页零条目仍 fail-closed）
     */
    private int fetchPagesMax = 1;

    /**
     * events 型回溯过去月数（0=现行口径；>0 时向前多取 N 个月份请求，跨月活动仍防漏）
     */
    private int eventsPastMonths = 0;

    /**
     * events 型未来滚动窗口周数（#323，票面=8）：按月取数拼窗——月份集合=覆盖
     * 「今天起 N 周」所需的全部月份（2 或 3 个月，跨年自然拼接），跨月条目按
     * 规范化 URL 去重；fail-closed 口径不随窗口放大（仅当前月+下月维持零条目
     * 结构守卫，+2 月及以后零排期属正常宽和收空）
     */
    private int eventsWindowWeeks = 8;

    // ================== 准入与状态合同（#185，父票 #180 §2/§3） ==================
    //
    // 源分级（tier）语义约定（本票只定语义不落 seed，列与数据归 #188）：
    //   T1=核心一手源（官方博客/研究机构主站），T2=次级聚合/专题源——tier 是
    //   「信源质量分级」，独立于 t_news_source.official（后者=polyu.edu.hk 官网
    //   校园语义，两者正交：AI 源 tier=T1 时 official=false）。
    //   准入影响：tier 不改变本类的公平轮转（每源每轮一票），仅作为 #188 启源时
    //   配置 per-source 日准入上限的依据（#180 §1 冻结清单按源给出 10/20）。

    /**
     * 全站新准入日上限（#185 硬合同）：含既有源在内，每日（HKT）进入管线
     * （status=pending）的新条目 ≤ 本值。19 新增源的局部上限合计 200 只是局部
     * 上限，不覆盖本值。默认 60=与富化能力（20 条×3 轮/日）匹配的分批放量；
     * 调整须同步输入/预算/资源回放并走扩量门
     */
    private int admissionDailySiteCap = 60;

    /**
     * 源日准入默认上限（#185）：#180 §1 冻结清单外的源（现行 11 个既有源）适用，
     * 沿用既有单源批上限量级（50/日）保持校园源行为不变；清单内源由
     * {@link #admissionSourceDailyCaps} 按 sourceKey 覆盖（#188 启源时配置 10/20）
     */
    private int admissionDefaultSourceDailyCap = 50;

    /**
     * 源日准入上限表（sourceKey → 条/日，HKT）：#180 §1 冻结清单的每源上限
     * （如 ai-openai-news=10、ai-arxiv-rss=20）——命中者覆盖默认值；
     * 本表只是局部上限，全站仍受 {@link #admissionDailySiteCap} 约束
     */
    private Map<String, Integer> admissionSourceDailyCaps = new LinkedHashMap<>();

    /**
     * 八校确定性门生效的 source_key 集（#277）：仅指定源走原始标题/原摘要白名单，
     * 其余源（含全部官网源）不经门——「只对指定新 source_key 生效」红线。
     */
    private java.util.Set<String> gateSourceKeys = java.util.Set.of(
            "scmp-education", "rthk-local-news", "gia-news");

    /**
     * 八校白名单·无歧义组（#277）：任意语境可命中的全名/明确缩写/特指简称
     * （配置可整组覆盖；默认与 EightUniversityGate.withDefaults 一致）
     */
    private java.util.List<String> unambiguousTerms = java.util.List.of(
            "University of Hong Kong", "Chinese University of Hong Kong",
            "Hong Kong University of Science and Technology",
            "Hong Kong Polytechnic University", "Polytechnic University",
            "Hong Kong Baptist University", "Education University of Hong Kong",
            "HKU", "HKUST", "CUHK", "HKBU", "EdUHK", "PolyU",
            "香港大學", "香港大学", "香港中文大學", "香港中文大学",
            "香港科技大學", "香港科技大学", "香港理工大學", "香港理工大学",
            "香港浸會大學", "香港浸会大学", "香港城市大學", "香港城市大学",
            "香港教育大學", "香港教育大学", "嶺南大學", "岭南大学",
            "港大", "理大", "浸大", "教大");

    /**
     * 八校白名单·歧义组（#277）：需文本另有 Hong Kong/HK/香港 语境才命中
     * （CityU/Lingnan/城市大学/中大/科大/城大/岭南——防境外同名与泛指误命中）
     */
    private java.util.List<String> contextualTerms = java.util.List.of(
            "CityU", "Lingnan", "City University", "城市大學", "城市大学",
            "中大", "科大", "城大", "嶺南", "岭南");

    /** 八校门单例（词表配置解析后构造） */
    private transient volatile com.nageoffer.ai.ragent.news.gate.EightUniversityGate eightUniversityGate;

    /** 门是否对该 sourceKey 生效（#277） */
    public boolean gateAppliesTo(String sourceKey) {
        return sourceKey != null && gateSourceKeys.contains(sourceKey);
    }

    /** 惰性构造门（词表配置可 yaml 覆盖；线程安全 double-checked 足够——只读） */
    public com.nageoffer.ai.ragent.news.gate.EightUniversityGate eightUniversityGate() {
        com.nageoffer.ai.ragent.news.gate.EightUniversityGate gate = eightUniversityGate;
        if (gate == null) {
            synchronized (this) {
                gate = eightUniversityGate;
                if (gate == null) {
                    gate = new com.nageoffer.ai.ragent.news.gate.EightUniversityGate(unambiguousTerms, contextualTerms);
                    eightUniversityGate = gate;
                }
            }
        }
        return gate;
    }

    /**
     * 旧文归档阈值（小时，#185）：发现时原文发布时间早于 now-本值 → archived
     * 终态（不进「今天」、跳过付费富化、不计日准入）。lastmod=修改时间不得
     * 冒充首发时间（解析侧空发布时间已过滤，服务侧 null 一律不入库）
     */
    private int staleArticleHours = 48;

    /**
     * 待富化 TTL（小时，#185）：从首次发现（fetch_time）起算，超期未获发布资格
     * 的 pending 条目转 expired 终态并退出待办；同 URL 重现不重建付费待办。
     * 不承诺无条件次日清空
     */
    private int pendingTtlHours = 48;

    /**
     * 发布门时长（秒，#185）：从发布资格就绪（eligible_time）起算的公开延迟，
     * 统一公开资格查询侧判据（见 {@link com.nageoffer.ai.ragent.news.dao.entity.NewsItemStatus}）
     */
    private int publishGateSeconds = 180;

    // ================== 日报（#212，父票 #182 r3 §日报——P2-a 出口） ==================

    /**
     * 日报单刊快照条数硬上界：防御性容量护栏（防历史回灌日把单刊撑爆），
     * <b>不是选材过滤器</b>——确定性选材冻结口径=「全部动态」（窗口内全部
     * 公开资格条目，无 tier/配额/top-N 精选；容量合同 ≤60 条/日新准入，
     * 默认 200 已远超日常量级，触界=异常态按 seq 截断并 WARN）
     */
    private int digestMaxItems = 200;

    /**
     * 日报漏跑回补窗口（HKT 日，含当日）：每日调度时检查窗口内缺失日期并补跑
     * （已存在的刊不自动重建——重建只由显式 rebuild 触发）；非正回退 2
     * （当日+昨日，覆盖单日宕机场景）
     */
    private int digestBackfillDays = 2;

    /**
     * 日报 RSS 频道站点基准 URL（channel/link 与条目 guid 前缀）——RSS 规范要求
     * 绝对 URL；默认线上域名，部署异构时外置覆盖
     */
    private String digestRssSiteUrl = "https://polyuguide.com";

    // ================== 日报校历关键日期栏目（#316，总纲 #315 线一 L1） ==================

    /**
     * 关键日期栏目窗口天数（含刊日当天的历日数）：窗口=[D, D+N-1] 含端，
     * date_start 或 date_end 落窗的 published 事件入选；纯数据零 LLM，
     * 供给与资讯量解耦（空刊保底栏目）
     */
    private int digestKeyDateWindowDays = 14;

    /**
     * 关键日期栏目最大条数：超限取最近（date_start 升序截断）；非正回退 8
     */
    private int digestKeyDateMaxEntries = 8;

    /**
     * 关键日期栏目窗口天数有效值（含端历日数，非正回退 14）
     */
    public int effectiveDigestKeyDateWindowDays() {
        return digestKeyDateWindowDays > 0 ? digestKeyDateWindowDays : 14;
    }

    /**
     * 关键日期栏目最大条数有效值（非正回退 8）
     */
    public int effectiveDigestKeyDateMaxEntries() {
        return digestKeyDateMaxEntries > 0 ? digestKeyDateMaxEntries : 8;
    }

    // ================== 日报校园活动版面（#330，父票 #317——总纲 #315 线一 L2） ==================

    /**
     * 活动版面窗口天数（含刊日当天的历日数）：窗口=[D, D+N-1] 含端，活动区间
     * （publish_time 活动开始 → activity_end_time 活动结束，HKT 历日）与窗口
     * 任一历日重叠即入选；默认 56=8 周（与 events 抓取扩窗同口径，#323）；
     * 纯数据零 LLM，供给与资讯量解耦（空刊保底版面）
     */
    private int digestActivityWindowDays = 56;

    /**
     * 活动版面最大条数：超限取最近（date_start 升序截断）；非正回退 10
     */
    private int digestActivityMaxEntries = 10;

    /**
     * 活动版面窗口天数有效值（含端历日数，非正回退 56）
     */
    public int effectiveDigestActivityWindowDays() {
        return digestActivityWindowDays > 0 ? digestActivityWindowDays : 56;
    }

    /**
     * 活动版面最大条数有效值（非正回退 10）
     */
    public int effectiveDigestActivityMaxEntries() {
        return digestActivityMaxEntries > 0 ? digestActivityMaxEntries : 10;
    }

    /**
     * 日报单刊快照条数硬上界有效值（非正回退 200）
     */
    public int effectiveDigestMaxItems() {
        return digestMaxItems > 0 ? digestMaxItems : 200;
    }

    /**
     * 日报漏跑回补窗口有效值（HKT 日数，非正回退 2）
     */
    public int effectiveDigestBackfillDays() {
        return digestBackfillDays > 0 ? digestBackfillDays : 2;
    }

    /**
     * 日报 RSS 站点基准 URL 有效值（空白回退默认域名）
     */
    public String effectiveDigestRssSiteUrl() {
        return digestRssSiteUrl == null || digestRssSiteUrl.isBlank()
                ? "https://polyuguide.com" : digestRssSiteUrl.strip();
    }

    // ================== 站点出口 SEO（#213，父票 #182 r3 §RSS、发现面——P2-b） ==================

    /**
     * 站点 canonical 基准 URL（feed.xml 条目链接/sitemap loc/IndexNow 提交共用的
     * 绝对 URL 前缀）。与 {@link #digestRssSiteUrl} 同默认值但语义分离：本键是站点级
     * canonical 面（所有出口一致），日报键是 #212 期刊 RSS 专有——两键暂不同并，
     * 部署异构时须同步覆盖
     */
    private String siteBaseUrl = "https://polyuguide.com";

    /**
     * IndexNow 提交开关（#213，票面已批协议）：默认开；关=调度钩零调用（幂等可灰度）
     */
    private boolean indexnowEnabled = true;

    /**
     * IndexNow key（协议要求自生成、无账号注册）：与站点根静态键文件
     * frontend/public/{key}.txt 同源同值——key 明文进公开静态文件是协议设计
     * （验证提交者控制该站点），非泄密
     */
    private String indexnowKey = "ee751b74b79272cc9e40f864450cbca4";

    /**
     * IndexNow 提交端点（官方公共端点；后续如换自托管再外置）
     */
    private String indexnowEndpoint = "https://api.indexnow.org/indexnow";

    /**
     * 站点 canonical 基准 URL 有效值（空白回退默认域名；去尾斜杠防拼接双斜杠）
     */
    public String effectiveSiteBaseUrl() {
        String value = siteBaseUrl == null || siteBaseUrl.isBlank()
                ? "https://polyuguide.com" : siteBaseUrl.strip();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * IndexNow key 有效值（空白=视为未配置，提交跳过并 WARN——缺 key 的提交必被
     * 搜索引擎拒收，不如本侧先行短路）
     */
    public String effectiveIndexnowKey() {
        return indexnowKey == null ? "" : indexnowKey.strip();
    }

    /**
     * IndexNow 端点有效值（空白回退官方公共端点）
     */
    public String effectiveIndexnowEndpoint() {
        return indexnowEndpoint == null || indexnowEndpoint.isBlank()
                ? "https://api.indexnow.org/indexnow" : indexnowEndpoint.strip();
    }

    // ================== 源治理：成功分类学/允许空/停用原因/探活（#186，父票 #181 §2） ==================

    /**
     * 允许零条目为健康结果的源（sourceKey 集，#186）：解析结构有效但零条目 →
     * VALID_EMPTY（有效空或无新增，源健康、探活可复归）；未列入的源零条目仍
     * fail-closed → STRUCTURE_MISMATCH。逐源外置=同一策略下不同源的空态语义
     * 由运营口径决定（如新频道空 feed 属正常，官网列表空页=模板改版嫌疑）
     */
    private java.util.Set<String> allowEmptySources = new java.util.LinkedHashSet<>();

    /**
     * 自动隔离源探活：复归所需连续有效完整成功次数（#186 票面=两次；非正回退 2）
     */
    private int probeRequiredSuccesses = 2;

    /**
     * 探活成功连续窗口（小时，#186 票面 ≤48h）：相邻两次有效完整成功间隔超过
     * 本值视为不连续（streak 重起）；非正回退 48
     */
    private int probeSuccessWindowHours = 48;

    /**
     * robots.txt 进程内缓存 TTL（秒，#186 修「缓存永不过期」）：过期后对同 host 的
     * 下一次请求重拉 robots（自身仍走 host 节拍）——robots 规则变更（含 Disallow
     * 解除）可在 TTL 内被观测到；非正回退 86400（24h）
     */
    private long robotsCacheTtlSeconds = 86400L;

    /**
     * 源是否允许零条目为健康结果（allow-empty 判定）
     */
    public boolean isAllowEmptySource(String sourceKey) {
        return sourceKey != null && allowEmptySources.contains(sourceKey);
    }

    /**
     * 探活复归所需连续成功次数有效值（非正回退 2）
     */
    public int effectiveProbeRequiredSuccesses() {
        return probeRequiredSuccesses > 0 ? probeRequiredSuccesses : 2;
    }

    /**
     * 探活成功连续窗口有效值（小时，非正回退 48）
     */
    public int effectiveProbeSuccessWindowHours() {
        return probeSuccessWindowHours > 0 ? probeSuccessWindowHours : 48;
    }

    /**
     * events 未来滚动窗口周数有效值（非正回退 8，#323 票面口径）
     */
    public int effectiveEventsWindowWeeks() {
        return eventsWindowWeeks > 0 ? eventsWindowWeeks : 8;
    }

    /**
     * robots 缓存 TTL 有效值（毫秒，非正回退 24h）
     */
    public long effectiveRobotsCacheTtlMillis() {
        return robotsCacheTtlSeconds > 0 ? robotsCacheTtlSeconds * 1000L : 86400_000L;
    }

    // ================== LLM 摘要预算护栏（#184，父票 #181 §1 合同） ==================

    /**
     * 资讯摘要日额度（元，HKT 日切）：超额当日降级=仅入库不富化，次日补偿。
     * 口径：资讯 LLM 调用专用独立额度，与全项目成本（资讯+主链 RAG 等）分开统计不混算
     */
    private double budgetDailyYuan = 1.0D;

    /**
     * 资讯摘要月额度（元，HKT 月切）：维护者 2026-09-29 指定保守默认（≤¥10/月）
     */
    private double budgetMonthlyYuan = 10.0D;

    /**
     * 摘要输出 token 上限：ChatRequest.maxTokens 透传（底层已支持 max_tokens）
     */
    private int summaryMaxTokens = 1024;

    /**
     * 送 LLM 的正文输入 token 上限（估算口径：CJK 字 1 token、其余 4 字符 1 token）
     */
    private int maxInputTokens = 4000;

    /**
     * 提示词开销 token 预算（模板指令+动态主题词表+标题行，保守 2000）——
     * 与 {@link #maxInputTokens} 合成单次输入限额参与成本上界推导
     */
    private int budgetPromptOverheadTokens = 2000;

    /**
     * 候选模型单价表（元/百万 tokens，百炼北京区列表价非思考档；来源
     * docs.bailian.console.aliyun.com 模型价格页，2026-09-29 核对）——
     * 单次成本上界=FAST 链内<b>最贵已配价候选</b>×完整请求限额；
     * 链内出现未配价候选时按表内最贵单价兜底并 WARN（不拍脑袋放大）
     */
    private Map<String, ModelPrice> budgetModelPrices = new LinkedHashMap<>(Map.of(
            "qwen-flash", new ModelPrice(0.5D, 2.0D),
            "qwen-plus", new ModelPrice(0.8D, 2.0D)));

    /**
     * 同请求网关层重试上限（≤2，不含首次；路由 fallback 不算网关重试）——
     * 口径为<b>同指纹累计</b>：跨调度/重启后从回执 retries 续算，耗尽即不再重试
     */
    private int llmMaxRetries = 2;

    // ================== 有效值推导（单一事实源：富化截断/预算成本上界共用同一口径） ==================

    /**
     * 全站新准入日上限有效值（非正配置回退默认 60）
     */
    public int effectiveAdmissionDailySiteCap() {
        return admissionDailySiteCap > 0 ? admissionDailySiteCap : 60;
    }

    /**
     * 源日准入上限有效值：sourceKey 命中 {@link #admissionSourceDailyCaps} 取其值
     * （非正值视为未配置），否则取 {@link #admissionDefaultSourceDailyCap}（非正回退 50）
     */
    public int effectiveSourceDailyCap(String sourceKey) {
        Integer configured = sourceKey == null ? null : admissionSourceDailyCaps.get(sourceKey);
        if (configured != null && configured > 0) {
            return configured;
        }
        return admissionDefaultSourceDailyCap > 0 ? admissionDefaultSourceDailyCap : 50;
    }

    /**
     * 旧文归档阈值有效值（小时，非正回退默认 48）
     */
    public int effectiveStaleArticleHours() {
        return staleArticleHours > 0 ? staleArticleHours : 48;
    }

    /**
     * 待富化 TTL 有效值（小时，非正回退默认 48）
     */
    public int effectivePendingTtlHours() {
        return pendingTtlHours > 0 ? pendingTtlHours : 48;
    }

    /**
     * 发布门时长有效值（秒，非正回退默认 180）
     */
    public int effectivePublishGateSeconds() {
        return publishGateSeconds > 0 ? publishGateSeconds : 180;
    }

    /**
     * 正文输入 token 上限有效值（非正配置回退默认 4000）
     */
    public int effectiveMaxInputTokens() {
        return maxInputTokens > 0 ? maxInputTokens : 4000;
    }

    /**
     * 摘要输出 token 上限有效值（非正配置回退默认 1024）——ChatRequest.maxTokens
     * 与成本上界的输出限额同源（调整配置即同步调整成本模型）
     */
    public int effectiveSummaryMaxTokens() {
        return summaryMaxTokens > 0 ? summaryMaxTokens : 1024;
    }

    /**
     * 完整渲染请求输入限额有效值=正文输入上限+提示词开销（非正配置各自回退默认）——
     * 富化侧完整 prompt 收口与预算侧成本上界推导共用（口径漂移即预算失真）
     */
    public int effectiveInputQuotaTokens() {
        return effectiveMaxInputTokens() + (budgetPromptOverheadTokens > 0 ? budgetPromptOverheadTokens : 2000);
    }

    /**
     * 候选模型单价（元/百万 tokens，非思考档）
     */
    @Data
    public static class ModelPrice {

        /**
         * 输入单价（元/百万 tokens）
         */
        private double inputYuanPerM;

        /**
         * 输出单价（元/百万 tokens）
         */
        private double outputYuanPerM;

        public ModelPrice() {
        }

        public ModelPrice(double inputYuanPerM, double outputYuanPerM) {
            this.inputYuanPerM = inputYuanPerM;
            this.outputYuanPerM = outputYuanPerM;
        }
    }
}
