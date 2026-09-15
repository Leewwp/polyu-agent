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

package com.nageoffer.ai.ragent.agent.tool;

import com.nageoffer.ai.ragent.agent.dto.AgentBlockSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolSourceStashTest {

    @AfterEach
    void cleanUp() {
        AgentToolSourceStash.reset();
    }

    @Test
    void putThenTakeReturnsSourcesAndRemovesEntry() {
        List<AgentBlockSource> sources = List.of(new AgentBlockSource("doc-1", "图书馆指南", "开放时间…"));
        AgentToolSourceStash.put("call-1", sources);

        assertThat(AgentToolSourceStash.take("call-1")).isEqualTo(sources);
        // 取出即移除：第二次取为空
        assertThat(AgentToolSourceStash.take("call-1")).isNull();
    }

    @Test
    void blankKeyOrEmptySourcesIsIgnored() {
        AgentToolSourceStash.put(null, List.of(new AgentBlockSource("doc-1", "n", "e")));
        AgentToolSourceStash.put(" ", List.of(new AgentBlockSource("doc-1", "n", "e")));
        AgentToolSourceStash.put("call-2", List.of());

        assertThat(AgentToolSourceStash.take(null)).isNull();
        assertThat(AgentToolSourceStash.take(" ")).isNull();
        assertThat(AgentToolSourceStash.take("call-2")).isNull();
    }

    @Test
    void takeUnknownKeyReturnsNull() {
        assertThat(AgentToolSourceStash.take("no-such-call")).isNull();
    }

    @Test
    void overflowClearsAllEntries() {
        List<AgentBlockSource> sources = List.of(new AgentBlockSource("doc-1", "n", "e"));
        IntStream.rangeClosed(1, 512).forEach(i -> AgentToolSourceStash.put("call-" + i, sources));

        // 第 513 次 put 触顶清空：连最早那条也一起没了，只留新放进来的
        AgentToolSourceStash.put("call-513", sources);

        assertThat(AgentToolSourceStash.take("call-1")).isNull();
        assertThat(AgentToolSourceStash.take("call-513")).isEqualTo(sources);
    }
}
