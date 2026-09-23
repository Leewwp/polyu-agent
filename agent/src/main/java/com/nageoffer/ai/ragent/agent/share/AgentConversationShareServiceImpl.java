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

import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import com.nageoffer.ai.ragent.agent.tool.KnowledgeSearchTool;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.share.RevocationActor;
import com.nageoffer.ai.ragent.share.ShareAdminView;
import com.nageoffer.ai.ragent.share.ShareKind;
import com.nageoffer.ai.ragent.share.ShareOwnedView;
import com.nageoffer.ai.ragent.share.SharePublicView;
import com.nageoffer.ai.ragent.share.ShareSnapshotService;
import com.nageoffer.ai.ragent.share.ShareTicket;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Agent 会话只读分享服务实现——统一机制 adapter（issue #82 → #124）
 *
 * <p>只负责本粒度的三件事：从会话/消息库装配快照载荷（含归属校验与游客硬阻断）、
 * 载荷序列化/反序列化、module 视图到对外 VO 的白名单投影。token 熵/状态机/撤销
 * 幂等/过期数学/防枚举语义全在 system 的 ShareSnapshotService。快照白名单：
 * role/content/createTime +（v2）assistant 条目的可选 sources 投影；
 * blocks/思考/耗时/ID/身份字段一律不进快照（隐私负面清单）。
 */
@Service
@RequiredArgsConstructor
public class AgentConversationShareServiceImpl implements AgentConversationShareService {

    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_USER = "user";
    private static final String ROLE_GUEST = "guest";
    private static final String BLOCK_KIND_TOOL = "tool";
    private static final String LANG_ZH = "zh";
    private static final int TITLE_PREVIEW_LENGTH = 50;

    private final ShareSnapshotService shareSnapshotService;
    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;

    /**
     * 内容/知识版本标记（随分享快照落 payload；粒度侧自持键）；v2 起 assistant 条目携带 sources 投影
     */
    @Value("${agent.share.content-version:v2}")
    private String contentVersion;

    @Override
    public AgentShareCreatedVO createShare(String conversationId, String userId, String role) {
        Assert.notBlank(userId, () -> new ClientException("未获取到当前登录用户"));
        // 游客硬阻断（issue #91 增补，2026-09-19 维护者裁定）：游客为临时身份，cookie
        // 丢失后其分享成为无人可撤销的孤儿、仅剩 admin 兜底；与前端按钮对 guest 隐藏互为双保险
        Assert.isTrue(!ROLE_GUEST.equals(role), () -> new ClientException("游客身份不支持创建分享，请登录后使用"));
        Assert.notBlank(conversationId, () -> new ClientException("会话ID不能为空"));

        AgentConversationDO conversation = conversationMapper.selectOne(
                Wrappers.lambdaQuery(AgentConversationDO.class)
                        .eq(AgentConversationDO::getConversationId, conversationId)
                        .eq(AgentConversationDO::getUserId, userId));
        Assert.notNull(conversation, () -> new ClientException("会话不存在或无权分享"));

        List<AgentMessageDO> messages = messageMapper.selectList(
                Wrappers.lambdaQuery(AgentMessageDO.class)
                        .eq(AgentMessageDO::getConversationId, conversationId)
                        .orderByAsc(AgentMessageDO::getId));

        // 快照白名单：role/content/createTime +（v2）assistant 条目的可选 sources 投影；
        // 空白正文（挂起确认卡、空轮）自然跳过
        List<AgentShareSnapshotItem> snapshot = messages.stream()
                .filter(message -> ROLE_USER.equals(message.getRole()) || ROLE_ASSISTANT.equals(message.getRole()))
                .filter(message -> message.getContent() != null && !message.getContent().isBlank())
                .map(message -> AgentShareSnapshotItem.builder()
                        .role(message.getRole())
                        .content(message.getContent())
                        .createTime(message.getCreateTime())
                        .sources(ROLE_ASSISTANT.equals(message.getRole()) ? extractSources(message) : null)
                        .build())
                .toList();
        boolean hasQuestion = snapshot.stream().anyMatch(item -> ROLE_USER.equals(item.getRole()));
        boolean hasAnswer = snapshot.stream().anyMatch(item -> ROLE_ASSISTANT.equals(item.getRole()));
        if (!hasQuestion || !hasAnswer) {
            throw new ClientException("会话中没有可分享的问答内容");
        }

        String title = conversation.getTitle() != null && !conversation.getTitle().isBlank()
                ? conversation.getTitle() : "新对话";
        // 值复制语义：装配时即把标题与消息对固化进载荷，读路径不回链业务表
        String payloadJson = AgentConversationSharePayload.toJson(AgentConversationSharePayload.builder()
                .title(title)
                .messages(snapshot)
                .contentVersion(contentVersion)
                .build());
        ShareTicket ticket = shareSnapshotService.create(
                ShareKind.CONVERSATION, userId, conversationId, detectLang(snapshot), payloadJson);
        return AgentShareCreatedVO.builder().token(ticket.getToken()).expireTime(ticket.getExpireAt()).build();
    }

