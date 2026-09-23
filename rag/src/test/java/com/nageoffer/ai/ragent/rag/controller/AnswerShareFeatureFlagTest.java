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

package com.nageoffer.ai.ragent.rag.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.nageoffer.ai.ragent.rag.service.AnswerShareService;

/**
 * 统一分享 feature flag 装配面测试（答案粒度四件，AgentShareFeatureFlagTest 的
 * rag 侧镜像，issue #124 flag 归一为 share.enabled 后补——此前 rag 侧无孪生测试）
 *
 * <p>孪生语义：share.enabled=true 时装配启用态控制器三件（登录面/公开面/管理面）；
 * false/缺省时公开路径只装配 DisabledController（与「链接无效」同源兜底，不泄漏开关状态）。
 */
class AnswerShareFeatureFlagTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(AnswerShareService.class, () -> mock(AnswerShareService.class))
            .withUserConfiguration(AnswerShareController.class, PublicShareController.class,
                    PublicShareDisabledController.class, AdminShareController.class);

    @Test
    void flagOffAssemblesOnlyDisabledTwin() {
        runner.withPropertyValues("share.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(PublicShareDisabledController.class);
            assertThat(context).doesNotHaveBean(PublicShareController.class);
            assertThat(context).doesNotHaveBean(AnswerShareController.class);
            assertThat(context).doesNotHaveBean(AdminShareController.class);
        });
    }

    @Test
    void flagMissingDefaultsToDisabledTwin() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(PublicShareDisabledController.class);
            assertThat(context).doesNotHaveBean(PublicShareController.class);
            assertThat(context).doesNotHaveBean(AnswerShareController.class);
            assertThat(context).doesNotHaveBean(AdminShareController.class);
        });
    }

    @Test
    void flagOnAssemblesEnabledControllers() {
        runner.withPropertyValues("share.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(AnswerShareController.class);
            assertThat(context).hasSingleBean(PublicShareController.class);
            assertThat(context).hasSingleBean(AdminShareController.class);
            assertThat(context).doesNotHaveBean(PublicShareDisabledController.class);
        });
    }

    /**
     * 直接确认孪生注解形状：两端 matchIfMissing 语义互补，不存在同装或同缺
     */
    @Test
    void twinAnnotationsAreComplementary() {
        assertThat(PublicShareController.class.getAnnotation(ConditionalOnProperty.class))
                .satisfies(annotation -> {
                    assertThat(annotation.name()).containsExactly("share.enabled");
                    assertThat(annotation.havingValue()).isEqualTo("true");
                    assertThat(annotation.matchIfMissing()).isFalse();
                });
        assertThat(PublicShareDisabledController.class.getAnnotation(ConditionalOnProperty.class))
                .satisfies(annotation -> {
                    assertThat(annotation.name()).containsExactly("share.enabled");
                    assertThat(annotation.havingValue()).isEqualTo("false");
                    assertThat(annotation.matchIfMissing()).isTrue();
                });
        assertThat(AnswerShareController.class.getAnnotation(ConditionalOnProperty.class))
                .satisfies(annotation -> {
                    assertThat(annotation.name()).containsExactly("share.enabled");
                    assertThat(annotation.havingValue()).isEqualTo("true");
                    assertThat(annotation.matchIfMissing()).isFalse();
                });
    }
}
