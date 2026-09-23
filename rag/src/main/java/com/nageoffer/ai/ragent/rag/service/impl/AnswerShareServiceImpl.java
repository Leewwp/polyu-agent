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
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.controller.vo.PublicShareVO;
import com.nageoffer.ai.ragent.rag.controller.vo.ShareCreatedVO;
import com.nageoffer.ai.ragent.rag.controller.vo.ShareMineItemVO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.service.AnswerShareService;
import com.nageoffer.ai.ragent.share.RevocationActor;
import com.nageoffer.ai.ragent.share.ShareKind;
import com.nageoffer.ai.ragent.share.ShareOwnedView;
import com.nageoffer.ai.ragent.share.SharePublicView;
import com.nageoffer.ai.ragent.share.ShareSnapshotService;
import com.nageoffer.ai.ragent.share.ShareTicket;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 公开答案分享服务实现——统一机制 adapter（issue #124）
 *
 * <p>只负责本粒度的三件事：从消息库装配快照载荷（含归属校验）、载荷序列化/反序列化、
 * module 视图到对外 VO 的字段白名单投影。token 熵/状态机/撤销幂等/过期数学/防枚举
 * 语义全在 system 的 ShareSnapshotService。公开载荷为字段白名单（PublicShareVO），
 * 不含用户身份、消息/会话 ID、思考内容与内部检索轨迹（隐私负面清单）。
 */
@Service
@RequiredArgsConstructor
public class AnswerShareServiceImpl implements AnswerShareService {

    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_USER = "user";
    private static final String LANG_ZH = "zh";
    private static final int QUESTION_PREVIEW_LENGTH = 50;

    private final ShareSnapshotService shareSnapshotService;
    private final ConversationMessageMapper conversationMessageMapper;

    /**
     * 内容/知识版本标记（随分享快照落 payload；粒度侧自持键）
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
        // 值复制语义：装配时即把 question/answer/citations 固化进载荷，读路径不回链 t_message
        String payloadJson = AnswerSharePayload.toJson(AnswerSharePayload.builder()
                .messageId(message.getId())
                .question(question)
                .answerMd(message.getContent())
                .citations(message.getSources())
                .contentVersion(contentVersion)
                .build());
        ShareTicket ticket = shareSnapshotService.create(
                ShareKind.ANSWER, userId, message.getConversationId(), detectLang(question), payloadJson);
        return ShareCreatedVO.builder().token(ticket.getToken()).expireTime(ticket.getExpireAt()).build();
    }

    @Override
    public PublicShareVO getPublicShare(String token) {
        SharePublicView view = shareSnapshotService.readByToken(token);
        AnswerSharePayload payload = AnswerSharePayload.parse(view.getPayload());
        return PublicShareVO.builder()
                .question(payload.getQuestion())
                .answerMd(payload.getAnswerMd())
                .citations(payload.getCitations())
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
    public List<ShareMineItemVO> listMine(String userId) {
        Assert.notBlank(userId, () -> new ClientException("未获取到当前登录用户"));
        return shareSnapshotService.listByOwner(userId, ShareKind.ANSWER).stream()
                .map(this::toItemVO)
                .toList();
    }

    private ShareMineItemVO toItemVO(ShareOwnedView share) {
        AnswerSharePayload payload = AnswerSharePayload.parse(share.getPayload());
        return ShareMineItemVO.builder()
                .token(share.getToken())
                .questionPreview(preview(payload.getQuestion()))
                .status(share.getStatus())
                .expireTime(share.getExpireTime())
                .createTime(share.getCreateTime())
                .build();
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

    /**
     * 轻量语言启发：含 CJK 统一表意文字即 zh，否则 en；仅决定分享页默认展示语言
     */
    private String detectLang(String text) {
        return text != null && text.chars().anyMatch(cp -> (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF))
                ? LANG_ZH : "en";
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= QUESTION_PREVIEW_LENGTH ? text : text.substring(0, QUESTION_PREVIEW_LENGTH) + "…";
    }
}
