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

package com.nageoffer.ai.ragent.agent.share.vo;

import com.nageoffer.ai.ragent.agent.share.AgentShareSnapshotItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * 会话分享公开载荷（匿名可读，字段白名单）
 *
 * <p>只含标题快照、白名单消息序列与展示元信息；消息条目类型即快照白名单
 * {@link AgentShareSnapshotItem}（role/content/createTime），公开读不存在
 * 携带身份/ID/思考/轨迹字段的路径。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PublicAgentShareVO {

    /**
     * 会话标题快照
     */
    private String title;

    /**
     * 按时间序的白名单消息对（提问与终答）
     */
    private List<AgentShareSnapshotItem> messages;

    /**
     * 内容语言启发标记（zh/en）
     */
    private String lang;

    /**
     * 内容/知识版本标记
     */
    private String contentVersion;

    /**
     * 分享创建时间
     */
    private Date createTime;

    /**
     * 过期时刻；NULL 即不过期
     */
    private Date expireTime;
}
