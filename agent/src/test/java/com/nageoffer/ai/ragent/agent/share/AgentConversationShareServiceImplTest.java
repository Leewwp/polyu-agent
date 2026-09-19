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

package com.nageoffer.ai.ragent.agent.share;

import com.nageoffer.ai.ragent.agent.dao.entity.AgentConversationDO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentConversationMapper;
import com.nageoffer.ai.ragent.agent.share.dao.AgentConversationShareMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.share.vo.AgentShareCreatedVO;
import com.nageoffer.ai.ragent.agent.share.vo.PublicAgentShareVO;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话分享服务单元测试（克隆 AnswerShareServiceImplTest 模式，mock mapper）
 *
 * <p>覆盖 issue #82 预注册判据：快照白名单（blocks/thinking/ID/userId 一律不落）、
 * 创建守卫（非本人/无问答）、token 形态、过期判定、撤销幂等与越权、统一无效语义。
 */
class AgentConversationShareServiceImplTest {

    private AgentConversationShareMapper shareMapper;
    private AgentConversationMapper conversationMapper;
    private AgentMessageMapper messageMapper;
    private AgentConversationShareServiceImpl shareService;

    @BeforeEach
    void setUp() {
        shareMapper = mock(AgentConversationShareMapper.class);
        conversationMapper = mock(AgentConversationMapper.class);
        messageMapper = mock(AgentMessageMapper.class);
        shareService = new AgentConversationShareServiceImpl(shareMapper, conversationMapper, messageMapper);
        ReflectionTestUtils.setField(shareService, "defaultExpireDays", 90);
        ReflectionTestUtils.setField(shareService, "contentVersion", "v1");
    }

    private AgentConversationDO conversation(String conversationId, String userId) {
        return AgentConversationDO.builder()
                .id("row-1")
                .conversationId(conversationId)
                .userId(userId)
                .title("How to apply for a dorm?")
                .build();
    }

    private AgentMessageDO message(String id, String role, String content) {
        return AgentMessageDO.builder()
                .id(id)
                .conversationId("c1")
                .userId("u1")
                .role(role)
                .content(content)
                // 隐私负面清单字段：快照白名单外的全部携带物
                .thinkingContent("SECRET-THINKING")
                .blocks(List.of())
                .replyToMessageId("reply-" + id)
                .messageStatus("NORMAL")
                .durationMs(12345L)
                .build();
    }

