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

package com.nageoffer.ai.ragent.news.fetch;

/**
 * 结构失配（#186 成功分类学第三类）：源内容结构与预期模板不符——解析器 fail-closed
 * 零条目、XML/JSON 不合法、events 端点缺占位、未知 fetch_strategy（本地配置失配）等。
 *
 * <p>语义：HTTP 层是通的（区别于网络失败），但产出不可信——fail-closed 防模板改版
 * 静默空结果。计入失败滞回；<b>不是探活的有效成功</b>（HTTP 200 不是恢复充分条件）。
 * 允许空结果的源（allow-empty 配置）解析有效但零条目时不抛本类，归
 * {@link NewsFetchOutcome#VALID_EMPTY}。
 */
public class NewsFetchStructureException extends NewsFetchException {

    public NewsFetchStructureException(String message) {
        super(message, false);
    }

    public NewsFetchStructureException(String message, Throwable cause) {
        super(message, false, cause);
    }
}
