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

package com.nageoffer.ai.ragent.agent.share;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * 会话分享快照的消息条目（隐私负面清单白名单）
 *
 * <p>每条消息保留 role / 终答或提问正文 / createTime，与用户所见逐字一致；
 * v2（issue #91）起 assistant 条目追加可选 sources 投影（检索来源徽章依据）。
 * blocks 本体（工具调用轨迹/入参/结果）、thinkingContent、durationMs、messageStatus、
 * 消息与会话 ID、userId 一律仍不进快照（issue #82 快照白名单，沿用 V1 总决议 §10
 * 公开载荷负面清单先例）。该类型同时是公开载荷 VO 的消息条目——公开读不存在超出
 * 白名单的路径。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentShareSnapshotItem {

    /**
     * user / assistant
     */
    private String role;

    /**
     * 提问或终答正文（空白正文的消息在快照构建时已自然跳过）
     */
    private String content;

    /**
     * 原消息创建时间快照
     */
    private Date createTime;

    /**
     * 检索来源投影（v2 可选，issue #91）：仅 assistant 条目，从 blocks 的
     * search_knowledge 工具块提取；user 条目与 v1 旧快照恒为 null——前端缺省
     * 即不渲染来源徽章（老链接优雅降级）
     */
    private List<AgentShareSnapshotSource> sources;
}
