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

package com.nageoffer.ai.ragent.knowledge.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.core.parser.registry.ParserRegistry;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentUploadRequest;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentScheduleService;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeDocumentServiceImpl;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * K1（doc 17）：source_location 回跳缺口回归测试。
 * 历史 bug：FILE 类 upload 一律写 null；update 的 sourceLocation 更新被锁在 URL-only 分支，
 * FILE 类批量灌库后无法补救（D16 判例）。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentSourceLocationTest {

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Mock
    private KnowledgeDocumentMapper documentMapper;
    @Mock
    private ParserRegistry parserRegistry;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private IngestionSpecCodec ingestionSpecCodec;
    @Mock
    private BizChangeLogContext bizChangeLogContext;
    @Mock
    private KnowledgeDocumentScheduleService scheduleService;

    @InjectMocks
    private KnowledgeDocumentServiceImpl documentService;

    @Mock
    private MultipartFile file;

    @BeforeEach
    void initLambdaCache() {
        // 纯单测环境没有 Mapper 扫描，LambdaUpdateWrapper.set 需要 TableInfo 缓存
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), KnowledgeDocumentDO.class);
    }

    @Test
    void uploadFileWritesSourceLocationFromStoredUrl() {
        when(knowledgeBaseMapper.selectById("kb1"))
                .thenReturn(KnowledgeBaseDO.builder().id("kb1").collectionName("col").build());
        StoredFileDTO stored = StoredFileDTO.builder()
                .url("minio://col/doc-abc.pdf")
                .detectedType("pdf")
                .mimeType("application/pdf")
                .size(100L)
                .originalFilename("abc.pdf")
                .build();
        when(fileStorageService.upload("col", file)).thenReturn(stored);
        when(parserRegistry.canParse("application/pdf")).thenReturn(true);
        when(ingestionSpecCodec.normalize(null)).thenReturn("{}");

        KnowledgeDocumentUploadRequest request = new KnowledgeDocumentUploadRequest();
        request.setSourceType("file");
        request.setProcessMode("chunk");

        documentService.upload("kb1", request, file);

        ArgumentCaptor<KnowledgeDocumentDO> captor = ArgumentCaptor.forClass(KnowledgeDocumentDO.class);
        verify(documentMapper).insert(captor.capture());
        assertEquals("minio://col/doc-abc.pdf", captor.getValue().getSourceLocation());
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateFileDocAcceptsSourceLocationWithoutTouchingSchedule() {
        KnowledgeDocumentDO doc = KnowledgeDocumentDO.builder()
                .id("d1").docName("n").status(DocumentStatus.PENDING.getCode())
                .sourceType("file").scheduleEnabled(0)
                .build();
        when(documentMapper.selectById("d1")).thenReturn(doc);

        KnowledgeDocumentUpdateRequest request = new KnowledgeDocumentUpdateRequest();
        request.setDocName("n2");
        request.setSourceLocation("minio://col/doc-abc.pdf");

        documentService.update("d1", request);

        ArgumentCaptor<Wrapper<KnowledgeDocumentDO>> captor =
                ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(documentMapper).update(captor.capture());
        assertTrue(captor.getValue().getSqlSet().contains("source_location"),
                "FILE 类 update 应写入 source_location（回跳补救通道）");
        verify(scheduleService, never()).upsertSchedule(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateUrlDocStillTriggersScheduleUpsert() {
        KnowledgeDocumentDO doc = KnowledgeDocumentDO.builder()
                .id("d2").docName("n").status(DocumentStatus.PENDING.getCode())
                .sourceType("url").scheduleEnabled(0)
                .build();
        when(documentMapper.selectById("d2")).thenReturn(doc);

        KnowledgeDocumentUpdateRequest request = new KnowledgeDocumentUpdateRequest();
        request.setDocName("n2");
        request.setSourceLocation("https://www.polyu.edu.hk/ar/example/");

        documentService.update("d2", request);

        ArgumentCaptor<Wrapper<KnowledgeDocumentDO>> captor =
                ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(documentMapper).update(captor.capture());
        assertTrue(captor.getValue().getSqlSet().contains("source_location"));
        verify(scheduleService).upsertSchedule(any());
    }
}
