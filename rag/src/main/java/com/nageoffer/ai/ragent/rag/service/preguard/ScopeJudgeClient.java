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

package com.nageoffer.ai.ragent.rag.service.preguard;

/**
 * 不确定带的轻量 LLM 判定客户端：一次判定一票，超时/报错/解析失败一律 FAILED（fail-open）
 */
public interface ScopeJudgeClient {

    /**
     * 判定问题是否属于本系统可答范围（PolyU 校园/机构/学生生活语境）
     *
     * @param question 用户原始问题
     * @return 判定票；FAILED 表示超时/报错/解析失败，调用方按放行处理
     */
    Vote vote(String question);

    /**
     * 判定票
     */
    enum Vote {

        /**
         * 域内可答，放行
         */
        IN_SCOPE,

        /**
         * 开放域无关直答，拒答
         */
        OUT_OF_SCOPE,

        /**
         * 超时/报错/解析失败，fail-open 放行
         */
        FAILED
    }
}
