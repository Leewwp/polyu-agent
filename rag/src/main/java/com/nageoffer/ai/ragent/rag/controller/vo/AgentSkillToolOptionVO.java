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

package com.nageoffer.ai.ragent.rag.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 技能可引用的工具选项，来自意图树里已启用的 MCP 节点
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentSkillToolOptionVO {

    private String toolId;

    /**
     * 意图节点上配的展示名
     */
    private String name;

    private String description;

    /**
     * 执行前是否需要用户确认，用于在选择器上标出写操作
     */
    private Boolean requireConfirm;

    /**
     * MCP 注册表里是否有对应执行器，为 false 说明服务没起或工具已下线
     */
    private Boolean available;

    /**
     * 已被哪个技能收为解锁项，未被收走时为空
     * 同一工具可被多个技能共用，这里只报第一个，够提示「它已经归技能管了」
     */
    private String referencedBySkillName;
}
