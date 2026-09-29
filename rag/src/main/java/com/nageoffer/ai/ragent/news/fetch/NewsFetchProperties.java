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
 * 单源单轮 50 条、HTML_LIST 仅首页、events 当前月+下月）。历史回灌（近
 * 3 个月一次性补齐）通过命令行参数临时调大跑完即还原，日常不改动：
 * {@code --rag.news.backfill-days=95 --rag.news.max-items-per-source=500
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
     * events 型回溯过去月数（0=现行口径：当前月+下月两请求；>0 时向前多取
     * N 个月份请求，跨月活动仍防漏）
     */
    private int eventsPastMonths = 0;

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
