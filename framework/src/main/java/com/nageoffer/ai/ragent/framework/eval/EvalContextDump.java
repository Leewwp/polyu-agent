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

package com.nageoffer.ai.ragent.framework.eval;

import cn.hutool.json.JSONUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实验评测埋点（#371 双配置重放回放专用，worktree 不进 main，同 2a3043ca 形态）：
 * 环境变量 RAG_EVAL_DUMP_FILE 指向 JSONL 追加文件；未设置时全部调用直返零开销。
 * 埋点失败绝不抛出、绝不影响主链路。
 */
public final class EvalContextDump {

    private static final String ENV_KEY = "RAG_EVAL_DUMP_FILE";

    private EvalContextDump() {
    }

    public static boolean enabled() {
        return envPath() != null;
    }

    /**
     * 追加一行 {"ts":..., "kind":..., "data":...}；串行化写避免交错
     */
    public static synchronized void write(String kind, Object data) {
        String path = envPath();
        if (path == null) {
            return;
        }
        try {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ts", Instant.now().toString());
            row.put("kind", kind);
            row.put("data", data);
            Files.writeString(Path.of(path), JSONUtil.toJsonStr(row) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // 静默：埋点观察者不能反噬业务链路
        }
    }

    private static String envPath() {
        String p = System.getenv(ENV_KEY);
        return p == null || p.isBlank() ? null : p;
    }
}
