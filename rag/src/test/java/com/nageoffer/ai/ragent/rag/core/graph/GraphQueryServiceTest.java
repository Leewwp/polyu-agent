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

package com.nageoffer.ai.ragent.rag.core.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M6 回归：图可视化的库范围过滤按 GraphFileSource.parse + 全名等值，
 * 前缀相邻知识库（kb 与 kb_hr）不得互相串库。
 */
class GraphQueryServiceTest {

    private LightRagClient client;
    private GraphQueryService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = mock(LightRagClient.class);
        ObjectProvider<LightRagClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        service = new GraphQueryService(provider);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode graphNode(String id, String filePath) {
        com.fasterxml.jackson.databind.node.ObjectNode node = mapper.createObjectNode();
        node.put("id", id);
        com.fasterxml.jackson.databind.node.ObjectNode props = node.putObject("properties");
        props.put("file_path", filePath);
        props.put("entity_id", id);
        return node;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode graph(String... filePaths) {
        com.fasterxml.jackson.databind.node.ObjectNode root = mapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode nodes = root.putArray("nodes");
        for (String filePath : filePaths) {
            nodes.add(graphNode(filePath.substring(0, filePath.lastIndexOf('_')), filePath));
        }
        root.put("is_truncated", false);
        return root;
    }

    @Test
    @DisplayName("M6：前缀相邻库名不串库——查 kb 不命中 kb_hr_*")
    void prefixAdjacentCollectionsDoNotLeak() {
        when(client.fetchGraph(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(graph("kb_123.txt", "kb_hr_456.txt", "kb_789.txt"));

        var view = service.getGraph(null, "kb", null, 2, 200);

        assertEquals(2, view.getNodes().size());
        assertTrue(view.getNodes().stream().allMatch(n -> !n.getId().contains("hr")));
    }

    @Test
    @DisplayName("M6：文档级过滤按 docId 等值，不含 docId 的来源不混入")
    void docLevelFilterMatchesByEquality() {
        when(client.fetchGraph(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(graph("kb_123.txt", "kb_456.txt"));

        var view = service.getGraph(null, "kb", "123", 2, 200);

        assertEquals(1, view.getNodes().size());
        assertEquals("kb", view.getNodes().get(0).getId());
    }

    @Test
    @DisplayName("无范围过滤时不过滤；有范围时向服务端拉宽到上限")
    void noScopeKeepsAllAndScopedFetchesWide() {
        when(client.fetchGraph(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(graph("kb_1.txt", "kb2_2.txt"));

        service.getGraph(null, null, null, 2, 50);
        ArgumentCaptor<Integer> wideCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(client).fetchGraph(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), wideCaptor.capture());
        assertEquals(50, wideCaptor.getValue(), "无过滤时按展示上限拉取");

        service.getGraph(null, "kb", null, 2, 50);
        verify(client, org.mockito.Mockito.times(2)).fetchGraph(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), wideCaptor.capture());
        assertEquals(1000, wideCaptor.getValue(), "有范围过滤时拉宽到服务端上限");
    }
}
