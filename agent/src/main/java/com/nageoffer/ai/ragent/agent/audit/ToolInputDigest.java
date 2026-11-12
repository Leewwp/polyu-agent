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

package com.nageoffer.ai.ragent.agent.audit;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts.ToolInputSummary;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * 工具入参摘要（#486 纪律）：SHA-256 digest（对完整入参 canonical JSON 计算，可复算）
 * + 截断脱敏 excerpt（≤256 字符），禁全文落库。
 * canonical JSON = TreeMap 键序紧凑串（hutool 序列化），同一入参恒得同一摘要。
 */
public final class ToolInputDigest {

    /**
     * excerpt 截断上限（字符）
     */
    public static final int EXCERPT_MAX_CHARS = 256;

    private ToolInputDigest() {
    }

    /**
     * 入参为 null 不产出摘要（无调用入参可言）；空 Map 摘 "{}"
     */
    public static ToolInputSummary summarize(Map<String, Object> input) {
        if (input == null) {
            return null;
        }
        String canonical = JSONUtil.toJsonStr(new TreeMap<>(input));
        return new ToolInputSummary(sha256Hex(canonical), StrUtil.sub(canonical, 0, EXCERPT_MAX_CHARS));
    }

    private static String sha256Hex(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // JVM 规范保证 SHA-256 存在，此处不可达
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
