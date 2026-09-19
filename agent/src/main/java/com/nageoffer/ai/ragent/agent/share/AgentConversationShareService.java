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

import com.nageoffer.ai.ragent.agent.share.vo.AgentShareAdminItemVO;
import com.nageoffer.ai.ragent.agent.share.vo.AgentShareCreatedVO;
import com.nageoffer.ai.ragent.agent.share.vo.AgentShareMineItemVO;
import com.nageoffer.ai.ragent.agent.share.vo.PublicAgentShareVO;

import java.util.List;

/**
 * Agent 会话只读分享服务（issue #82）
 *
 * <p>快照不可变：创建时对标题与白名单消息对值复制进 t_agent_conversation_share，
 * 公开读只读本表。克隆 AnswerShareService 契约形态（创建/公开读/撤销/我的列表）。
 */
public interface AgentConversationShareService {

    /**
     * 创建会话分享快照（校验会话存在、属于本人、含至少一组有效问答）
     */
    AgentShareCreatedVO createShare(String conversationId, String userId);

    /**
     * 匿名读公开载荷（不存在/已撤销/已过期统一抛「分享链接无效或已撤销」）
     */
    PublicAgentShareVO getPublicShare(String token);

    /**
     * 撤销分享（owner 校验；adminOverride 跳过；重复撤销幂等）
     */
    void revokeShare(String token, String userId, boolean adminOverride);

    /**
     * 本人分享列表
     */
    List<AgentShareMineItemVO> listMine(String userId);

    /**
     * 管理面全量列表（治理违规内容）
     */
    List<AgentShareAdminItemVO> listAllForAdmin();
}
