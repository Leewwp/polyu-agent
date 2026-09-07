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

import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.controller.vo.PublicShareVO;
import com.nageoffer.ai.ragent.rag.controller.vo.ShareCreatedVO;
import com.nageoffer.ai.ragent.rag.controller.vo.ShareMineItemVO;
import com.nageoffer.ai.ragent.rag.dao.entity.AnswerShareDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AnswerShareMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.service.AnswerShareService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * 公开答案分享服务实现
 *
 * <p>快照不可变：question/answer/citations 在创建时值复制进 t_answer_share，
 * 公开读只读本表。公开载荷为字段白名单（PublicShareVO），不含用户身份、
 * 消息/会话 ID、思考内容与内部检索轨迹（doc 13 §10 负面清单）。
 */
@Service
@RequiredArgsConstructor
public class AnswerShareServiceImpl implements AnswerShareService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_REVOKED = "REVOKED";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_USER = "user";
    private static final String LANG_ZH = "zh";
    private static final String LANG_EN = "en";
    private static final int TOKEN_BYTES = 32;
    private static final int QUESTION_PREVIEW_LENGTH = 50;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AnswerShareMapper answerShareMapper;
    private final ConversationMessageMapper conversationMessageMapper;

    /**
     * 过期默认天数；0 或负数 = 不过期。终值随门批复（doc 13 §16 建议 90 天或不过期）
     */
    @Value("${rag.share.default-expire-days:90}")
    private int defaultExpireDays;

    /**
     * 内容/知识版本标记（随分享快照落库）
     */
    @Value("${rag.share.content-version:v1}")
    private String contentVersion;

    @Override
    public ShareCreatedVO createShare(String messageId, String userId) {
        Assert.notBlank(userId, () -> new ClientException("未获取到当前登录用户"));
        Assert.notBlank(messageId, () -> new ClientException("消息ID不能为空"));

        ConversationMessageDO message = conversationMessageMapper.selectById(messageId);
        Assert.notNull(message, () -> new ClientException("消息不存在"));
        if (!ROLE_ASSISTANT.equals(message.getRole())) {
            throw new ClientException("仅支持分享助手回答");
        }
        if (!userId.equals(message.getUserId())) {
            throw new ClientException("无权分享该消息");
        }

        String question = loadQuestionSnapshot(message);
        Date now = new Date();
        Date expireTime = defaultExpireDays > 0 ? new Date(now.getTime() + defaultExpireDays * 86_400_000L) : null;

        AnswerShareDO share = AnswerShareDO.builder()
                .token(generateToken())
                .ownerUserId(userId)
                .messageId(message.getId())
                .conversationId(message.getConversationId())
                .question(question)
                .answerMd(message.getContent())
                // 值复制语义：SourceRefListTypeHandler 落库时序列化快照，读路径不再回链 t_message
                .citations(message.getSources())
                .lang(detectLang(question))
                .contentVersion(contentVersion)
                .status(STATUS_ACTIVE)
                .expireTime(expireTime)
                .build();
        answerShareMapper.insert(share);
        return ShareCreatedVO.builder().token(share.getToken()).expireTime(expireTime).build();
    }

    @Override
    public PublicShareVO getPublicShare(String token) {
        AnswerShareDO share = selectByToken(token);
        // 不存在/已撤销/已过期统一同一语义，防 token 探测侧信道
        if (share == null || STATUS_REVOKED.equals(share.getStatus()) || isExpired(share)) {
            throw new ClientException("分享链接无效或已撤销");
        }
        return PublicShareVO.builder()
                .question(share.getQuestion())
                .answerMd(share.getAnswerMd())
                .citations(share.getCitations())
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
        AnswerShareDO share = selectByToken(token);
        Assert.notNull(share, () -> new ClientException("分享不存在"));
        if (!adminOverride && !userId.equals(share.getOwnerUserId())) {
            throw new ClientException("无权撤销该分享");
        }
        if (STATUS_REVOKED.equals(share.getStatus())) {
            return;
        }
        AnswerShareDO update = new AnswerShareDO();
        update.setId(share.getId());
        update.setStatus(STATUS_REVOKED);
        update.setRevokedTime(new Date());
        answerShareMapper.updateById(update);
    }

    @Override
    public List<ShareMineItemVO> listMine(String userId) {
        Assert.notBlank(userId, () -> new ClientException("未获取到当前登录用户"));
        LambdaQueryWrapper<AnswerShareDO> wrapper = new LambdaQueryWrapper<AnswerShareDO>()
                .eq(AnswerShareDO::getOwnerUserId, userId)
                .orderByDesc(AnswerShareDO::getCreateTime);
        return answerShareMapper.selectList(wrapper).stream()
                .map(share -> ShareMineItemVO.builder()
                        .token(share.getToken())
                        .questionPreview(preview(share.getQuestion()))
                        .status(share.getStatus())
                        .expireTime(share.getExpireTime())
                        .createTime(share.getCreateTime())
                        .build())
                .toList();
    }

    /**
     * 问题快照：沿 assistant 消息的 reply_to_message_id 回溯前驱 user 消息；
     * 前驱缺失（数据异常）时给出可读占位，绝不因此阻塞分享
     */
    private String loadQuestionSnapshot(ConversationMessageDO assistantMessage) {
        String replyTo = assistantMessage.getReplyToMessageId();
        if (replyTo != null && !replyTo.isBlank()) {
            ConversationMessageDO question = conversationMessageMapper.selectById(replyTo);
            if (question != null && ROLE_USER.equals(question.getRole())
                    && question.getContent() != null && !question.getContent().isBlank()) {
                return question.getContent();
            }
        }
        throw new ClientException("未找到该回答对应的提问");
    }

    private AnswerShareDO selectByToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return answerShareMapper.selectOne(new LambdaQueryWrapper<AnswerShareDO>()
                .eq(AnswerShareDO::getToken, token));
    }

    private boolean isExpired(AnswerShareDO share) {
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
     * 轻量语言启发：含 CJK 统一表意文字即 zh，否则 en；仅决定分享页默认展示语言
     */
    private String detectLang(String text) {
        return text != null && text.chars().anyMatch(cp -> (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF))
                ? LANG_ZH : LANG_EN;
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= QUESTION_PREVIEW_LENGTH ? text : text.substring(0, QUESTION_PREVIEW_LENGTH) + "…";
    }
}
