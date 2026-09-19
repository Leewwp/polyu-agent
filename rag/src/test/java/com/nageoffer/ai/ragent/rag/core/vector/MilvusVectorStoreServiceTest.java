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

import com.nageoffer.ai.ragent.core.chunk.model.Chunk;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkMetadata;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.UpsertResp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O7 Milvus 数据面回归：M9 批量写入 upsert 幂等；M7 删除表达式值转义；L13 chunk 删除归属限定。
 */
class MilvusVectorStoreServiceTest {

    private MilvusClientV2 milvusClient;
    private MilvusVectorStoreService service;

    @BeforeEach
    void setUp() {
        milvusClient = mock(MilvusClientV2.class);
        RAGDefaultProperties properties = new RAGDefaultProperties();
        properties.setDimension(2);
        properties.setCollectionName("rag_default_store");
        service = new MilvusVectorStoreService(milvusClient, properties);
    }

    private EmbeddedChunk chunk(String chunkId) {
        return new EmbeddedChunk(
                new Chunk(chunkId, 0, "content-" + chunkId, "text-" + chunkId, ChunkMetadata.empty()),
                new float[]{0.1F, 0.2F});
    }

    @Test
    @DisplayName("M9：批量写入走 UpsertReq——同 chunkId 重放幂等（重复主键不产生重复行）")
    void indexDocumentChunksIsUpsert() {
        UpsertResp resp = mock(UpsertResp.class);
        when(resp.getUpsertCnt()).thenReturn(2L);
        when(milvusClient.upsert(any(UpsertReq.class))).thenReturn(resp);

        service.indexDocumentChunks("kb", "doc-1", List.of(chunk("c1"), chunk("c2")));

        ArgumentCaptor<UpsertReq> captor = ArgumentCaptor.forClass(UpsertReq.class);
        verify(milvusClient).upsert(captor.capture());
        assertEquals("rag_default_store", captor.getValue().getCollectionName());
    }

    @Test
    @DisplayName("M7：库名含引号时过滤表达式值被转义，不可逃逸字面量")
    void deleteDocumentVectorsEscapesCollectionName() {
        when(milvusClient.delete(any(DeleteReq.class))).thenReturn(mock(io.milvus.v2.service.vector.response.DeleteResp.class));

        service.deleteDocumentVectors("kb\" || true || \"", "doc-1");

        ArgumentCaptor<DeleteReq> captor = ArgumentCaptor.forClass(DeleteReq.class);
        verify(milvusClient).delete(captor.capture());
        String filter = captor.getValue().getFilter();
        assertFalse(filter.contains("\" || true || \""),
                "未转义的引号会逃逸表达式字面量：" + filter);
        assertTrue(filter.contains("kb\\\" || true || \\\""),
                "值必须以转义形式出现：" + filter);
    }

    @Test
    @DisplayName("L13：单条/批量 chunk 删除均叠加 collection_name 归属条件")
    void chunkDeletesScopeCollection() {
        when(milvusClient.delete(any(DeleteReq.class))).thenReturn(mock(io.milvus.v2.service.vector.response.DeleteResp.class));

        service.deleteChunkById("kb", "c1");
        ArgumentCaptor<DeleteReq> single = ArgumentCaptor.forClass(DeleteReq.class);
        verify(milvusClient).delete(single.capture());
        assertTrue(single.getValue().getFilter().contains("collection_name == \"kb\""),
                "单条删除必须限定库：" + single.getValue().getFilter());
        assertTrue(single.getValue().getFilter().contains("id == \"c1\""));

        service.deleteChunksByIds("kb", List.of("c1", "c2"));
        ArgumentCaptor<DeleteReq> batch = ArgumentCaptor.forClass(DeleteReq.class);
        verify(milvusClient, org.mockito.Mockito.times(2)).delete(batch.capture());
        String batchFilter = batch.getValue().getFilter();
        assertTrue(batchFilter.contains("collection_name == \"kb\""));
        assertTrue(batchFilter.contains("id in ["));
    }
}
