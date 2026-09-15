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

package com.nageoffer.ai.ragent.rag.controller.vo;

import com.nageoffer.ai.ragent.framework.convention.SourceRef;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * 公开分享载荷（匿名可读）
 *
 * <p>字段白名单合同：只含问题、回答、结构化引用、语言、内容版本与时间；
 * 不含任何用户身份、消息/会话 ID、思考内容与内部检索轨迹。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicShareVO {

    /**
     * 问题快照
     */
    private String question;

    /**
     * 回答 Markdown 快照
     */
    private String answerMd;

    /**
     * 结构化官方引用
     */
    private List<SourceRef> citations;

    /**
     * 问题语言（zh/en）
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
