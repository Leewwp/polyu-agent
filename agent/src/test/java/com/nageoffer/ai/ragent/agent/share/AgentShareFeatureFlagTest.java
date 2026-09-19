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

package com.nageoffer.ai.ragent.agent.share;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 会话分享 feature flag 装配面测试（克隆 PublicNewsFeatureFlagTest 模式）
 *
 * <p>孪生语义：agent.share.enabled=true 时装配启用态控制器三件（登录面/公开面/管理面）；
 * false/缺省时公开路径只装配 DisabledController（与「链接无效」同源兜底，不泄漏开关状态）。
 */
class AgentShareFeatureFlagTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(AgentConversationShareService.class, () -> mock(AgentConversationShareService.class))
            .withUserConfiguration(AgentShareController.class, PublicAgentShareController.class,
                    PublicAgentShareDisabledController.class, AdminAgentShareController.class);

    @Test
    void flagOffAssemblesOnlyDisabledTwin() {
        runner.withPropertyValues("agent.share.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(PublicAgentShareDisabledController.class);
            assertThat(context).doesNotHaveBean(PublicAgentShareController.class);
            assertThat(context).doesNotHaveBean(AgentShareController.class);
            assertThat(context).doesNotHaveBean(AdminAgentShareController.class);
        });
    }

    @Test
    void flagMissingDefaultsToDisabledTwin() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(PublicAgentShareDisabledController.class);
            assertThat(context).doesNotHaveBean(PublicAgentShareController.class);
            assertThat(context).doesNotHaveBean(AgentShareController.class);
            assertThat(context).doesNotHaveBean(AdminAgentShareController.class);
        });
    }

    @Test
    void flagOnAssemblesEnabledControllers() {
        runner.withPropertyValues("agent.share.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(AgentShareController.class);
            assertThat(context).hasSingleBean(PublicAgentShareController.class);
            assertThat(context).hasSingleBean(AdminAgentShareController.class);
            assertThat(context).doesNotHaveBean(PublicAgentShareDisabledController.class);
        });
    }

    /**
     * 直接确认孪生注解形状：两端 matchIfMissing 语义互补，不存在同装或同缺
     */
    @Test
    void twinAnnotationsAreComplementary() {
        assertThat(PublicAgentShareController.class.getAnnotation(ConditionalOnProperty.class))
                .satisfies(annotation -> {
                    assertThat(annotation.name()).containsExactly("agent.share.enabled");
                    assertThat(annotation.havingValue()).isEqualTo("true");
                    assertThat(annotation.matchIfMissing()).isFalse();
                });
        assertThat(PublicAgentShareDisabledController.class.getAnnotation(ConditionalOnProperty.class))
                .satisfies(annotation -> {
                    assertThat(annotation.name()).containsExactly("agent.share.enabled");
                    assertThat(annotation.havingValue()).isEqualTo("false");
                    assertThat(annotation.matchIfMissing()).isTrue();
                });
        assertThat(AgentShareController.class.getAnnotation(ConditionalOnProperty.class))
                .satisfies(annotation -> {
                    assertThat(annotation.name()).containsExactly("agent.share.enabled");
                    assertThat(annotation.havingValue()).isEqualTo("true");
                    assertThat(annotation.matchIfMissing()).isFalse();
                });
    }
}