    @Test
    void createShareBuildsWhitelistSnapshot() {
        when(conversationMapper.selectOne(any())).thenReturn(conversation("c1", "u1"));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("m1", "user", "How do I apply for a dorm?"),
                // 空白正文（挂起确认卡）：自然跳过
                message("m2", "assistant", ""),
                message("m3", "assistant", "Apply online via the **portal**."),
                message("m4", "user", "thanks")));

        AgentShareCreatedVO created = shareService.createShare("c1", "u1");

        assertNotNull(created);
        assertEquals(43, created.getToken().length());
        assertTrue(created.getToken().matches("[A-Za-z0-9_-]+"));
        assertNotNull(created.getExpireTime());

        ArgumentCaptor<AgentConversationShareDO> captor = ArgumentCaptor.forClass(AgentConversationShareDO.class);
        verify(shareMapper).insert(captor.capture());
        AgentConversationShareDO snapshot = captor.getValue();
        // 快照白名单：三字段条目按序值复制，空白正文跳过
        assertEquals(3, snapshot.getMessages().size());
        assertEquals("user", snapshot.getMessages().get(0).getRole());
        assertEquals("How do I apply for a dorm?", snapshot.getMessages().get(0).getContent());
        assertEquals("assistant", snapshot.getMessages().get(1).getRole());
        assertEquals("Apply online via the **portal**.", snapshot.getMessages().get(1).getContent());
        // 白名单外字段在快照条目类型上不存在（编译期保证），身份/溯源只落在内部列
        assertEquals("u1", snapshot.getOwnerUserId());
        assertEquals("c1", snapshot.getConversationId());
        assertEquals("How to apply for a dorm?", snapshot.getTitle());
        assertEquals("ACTIVE", snapshot.getStatus());
        assertEquals("v1", snapshot.getContentVersion());
        assertEquals("en", snapshot.getLang());
    }

    @Test
    void createShareRejectsForeignConversationAndEmptyTalk() {
        // 非本人（eq userId 查不到行）→ 统一「会话不存在或无权分享」，不泄漏存在性
        when(conversationMapper.selectOne(any())).thenReturn(null);
        ClientException foreign = assertThrows(ClientException.class, () -> shareService.createShare("c1", "u2"));
        assertEquals("会话不存在或无权分享", foreign.getMessage());

        // 只有提问没有回答：不可分享
        when(conversationMapper.selectOne(any())).thenReturn(conversation("c1", "u1"));
        when(messageMapper.selectList(any())).thenReturn(List.of(message("m1", "user", "q")));
        ClientException noAnswer = assertThrows(ClientException.class, () -> shareService.createShare("c1", "u1"));
        assertEquals("会话中没有可分享的问答内容", noAnswer.getMessage());

        // 空会话同理
        when(messageMapper.selectList(any())).thenReturn(List.of());
        assertThrows(ClientException.class, () -> shareService.createShare("c1", "u1"));
        verify(shareMapper, never()).insert(any(AgentConversationShareDO.class));
    }

    @Test
    void publicPayloadIsFieldWhitelist() {
        AgentConversationShareDO stored = AgentConversationShareDO.builder()
                .token("t".repeat(43))
                .ownerUserId("u1")
                .conversationId("c1")
                .title("标题")
                .messages(List.of(
                        AgentShareSnapshotItem.builder().role("user").content("q").createTime(new Date()).build(),
                        AgentShareSnapshotItem.builder().role("assistant").content("a").createTime(new Date()).build()))
                .lang("zh")
                .contentVersion("v1")
                .status("ACTIVE")
                .createTime(new Date())
                .build();
        when(shareMapper.selectOne(any())).thenReturn(stored);

        PublicAgentShareVO vo = shareService.getPublicShare(stored.getToken());
        // 白名单：标题/消息序列/语言/版本/时间；身份与溯源字段不存在于公开类型
        assertEquals("标题", vo.getTitle());
        assertEquals(2, vo.getMessages().size());
        assertEquals("q", vo.getMessages().get(0).getContent());
        assertEquals("zh", vo.getLang());
        assertEquals("v1", vo.getContentVersion());
        assertNotNull(vo.getCreateTime());
    }

    @Test
    void revokedExpiredAndMissingShareOneSemantics() {
        String msg = "分享链接无效或已撤销";

        when(shareMapper.selectOne(any())).thenReturn(null);
        ClientException missing = assertThrows(ClientException.class, () -> shareService.getPublicShare("nope"));
        assertEquals(msg, missing.getMessage());

        AgentConversationShareDO revoked = AgentConversationShareDO.builder()
                .token("t".repeat(43)).status("REVOKED").build();
        when(shareMapper.selectOne(any())).thenReturn(revoked);
        ClientException revokedEx = assertThrows(ClientException.class, () -> shareService.getPublicShare(revoked.getToken()));
        assertEquals(msg, revokedEx.getMessage());

        AgentConversationShareDO expired = AgentConversationShareDO.builder()
                .token("t".repeat(43)).status("ACTIVE")
                .expireTime(new Date(System.currentTimeMillis() - 1000)).build();
        when(shareMapper.selectOne(any())).thenReturn(expired);
        ClientException expiredEx = assertThrows(ClientException.class, () -> shareService.getPublicShare(expired.getToken()));
        assertEquals(msg, expiredEx.getMessage());
    }

    @Test
    void revokeChecksOwnershipAndIsIdempotent() {
        AgentConversationShareDO stored = AgentConversationShareDO.builder()
                .id("s1").token("t".repeat(43))
                .ownerUserId("u1").status("ACTIVE").build();
        when(shareMapper.selectOne(any())).thenReturn(stored);

        // 非 owner 且非管理员：拒绝
        assertThrows(ClientException.class, () -> shareService.revokeShare(stored.getToken(), "u2", false));
        verify(shareMapper, never()).updateById(any(AgentConversationShareDO.class));

        // owner：撤销成功
        shareService.revokeShare(stored.getToken(), "u1", false);
        ArgumentCaptor<AgentConversationShareDO> captor = ArgumentCaptor.forClass(AgentConversationShareDO.class);
        verify(shareMapper).updateById(captor.capture());
        assertEquals("REVOKED", captor.getValue().getStatus());
        assertNotNull(captor.getValue().getRevokedTime());

        // 已撤销：幂等不报错
        when(shareMapper.selectOne(any())).thenReturn(AgentConversationShareDO.builder()
                .id("s1").token("t".repeat(43)).ownerUserId("u1").status("REVOKED").build());
        shareService.revokeShare(stored.getToken(), "u1", false);
        verify(shareMapper).updateById(any(AgentConversationShareDO.class));
    }

    @Test
    void noExpiryWhenDaysNonPositive() {
        ReflectionTestUtils.setField(shareService, "defaultExpireDays", 0);
        when(conversationMapper.selectOne(any())).thenReturn(conversation("c1", "u1"));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("m1", "user", "问"),
                message("m2", "assistant", "答")));

        AgentShareCreatedVO created = shareService.createShare("c1", "u1");
        assertNull(created.getExpireTime());
    }

    @Test
    void tokenGenerationIsUniqueAndUrlSafe() {
        when(conversationMapper.selectOne(any())).thenReturn(conversation("c1", "u1"));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("m1", "user", "问"),
                message("m2", "assistant", "答")));
        AgentShareCreatedVO first = shareService.createShare("c1", "u1");
        AgentShareCreatedVO second = shareService.createShare("c1", "u1");
        assertEquals(43, first.getToken().length());
        assertEquals(43, second.getToken().length());
        assertTrue(first.getToken().matches("[A-Za-z0-9_-]+"));
        assertNotEquals(first.getToken(), second.getToken());
    }

    @Test
    void listMineMapsItemsAndAdminListCarriesOwner() {
        when(shareMapper.selectList(any())).thenReturn(List.of(AgentConversationShareDO.builder()
                .token("t".repeat(43))
                .ownerUserId("u1")
                .title("标题".repeat(30))
                .messages(List.of(
                        AgentShareSnapshotItem.builder().role("user").content("q").createTime(new Date()).build(),
                        AgentShareSnapshotItem.builder().role("assistant").content("a").createTime(new Date()).build()))
                .status("ACTIVE")
                .expireTime(new Date())
                .createTime(new Date())
                .build()));

        assertEquals(1, shareService.listMine("u1").size());
        var mine = shareService.listMine("u1").get(0);
        assertEquals("t".repeat(43), mine.getToken());
        assertEquals(51, mine.getTitlePreview().length());
        assertTrue(mine.getTitlePreview().endsWith("…"));
        assertEquals(2, mine.getMessageCount());

        var admin = shareService.listAllForAdmin().get(0);
        assertEquals("u1", admin.getOwnerUserId());
    }

    @Test
    void chineseContentDetectedAsZh() {
        when(conversationMapper.selectOne(any())).thenReturn(conversation("c1", "u1"));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("m1", "user", "如何申请宿舍？"),
                message("m2", "assistant", "在线申请即可。")));

        AgentShareCreatedVO created = shareService.createShare("c1", "u1");
        ArgumentCaptor<AgentConversationShareDO> captor = ArgumentCaptor.forClass(AgentConversationShareDO.class);
        verify(shareMapper).insert(captor.capture());
        assertEquals("zh", captor.getValue().getLang());
    }
}
