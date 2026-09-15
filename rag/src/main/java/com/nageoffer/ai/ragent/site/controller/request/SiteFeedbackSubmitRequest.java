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

package com.nageoffer.ai.ragent.site.controller.request;

import lombok.Data;

/**
 * 匿名反馈提交请求（doc 25 D1：不要求登录，内容必填+联系方式选填）
 */
@Data
public class SiteFeedbackSubmitRequest {

    /**
     * 反馈内容（10–2000 字）
     */
    private String content;

    /**
     * 选填联系方式（≤100 字）
     */
    private String contact;
}
