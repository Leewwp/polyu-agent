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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentConversationDO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentConversationMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.share.vo.AgentShareAdminItemVO;
import com.nageoffer.ai.ragent.agent.share.vo.AgentShareCreatedVO;
import com.nageoffer.ai.ragent.agent.share.vo.AgentShareMineItemVO;
import com.nageoffer.ai.ragent.agent.share.vo.PublicAgentShareVO;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * Agent 会话只读分享服务实现（issue #82）
 *
 * <p>克隆 AnswerShareServiceImpl 契约形态；差异仅在快照粒度——本服务做会话级
 * 快照：标题 + 按序白名单消息对（role/content/createTime），空白正文消息自然
 * 跳过，blocks/思考/耗时/ID/身份字段一律不进快照（隐私负面清单）。
 */
@Service
@RequiredArgsConstructor
public class AgentConversationShareServiceImpl implements AgentConversationShareService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_REVOKED = "REVOKED";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_USER = "user";
    private static final String LANG_ZH = "zh";
    private static final String LANG_EN = "en";
    private static final int TOKEN_BYTES = 32;
    private static final int TITLE_PREVIEW_LENGTH = 50;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AgentConversationShareMapper shareMapper;
    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;

    /**
     * 过期默认天数；0 或负数 = 不过期（agent.share.default-expire-days）
     */
    @Value("${agent.share.default-expire-days:90}")
    private int defaultExpireDays;

    /**
     * 内容/知识版本标记（随分享快照落库）
     */
    @Value("${agent.share.content-version:v1}")
    private String contentVersion;

    @Override
    public AgentShareCreatedVO createShare(String conversationId, String userId) {
        Assert.notBlank(userId, () -> new ClientException("未获取到当前登录用户"));
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

        // 快照白名单：仅 role/content/createTime；空白正文（挂起确认卡、空轮）自然跳过
        List<AgentShareSnapshotItem> snapshot = messages.stream()
                .filter(message -> ROLE_USER.equals(message.getRole()) || ROLE_ASSISTANT.equals(message.getRole()))
                .filter(message -> message.getContent() != null && !message.getContent().isBlank())
                .map(message -> AgentShareSnapshotItem.builder()
                        .role(message.getRole())
                        .content(message.getContent())
                        .createTime(message.getCreateTime())
                        .build())
                .toList();
        boolean hasQuestion = snapshot.stream().anyMatch(item -> ROLE_USER.equals(item.getRole()));
        boolean hasAnswer = snapshot.stream().anyMatch(item -> ROLE_ASSISTANT.equals(item.getRole()));
        if (!hasQuestion || !hasAnswer) {
            throw new ClientException("会话中没有可分享的问答内容");
        }

        Date now = new Date();
        Date expireTime = defaultExpireDays > 0 ? new Date(now.getTime() + defaultExpireDays * 86_400_000L) : null;

        String title = conversation.getTitle() != null && !conversation.getTitle().isBlank()
                ? conversation.getTitle() : "新对话";
        AgentConversationShareDO share = AgentConversationShareDO.builder()
                .token(generateToken())
                .ownerUserId(userId)
                .conversationId(conversationId)
                .title(title)
                // 值复制语义：typeHandler 落库时序列化快照，读路径不再回链业务表
                .messages(snapshot)
                .lang(detectLang(snapshot))
                .contentVersion(contentVersion)
                .status(STATUS_ACTIVE)
                .expireTime(expireTime)
                .build();
        shareMapper.insert(share);
        return AgentShareCreatedVO.builder().token(share.getToken()).expireTime(expireTime).build();
    }

    @Override
    public PublicAgentShareVO getPublicShare(String token) {
        AgentConversationShareDO share = selectByToken(token);
        // 不存在/已撤销/已过期统一同一语义，防 token 探测侧信道
        if (share == null || STATUS_REVOKED.equals(share.getStatus()) || isExpired(share)) {
            throw new ClientException("分享链接无效或已撤销");
        }
        return PublicAgentShareVO.builder()
                .title(share.getTitle())
                .messages(share.getMessages())
                .lang(share.getLang())
                .contentVersion(share.getContentVersion())
                .createTime(share.getCreateTime())
                .expireTime(share.getExpireTime())
                .build();
    }

    @Override
    public void revokeShare(String token, String userId, boolean adminOverride) {
        if (!adminOverride) {
            Assert.notBlank(userId, () -> new ClientException("未获取到当前登录用户"));
        }
        AgentConversationShareDO share = selectByToken(token);
        Assert.notNull(share, () -> new ClientException("分享不存在"));
        if (!adminOverride && !Objects.equals(userId, share.getOwnerUserId())) {
            throw new ClientException("无权撤销该分享");
        }
        if (STATUS_REVOKED.equals(share.getStatus())) {
            return;
        }
        AgentConversationShareDO update = new AgentConversationShareDO();
        update.setId(share.getId());
        update.setStatus(STATUS_REVOKED);
        update.setRevokedTime(new Date());
        shareMapper.updateById(update);
    }

    @Override
    public List<AgentShareMineItemVO> listMine(String userId) {
        Assert.notBlank(userId, () -> new ClientException("未获取到当前登录用户"));
        return shareMapper.selectList(new LambdaQueryWrapper<AgentConversationShareDO>()
                        .eq(AgentConversationShareDO::getOwnerUserId, userId)
                        .orderByDesc(AgentConversationShareDO::getCreateTime))
                .stream()
                .map(this::toItemVO)
                .toList();
    }

    @Override
    public List<AgentShareAdminItemVO> listAllForAdmin() {
        return shareMapper.selectList(new LambdaQueryWrapper<AgentConversationShareDO>()
                        .orderByDesc(AgentConversationShareDO::getCreateTime))
                .stream()
                .map(share -> {
                    AgentShareAdminItemVO item = new AgentShareAdminItemVO();
                    item.setToken(share.getToken());
                    item.setTitlePreview(preview(share.getTitle()));
                    item.setMessageCount(share.getMessages() == null ? 0 : share.getMessages().size());
                    item.setStatus(share.getStatus());
                    item.setExpireTime(share.getExpireTime());
                    item.setCreateTime(share.getCreateTime());
                    item.setOwnerUserId(share.getOwnerUserId());
                    return item;
                })
                .toList();
    }

    private AgentShareMineItemVO toItemVO(AgentConversationShareDO share) {
        return AgentShareMineItemVO.builder()
                .token(share.getToken())
                .titlePreview(preview(share.getTitle()))
                .messageCount(share.getMessages() == null ? 0 : share.getMessages().size())
                .status(share.getStatus())
                .expireTime(share.getExpireTime())
                .createTime(share.getCreateTime())
                .build();
    }

    private AgentConversationShareDO selectByToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return shareMapper.selectOne(new LambdaQueryWrapper<AgentConversationShareDO>()
                .eq(AgentConversationShareDO::getToken, token));
    }

    private boolean isExpired(AgentConversationShareDO share) {
        return share.getExpireTime() != null && share.getExpireTime().before(new Date());
    }

    /**
     * 256-bit 加密随机 token（Base64URL 无填充，43 字符），不可枚举
     */
    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 轻量语言启发：任一消息含 CJK 统一表意文字即 zh，否则 en；仅决定分享页默认展示语言
     */
    private String detectLang(List<AgentShareSnapshotItem> snapshot) {
        return snapshot.stream()
                .map(AgentShareSnapshotItem::getContent)
                .anyMatch(text -> text != null && text.chars().anyMatch(cp ->
                        (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF)))
                ? LANG_ZH : LANG_EN;
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= TITLE_PREVIEW_LENGTH ? text : text.substring(0, TITLE_PREVIEW_LENGTH) + "…";
    }
}
