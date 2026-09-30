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

package com.nageoffer.ai.ragent.calendar.model;

/**
 * 候选片段去向（合同§4 门 2）：每个有业务含义的片段恰有一个去向；未知候选
 * 必须为 0 才允许整源发布（百分比不是授权——r3 撤销了 r2 的未知率≤10% 放行）。
 * UNKNOWN 仅存在于解析中间态：出现即整源退化（不写库的判据）。
 */
public enum Disposition {

    /**
     * 权威写事件片段（进白名单合并与发布）
     */
    WRITE,

    /**
     * 校验片段（与权威写者比对，只产生 discrepancy 记录，零写径）
     */
    VERIFY,

    /**
     * 已知跳过（导航/装饰/占位，逐项规则显式排除——不是「解析不了」的兜底）
     */
    KNOWN_SKIP,

    /**
     * 未知候选（词表/模式未命中或日期组装失败）——出现即整源退化
     */
    UNKNOWN
}
