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

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实验埋点（#371 双配置重放专用，worktree 不进 main，同 9033731c 形态）：
 * 启动就绪后把 code/镜像/语料版本 env 落一行 runtime_meta 进 RAG_EVAL_DUMP_FILE，
 * 供采集产物与代码/语料版本对锚。env 由启动脚本注入（R371_* 键族）。
 * 未注入的列落 "unset"（如实标缺，不推测）。
 */
@Component
public class G1RuntimeMetaDumper {

    static final String[] META_ENV_KEYS = {
            "R371_CODE_SHA", "R371_STACK", "R371_CODE_DIRTY", "R371_STARTED_AT",
            "R371_DB", "R371_REDIS", "R371_ES_INDEX"
    };

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!EvalContextDump.enabled()) {
            return;
        }
        EvalContextDump.write("runtime_meta", buildMetaMap(System::getenv));
    }

    /**
     * 包内可见+纯函数：env 未注入列=unset、注入列原样透传。
     */
    static Map<String, Object> buildMetaMap(java.util.function.Function<String, String> envLookup) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dump_schema", "r371-runtime-meta-v1");
        for (String key : META_ENV_KEYS) {
            String v = envLookup.apply(key);
            m.put(key.toLowerCase(), v == null || v.isBlank() ? "unset" : v);
        }
        m.put("jvm", System.getProperty("java.version", ""));
        m.put("spring_profiles", System.getProperty("spring.profiles.active", ""));
        return m;
    }
}
