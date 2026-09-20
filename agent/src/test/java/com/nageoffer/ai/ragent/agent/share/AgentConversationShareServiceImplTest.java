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
import com.nageoffer.ai.ragent.agent.dto.AgentBlock;
import com.nageoffer.ai.ragent.agent.dto.AgentBlockSource;
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
 * 创建守卫（非本人/无问答）、token 形态、过期判定、撤销幂等与越权、统一无效语义；
 * issue #91 追加：快照 v2 sources 投影白名单、guest 硬阻断、v1 旧快照公开读兼容。
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
        ReflectionTestUtils.setField(shareService, "contentVersion", "v2");
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
        assertNull(snapshot.getMessages().get(0).getSources());
        assertEquals("assistant", snapshot.getMessages().get(1).getRole());
        assertEquals("Apply online via the **portal**.", snapshot.getMessages().get(1).getContent());
        // 白名单外字段在快照条目类型上不存在（编译期保证），身份/溯源只落在内部列
        assertEquals("u1", snapshot.getOwnerUserId());
        assertEquals("c1", snapshot.getConversationId());
        assertEquals("How to apply for a dorm?", snapshot.getTitle());
        assertEquals("ACTIVE", snapshot.getStatus());
        assertEquals("v2", snapshot.getContentVersion());
        assertEquals("en", snapshot.getLang());
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

        ArgumentCaptor<AgentConversationShareDO> captor = ArgumentCaptor.forClass(AgentConversationShareDO.class);
        verify(shareMapper).insert(captor.capture());
        List<AgentShareSnapshotItem> messages = captor.getValue().getMessages();
        assertEquals(2, messages.size());
        List<AgentShareSnapshotSource> sources = messages.get(1).getSources();
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
        // 工具结果/思考正文不出现在快照 JSON 的任何条目里
        String snapshotJson = cn.hutool.json.JSONUtil.toJsonStr(messages);
        assertTrue(!snapshotJson.contains("SECRET-TOOL-RESULT") && !snapshotJson.contains("SECRET-FOREIGN-RESULT"));
        assertTrue(!snapshotJson.contains("SECRET-REASONING") && !snapshotJson.contains("SECRET-THINKING"));
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

        ArgumentCaptor<AgentConversationShareDO> captor = ArgumentCaptor.forClass(AgentConversationShareDO.class);
        verify(shareMapper).insert(captor.capture());
        assertNull(captor.getValue().getMessages().get(1).getSources());
    }

    @Test
    void createShareRejectsGuestHard() {
        // 游客硬阻断（issue #91 增补）：与前端按钮隐藏互为双保险
        ClientException rejected = assertThrows(ClientException.class,
                () -> shareService.createShare("c1", "g1", "guest"));
        assertEquals("游客身份不支持创建分享，请登录后使用", rejected.getMessage());
        verify(shareMapper, never()).insert(any(AgentConversationShareDO.class));
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
        verify(shareMapper, never()).insert(any(AgentConversationShareDO.class));
    }

    @Test
    void publicPayloadIsFieldWhitelist() {
        // v1 旧快照（无 sources 字段）公开读兼容：条目原样读出、sources 自然缺省
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
        assertNull(vo.getMessages().get(0).getSources());
        assertNull(vo.getMessages().get(1).getSources());
        assertEquals("zh", vo.getLang());
        assertEquals("v1", vo.getContentVersion());
        assertNotNull(vo.getCreateTime());
    }

    @Test
    void publicPayloadPassesSourcesThroughForV2Snapshot() {
        AgentConversationShareDO stored = AgentConversationShareDO.builder()
                .token("t".repeat(43))
                .ownerUserId("u1")
                .conversationId("c1")
                .title("标题")
                .messages(List.of(
                        AgentShareSnapshotItem.builder().role("user").content("q").createTime(new Date()).build(),
                        AgentShareSnapshotItem.builder().role("assistant").content("a").createTime(new Date())
                                .sources(List.of(AgentShareSnapshotSource.builder()
                                        .index(1).docId("d1").docName("宿舍指南")
                                        .excerpt("摘录").sourceType("file").url("https://polyu.edu.hk/dl").build()))
                                .build()))
                .lang("zh")
                .contentVersion("v2")
                .status("ACTIVE")
                .createTime(new Date())
                .build();
        when(shareMapper.selectOne(any())).thenReturn(stored);

        PublicAgentShareVO vo = shareService.getPublicShare(stored.getToken());
        assertEquals(1, vo.getMessages().get(1).getSources().size());
        assertEquals("d1", vo.getMessages().get(1).getSources().get(0).getDocId());
        assertEquals("file", vo.getMessages().get(1).getSources().get(0).getSourceType());
        assertEquals("v2", vo.getContentVersion());
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

        AgentShareCreatedVO created = shareService.createShare("c1", "u1", "user");
        assertNull(created.getExpireTime());
    }

    @Test
    void tokenGenerationIsUniqueAndUrlSafe() {
        when(conversationMapper.selectOne(any())).thenReturn(conversation("c1", "u1"));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("m1", "user", "问"),
                message("m2", "assistant", "答")));
        AgentShareCreatedVO first = shareService.createShare("c1", "u1", "user");
        AgentShareCreatedVO second = shareService.createShare("c1", "u1", "user");
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

        AgentShareCreatedVO created = shareService.createShare("c1", "u1", "user");
        ArgumentCaptor<AgentConversationShareDO> captor = ArgumentCaptor.forClass(AgentConversationShareDO.class);
        verify(shareMapper).insert(captor.capture());
        assertEquals("zh", captor.getValue().getLang());
    }
}
