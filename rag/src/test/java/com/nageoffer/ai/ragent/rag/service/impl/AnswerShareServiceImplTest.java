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
import com.nageoffer.ai.ragent.rag.controller.vo.ShareMineItemVO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.share.RevocationActor;
import com.nageoffer.ai.ragent.share.ShareKind;
import com.nageoffer.ai.ragent.share.ShareOwnedView;
import com.nageoffer.ai.ragent.share.SharePublicView;
import com.nageoffer.ai.ragent.share.ShareSnapshotService;
import com.nageoffer.ai.ragent.share.ShareTicket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 公开答案分享 adapter 单元测试（issue #124 统一机制后）：断言面与旧版一致——
 * 快照卫生（载荷值复制+隐私负面清单不进载荷）、归属校验、载荷序列化 round-trip、
 * module 视图到 VO 的白名单投影、撤销委托形状（owner/admin 双通道）。
 */
class AnswerShareServiceImplTest {

    private ShareSnapshotService shareSnapshotService;
    private ConversationMessageMapper conversationMessageMapper;
    private AnswerShareServiceImpl answerShareService;

    @BeforeEach
    void setUp() {
        shareSnapshotService = mock(ShareSnapshotService.class);
        conversationMessageMapper = mock(ConversationMessageMapper.class);
        answerShareService = new AnswerShareServiceImpl(shareSnapshotService, conversationMessageMapper);
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
        when(shareSnapshotService.create(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(ShareTicket.builder().token("t".repeat(43)).id("s1")
                        .expireAt(new Date()).build());

        ShareCreatedVO created = answerShareService.createShare("m1", "u1");

        assertNotNull(created);
        assertEquals("t".repeat(43), created.getToken());
        assertNotNull(created.getExpireTime());

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(shareSnapshotService).create(eq(ShareKind.ANSWER), eq("u1"), eq("c1"), eq("en"), payloadCaptor.capture());
        // 快照卫生：载荷值复制 question/answer/citations/contentVersion；思考内容不进载荷
        AnswerSharePayload payload = AnswerSharePayload.parse(payloadCaptor.getValue());
        assertEquals("How to apply for accommodation?", payload.getQuestion());
        assertEquals("answer **markdown**", payload.getAnswerMd());
        assertNotNull(payload.getCitations());
        assertEquals(1, payload.getCitations().size());
        assertEquals("v1", payload.getContentVersion());
        assertEquals("m1", payload.getMessageId());
        assertTrue(!payloadCaptor.getValue().contains("SECRET-THINKING"));
    }

    @Test
    void createShareRejectsNonOwnerAndNonAssistant() {
        when(conversationMessageMapper.selectById("m1")).thenReturn(assistantMessage("m1", "u1"));
        assertThrows(ClientException.class, () -> answerShareService.createShare("m1", "u2"));

        when(conversationMessageMapper.selectById("q1")).thenReturn(userQuestion("q1", "u1"));
        assertThrows(ClientException.class, () -> answerShareService.createShare("q1", "u1"));

        when(conversationMessageMapper.selectById("missing")).thenReturn(null);
        assertThrows(ClientException.class, () -> answerShareService.createShare("missing", "u1"));

        when(conversationMessageMapper.selectById("m1")).thenReturn(assistantMessage("m1", "u1"));
        when(conversationMessageMapper.selectById("q1")).thenReturn(null);
        assertThrows(ClientException.class, () -> answerShareService.createShare("m1", "u1"));
    }

    @Test
    void publicPayloadIsFieldWhitelist() {
        // 载荷 round-trip：module 只回吐不透明 JSON，投影字段全部来自载荷+视图
        when(shareSnapshotService.readByToken(anyString())).thenReturn(SharePublicView.builder()
                .kind(ShareKind.ANSWER)
                .lang("en")
                .payload(AnswerSharePayload.toJson(AnswerSharePayload.builder()
                        .messageId("m1")
                        .question("q")
                        .answerMd("a")
                        .citations(List.of(new SourceRef()))
                        .contentVersion("v1")
                        .build()))
                .createTime(new Date())
                .expireTime(null)
                .build());

        PublicShareVO vo = answerShareService.getPublicShare("t".repeat(43));
        // 白名单：问题/回答/引用/语言/版本/时间；身份与溯源字段不存在于公开类型
        assertEquals("q", vo.getQuestion());
        assertEquals("a", vo.getAnswerMd());
        assertEquals(1, vo.getCitations().size());
        assertEquals("en", vo.getLang());
        assertEquals("v1", vo.getContentVersion());
        assertNotNull(vo.getCreateTime());
        assertNull(vo.getExpireTime());
    }

    @Test
    void publicPayloadToleratesNullCitationsLikeLegacyRows() {
        // 旧表 citations NULL 的行迁移后载荷里是 JSON null：投影回 null（与迁移前行为一致）
        when(shareSnapshotService.readByToken(anyString())).thenReturn(SharePublicView.builder()
                .kind(ShareKind.ANSWER)
                .lang("zh")
                .payload("{\"question\":\"q\",\"answerMd\":\"a\",\"citations\":null}")
                .createTime(new Date())
                .build());

        PublicShareVO vo = answerShareService.getPublicShare("t".repeat(43));
        assertNull(vo.getCitations());
    }

    @Test
    void revokeDelegatesOwnerAndAdminActors() {
        answerShareService.revokeShare("t".repeat(43), "u1", false);
        verify(shareSnapshotService).revoke(eq("t".repeat(43)),
                org.mockito.Mockito.argThat(actor -> "u1".equals(actor.getUserId()) && !actor.isAdminOverride()));

        answerShareService.revokeShare("t".repeat(43), null, true);
        verify(shareSnapshotService).revoke(eq("t".repeat(43)),
                org.mockito.Mockito.argThat(actor -> actor.isAdminOverride()));
    }

    @Test
    void listMineProjectsQuestionPreview() {
        when(shareSnapshotService.listByOwner("u1", ShareKind.ANSWER)).thenReturn(List.of(ShareOwnedView.builder()
                .token("t".repeat(43))
                .kind(ShareKind.ANSWER)
                .status("ACTIVE")
                .payload(AnswerSharePayload.toJson(AnswerSharePayload.builder()
                        .question("很长的提问".repeat(30))
                        .build()))
                .expireTime(new Date())
                .createTime(new Date())
                .build()));

        List<ShareMineItemVO> mine = answerShareService.listMine("u1");
        assertEquals(1, mine.size());
        assertEquals("t".repeat(43), mine.get(0).getToken());
        assertEquals(51, mine.get(0).getQuestionPreview().length());
        assertTrue(mine.get(0).getQuestionPreview().endsWith("…"));
        assertEquals("ACTIVE", mine.get(0).getStatus());
        assertNotNull(mine.get(0).getExpireTime());
    }

    @Test
    void blankUserIsRejectedWithStableMessage() {
        ClientException ex = assertThrows(ClientException.class, () -> answerShareService.listMine(" "));
        assertEquals("未获取到当前登录用户", ex.getMessage());
    }
}
