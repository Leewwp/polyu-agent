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
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.dto.AgentBlock;
import com.nageoffer.ai.ragent.agent.dto.AgentBlockSource;
import com.nageoffer.ai.ragent.agent.share.vo.AgentShareAdminItemVO;
import com.nageoffer.ai.ragent.agent.share.vo.AgentShareCreatedVO;
import com.nageoffer.ai.ragent.agent.share.vo.AgentShareMineItemVO;
import com.nageoffer.ai.ragent.agent.share.vo.PublicAgentShareVO;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.share.RevocationActor;
import com.nageoffer.ai.ragent.share.ShareAdminView;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话分享 adapter 单元测试（issue #124 统一机制后）：断言面与旧版一致——
 * 快照白名单（blocks/thinking/ID/userId 一律不进载荷）、创建守卫（游客硬阻断/
 * 非本人/无问答）、sources 投影白名单与去重、载荷序列化 round-trip、
 * module 视图到 VO 的投影、撤销委托形状。
 */
class AgentConversationShareServiceImplTest {

    private ShareSnapshotService shareSnapshotService;
    private AgentConversationMapper conversationMapper;
    private AgentMessageMapper messageMapper;
    private AgentConversationShareServiceImpl shareService;

    @BeforeEach
    void setUp() {
        shareSnapshotService = mock(ShareSnapshotService.class);
        conversationMapper = mock(AgentConversationMapper.class);
        messageMapper = mock(AgentMessageMapper.class);
        shareService = new AgentConversationShareServiceImpl(shareSnapshotService, conversationMapper, messageMapper);
        ReflectionTestUtils.setField(shareService, "contentVersion", "v2");
        when(shareSnapshotService.create(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(ShareTicket.builder().token("t".repeat(43)).id("s1")
                        .expireAt(new Date()).build());
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
        return message(id, role, content, List.of());
    }

    private AgentMessageDO message(String id, String role, String content, List<AgentBlock> blocks) {
        return AgentMessageDO.builder()
                .id(id)
                .conversationId("c1")
                .userId("u1")
                .role(role)
                .content(content)
                // 隐私负面清单字段：快照白名单外的全部携带物
                .thinkingContent("SECRET-THINKING")
                .blocks(blocks)
                .replyToMessageId("reply-" + id)
                .messageStatus("NORMAL")
                .durationMs(12345L)
                .build();
    }

    /** search_knowledge 工具块（携带来源）；工具入参/结果等其余字段刻意留痕验证不外发 */
    private AgentBlock knowledgeBlock(String... docIds) {
        return AgentBlock.builder()
                .kind("tool")
                .name("search_knowledge")
                .status("done")
                .result("SECRET-TOOL-RESULT")
                .durationMs(999L)
                .sources(java.util.Arrays.stream(docIds)
                        .map(docId -> AgentBlockSource.builder()
                                .docId(docId)
                                .docName("doc-" + docId)
                                .excerpt("excerpt-" + docId)
                                .sourceType("url")
                                .url("https://polyu.edu.hk/" + docId)
                                .build())
                        .toList())
                .build();
    }

    @Test
    void createShareBuildsWhitelistSnapshot() {
        when(conversationMapper.selectOne(any())).thenReturn(conversation("c1", "u1"));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("m1", "user", "How do I apply for a dorm?"),
                // 空白正文（挂起确认卡）：自然跳过
                message("m2", "assistant", ""),
                message("m3", "assistant", "Apply online via the **portal**.", List.of(knowledgeBlock("d1"))),
                message("m4", "user", "thanks")));

        AgentShareCreatedVO created = shareService.createShare("c1", "u1", "user");

        assertNotNull(created);
        assertEquals("t".repeat(43), created.getToken());
        assertNotNull(created.getExpireTime());

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(shareSnapshotService).create(eq(ShareKind.CONVERSATION), eq("u1"), eq("c1"), eq("en"), payloadCaptor.capture());
        // 快照白名单：三字段条目按序值复制，空白正文跳过
        AgentConversationSharePayload payload = AgentConversationSharePayload.parse(payloadCaptor.getValue());
        assertEquals(3, payload.getMessages().size());
        assertEquals("user", payload.getMessages().get(0).getRole());
        assertEquals("How do I apply for a dorm?", payload.getMessages().get(0).getContent());
        assertNull(payload.getMessages().get(0).getSources());
        assertEquals("assistant", payload.getMessages().get(1).getRole());
        assertEquals("Apply online via the **portal**.", payload.getMessages().get(1).getContent());
        // 白名单外字段在快照条目类型上不存在（编译期保证），身份/溯源只经 module 公共列
        assertEquals("How to apply for a dorm?", payload.getTitle());
        assertEquals("v2", payload.getContentVersion());
        // 载荷 JSON 不含任何隐私负面清单携带物
        assertTrue(!payloadCaptor.getValue().contains("SECRET-THINKING"));
        assertTrue(!payloadCaptor.getValue().contains("SECRET-TOOL-RESULT"));
    }

