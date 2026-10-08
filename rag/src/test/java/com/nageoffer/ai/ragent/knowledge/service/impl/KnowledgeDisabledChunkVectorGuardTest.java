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

package com.nageoffer.ai.ragent.knowledge.service.impl;

import com.nageoffer.ai.ragent.core.chunk.model.Chunk;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.core.ingest.VectorTarget;
import com.nageoffer.ai.ragent.core.ingest.embed.ChunkEmbeddingService;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.infra.token.TokenCounterService;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.support.VectorTargetResolver;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上游 #156 收编行为回归：禁用状态的 chunk/文档不得再进向量库——
 * 一旦写入，检索层会召回不允许被检索到的向量。守卫落点=update() 的向量同步段与 startChunk() 入口。
 */
class KnowledgeDisabledChunkVectorGuardTest {

    private static final String DOC_ID = "doc-1";
    private static final String CHUNK_ID = "chunk-1";
    private static final String KB_ID = "kb-1";

    private KnowledgeChunkMapper chunkMapper;
    private KnowledgeDocumentMapper documentMapper;
    private KnowledgeBaseMapper knowledgeBaseMapper;
    private ChunkEmbeddingService chunkEmbeddingService;
    private VectorTargetResolver vectorTargetResolver;
    private VectorStoreService vectorStoreService;
    private KnowledgeChunkServiceImpl service;

    @BeforeEach
    void setUp() {
        chunkMapper = mock(KnowledgeChunkMapper.class);
        documentMapper = mock(KnowledgeDocumentMapper.class);
        knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        chunkEmbeddingService = mock(ChunkEmbeddingService.class);
        vectorTargetResolver = mock(VectorTargetResolver.class);
        vectorStoreService = mock(VectorStoreService.class);
        service = new KnowledgeChunkServiceImpl(
                chunkMapper,
                documentMapper,
                knowledgeBaseMapper,
                chunkEmbeddingService,
                vectorTargetResolver,
                mock(TokenCounterService.class),
                vectorStoreService,
                null,
                mock(com.nageoffer.ai.ragent.audit.support.BizChangeLogContext.class)
        );
    }

    private KnowledgeDocumentDO document(Integer enabled) {
        KnowledgeDocumentDO documentDO = new KnowledgeDocumentDO();
        documentDO.setId(DOC_ID);
        documentDO.setKbId(KB_ID);
        documentDO.setStatus("pending");
        documentDO.setEnabled(enabled);
        return documentDO;
    }

    private KnowledgeChunkDO chunk(Integer enabled) {
        KnowledgeChunkDO chunkDO = new KnowledgeChunkDO();
        chunkDO.setId(CHUNK_ID);
        chunkDO.setDocId(DOC_ID);
        chunkDO.setChunkIndex(0);
        chunkDO.setContent("旧内容");
        chunkDO.setEnabled(enabled);
        return chunkDO;
    }

    private KnowledgeChunkUpdateRequest updateRequest() {
        KnowledgeChunkUpdateRequest request = new KnowledgeChunkUpdateRequest();
        request.setContent("新内容——与旧内容不同以走到向量同步段");
        return request;
    }

    private void stubHappyPath(KnowledgeDocumentDO documentDO, KnowledgeChunkDO chunkDO) {
        when(documentMapper.selectById(DOC_ID)).thenReturn(documentDO);
        when(chunkMapper.selectById(CHUNK_ID)).thenReturn(chunkDO);
        KnowledgeBaseDO kbDO = new KnowledgeBaseDO();
        kbDO.setId(KB_ID);
        kbDO.setCollectionName("kb_c1");
        kbDO.setEmbeddingModel("qwen3-embedding:8b-fp16");
        when(knowledgeBaseMapper.selectById(KB_ID)).thenReturn(kbDO);
        // VectorTargetResolver 未 stub 时 resolve 返回 null，any(Class) 不匹配 null——用 any() 全匹配
        when(chunkEmbeddingService.embed(anyList(), any()))
                .thenReturn(List.of(new EmbeddedChunk(
                        new Chunk(CHUNK_ID, 0, "向量内容", "向量内容", null),
                        new float[]{0.1f})));
    }

