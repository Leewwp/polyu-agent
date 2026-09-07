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

package com.nageoffer.ai.ragent.rag.service.impl;

import com.nageoffer.ai.ragent.framework.convention.SourceRef;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.controller.vo.PublicShareVO;
import com.nageoffer.ai.ragent.rag.controller.vo.ShareCreatedVO;
import com.nageoffer.ai.ragent.rag.dao.entity.AnswerShareDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AnswerShareMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 公开答案分享服务单元测试
 *
 * <p>覆盖设计合同（share-feature-design.md §6）：快照卫生、不可变语义、
 * 越权拒绝、撤销/过期统一语义、token 形状。
 */
class AnswerShareServiceImplTest {

    private AnswerShareMapper answerShareMapper;
    private ConversationMessageMapper conversationMessageMapper;
    private AnswerShareServiceImpl answerShareService;

    @BeforeEach
    void setUp() {
        answerShareMapper = mock(AnswerShareMapper.class);
        conversationMessageMapper = mock(ConversationMessageMapper.class);
        answerShareService = new AnswerShareServiceImpl(answerShareMapper, conversationMessageMapper);
        ReflectionTestUtils.setField(answerShareService, "defaultExpireDays", 90);
        ReflectionTestUtils.setField(answerShareService, "contentVersion", "v1");
    }

    private ConversationMessageDO assistantMessage(String id, String userId) {
        return ConversationMessageDO.builder()
                .id(id)
                .conversationId("c1")
                .userId(userId)
                .role("assistant")
                .content("answer **markdown**")
                .thinkingContent("SECRET-THINKING")
                .sources(List.of(new SourceRef()))
                .replyToMessageId("q1")
                .build();
    }

    private ConversationMessageDO userQuestion(String id, String userId) {
        return ConversationMessageDO.builder()
                .id(id)
                .conversationId("c1")
                .userId(userId)
                .role("user")
                .content("How to apply for accommodation?")
                .build();
    }

    @Test
    void createShareBuildsImmutableSnapshot() {
        when(conversationMessageMapper.selectById("m1")).thenReturn(assistantMessage("m1", "u1"));
        when(conversationMessageMapper.selectById("q1")).thenReturn(userQuestion("q1", "u1"));

        ShareCreatedVO created = answerShareService.createShare("m1", "u1");

        assertNotNull(created);
        assertEquals(43, created.getToken().length());
        assertTrue(created.getToken().matches("[A-Za-z0-9_-]+"));
        assertNotNull(created.getExpireTime());

        ArgumentCaptor<AnswerShareDO> captor = ArgumentCaptor.forClass(AnswerShareDO.class);
        verify(answerShareMapper).insert(captor.capture());
        AnswerShareDO snapshot = captor.getValue();
        // 快照卫生：question/answer/citations 已值复制
        assertEquals("How to apply for accommodation?", snapshot.getQuestion());
        assertEquals("answer **markdown**", snapshot.getAnswerMd());
        assertNotNull(snapshot.getCitations());
        assertEquals(1, snapshot.getCitations().size());
        assertEquals("u1", snapshot.getOwnerUserId());
        assertEquals("ACTIVE", snapshot.getStatus());
        assertEquals("v1", snapshot.getContentVersion());
        // zh/en 启发：英文问题 → en
        assertEquals("en", snapshot.getLang());
    }

    @Test
    void createShareRejectsNonOwnerAndNonAssistant() {
        when(conversationMessageMapper.selectById("m1")).thenReturn(assistantMessage("m1", "u1"));
        assertThrows(ClientException.class, () -> answerShareService.createShare("m1", "u2"));

        ConversationMessageDO userMsg = userQuestion("q1", "u1");
        when(conversationMessageMapper.selectById("q1")).thenReturn(userMsg);
        assertThrows(ClientException.class, () -> answerShareService.createShare("q1", "u1"));

        when(conversationMessageMapper.selectById("missing")).thenReturn(null);
        assertThrows(ClientException.class, () -> answerShareService.createShare("missing", "u1"));
    }

