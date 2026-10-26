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

import java.util.Optional;

/**
 * Agent 会话分享范围（issue #138；#310 收敛后契约面仅两档）：full=完整对话、
 * selection=显式勾选的多轮集合（anchorAssistantMessageIds 列表，可跨轮不连续，
 * 按会话物理顺序拼接）。
 *
 * <p>请求体缺省兼容：missing/null/blank 一律按 full（旧客户端零变化）；
 * 其余非空值不在此枚举内即业务异常拒绝。值精确匹配小写字面量，不做大小写归一。
 */
public enum AgentShareScope {

    FULL,
    SELECTION;

    /**
     * 解析请求体 scope 原值：空白→full；合法两值→对应枚举；其他非空值→empty（调用方拒绝）
     */
    public static Optional<AgentShareScope> resolve(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.of(FULL);
        }
        return switch (raw) {
            case "full" -> Optional.of(FULL);
            case "selection" -> Optional.of(SELECTION);
            default -> Optional.empty();
        };
    }
}
