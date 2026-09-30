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

package com.nageoffer.ai.ragent.news.governance;

import com.nageoffer.ai.ragent.news.controller.request.NewsTopicGovernanceApplyRequest;
import com.nageoffer.ai.ragent.news.controller.vo.NewsTopicGovernanceApplyResultVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsTopicGovernanceEventVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsTopicProposalVO;

import java.util.List;

/**
 * 主题提案治理服务（#202 三轨处置 + 别名账 + 留痕）
 *
 * <p><b>三轨</b>（票面规则合同拍板冻结）：
 * <ul>
 *   <li><b>merge 并入</b>：与 curated 主题近义 → {@code t_news_item_topic} 关联
 *       迁移至目标 topic_id（先去重同挂两主题的源侧行防复合主键冲突），提案行
 *       status='merged'（curated 保持 false），名称入别名账；</li>
 *   <li><b>promote 转正</b>：无近义 curated 目标 AND item_refs ≥ 阈值（默认 10，
 *       {@link NewsTopicGovernanceProperties} 可调）AND PolyU 检索价值 → curated=true +
 *       归入正式 topic_group + 稳定 slug 替换 prop-* 哈希（关联按 topic_id 引用，
 *       slug 变更不伤关联）；</li>
 *   <li><b>reject 弃</b>：泛化无检索价值 → status='rejected'，名称入别名账，
 *       残留 item 关联摘除（计数入留痕 detail——票面明文授权的治理摘除）。</li>
 * </ul>
 *
 * <p><b>软状态铁律</b>：t_news_topic 行一律不硬删；merge 的关联迁移=改写 topic_id
 * （行不删），reject 的关联摘除=票面授权的治理动作且计数留痕。
 *
 * <p><b>幂等</b>：批量应用可重复运行——已处于目标终态的行 SKIPPED；改判冲突
 * （终态轨别与请求不一致）整批拒绝（全批原子），软状态不自动反转，改判归维护者。
 */
public interface NewsTopicGovernanceService {

    /**
     * 待审提案列表（curated=false AND status='active'）：逐行覆盖数（item_refs）
     * 与规则建议轨别（promote/reject/review——机器侧只看引用数与阈值，
     * 近义判定归人工）。按 id 升序稳定序
     */
    List<NewsTopicProposalVO> listPendingProposals();

    /**
     * 治理留痕流水（append-only，admin 处置历史可复核）：倒序最近 N 条
     * （服务端限幅 1..200，缺省 50）
     */
    List<NewsTopicGovernanceEventVO> listGovernanceEvents(int limit);

    /**
     * 批量应用处置（按建议或人工改轨）：全批原子（任一指令非法整批拒绝），
     * 已处目标终态的行 SKIPPED（幂等重跑安全）。operator 落留痕与别名账
     */
    NewsTopicGovernanceApplyResultVO applyBatch(
            List<NewsTopicGovernanceApplyRequest.NewsTopicDisposition> dispositions, String operator);
}
