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

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseCreateRequest;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M7 回归：KB 创建入口对 collectionName 做格式校验（对齐 Milvus VarChar(64) schema 与过滤表达式安全），
 * 含引号/超长/大写/空值一律在建库前拒绝。
 */
class KnowledgeBaseServiceImplCreateValidationTest {

    private KnowledgeBaseMapper knowledgeBaseMapper;
    private KnowledgeBaseServiceImpl service;

    @BeforeEach
    void setUp() {
        knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        service = new KnowledgeBaseServiceImpl(
                knowledgeBaseMapper,
                mock(KnowledgeDocumentMapper.class),
                mock(com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin.class),
                mock(com.nageoffer.ai.ragent.rag.service.FileStorageService.class),
                mock(MessageQueueProducer.class),
                mock(com.nageoffer.ai.ragent.audit.support.BizChangeLogContext.class)
        );
    }

    private KnowledgeBaseCreateRequest request(String collectionName) {
        KnowledgeBaseCreateRequest request = new KnowledgeBaseCreateRequest();
        request.setName("测试库");
        request.setEmbeddingModel("qwen3-embedding:8b-fp16");
        request.setCollectionName(collectionName);
        return request;
    }

    @Test
    @DisplayName("M7：collectionName 含引号被拒——建库前拦截，不触碰 mapper")
    void rejectsQuotedCollectionName() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.create(request("kb\" || true || \"")));
        assert ex.getMessage().contains("小写字母");
        verify(knowledgeBaseMapper, never()).insert(any(KnowledgeBaseDO.class));
    }

    @Test
    @DisplayName("M7：超长（>64）与大写与空值同样拒绝")
    void rejectsOverlengthUppercaseAndBlank() {
        assertThrows(ServiceException.class, () -> service.create(request("a".repeat(65))));
        assertThrows(ServiceException.class, () -> service.create(request("Kb-Hr")));
        assertThrows(ServiceException.class, () -> service.create(request(null)));
        verify(knowledgeBaseMapper, never()).selectCount(any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("合法格式放行进入后续重复校验（selectCount 被调到）")
    void acceptsLegalNameAndProceeds() {
        when(knowledgeBaseMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        // 合法名通过格式校验后走到重复校验（后续 insert 依赖未 mock 的下游，允许其抛错中断——只断言格式门已过）
        try {
            service.create(request("kb_hr_0b3a"));
        } catch (Exception expected) {
            // UserContext/下游 bean 未初始化时的中断是预期——格式门已通过
        }
        // 名称与 Collection 各查一次重复（格式门已过才会走到这里）
        verify(knowledgeBaseMapper, org.mockito.Mockito.times(2)).selectCount(any(LambdaQueryWrapper.class));
    }
}