    @Override
    public PublicAgentShareVO getPublicShare(String token) {
        SharePublicView view = shareSnapshotService.readByToken(token);
        AgentConversationSharePayload payload = AgentConversationSharePayload.parse(view.getPayload());
        return PublicAgentShareVO.builder()
                .title(payload.getTitle())
                .messages(payload.getMessages())
                .lang(view.getLang())
                .contentVersion(payload.getContentVersion())
                .createTime(view.getCreateTime())
                .expireTime(view.getExpireTime())
                .build();
    }

    @Override
    public void revokeShare(String token, String userId, boolean adminOverride) {
        shareSnapshotService.revoke(token, adminOverride ? RevocationActor.admin() : RevocationActor.owner(userId));
    }

    @Override
    public List<AgentShareMineItemVO> listMine(String userId) {
        Assert.notBlank(userId, () -> new ClientException("未获取到当前登录用户"));
        return shareSnapshotService.listByOwner(userId, ShareKind.CONVERSATION).stream()
                .map(this::toItemVO)
                .toList();
    }

    @Override
    public List<AgentShareAdminItemVO> listAllForAdmin() {
        return shareSnapshotService.adminList(ShareKind.CONVERSATION).stream()
                .map(share -> {
                    AgentConversationSharePayload payload = AgentConversationSharePayload.parse(share.getPayload());
                    AgentShareAdminItemVO item = new AgentShareAdminItemVO();
                    item.setToken(share.getToken());
                    item.setTitlePreview(preview(payload.getTitle()));
                    item.setMessageCount(payload.getMessages() == null ? 0 : payload.getMessages().size());
                    item.setStatus(share.getStatus());
                    item.setExpireTime(share.getExpireTime());
                    item.setCreateTime(share.getCreateTime());
                    item.setOwnerUserId(share.getOwnerUserId());
                    return item;
                })
                .toList();
    }

    /**
     * 从 assistant 消息 blocks 提取检索来源投影（issue #91 白名单 v2）：
     * 只认 search_knowledge 工具块携带的 sources，按块序摊平、docId 去重
     * （同一文档多次命中只保留首次），展示序号按合并后顺序 1 基编号；
     * 工具块的入参/结果/耗时等其余字段不外发。无可投影来源返回 null（user 条目同）。
     */
    private List<AgentShareSnapshotSource> extractSources(AgentMessageDO message) {
        if (message.getBlocks() == null || message.getBlocks().isEmpty()) {
            return null;
        }
        Set<String> seenDocIds = new HashSet<>();
        List<AgentShareSnapshotSource> merged = new ArrayList<>();
        for (AgentBlock block : message.getBlocks()) {
            if (block == null || !BLOCK_KIND_TOOL.equals(block.getKind())
                    || !KnowledgeSearchTool.TOOL_NAME.equals(block.getName())
                    || block.getSources() == null) {
                continue;
            }
            for (AgentBlockSource source : block.getSources()) {
                if (source == null || source.getDocId() == null || !seenDocIds.add(source.getDocId())) {
                    continue;
                }
                merged.add(AgentShareSnapshotSource.builder()
                        .docId(source.getDocId())
                        .docName(source.getDocName())
                        .excerpt(source.getExcerpt())
                        .sourceType(source.getSourceType())
                        .url(source.getUrl())
                        .build());
            }
        }
        if (merged.isEmpty()) {
            return null;
        }
        for (int i = 0; i < merged.size(); i++) {
            merged.get(i).setIndex(i + 1);
        }
        return merged;
    }

    private AgentShareMineItemVO toItemVO(ShareOwnedView share) {
        AgentConversationSharePayload payload = AgentConversationSharePayload.parse(share.getPayload());
        return AgentShareMineItemVO.builder()
                .token(share.getToken())
                .titlePreview(preview(payload.getTitle()))
                .messageCount(payload.getMessages() == null ? 0 : payload.getMessages().size())
                .status(share.getStatus())
                .expireTime(share.getExpireTime())
                .createTime(share.getCreateTime())
                .build();
    }

    /**
     * 轻量语言启发：任一消息含 CJK 统一表意文字即 zh，否则 en；仅决定分享页默认展示语言
     */
    private String detectLang(List<AgentShareSnapshotItem> snapshot) {
        return snapshot.stream()
                .map(AgentShareSnapshotItem::getContent)
                .anyMatch(text -> text != null && text.chars().anyMatch(cp ->
                        (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF)))
                ? LANG_ZH : "en";
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= TITLE_PREVIEW_LENGTH ? text : text.substring(0, TITLE_PREVIEW_LENGTH) + "…";
    }
}
