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

package com.nageoffer.ai.ragent.site.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 反馈列表项（admin 面）：IP 脱敏展示（保留末段），原文只存库
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SiteFeedbackVO {

    private Long id;

    /**
     * 反馈内容
     */
    private String content;

    /**
     * 选填联系方式
     */
    private String contact;

    /**
     * 客户端 IP（脱敏：1.2.*.*，仅末段还原不够定位滥用，保留前后段）
     */
    private String clientIpMasked;

    /**
     * 0 未处理 / 1 已处理 / 2 忽略
     */
    private Integer status;

    private Date createTime;

    private Date updateTime;
}
