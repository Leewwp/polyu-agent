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

import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts.ToolInputSummary;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入参摘要纪律（#486 验收 3）：SHA-256 digest 对完整入参 JSON 可复算；excerpt ≤256 字符截断；
 * 超大入参（>10KB）落库值不含全文。
 */
class ToolInputDigestTest {

    /** 独立于被测代码的 SHA-256 复算：期望值不来自同一实现路径 */
    private static String sha256Hex(String text) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    @Test
    void digestIsRecomputableSha256OverCanonicalJson() throws Exception {
        Map<String, Object> input = new HashMap<>();
        input.put("query", "图书馆开放时间");
        input.put("top_k", 5);

        ToolInputSummary summary = ToolInputDigest.summarize(input);

        // canonical 形态 = TreeMap 键序紧凑 JSON，第三方可按同一字符串复算
        assertThat(summary.excerpt()).isEqualTo("{\"query\":\"图书馆开放时间\",\"top_k\":5}");
        assertThat(summary.digest()).isEqualTo(sha256Hex("{\"query\":\"图书馆开放时间\",\"top_k\":5}"))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void excerptNeverExceeds256Chars() {
        Map<String, Object> input = Map.of("query", "长".repeat(5_000));

        ToolInputSummary summary = ToolInputDigest.summarize(input);

        assertThat(summary.excerpt()).hasSizeLessThanOrEqualTo(256);
    }

    @Test
    void oversizedInputNeverLandsInFull() {
        String payload = "x".repeat(12 * 1024);
        Map<String, Object> input = Map.of("query", payload);

        ToolInputSummary summary = ToolInputDigest.summarize(input);

        // 落库两字段（digest+excerpt）都不得包含 10KB 原文：digest 是定长 hex、excerpt 是截断
        assertThat(summary.digest()).hasSize(64).doesNotContain(payload);
        assertThat(summary.excerpt()).hasSizeLessThanOrEqualTo(256).doesNotContain(payload);
        // 截断确实发生在开头 256 字符处，前缀可对上
        assertThat(summary.excerpt()).startsWith("{\"query\":\"xxxxxxxx");
    }

    @Test
    void nullInputYieldsNullSummary() {
        assertThat(ToolInputDigest.summarize(null)).isNull();
    }

    @Test
    void emptyInputDigestsEmptyObject() throws Exception {
        ToolInputSummary summary = ToolInputDigest.summarize(Map.of());

        assertThat(summary.digest()).isEqualTo(sha256Hex("{}"));
        assertThat(summary.excerpt()).isEqualTo("{}");
    }

    @Test
    void nestedUnorderedKeysYieldIdenticalDigest() throws Exception {
        // 乱序构造：嵌套 Map 键序与顶层插入序都不同，canonical 化后须逐位同串
        Map<String, Object> innerA = new HashMap<>();
        innerA.put("b", 2);
        innerA.put("a", 1);
        Map<String, Object> inputA = new HashMap<>();
        inputA.put("z", innerA);
        inputA.put("filter", List.of(Map.of("k", "v")));

        Map<String, Object> innerB = new HashMap<>();
        innerB.put("a", 1);
        innerB.put("b", 2);
        Map<String, Object> inputB = new HashMap<>();
        inputB.put("filter", List.of(Map.of("k", "v")));
        inputB.put("z", innerB);

        ToolInputSummary first = ToolInputDigest.summarize(inputA);
        ToolInputSummary second = ToolInputDigest.summarize(inputB);

        assertThat(first.digest()).isEqualTo(second.digest());
        assertThat(first.excerpt()).isEqualTo(second.excerpt());
        // 递归 TreeMap 键序（含 List 内嵌套层）独立复算锁定
        assertThat(first.digest())
                .isEqualTo(sha256Hex("{\"filter\":[{\"k\":\"v\"}],\"z\":{\"a\":1,\"b\":2}}"));
    }
}
