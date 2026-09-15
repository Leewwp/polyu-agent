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

package com.nageoffer.ai.ragent.agent.dao.handler;

import cn.hutool.json.JSONUtil;
import com.nageoffer.ai.ragent.agent.dto.AgentBlock;
import com.nageoffer.ai.ragent.agent.dto.AgentBlockSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * blocks 列的 hutool 序列化契约：sources 项必须带出 docId/docName/excerpt。
 * 历史缺陷（2026-09-15 浏览器走查逮到）：AgentBlockSource 曾是 record，hutool
 * 只认 getXxx，落库写成空对象 {} —— 回放徽章「5 篇来源」点开全是 /preview/doc/null。
 * SSE 侧走 Jackson 不受影响；本测试钉死 DAO 边界的往返形态
 */
class AgentBlockSourceTypeHandlerContractTest {

    @Test
    void hutoolRoundTripKeepsSourceFields() {
        AgentBlock block = AgentBlock.builder()
                .kind("tool")
                .name("search_knowledge")
                .status("done")
                .sources(List.of(AgentBlockSource.builder()
                        .docId("2096534451851935744")
                        .docName("Student_Handbook_2026-27_English.pdf")
                        .excerpt("Sports Facilities…")
                        .sourceType("file")
                        .url("https://www.polyu.edu.hk/ar/student-handbook/")
                        .build()))
                .build();

        String json = JSONUtil.toJsonStr(List.of(block));
        assertThat(json).contains("\"docId\":\"2096534451851935744\"");
        assertThat(json).contains("Student_Handbook");
        assertThat(json).contains("\"sourceType\":\"file\"");
        assertThat(json).contains("polyu.edu.hk/ar/student-handbook");

        List<AgentBlock> parsed = JSONUtil.toList(json, AgentBlock.class);
        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).getSources()).hasSize(1);
        assertThat(parsed.get(0).getSources().get(0).getDocId()).isEqualTo("2096534451851935744");
        assertThat(parsed.get(0).getSources().get(0).getDocName()).contains("Student_Handbook");
        assertThat(parsed.get(0).getSources().get(0).getExcerpt()).isEqualTo("Sports Facilities…");
        // 两态字段（doc 32 决策一）必须过 hutool 往返——丢字段=回放退化站内预览形态
        assertThat(parsed.get(0).getSources().get(0).getSourceType()).isEqualTo("file");
        assertThat(parsed.get(0).getSources().get(0).getUrl())
                .isEqualTo("https://www.polyu.edu.hk/ar/student-handbook/");
    }
}
