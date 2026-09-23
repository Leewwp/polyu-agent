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

package com.nageoffer.ai.ragent.rag.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * 抓取/上传字节上限单点（issue #125）：「上传=抓取同口径」从四处各读一次的注释级
 * 约定升格为单点——上限值仍读 Spring multipart 标准键（{@code spring.servlet.multipart.
 * max-file-size}），消费点一律注入本类，不再各自 @Value（grep max-file-size 在 main
 * 代码仅本类一处，未来调口径只动 yaml）。
 *
 * <p>持有原始字符串（如 "50MB"）而非 DataSize：超限拒绝的用户文案须逐字保留 yaml
 * 原文（DataSize 会把 50MB 规范化成 52428800B，改变既有消息文案）。
 */
@Component
public class FetchLimits {

    /**
     * 单文件字节上限原文（默认 50MB）：multipart 直传上限 / URL 抓取上限 / 资讯正文
     * 上限 / MinerU 结果 zip 上限 / 飞书内容抓取上限共用同一值
     */
    @Value("${spring.servlet.multipart.max-file-size:50MB}")
    private String maxFileSize;

    /**
     * 抓取与上传共用的字节上限
     */
    public long maxFetchBytes() {
        return DataSize.parse(maxFileSize).toBytes();
    }

    /**
     * 面向用户的上限文案（yaml 原文，如 "50MB"），超限拒绝消息用
     */
    public String maxFileSizeDisplay() {
        return maxFileSize;
    }
}