    @Test
    void publicPayloadIsFieldWhitelist() {
        AnswerShareDO stored = AnswerShareDO.builder()
                .token("t".repeat(43))
                .ownerUserId("u1")
                .messageId("m1")
                .conversationId("c1")
                .question("q")
                .answerMd("a")
                .citations(List.of(new SourceRef()))
                .lang("en")
                .contentVersion("v1")
                .status("ACTIVE")
                .createTime(new Date())
                .build();
        when(answerShareMapper.selectOne(any())).thenReturn(stored);

        PublicShareVO vo = answerShareService.getPublicShare(stored.getToken());
        // 白名单：问题/回答/引用/语言/版本/时间；身份与溯源字段不存在于公开类型
        assertEquals("q", vo.getQuestion());
        assertEquals("a", vo.getAnswerMd());
        assertEquals(1, vo.getCitations().size());
        assertEquals("en", vo.getLang());
        assertEquals("v1", vo.getContentVersion());
        assertNotNull(vo.getCreateTime());
    }

    @Test
    void revokedExpiredAndMissingShareShareOneSemantics() {
        String msg = "分享链接无效或已撤销";

        when(answerShareMapper.selectOne(any())).thenReturn(null);
        ClientException missing = assertThrows(ClientException.class, () -> answerShareService.getPublicShare("nope"));
        assertEquals(msg, missing.getMessage());

        AnswerShareDO revoked = AnswerShareDO.builder().token("t".repeat(43)).status("REVOKED").build();
        when(answerShareMapper.selectOne(any())).thenReturn(revoked);
        ClientException revokedEx = assertThrows(ClientException.class, () -> answerShareService.getPublicShare(revoked.getToken()));
        assertEquals(msg, revokedEx.getMessage());

        AnswerShareDO expired = AnswerShareDO.builder().token("t".repeat(43)).status("ACTIVE")
                .expireTime(new Date(System.currentTimeMillis() - 1000)).build();
        when(answerShareMapper.selectOne(any())).thenReturn(expired);
        ClientException expiredEx = assertThrows(ClientException.class, () -> answerShareService.getPublicShare(expired.getToken()));
        assertEquals(msg, expiredEx.getMessage());
    }

    @Test
    void revokeChecksOwnershipAndIsIdempotent() {
        AnswerShareDO stored = AnswerShareDO.builder().id("s1").token("t".repeat(43))
                .ownerUserId("u1").status("ACTIVE").build();
        when(answerShareMapper.selectOne(any())).thenReturn(stored);

        // 非 owner 且非管理员：拒绝
        assertThrows(ClientException.class, () -> answerShareService.revokeShare(stored.getToken(), "u2", false));
        verify(answerShareMapper, never()).updateById(any(AnswerShareDO.class));

        // owner：撤销成功
        answerShareService.revokeShare(stored.getToken(), "u1", false);
        ArgumentCaptor<AnswerShareDO> captor = ArgumentCaptor.forClass(AnswerShareDO.class);
        verify(answerShareMapper).updateById(captor.capture());
        assertEquals("REVOKED", captor.getValue().getStatus());
        assertNotNull(captor.getValue().getRevokedTime());

        // 管理员覆盖：跳过 owner 校验
        answerShareService.revokeShare(stored.getToken(), null, true);
        verify(answerShareMapper, org.mockito.Mockito.times(2)).updateById(any(AnswerShareDO.class));
    }

    @Test
    void tokenGenerationIsUniqueAndUrlSafe() {
        when(conversationMessageMapper.selectById("m1")).thenReturn(assistantMessage("m1", "u1"));
        when(conversationMessageMapper.selectById("q1")).thenReturn(userQuestion("q1", "u1"));
        ShareCreatedVO first = answerShareService.createShare("m1", "u1");
        ShareCreatedVO second = answerShareService.createShare("m1", "u1");
        assertEquals(43, first.getToken().length());
        assertEquals(43, second.getToken().length());
        assertNotEquals(first.getToken(), second.getToken());
    }
}
