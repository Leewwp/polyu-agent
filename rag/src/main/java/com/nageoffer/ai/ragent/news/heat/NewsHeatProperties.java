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

package com.nageoffer.ai.ragent.news.heat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 热度模型配置（rag.news 段的热度消费位）
 *
 * <p>source-weights 键=source_key（种子七源），缺 key 的信源权重按 0 计
 * （覆盖信源数仍计入热度基数）；story-merge-enabled=false 即进入
 * 降级态——不合并、注脚文案随降级改。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.news")
public class NewsHeatProperties {

    /**
     * 信源权重表（source_key → 权重；官网系 3 / events 3 / PRN 2 / YouTube 2）
     */
    private Map<String, Integer> sourceWeights = new LinkedHashMap<>();

    /**
     * 故事线合并开关（false=降级：不合并只计自身来源，榜单注脚文案须同步改）
     */
    private boolean storyMergeEnabled = true;
}
