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

package com.nageoffer.ai.ragent.news;

import com.nageoffer.ai.ragent.news.controller.PublicNewsController;
import com.nageoffer.ai.ragent.news.controller.PublicNewsDisabledController;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceMapper;
import com.nageoffer.ai.ragent.news.heat.NewsHeatService;
import com.nageoffer.ai.ragent.news.retain.NewsRetentionJob;
import com.nageoffer.ai.ragent.news.schedule.NewsFetchJob;
import com.nageoffer.ai.ragent.news.service.NewsQueryService;
import com.nageoffer.ai.ragent.news.service.impl.NewsEnrichService;
import com.nageoffer.ai.ragent.news.service.impl.NewsFetchService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 资讯流 feature flag 装配面测试（「flag 关时不跑」）
 *
 * <p>孪生语义：rag.news.enabled=true 时只装配启用态控制器+两个 Job；
 * false/缺省时只装配 DisabledController（HTTP 404 兜底）。沿 RAG_SHARE_ENABLED 既有口径，
 * @ConditionalOnProperty 按属性逐段映射，不依赖任何 env 名前缀假设。
 */
class PublicNewsFeatureFlagTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(NewsQueryService.class, () -> mock(NewsQueryService.class))
            .withBean(NewsSourceMapper.class, () -> mock(NewsSourceMapper.class))
            .withBean(NewsItemMapper.class, () -> mock(NewsItemMapper.class))
            // 抽检 tags 接线后 NewsFetchJob 增补的两个 join 表 mapper
            .withBean(com.nageoffer.ai.ragent.news.dao.mapper.NewsItemTopicMapper.class,
                    () -> mock(com.nageoffer.ai.ragent.news.dao.mapper.NewsItemTopicMapper.class))
            .withBean(com.nageoffer.ai.ragent.news.dao.mapper.NewsTopicMapper.class,
                    () -> mock(com.nageoffer.ai.ragent.news.dao.mapper.NewsTopicMapper.class))
            .withBean(NewsFetchService.class, () -> mock(NewsFetchService.class))
            .withBean(NewsEnrichService.class, () -> mock(NewsEnrichService.class))
            .withBean(NewsHeatService.class, () -> mock(NewsHeatService.class))
            .withBean(org.springframework.jdbc.core.JdbcTemplate.class,
                    () -> mock(org.springframework.jdbc.core.JdbcTemplate.class))
            .withUserConfiguration(PublicNewsController.class, PublicNewsDisabledController.class,
                    NewsFetchJob.class, NewsRetentionJob.class);

    @Test
    void flagOffAssemblesOnlyDisabledTwin() {
        runner.withPropertyValues("rag.news.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(PublicNewsDisabledController.class);
            assertThat(context).doesNotHaveBean(PublicNewsController.class);
            assertThat(context).doesNotHaveBean(NewsFetchJob.class);
            assertThat(context).doesNotHaveBean(NewsRetentionJob.class);
        });
    }

    @Test
    void flagMissingDefaultsToDisabledTwin() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(PublicNewsDisabledController.class);
            assertThat(context).doesNotHaveBean(PublicNewsController.class);
            assertThat(context).doesNotHaveBean(NewsFetchJob.class);
            assertThat(context).doesNotHaveBean(NewsRetentionJob.class);
        });
    }

    @Test
    void flagOnAssemblesEnabledControllerAndJobs() {
        runner.withPropertyValues("rag.news.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(PublicNewsController.class);
            assertThat(context).doesNotHaveBean(PublicNewsDisabledController.class);
            assertThat(context).hasSingleBean(NewsFetchJob.class);
            assertThat(context).hasSingleBean(NewsRetentionJob.class);
        });
    }

    /**
     * 直接确认孪生注解形状：两端 matchIfMissing 语义互补，不存在同装或同缺
     */
    @Test
    void twinAnnotationsAreComplementary() {
        assertThat(PublicNewsController.class.getAnnotation(ConditionalOnProperty.class))
                .satisfies(annotation -> {
                    assertThat(annotation.name()).containsExactly("rag.news.enabled");
                    assertThat(annotation.havingValue()).isEqualTo("true");
                    assertThat(annotation.matchIfMissing()).isFalse();
                });
        assertThat(PublicNewsDisabledController.class.getAnnotation(ConditionalOnProperty.class))
                .satisfies(annotation -> {
                    assertThat(annotation.name()).containsExactly("rag.news.enabled");
                    assertThat(annotation.havingValue()).isEqualTo("false");
                    assertThat(annotation.matchIfMissing()).isTrue();
                });
    }
}
