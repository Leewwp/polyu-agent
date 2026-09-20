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

/**
 * 会话分享快照的检索来源条目（issue #91 快照白名单 v2）
 *
 * <p>assistant 消息 blocks 中 search_knowledge 工具块 sources 的公开投影，
 * 字段与前端 SourceRef 等价（docId/docName/excerpt/url/sourceType + 展示序号）；
 * 工具入参/结果/耗时等块内其余字段仍不进快照（隐私负面清单口径不变）。
 * 必须保持 Lombok getter/setter 形态（不可改 record）：快照列经
 * {@link AgentShareSnapshotListTypeHandler} 用 hutool 序列化，hutool 只认
 * getXxx/setXxx（AgentBlockSource 同判例）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentShareSnapshotSource {

    /**
     * 合并列表内的展示序号（1 基）
     */
    private Integer index;

    private String docId;

    private String docName;

    /**
     * 命中段落摘录
     */
    private String excerpt;

    /**
     * 来源类型（file/url）：前端徽章两态展示（url 外链原文 / file 官网下载）依赖它
     */
    private String sourceType;

    /**
     * 官网原始地址；空=前端回落站内 docId 预览
     */
    private String url;
}
