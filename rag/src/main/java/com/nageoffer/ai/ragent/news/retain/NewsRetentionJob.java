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

package com.nageoffer.ai.ragent.news.retain;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 资讯保留期清理任务骨架（U12-A A2；doc 19 §3/§4-4）
 *
 * <p>独立于 U6 DataRetentionJob / DataRetentionProperties（避免碰已上线 U6 面）；
 * 90 天保留期阈值外置 rag.news.retention-days；flag rag.news.enabled 关（默认）时
 * 本组件不装配。t_news_item_topic 随 t_news_item 级联删除（ON DELETE CASCADE）。
 *
 * <p>A2 骨架占位：DELETE 批上限（PG 不支持 DELETE LIMIT，须 WHERE id IN
 * (SELECT … LIMIT n) 判例）随 A5 接线填充。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.news.enabled", havingValue = "true")
public class NewsRetentionJob {

    /**
     * 每小时巡检一次（doc 19 §4-4 fixedDelay 1h；参数形状沿 U6 ragent.retention 外置先例）
     */
    @Scheduled(fixedDelayString = "${rag.news.retention-scan-delay-ms:3600000}",
            initialDelayString = "${rag.news.retention-initial-delay-ms:120000}")
    public void purgeExpiredItems() {
        // A2 骨架：无删除副作用；A5 按 rag.news.retention-days 批量清 90 天前条目
        log.debug("[news] retention sweep triggered (A2 skeleton, A5 wiring pending)");
    }
}
