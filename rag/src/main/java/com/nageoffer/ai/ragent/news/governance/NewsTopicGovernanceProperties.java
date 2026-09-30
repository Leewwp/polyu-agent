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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 主题提案治理参数（#202，绑定 {@code rag.news.topic-governance.*}）
 *
 * <p>promote 轨的引用阈值外置可调（票面拍板：默认 10 落配置不写死）——
 * 「无近义 curated 目标 AND item_refs ≥ 阈值 AND PolyU 检索价值」三条件中，
 * 阈值条件由服务层机器校验，近义判定与检索价值归人工裁决（admin 批量应用
 * 请求逐条承载）。阈值默认值变更属规则框架变更，须回维护者裁决。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.news.topic-governance")
public class NewsTopicGovernanceProperties {

    /**
     * promote 转正所需最低条目关联数（item_refs）：低于阈值的处置请求
     * 被服务层拒绝（ClientException）——防止人工误改轨把未过线提案转正
     */
    private int promoteThreshold = 10;
}