    @Test
    @DisplayName("chunk 被禁用时 update() 不写向量：embed 与 updateChunk 均不触发")
    void updateSkipsVectorWhenChunkDisabled() {
        stubHappyPath(document(1), chunk(0));

        service.update(DOC_ID, CHUNK_ID, updateRequest());

        verify(chunkEmbeddingService, never()).embed(anyList(), any());
        verify(vectorStoreService, never()).updateChunk(anyString(), anyString(), any(EmbeddedChunk.class));
    }

    @Test
    @DisplayName("文档被禁用时 update() 不写向量：embed 与 updateChunk 均不触发")
    void updateSkipsVectorWhenDocumentDisabled() {
        stubHappyPath(document(0), chunk(1));

        service.update(DOC_ID, CHUNK_ID, updateRequest());

        verify(chunkEmbeddingService, never()).embed(anyList(), any());
        verify(vectorStoreService, never()).updateChunk(anyString(), anyString(), any(EmbeddedChunk.class));
    }

    @Test
    @DisplayName("正控制：chunk 与文档均启用时 update() 仍同步向量（守卫不误伤正常路径）")
    void updateStillSyncsVectorWhenBothEnabled() {
        stubHappyPath(document(1), chunk(1));

        service.update(DOC_ID, CHUNK_ID, updateRequest());

        verify(vectorStoreService, times(1)).updateChunk(anyString(), anyString(), any(EmbeddedChunk.class));
    }

    @Test
    @DisplayName("禁用文档 startChunk() 直接拒绝：分块入口不产生任何新向量")
    void startChunkRejectsDisabledDocument() {
        KnowledgeDocumentServiceImpl documentService = new KnowledgeDocumentServiceImpl(
                mock(KnowledgeBaseMapper.class),
                documentMapper,
                mock(com.nageoffer.ai.ragent.core.parser.registry.ParserRegistry.class),
                mock(com.nageoffer.ai.ragent.core.ingest.IngestionKernel.class),
                mock(com.nageoffer.ai.ragent.core.ingest.sink.ChunkIndexWriter.class),
                mock(com.nageoffer.ai.ragent.knowledge.support.IngestionSpecCodec.class),
                mock(com.nageoffer.ai.ragent.rag.service.FileStorageService.class),
                mock(VectorStoreService.class),
                mock(com.nageoffer.ai.ragent.knowledge.service.KnowledgeChunkService.class),
                mock(com.fasterxml.jackson.databind.ObjectMapper.class),
                mock(com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentScheduleService.class),
                mock(com.nageoffer.ai.ragent.ingestion.service.IngestionPipelineService.class),
                mock(com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionPipelineMapper.class),
                mock(com.nageoffer.ai.ragent.ingestion.engine.IngestionEngine.class),
                mock(com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentChunkLogMapper.class),
                mock(KnowledgeChunkMapper.class),
                null,
                mock(com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer.class),
                mock(com.nageoffer.ai.ragent.knowledge.config.KnowledgeScheduleProperties.class),
                mock(com.nageoffer.ai.ragent.rag.security.IngestionUrlGuard.class),
                mock(com.nageoffer.ai.ragent.knowledge.handler.RemoteFileFetcher.class),
                mock(VectorTargetResolver.class),
                mock(com.nageoffer.ai.ragent.audit.support.BizChangeLogContext.class)
        );
        when(documentMapper.selectById(DOC_ID)).thenReturn(document(0));

        ClientException ex = assertThrows(ClientException.class,
                () -> documentService.startChunk(DOC_ID));
        assert ex.getMessage().contains("文档未启用");
    }
}
