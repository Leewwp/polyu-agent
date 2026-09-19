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

package com.nageoffer.ai.ragent.rag.core.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.model.Chunk;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkMetadata;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * O7 数据面修复回归：M9 批量写入 ON CONFLICT 幂等；L13 chunk 删除叠加 collection_name 归属。
 */
class PgVectorStoreServiceTest {

    private JdbcTemplate jdbcTemplate;
    private PgVectorStoreService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new PgVectorStoreService(jdbcTemplate, new ObjectMapper());
    }

    private EmbeddedChunk chunk(String chunkId) {
        return new EmbeddedChunk(
                new Chunk(chunkId, 0, "content-" + chunkId, "text-" + chunkId, ChunkMetadata.empty()),
                new float[]{0.1F, 0.2F});
    }

    @Test
    @DisplayName("M9：批量写入带 ON CONFLICT (id) DO UPDATE——同 chunkId 重放幂等")
    @SuppressWarnings("unchecked")
    void indexDocumentChunksIsUpsert() {
        service.indexDocumentChunks("kb", "doc-1", List.of(chunk("c1"), chunk("c2")));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).batchUpdate(sqlCaptor.capture(), any(List.class), anyInt(), any());
        assertTrue(sqlCaptor.getValue().contains("ON CONFLICT (id) DO UPDATE"),
                "批量写入必须是 upsert：" + sqlCaptor.getValue());
    }

    @Test
    @DisplayName("L13：单条 chunk 删除叠加 collection_name 归属条件")
    void deleteChunkByIdScopesCollection() {
        service.deleteChunkById("kb", "c1");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        assertTrue(sqlCaptor.getValue().contains("id = ? AND collection_name = ?"),
                "单条删除必须限定库：" + sqlCaptor.getValue());
    }

    @Test
    @DisplayName("L13：批量 chunk 删除叠加 collection_name 归属条件（参数尾位为库名）")
    void deleteChunksByIdsScopesCollection() {
        service.deleteChunksByIds("kb", List.of("c1", "c2"));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), argsCaptor.capture());
        assertTrue(sqlCaptor.getValue().contains("AND collection_name = ?"),
                "批量删除必须限定库：" + sqlCaptor.getValue());
        Object[] args = argsCaptor.getValue();
        assertEquals("kb", args[args.length - 1], "collectionName 必须作为参数绑定而非拼接");
    }
}
