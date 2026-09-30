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
 * 策略禁止（#186 成功分类学第五类）：抓取被策略性拒绝——robots.txt Disallow
 * （源站运营方明示禁止）或出站目标守卫拒绝（本方 SSRF 红线）。
 *
 * <p>语义：不是源故障（区别于网络失败/结构失配），重试无意义、可达性再好也不解禁——
 * 命中即转 {@code disabled_reason='policy'} 停用，退出探活对象（政策禁止不因可达
 * 自动解禁）；robots 规则变更后的复归=人工（robots 缓存有 TTL，复归后会重读）。
 */
public class NewsFetchPolicyException extends NewsFetchException {

    public NewsFetchPolicyException(String message) {
        super(message, false);
    }

    public NewsFetchPolicyException(String message, Throwable cause) {
        super(message, false, cause);
    }
}
