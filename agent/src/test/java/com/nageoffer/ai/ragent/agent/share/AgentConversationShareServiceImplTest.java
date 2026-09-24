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

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
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
 * 会话分享 adapter 单元测试（issue #124 统一机制后 + issue #138 scoped share）：
 * 断言面与旧版一致——快照白名单（blocks/thinking/ID/userId 一律不进载荷）、
 * 创建守卫（游客硬阻断/非本人/无问答）、sources 投影白名单与去重、
 * 载荷序列化 round-trip、module 视图到 VO 的投影、撤销委托形状；
 * #138 增补 scope 矩阵（full 零变更/turn/through）、String anchor 统一拒绝、
 * replyTo 权威+物理 Turn 窗口、fallback 收紧五例、消息查询会话+用户联合过滤。
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

        AgentShareCreatedVO created = shareService.createShare("c1", "u1", "user", null, null);

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

        shareService.createShare("c1", "u1", "user", null, null);

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

        shareService.createShare("c1", "u1", "user", null, null);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(shareSnapshotService).create(any(), anyString(), anyString(), anyString(), payloadCaptor.capture());
        AgentConversationSharePayload payload = AgentConversationSharePayload.parse(payloadCaptor.getValue());
        assertNull(payload.getMessages().get(1).getSources());
    }

    @Test
    void createShareRejectsGuestHard() {
        // 游客硬阻断（issue #91 增补）：与前端按钮隐藏互为双保险
        ClientException rejected = assertThrows(ClientException.class,
                () -> shareService.createShare("c1", "g1", "guest", null, null));
        assertEquals("游客身份不支持创建分享，请登录后使用", rejected.getMessage());
        verify(shareSnapshotService, never()).create(any(), anyString(), anyString(), anyString(), anyString());
        verify(conversationMapper, never()).selectOne(any());
    }

    @Test
    void createShareRejectsForeignConversationAndEmptyTalk() {
        // 非本人（eq userId 查不到行）→ 统一「会话不存在或无权分享」，不泄漏存在性
        when(conversationMapper.selectOne(any())).thenReturn(null);
        ClientException foreign = assertThrows(ClientException.class,
                () -> shareService.createShare("c1", "u2", "user", null, null));
        assertEquals("会话不存在或无权分享", foreign.getMessage());

        // 只有提问没有回答：不可分享
        when(conversationMapper.selectOne(any())).thenReturn(conversation("c1", "u1"));
        when(messageMapper.selectList(any())).thenReturn(List.of(message("m1", "user", "q")));
        ClientException noAnswer = assertThrows(ClientException.class, () -> shareService.createShare("c1", "u1", "user", null, null));
        assertEquals("会话中没有可分享的问答内容", noAnswer.getMessage());

        // 空会话同理
        when(messageMapper.selectList(any())).thenReturn(List.of());
        assertThrows(ClientException.class, () -> shareService.createShare("c1", "u1", "user", null, null));
        verify(shareSnapshotService, never()).create(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void createShareDefaultsBlankTitle() {
        when(conversationMapper.selectOne(any())).thenReturn(AgentConversationDO.builder()
                .id("row-1").conversationId("c1").userId("u1").build());
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("m1", "user", "问"),
                message("m2", "assistant", "答")));

        shareService.createShare("c1", "u1", "user", null, null);

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

        AgentShareCreatedVO created = shareService.createShare("c1", "u1", "user", null, null);
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

    /** scoped 用例的消息构造：replyTo/status 显式可控（#138 Turn 解析的物理依据） */
    private AgentMessageDO scopedMessage(String id, String role, String content, String replyTo, String status) {
        return scopedMessage(id, role, content, replyTo, status, null);
    }

    private AgentMessageDO scopedMessage(String id, String role, String content, String replyTo, String status,
                                         List<AgentBlock> blocks) {
        return AgentMessageDO.builder()
                .id(id)
                .conversationId("c1")
                .userId("u1")
                .role(role)
                .content(content)
                .thinkingContent("SECRET-THINKING")
                .blocks(blocks)
                .replyToMessageId(replyTo)
                .messageStatus(status)
                .durationMs(12345L)
                .build();
    }

    /** 三轮对话 + 确认续跑 + 窗口外游离 assistant 的标准样本（按 id ASC） */
    private List<AgentMessageDO> threeTurnConversation() {
        return List.of(
                scopedMessage("m1", "user", "Q1", null, "NORMAL"),
                scopedMessage("m2", "assistant", "A1-first", "m1", "NORMAL"),
                // 挂起确认卡：空正文，白名单流水线自然跳过
                scopedMessage("m3", "assistant", "", "m1", "AWAITING_CONFIRM"),
                scopedMessage("m4", "assistant", "A1-resumed", "m1", "NORMAL"),
                scopedMessage("m5", "user", "Q2", null, "NORMAL"),
                scopedMessage("m6", "assistant", "A2", "m5", "NORMAL"),
                // 游离数据：replyTo 指回轮 1 根消息但物理位置在轮 2 窗口内——权威路径按窗口+同 replyTo 剔除
                scopedMessage("m7", "assistant", "stray-same-reply", "m1", "NORMAL"),
                scopedMessage("m8", "user", "Q3", null, "NORMAL"),
                scopedMessage("m9", "assistant", "A3", "m8", "NORMAL"));
    }

    @Test
    void createShareExplicitFullKeepsLegacySemanticsUnchanged() {
        // scope=full 显式值=现行整段快照零变更；anchor 提供也被忽略；
        // 未完成轮非空内容（INTERRUPTED）仍按现行行为包含（#138 final 修订⑤：过滤未完成轮属未来另开决策）
        when(conversationMapper.selectOne(any())).thenReturn(conversation("c1", "u1"));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                scopedMessage("m1", "user", "Q1", null, "NORMAL"),
                scopedMessage("m2", "assistant", "partial-answer", "m1", "INTERRUPTED"),
                scopedMessage("m3", "user", "Q2", null, "NORMAL"),
                scopedMessage("m4", "assistant", "A2", "m3", "NORMAL")));

        shareService.createShare("c1", "u1", "user", "full", "m2");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(shareSnapshotService).create(any(), anyString(), anyString(), anyString(), payloadCaptor.capture());
        AgentConversationSharePayload payload = AgentConversationSharePayload.parse(payloadCaptor.getValue());
        assertEquals(4, payload.getMessages().size());
        assertEquals("partial-answer", payload.getMessages().get(1).getContent());
    }

    @Test
    void createShareTurnScopesToAnchorTurnOnly() {
        // turn=锚点所属完整 Turn：只含轮 2（Q2+A2），前后轮与窗口内游离 assistant 均不入选
        when(conversationMapper.selectOne(any())).thenReturn(conversation("c1", "u1"));
        when(messageMapper.selectList(any())).thenReturn(threeTurnConversation());

        shareService.createShare("c1", "u1", "user", "turn", "m6");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(shareSnapshotService).create(any(), anyString(), anyString(), anyString(), payloadCaptor.capture());
        AgentConversationSharePayload payload = AgentConversationSharePayload.parse(payloadCaptor.getValue());
        assertEquals(2, payload.getMessages().size());
        assertEquals("user", payload.getMessages().get(0).getRole());
        assertEquals("Q2", payload.getMessages().get(0).getContent());
        assertEquals("assistant", payload.getMessages().get(1).getRole());
        assertEquals("A2", payload.getMessages().get(1).getContent());
    }

    @Test
    void createShareTurnIncludesConfirmContinuationInSameTurn() {
        // 确认续跑产生的多 assistant（同 replyTo 根）归同一 Turn；挂起确认卡空正文自然跳过；
        // 窗口外同 replyTo 的 assistant（物理位置在下一轮窗口内）不被拉入——窗口层防御
        when(conversationMapper.selectOne(any())).thenReturn(conversation("c1", "u1"));
        when(messageMapper.selectList(any())).thenReturn(threeTurnConversationWithSources());

        shareService.createShare("c1", "u1", "user", "turn", "m2");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(shareSnapshotService).create(any(), anyString(), anyString(), anyString(), payloadCaptor.capture());
        AgentConversationSharePayload payload = AgentConversationSharePayload.parse(payloadCaptor.getValue());
        assertEquals(3, payload.getMessages().size());
        assertEquals("Q1", payload.getMessages().get(0).getContent());
        assertEquals("A1-first", payload.getMessages().get(1).getContent());
        assertEquals("A1-resumed", payload.getMessages().get(2).getContent());
        // scoped 选段仍走 v2 sources 投影
        assertEquals(1, payload.getMessages().get(1).getSources().size());
        assertEquals("d1", payload.getMessages().get(1).getSources().get(0).getDocId());
        // 隐私负面清单：thinking/replyTo/ID 不随选段外发
        assertTrue(!payloadCaptor.getValue().contains("SECRET-THINKING"));
        assertTrue(!payloadCaptor.getValue().contains("SECRET-TOOL-RESULT"));
    }

    /** 轮 1 首答携带检索来源块，其余同 threeTurnConversation（按 id 定点替换，免下标耦合） */
    private List<AgentMessageDO> threeTurnConversationWithSources() {
        return withReplaced(threeTurnConversation(),
                scopedMessage("m2", "assistant", "A1-first", "m1", "NORMAL", List.of(knowledgeBlock("d1"))));
    }

    @Test
    void createShareThroughCutsFromStartToAnchorTurnEnd() {
        // through=按消息顺序从开头到锚点轮末尾：轮 1 全量（含确认续答）+轮 2，轮 3 不含；
        // 锚点轮末尾之后的游离 assistant（m7 位于 m6 之后）也在截断点之外
        when(conversationMapper.selectOne(any())).thenReturn(conversation("c1", "u1"));
        when(messageMapper.selectList(any())).thenReturn(threeTurnConversation());

        shareService.createShare("c1", "u1", "user", "through", "m6");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(shareSnapshotService).create(any(), anyString(), anyString(), anyString(), payloadCaptor.capture());
        AgentConversationSharePayload payload = AgentConversationSharePayload.parse(payloadCaptor.getValue());
        assertEquals(5, payload.getMessages().size());
        assertEquals("Q1", payload.getMessages().get(0).getContent());
        assertEquals("A1-first", payload.getMessages().get(1).getContent());
        assertEquals("A1-resumed", payload.getMessages().get(2).getContent());
        assertEquals("Q2", payload.getMessages().get(3).getContent());
        assertEquals("A2", payload.getMessages().get(4).getContent());
    }

    @Test
    void createShareFallsBackSequentiallyOnNullReplyTo() {
        // legacy 缺数据（null replyTo）：顺序 fallback 与前端 groupTurns 同构——
        // anchor 向前最近一条 user 起到下一 user 前
        when(conversationMapper.selectOne(any())).thenReturn(conversation("c1", "u1"));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                scopedMessage("m1", "user", "Q1", null, "NORMAL"),
                scopedMessage("m2", "assistant", "A1", null, "NORMAL"),
                scopedMessage("m3", "user", "Q2", null, "NORMAL"),
                scopedMessage("m4", "assistant", "A2", null, "NORMAL")));

        shareService.createShare("c1", "u1", "user", "turn", "m4");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(shareSnapshotService).create(any(), anyString(), anyString(), anyString(), payloadCaptor.capture());
        AgentConversationSharePayload payload = AgentConversationSharePayload.parse(payloadCaptor.getValue());
        assertEquals(2, payload.getMessages().size());
        assertEquals("Q2", payload.getMessages().get(0).getContent());
        assertEquals("A2", payload.getMessages().get(1).getContent());
    }

    @Test
    void createShareFallsBackSequentiallyOnBlankReplyTo() {
        when(conversationMapper.selectOne(any())).thenReturn(conversation("c1", "u1"));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                scopedMessage("m1", "user", "Q1", null, "NORMAL"),
                scopedMessage("m2", "assistant", "A1", null, "NORMAL"),
                scopedMessage("m3", "user", "Q2", null, "NORMAL"),
                scopedMessage("m4", "assistant", "A2", " ", "NORMAL")));

        shareService.createShare("c1", "u1", "user", "through", "m4");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(shareSnapshotService).create(any(), anyString(), anyString(), anyString(), payloadCaptor.capture());
        AgentConversationSharePayload payload = AgentConversationSharePayload.parse(payloadCaptor.getValue());
        assertEquals(4, payload.getMessages().size());
    }

    @Test
    void createShareRejectsNonBlankButInvalidReplyTo() {
        // fallback 收紧：矛盾数据拒绝猜测——非空 replyTo 无效即持久化关系冲突统一拒绝，不静默 fallback
        when(conversationMapper.selectOne(any())).thenReturn(conversation("c1", "u1"));

        // ① 非空但目标不存在
        when(messageMapper.selectList(any())).thenReturn(threeTurnConversationWithAnchorReplyTo("ghost-id"));
        ClientException dangling = assertThrows(ClientException.class,
                () -> shareService.createShare("c1", "u1", "user", "turn", "m6"));
        assertEquals("消息关联关系异常，无法解析所属问答轮", dangling.getMessage());

        // ② 非空但目标在他会话/异属主——联合过滤集合内同样不可见（集合内不存在）
        when(messageMapper.selectList(any())).thenReturn(threeTurnConversationWithAnchorReplyTo("m777-other-owner"));
        ClientException crossConversation = assertThrows(ClientException.class,
                () -> shareService.createShare("c1", "u1", "user", "turn", "m6"));
        assertEquals("消息关联关系异常，无法解析所属问答轮", crossConversation.getMessage());

        // ③ 非空但目标是 assistant（非 user）
        when(messageMapper.selectList(any())).thenReturn(threeTurnConversationWithAnchorReplyTo("m2"));
        ClientException nonUser = assertThrows(ClientException.class,
                () -> shareService.createShare("c1", "u1", "user", "through", "m6"));
        assertEquals("消息关联关系异常，无法解析所属问答轮", nonUser.getMessage());

        verify(shareSnapshotService, never()).create(any(), anyString(), anyString(), anyString(), anyString());
    }

    /** 锚点=m6、replyTo 换成指定值的矛盾数据样本（其余同 threeTurnConversation，按 id 定点替换） */
    private List<AgentMessageDO> threeTurnConversationWithAnchorReplyTo(String replyTo) {
        return withReplaced(threeTurnConversation(), scopedMessage("m6", "assistant", "A2", replyTo, "NORMAL"));
    }

    private List<AgentMessageDO> withReplaced(List<AgentMessageDO> source, AgentMessageDO replacement) {
        return source.stream()
                .map(message -> replacement.getId().equals(message.getId()) ? replacement : message)
                .toList();
    }

    @Test
    void createShareRejectsInvalidAnchorUniformly() {
        // anchor 校验统一文案（无差异化防探测）：不存在/跨会话/跨属主/非 assistant/不可分享口径一律同文案
        when(conversationMapper.selectOne(any())).thenReturn(conversation("c1", "u1"));

        // ① 集合内不存在（含锚点属于他会话/异属主的形态——联合过滤后均不可见）
        when(messageMapper.selectList(any())).thenReturn(threeTurnConversation());
        ClientException missing = assertThrows(ClientException.class,
                () -> shareService.createShare("c1", "u1", "user", "turn", "missing"));
        assertEquals("锚点消息不存在或不属于该会话", missing.getMessage());

        // ② 锚点指向 user 消息
        when(messageMapper.selectList(any())).thenReturn(threeTurnConversation());
        ClientException userAnchor = assertThrows(ClientException.class,
                () -> shareService.createShare("c1", "u1", "user", "turn", "m1"));
        assertEquals("锚点消息不存在或不属于该会话", userAnchor.getMessage());

        // ③ INTERRUPTED 完成态之外不可作锚
        when(messageMapper.selectList(any())).thenReturn(List.of(
                scopedMessage("m1", "user", "Q1", null, "NORMAL"),
                scopedMessage("m2", "assistant", "partial", "m1", "INTERRUPTED")));
        ClientException interrupted = assertThrows(ClientException.class,
                () -> shareService.createShare("c1", "u1", "user", "turn", "m2"));
        assertEquals("锚点消息不存在或不属于该会话", interrupted.getMessage());

        // ④ 空白正文不可作锚
        when(messageMapper.selectList(any())).thenReturn(List.of(
                scopedMessage("m1", "user", "Q1", null, "NORMAL"),
                scopedMessage("m2", "assistant", "  ", "m1", "NORMAL")));
        ClientException blank = assertThrows(ClientException.class,
                () -> shareService.createShare("c1", "u1", "user", "through", "m2"));
        assertEquals("锚点消息不存在或不属于该会话", blank.getMessage());

        verify(shareSnapshotService, never()).create(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void createShareRejectsAnchorOutsideItsRootPhysicalWindow() {
        // 矛盾数据拒绝猜测（补强）：权威路径下锚点必须落在其 replyTo 根消息的物理窗口内——
        // 前指（锚点在根之前）或越窗（根与锚点之间隔着下一轮 user）都按关联冲突统一拒绝，
        // 不产出不含锚点本身的选段
        when(conversationMapper.selectOne(any())).thenReturn(conversation("c1", "u1"));

        // ① 前指：anchor 物理位置在其指向的根 user 之前
        when(messageMapper.selectList(any())).thenReturn(List.of(
                scopedMessage("m1", "assistant", "A1", "m3", "NORMAL"),
                scopedMessage("m2", "user", "Q1", null, "NORMAL"),
                scopedMessage("m3", "user", "Q2", null, "NORMAL")));
        ClientException forward = assertThrows(ClientException.class,
                () -> shareService.createShare("c1", "u1", "user", "turn", "m1"));
        assertEquals("消息关联关系异常，无法解析所属问答轮", forward.getMessage());

        // ② 越窗：根 user 与 anchor 之间物理上隔着下一轮 user（窗口右界先到）
        when(messageMapper.selectList(any())).thenReturn(List.of(
                scopedMessage("m1", "user", "Q1", null, "NORMAL"),
                scopedMessage("m2", "user", "Q2", null, "NORMAL"),
                scopedMessage("m3", "assistant", "A2", "m1", "NORMAL")));
        ClientException beyondWindow = assertThrows(ClientException.class,
                () -> shareService.createShare("c1", "u1", "user", "through", "m3"));
        assertEquals("消息关联关系异常，无法解析所属问答轮", beyondWindow.getMessage());

        verify(shareSnapshotService, never()).create(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void createShareRejectsHeadlessFallbackTurn() {
        // 顺序 fallback 找不到前置 user（headless 异常段）：明确拒绝而非猜一个轮
        when(conversationMapper.selectOne(any())).thenReturn(conversation("c1", "u1"));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                scopedMessage("m1", "assistant", "orphan", null, "NORMAL"),
                scopedMessage("m2", "user", "Q1", null, "NORMAL"),
                scopedMessage("m3", "assistant", "A1", "m2", "NORMAL")));

        ClientException headless = assertThrows(ClientException.class,
                () -> shareService.createShare("c1", "u1", "user", "turn", "m1"));
        assertEquals("该轮没有可分享的提问内容", headless.getMessage());
    }

    @Test
    void createShareRejectsInvalidScopeValue() {
        // 三值合法精确匹配；其他非空值（含大小写变体）一律业务异常
        for (String invalid : new String[]{"chapter", "FULL", "Turn"}) {
            ClientException rejected = assertThrows(ClientException.class,
                    () -> shareService.createShare("c1", "u1", "user", invalid, "m2"));
            assertEquals("无效的分享范围", rejected.getMessage());
        }
        verify(conversationMapper, never()).selectOne(any());
        verify(shareSnapshotService, never()).create(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void createShareRequiresAnchorForScopedShare() {
        // turn/through 必带 anchor（missing/null/blank 同拒）；full 不要求
        for (String scope : new String[]{"turn", "through"}) {
            for (String absent : new String[]{null, "", " "}) {
                ClientException rejected = assertThrows(ClientException.class,
                        () -> shareService.createShare("c1", "u1", "user", scope, absent));
                assertEquals("该分享范围必须指定锚点消息", rejected.getMessage());
            }
        }
        verify(conversationMapper, never()).selectOne(any());
    }

    @Test
    void createShareRejectsGuestForEveryScope() {
        // 游客硬阻断任意 scope 全拒（issue #91 增补维持），到达不了消息查询
        for (String scope : new String[]{null, "full", "turn", "through"}) {
            ClientException rejected = assertThrows(ClientException.class,
                    () -> shareService.createShare("c1", "g1", "guest", scope, "m2"));
            assertEquals("游客身份不支持创建分享，请登录后使用", rejected.getMessage());
        }
        verify(conversationMapper, never()).selectOne(any());
        verify(messageMapper, never()).selectList(any());
        verify(shareSnapshotService, never()).create(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void createShareLoadsMessagesUnderConversationUserJointFilter() {
        // 查询层防御：消息查询按 会话+用户 联合过滤（旧版仅会话），anchor/窗口解析全在该集合内
        when(conversationMapper.selectOne(any())).thenReturn(conversation("c1", "u1"));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                scopedMessage("m1", "user", "Q1", null, "NORMAL"),
                scopedMessage("m2", "assistant", "A1", "m1", "NORMAL")));

        shareService.createShare("c1", "u1", "user", null, null);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Wrapper<AgentMessageDO>> captor = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(messageMapper).selectList(captor.capture());
        AbstractWrapper<AgentMessageDO, ?, ?> wrapper =
                (AbstractWrapper<AgentMessageDO, ?, ?>) captor.getValue();
        // lambda 列解析需 TableInfo；getSqlSegment 同时把条件值物化进参数表
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentMessageDO.class);
        String segment = wrapper.getSqlSegment();
        Collection<Object> params = (Collection<Object>) wrapper.getParamNameValuePairs().values();
        assertTrue(params.contains("c1"));
        assertTrue(params.contains("u1"));
        assertTrue(segment.contains("user_id"));
        assertTrue(segment.contains("ORDER BY id ASC"));
    }
}
