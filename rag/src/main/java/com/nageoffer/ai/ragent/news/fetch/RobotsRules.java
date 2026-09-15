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

import java.util.List;

/**
 * robots.txt 单组规则。V1 只消费 Disallow 与 Crawl-delay 两类指令
 * （沿 fetch_sources.py 纪律合同；Allow 优先级等 REP 边角语义留待真实遇到再补）
 */
public final class RobotsRules {

    private final Double crawlDelaySeconds;
    private final List<String> disallowPrefixes;

    public RobotsRules(Double crawlDelaySeconds, List<String> disallowPrefixes) {
        this.crawlDelaySeconds = crawlDelaySeconds;
        this.disallowPrefixes = List.copyOf(disallowPrefixes);
    }

    /**
     * 空 Disallow（`Disallow:` 无值）= 允许全部，构造时即不应入约束列表
     */
    public static RobotsRules allowAll() {
        return new RobotsRules(null, List.of());
    }

    public Double crawlDelaySeconds() {
        return crawlDelaySeconds;
    }

    /**
     * 路径前缀匹配（对 path+query 整体判定）
     */
    public boolean disallows(String pathAndQuery) {
        String target = pathAndQuery == null ? "/" : pathAndQuery;
        return disallowPrefixes.stream().anyMatch(target::startsWith);
    }

    @Override
    public String toString() {
        return "RobotsRules{crawlDelaySeconds=" + crawlDelaySeconds + ", disallowPrefixes=" + disallowPrefixes + '}';
    }
}
