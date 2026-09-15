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

package com.nageoffer.ai.ragent.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具块来源：站内原文走既有 /preview/doc/{docId} 路由，不重复承载外链。
 * <p>
 * 必须保持 Lombok getter/setter 形态（不可改 record）：blocks 列经
 * {@code AgentBlockListTypeHandler} 用 hutool 序列化落库，hutool 只认
 * getXxx/setXxx——record 存取器（docId()）会被写成空对象 {}，回放徽章即丢
 * docId（SSE 侧走 Jackson 不受影响，两者形态必须兼容同一 JSON 字段名）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentBlockSource {

    private String docId;

    private String docName;

    private String excerpt;
}
