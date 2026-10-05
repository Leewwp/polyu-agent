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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #300 用户出参剥离：arguments 只留在持久化与 trace，出参副本必须剥净且不动原件
 */
class AgentConfirmCallScrubTest {

    private AgentConfirmCall call(String arguments) {
        return AgentConfirmCall.builder()
                .toolCallId("call-1")
                .name("apply_memory_change")
                .displayName("执行记忆变更")
                .fields(List.of(AgentConfirmField.builder()
                        .name("变更内容").label("变更内容").value("清空全部长期记忆").build()))
                .arguments(arguments)
                .build();
    }

    @Test
    void scrubbedCopyStripsArgumentsButKeepsIdentityAndFields() {
        AgentConfirmCall original = call("{\n  \"operationId\": \"9d3f\"\n}");

        AgentConfirmCall scrubbed = original.scrubbedCopy();

        assertThat(scrubbed).isNotSameAs(original);
        assertThat(scrubbed.getArguments()).isNull();
        assertThat(scrubbed.getToolCallId()).isEqualTo("call-1");
        assertThat(scrubbed.getName()).isEqualTo("apply_memory_change");
        assertThat(scrubbed.getDisplayName()).isEqualTo("执行记忆变更");
        assertThat(scrubbed.getFields()).isEqualTo(original.getFields());
        // 原件原封不动：死卡检测 operationIdOf 还要从它回读
        assertThat(original.getArguments()).contains("operationId");
    }

    @Test
    void scrubbedCopyOfIsListLevelAndNullSafe() {
        assertThat(AgentConfirmCall.scrubbedCopyOf(null)).isNull();
        assertThat(AgentConfirmCall.scrubbedCopyOf(List.of())).isEmpty();

        List<AgentConfirmCall> scrubbed = AgentConfirmCall.scrubbedCopyOf(List.of(call("a"), call("b")));

        assertThat(scrubbed).hasSize(2)
                .allSatisfy(item -> assertThat(item.getArguments()).isNull());
    }

    @Test
    void clientViewOfOnlyRewritesConfirmBlocks() {
        AgentBlock confirm = AgentBlock.builder()
                .kind(AgentBlock.KIND_CONFIRM).status("pending").calls(List.of(call("a"))).build();
        AgentBlock tool = AgentBlock.builder()
                .kind(AgentBlock.KIND_TOOL).name("search_knowledge").result("命中 3 篇").build();

        List<AgentBlock> view = AgentBlock.clientViewOf(List.of(confirm, tool));

        // 非 confirm 块原样直通
        assertThat(view.get(1)).isSameAs(tool);
        // confirm 块是副本：calls 已剥、其余字段保真
        assertThat(view.get(0)).isNotSameAs(confirm);
        assertThat(view.get(0).getKind()).isEqualTo(AgentBlock.KIND_CONFIRM);
        assertThat(view.get(0).getStatus()).isEqualTo("pending");
        assertThat(view.get(0).getCalls().get(0).getArguments()).isNull();
        // 入参列表原件未被触碰
        assertThat(confirm.getCalls().get(0).getArguments()).isEqualTo("a");
    }

    @Test
    void clientViewOfIsNullSafe() {
        assertThat(AgentBlock.clientViewOf(null)).isNull();
        assertThat(AgentBlock.clientViewOf(List.of())).isEmpty();
    }
}