    @Test
    void createShareProjectsSearchKnowledgeSourcesWithDedup() {
        // 非 search_knowledge 的工具块即使带 sources 也不投影（白名单只认检索工具）
        AgentBlock foreignToolBlock = AgentBlock.builder()
                .kind("tool")
                .name("register_course")
                .status("done")
                .result("SECRET-FOREIGN-RESULT")
                .sources(List.of(AgentBlockSource.builder().docId("dX").docName("foreign").build()))
                .build();
        when(conversationMapper.selectOne(any())).thenReturn(conversation("c1", "u1"));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("m1", "user", "问"),
                message("m2", "assistant", "答", List.of(
                        AgentBlock.builder().kind("reasoning").text("SECRET-REASONING").build(),
                        knowledgeBlock("d1", "d2"),
                        // 同一文档二次命中：去重只留首次
                        knowledgeBlock("d2", "d3"),
                        foreignToolBlock))));

        shareService.createShare("c1", "u1", "user");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(shareSnapshotService).create(any(), anyString(), anyString(), anyString(), payloadCaptor.capture());
        AgentConversationSharePayload payload = AgentConversationSharePayload.parse(payloadCaptor.getValue());
        assertEquals(2, payload.getMessages().size());
        List<AgentShareSnapshotSource> sources = payload.getMessages().get(1).getSources();
        // 摊平去重后 d1/d2/d3 按序入快照，序号 1 基；入参/结果/思考/耗时不在投影面
        assertEquals(3, sources.size());
        assertEquals(Integer.valueOf(1), sources.get(0).getIndex());
        assertEquals("d1", sources.get(0).getDocId());
        assertEquals("doc-d1", sources.get(0).getDocName());
        assertEquals("excerpt-d1", sources.get(0).getExcerpt());
        assertEquals("url", sources.get(0).getSourceType());
        assertEquals("https://polyu.edu.hk/d1", sources.get(0).getUrl());
        assertEquals("d3", sources.get(2).getDocId());
        assertEquals(Integer.valueOf(3), sources.get(2).getIndex());
        // 工具结果/思考正文不出现在载荷 JSON 的任何条目里
        assertTrue(!payloadCaptor.getValue().contains("SECRET-TOOL-RESULT"));
        assertTrue(!payloadCaptor.getValue().contains("SECRET-FOREIGN-RESULT"));
        assertTrue(!payloadCaptor.getValue().contains("SECRET-REASONING"));
        assertTrue(!payloadCaptor.getValue().contains("SECRET-THINKING"));
    }

    @Test
    void createShareWithoutSourcesYieldsNullSourcesField() {
        // 无检索块的 assistant 条目（纯闲聊）：sources 为 null，条目仍成立
        when(conversationMapper.selectOne(any())).thenReturn(conversation("c1", "u1"));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("m1", "user", "问"),
                message("m2", "assistant", "答", List.of(
                        AgentBlock.builder().kind("answer").text("答").build()))));

        shareService.createShare("c1", "u1", "user");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(shareSnapshotService).create(any(), anyString(), anyString(), anyString(), payloadCaptor.capture());
        AgentConversationSharePayload payload = AgentConversationSharePayload.parse(payloadCaptor.getValue());
        assertNull(payload.getMessages().get(1).getSources());
    }

    @Test
    void createShareRejectsGuestHard() {
        // 游客硬阻断（issue #91 增补）：与前端按钮隐藏互为双保险
        ClientException rejected = assertThrows(ClientException.class,
                () -> shareService.createShare("c1", "g1", "guest"));
        assertEquals("游客身份不支持创建分享，请登录后使用", rejected.getMessage());
        verify(shareSnapshotService, never()).create(any(), anyString(), anyString(), anyString(), anyString());
        verify(conversationMapper, never()).selectOne(any());
    }

    @Test
    void createShareRejectsForeignConversationAndEmptyTalk() {
        // 非本人（eq userId 查不到行）→ 统一「会话不存在或无权分享」，不泄漏存在性
        when(conversationMapper.selectOne(any())).thenReturn(null);
        ClientException foreign = assertThrows(ClientException.class, () -> shareService.createShare("c1", "u2", "user"));
        assertEquals("会话不存在或无权分享", foreign.getMessage());

        // 只有提问没有回答：不可分享
        when(conversationMapper.selectOne(any())).thenReturn(conversation("c1", "u1"));
        when(messageMapper.selectList(any())).thenReturn(List.of(message("m1", "user", "q")));
        ClientException noAnswer = assertThrows(ClientException.class, () -> shareService.createShare("c1", "u1", "user"));
        assertEquals("会话中没有可分享的问答内容", noAnswer.getMessage());

        // 空会话同理
        when(messageMapper.selectList(any())).thenReturn(List.of());
        assertThrows(ClientException.class, () -> shareService.createShare("c1", "u1", "user"));
        verify(shareSnapshotService, never()).create(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void createShareDefaultsBlankTitle() {
        when(conversationMapper.selectOne(any())).thenReturn(AgentConversationDO.builder()
                .id("row-1").conversationId("c1").userId("u1").build());
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("m1", "user", "问"),
                message("m2", "assistant", "答")));

        shareService.createShare("c1", "u1", "user");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(shareSnapshotService).create(any(), anyString(), anyString(), anyString(), payloadCaptor.capture());
        assertEquals("新对话", AgentConversationSharePayload.parse(payloadCaptor.getValue()).getTitle());
    }

    @Test
    void publicPayloadIsFieldWhitelistAndRoundTrips() {
        // 载荷 round-trip：module 只回吐不透明 JSON，v1 旧快照（无 sources 字段）公开读兼容
        when(shareSnapshotService.readByToken(anyString())).thenReturn(SharePublicView.builder()
                .kind(ShareKind.CONVERSATION)
                .lang("zh")
                .payload(AgentConversationSharePayload.toJson(AgentConversationSharePayload.builder()
                        .title("标题")
                        .messages(List.of(
                                AgentShareSnapshotItem.builder().role("user").content("q").createTime(new Date()).build(),
                                AgentShareSnapshotItem.builder().role("assistant").content("a").createTime(new Date()).build()))
                        .contentVersion("v1")
                        .build()))
                .createTime(new Date())
                .expireTime(null)
                .build());

        PublicAgentShareVO vo = shareService.getPublicShare("t".repeat(43));
        // 白名单：标题/消息序列/语言/版本/时间；身份与溯源字段不存在于公开类型
        assertEquals("标题", vo.getTitle());
        assertEquals(2, vo.getMessages().size());
        assertEquals("q", vo.getMessages().get(0).getContent());
        assertNull(vo.getMessages().get(0).getSources());
        assertNull(vo.getMessages().get(1).getSources());
        assertEquals("zh", vo.getLang());
        assertEquals("v1", vo.getContentVersion());
        assertNotNull(vo.getCreateTime());
    }

    @Test
    void publicPayloadPassesSourcesThroughForV2Snapshot() {
        when(shareSnapshotService.readByToken(anyString())).thenReturn(SharePublicView.builder()
                .kind(ShareKind.CONVERSATION)
                .lang("zh")
                .payload(AgentConversationSharePayload.toJson(AgentConversationSharePayload.builder()
                        .title("标题")
                        .messages(List.of(
                                AgentShareSnapshotItem.builder().role("user").content("q").createTime(new Date()).build(),
                                AgentShareSnapshotItem.builder().role("assistant").content("a").createTime(new Date())
                                        .sources(List.of(AgentShareSnapshotSource.builder()
                                                .index(1).docId("d1").docName("宿舍指南")
                                                .excerpt("摘录").sourceType("file").url("https://polyu.edu.hk/dl").build()))
                                        .build()))
                        .contentVersion("v2")
                        .build()))
                .createTime(new Date())
                .build());

        PublicAgentShareVO vo = shareService.getPublicShare("t".repeat(43));
        assertEquals(1, vo.getMessages().get(1).getSources().size());
        assertEquals("d1", vo.getMessages().get(1).getSources().get(0).getDocId());
        assertEquals("file", vo.getMessages().get(1).getSources().get(0).getSourceType());
        assertEquals("v2", vo.getContentVersion());
    }

    @Test
    void revokeDelegatesOwnerAndAdminActors() {
        shareService.revokeShare("t".repeat(43), "u1", false);
        verify(shareSnapshotService).revoke(eq("t".repeat(43)),
                org.mockito.Mockito.argThat(actor -> "u1".equals(actor.getUserId()) && !actor.isAdminOverride()));

        shareService.revokeShare("t".repeat(43), null, true);
        verify(shareSnapshotService).revoke(eq("t".repeat(43)),
                org.mockito.Mockito.argThat(actor -> actor.isAdminOverride()));
    }

    @Test
    void noExpiryEchoesModuleTicket() {
        when(shareSnapshotService.create(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(ShareTicket.builder().token("t".repeat(43)).id("s1").expireAt(null).build());
        when(conversationMapper.selectOne(any())).thenReturn(conversation("c1", "u1"));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("m1", "user", "问"),
                message("m2", "assistant", "答")));

        AgentShareCreatedVO created = shareService.createShare("c1", "u1", "user");
        assertNull(created.getExpireTime());
    }

    @Test
    void listMineProjectsTitlePreviewAndAdminListCarriesOwner() {
        when(shareSnapshotService.listByOwner("u1", ShareKind.CONVERSATION)).thenReturn(List.of(ShareOwnedView.builder()
                .token("t".repeat(43))
                .kind(ShareKind.CONVERSATION)
                .status("ACTIVE")
                .payload(AgentConversationSharePayload.toJson(AgentConversationSharePayload.builder()
                        .title("标题".repeat(30))
                        .messages(List.of(
                                AgentShareSnapshotItem.builder().role("user").content("q").createTime(new Date()).build(),
                                AgentShareSnapshotItem.builder().role("assistant").content("a").createTime(new Date()).build()))
                        .build()))
                .expireTime(new Date())
                .createTime(new Date())
                .build()));
        when(shareSnapshotService.adminList(ShareKind.CONVERSATION)).thenReturn(List.of(ShareAdminView.builder()
                .token("t".repeat(43))
                .kind(ShareKind.CONVERSATION)
                .ownerUserId("u1")
                .status("ACTIVE")
                .payload(AgentConversationSharePayload.toJson(AgentConversationSharePayload.builder()
                        .title("标题")
                        .messages(List.of(AgentShareSnapshotItem.builder().role("user").content("q").build()))
                        .build()))
                .expireTime(new Date())
                .createTime(new Date())
                .build()));

        List<AgentShareMineItemVO> mine = shareService.listMine("u1");
        assertEquals(1, mine.size());
        assertEquals("t".repeat(43), mine.get(0).getToken());
        assertEquals(51, mine.get(0).getTitlePreview().length());
        assertTrue(mine.get(0).getTitlePreview().endsWith("…"));
        assertEquals(2, mine.get(0).getMessageCount());

        List<AgentShareAdminItemVO> admin = shareService.listAllForAdmin();
        assertEquals("u1", admin.get(0).getOwnerUserId());
        assertEquals(1, admin.get(0).getMessageCount());
    }

    @Test
    void chineseContentDetectedAsZh() {
        when(conversationMapper.selectOne(any())).thenReturn(conversation("c1", "u1"));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("m1", "user", "如何申请宿舍？"),
                message("m2", "assistant", "在线申请即可。")));

        shareService.createShare("c1", "u1", "user");

        verify(shareSnapshotService).create(any(), anyString(), anyString(), eq("zh"), anyString());
    }
}
