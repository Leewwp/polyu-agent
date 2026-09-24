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

package com.nageoffer.ai.ragent.agent.share.request;

import lombok.Data;

/**
 * 创建会话分享请求体（issue #138 向后兼容扩展 scope/anchor，旧客户端只传 conversationId 仍是完整对话）
 */
@Data
public class AgentShareCreateRequest {

    /**
     * 会话业务 ID（校验存在且属于本人且含至少一组有效问答）
     */
    private String conversationId;

    /**
     * 分享范围 full|turn|through（issue #138）；missing/null/blank=full，
     * 其他非空值业务异常拒绝；full 不要求 anchor（有也忽略）
     *
     * @see com.nageoffer.ai.ragent.agent.share.AgentShareScope
     */
    private String scope;

    /**
     * 锚点 assistant 消息 ID（turn/through 必带）：turn=锚点所属完整 Turn、
     * through=从开头到锚点轮末尾。**全链 String**——消息主键为 VARCHAR(20) 雪花
     * （18-19 位超 JS Number 安全整数），禁止 Long/parseLong/parseInt
     */
    private String anchorAssistantMessageId;
}
