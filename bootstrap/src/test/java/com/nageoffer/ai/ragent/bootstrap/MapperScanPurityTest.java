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

package com.nageoffer.ai.ragent.bootstrap;

import com.nageoffer.ai.ragent.RagentApplication;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MapperScan 扫描包纯度护栏（issue #82 生产事故回归门）
 *
 * <p>@MapperScan 会把目标包内<b>全部</b>接口注册为 mapper bean——2026-09-19 会话分享
 * 首版把 Mapper 与服务接口同包，开启 flag 后控制器注入撞双 bean 致启动崩溃（生产
 * crash loop，env 回滚恢复）。本测试按命名约定断言每个扫描包内的每个接口都以
 * Mapper 结尾（手写 SQL 的裸 *Mapper 接口是仓内合法先例，如 AgentStateMapper，
 * 故不以 extends BaseMapper 为准）；服务/其他接口混进扫描包在 CI 即红。
 */
class MapperScanPurityTest {

    @Test
    void 扫描包内的接口必须全部以Mapper结尾() throws Exception {
        MapperScan mapperScan = RagentApplication.class.getAnnotation(MapperScan.class);
        assertThat(mapperScan).isNotNull();
        assertThat(mapperScan.basePackages()).isNotEmpty();

        List<String> offenders = new ArrayList<>();
        for (String basePackage : mapperScan.basePackages()) {
            // ClassPathScanningCandidateComponentProvider 默认只收具体类——接口须重写
            // isCandidateComponent 放行（MyBatis 自己的 scanner 同款口径），否则纯度检查空转
            ClassPathScanningCandidateComponentProvider allInterfaces =
                    new ClassPathScanningCandidateComponentProvider(false) {
                        @Override
                        protected boolean isCandidateComponent(
                                org.springframework.beans.factory.annotation.AnnotatedBeanDefinition beanDefinition) {
                            return beanDefinition.getMetadata().isIndependent()
                                    && beanDefinition.getMetadata().isInterface();
                        }
                    };
            allInterfaces.addIncludeFilter((metadataReader, metadataReaderFactory) ->
                    metadataReader.getClassMetadata().isInterface());
            List<Class<?>> interfaces = new ArrayList<>();
            for (BeanDefinition definition : allInterfaces.findCandidateComponents(basePackage)) {
                Class<?> clazz = Class.forName(definition.getBeanClassName());
                interfaces.add(clazz);
                if (!clazz.getSimpleName().endsWith("Mapper")) {
                    offenders.add(clazz.getName());
                }
            }
            // 包内至少要有一个真 mapper——空包/typo 包/接口被过滤器吞掉都在 CI 即红，不静默空扫
            assertThat(interfaces)
                    .as("MapperScan 包 %s 至少应扫到一个接口（零命中=扫描口径失效或路径写错）", basePackage)
                    .isNotEmpty();
            assertThat(interfaces.stream().filter(c -> c.getSimpleName().endsWith("Mapper")).toList())
                    .as("MapperScan 包 %s 至少应含一个 *Mapper 接口", basePackage)
                    .isNotEmpty();
        }
        assertThat(offenders)
                .as("以下接口落在 @MapperScan 扫描包内但不是 *Mapper 命名——MapperScan 会把包内全部"
                        + "接口注册为 mapper bean，服务接口混扫会与 @Service 实现撞双 bean（判例：issue #82 "
                        + "首版 agent.share 服务接口混扫致 flag 开启即生产崩溃）；请把非 mapper 接口移出扫描包，"
                        + "或让 mapper 独占子包（agent.share.dao 先例）")
                .isEmpty();
    }
}
