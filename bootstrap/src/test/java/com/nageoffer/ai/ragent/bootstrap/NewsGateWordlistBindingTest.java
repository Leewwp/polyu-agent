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

import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 八校门词表配置绑定绊网（#277 修复回归）：词表字段是 rag.news 下的平铺属性，
 * yaml 曾把它们嵌在 eight-university-gate: 子节点下——Java 侧无可绑定路径（同名成员是
 * transient 运行时缓存，EightUniversityGate 为 final 类无 setter），Spring Binder 静默丢弃，
 * 覆盖面整个是死的。此处钉两头：文件键形必须平铺 + 平铺路径必须真的绑进属性类。
 */
class NewsGateWordlistBindingTest {

    private static Properties loadApplicationYaml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        Properties properties = yaml.getObject();
        assertThat(properties).isNotNull();
        return properties;
    }

    /**
     * 键形绊网：词表两键必须是 rag.news 的直接子键，不得再出现 eight-university-gate 包装层
     */
    @Test
    void 词表键必须平铺在ragNews下() {
        Properties properties = loadApplicationYaml();
        assertThat(properties.getProperty("rag.news.unambiguous-terms[0]"))
                .as("unambiguous-terms 必须直接挂在 rag.news 下（嵌套进 eight-university-gate 会使绑定静默失效）")
                .isEqualTo("University of Hong Kong");
        assertThat(properties.getProperty("rag.news.contextual-terms[0]"))
                .as("contextual-terms 同理")
                .isEqualTo("CityU");
        assertThat(properties.keySet().stream().map(Object::toString))
                .as("eight-university-gate 包装层在 Java 侧无可绑定面，出现即配置面坏死")
                .noneMatch(key -> key.startsWith("rag.news.eight-university-gate"));
    }

    /**
     * 活路径探针：经真实绑定器（与生产同机制）把探针值从平铺路径送进属性类——
     * 证明 rag.news.unambiguous-terms / contextual-terms 就是生效的覆盖路径
     */
    @Test
    void 平铺词表路径必须真实绑进属性类() {
        Map<String, Object> probe = new HashMap<>();
        probe.put("rag.news.unambiguous-terms[0]", "__PROBE_UNAMBIGUOUS__");
        probe.put("rag.news.contextual-terms[0]", "__PROBE_CONTEXTUAL__");
        probe.put("rag.news.gate-source-keys[0]", "__PROBE_SOURCE__");
        PropertySource<Map<String, Object>> source = new MapPropertySource("gate-probe", probe);
        Iterable<ConfigurationPropertySource> configSources = ConfigurationPropertySources.from(source);

        NewsFetchProperties bound = new Binder(configSources)
                .bind("rag.news", Bindable.of(NewsFetchProperties.class)).get();

        assertThat(bound.getUnambiguousTerms()).first().isEqualTo("__PROBE_UNAMBIGUOUS__");
        assertThat(bound.getContextualTerms()).first().isEqualTo("__PROBE_CONTEXTUAL__");
        assertThat(bound.getGateSourceKeys()).first().isEqualTo("__PROBE_SOURCE__");
    }

    /**
     * 端到端：真实 application.yaml 整档过绑定器，门生效源（#277 媒体二源+#410 B 线
     * 官方三源）与词表量级可从属性对象读出
     */
    @Test
    void 真实yaml整档绑定后门配置可达() {
        Properties properties = loadApplicationYaml();
        Map<String, Object> flat = new HashMap<>();
        properties.forEach((key, value) -> flat.put(String.valueOf(key), value));

        NewsFetchProperties bound = new Binder(
                ConfigurationPropertySources.from(new MapPropertySource("application-yaml", flat)))
                .bind("rag.news", Bindable.of(NewsFetchProperties.class)).get();

        assertThat(bound.getGateSourceKeys())
                .containsExactlyInAnyOrder("scmp-education", "gia-news",
                        "jupas-news", "jupas-programme-changes", "ugc-whatnews");
        assertThat(bound.getUnambiguousTerms())
                .as("词表规模应与 yaml 显式清单一致（30+ 项），缩水说明绑定退回了 Java 默认拷贝")
                .hasSizeGreaterThanOrEqualTo(30)
                .contains("香港理工大学", "PolyU");
        assertThat(bound.getContextualTerms()).contains("CityU", "中大", "科大");
    }

    /**
     * 缺省漂移绊网（#432）：Java 缺省键集必须与 application.yaml 键集一致——缺省是
     * 「无 yaml 覆盖装配形态」（如纯 Java 缺省绑定的单测/工具面）的唯一门面，两侧分叉
     * 即环境差异产生门行为漂移；经公开判据 {@code gateAppliesTo} 对照默认实例与
     * yaml 绑定实例（#277「只对指定 source_key 生效」红线不破：键集内容变、判据语义不变）
     */
    @Test
    void 门生效键Java缺省必须与yaml集一致防缺省漂移() {
        Properties properties = loadApplicationYaml();
        Map<String, Object> flat = new HashMap<>();
        properties.forEach((key, value) -> flat.put(String.valueOf(key), value));

        NewsFetchProperties bound = new Binder(
                ConfigurationPropertySources.from(new MapPropertySource("application-yaml", flat)))
                .bind("rag.news", Bindable.of(NewsFetchProperties.class)).get();
        NewsFetchProperties defaults = new NewsFetchProperties();

        assertThat(defaults.getGateSourceKeys())
                .as("Java 缺省必须与 yaml 键集一致（#432 五键镜像），分叉=无 yaml 覆盖环境的门行为漂移")
                .containsExactlyInAnyOrderElementsOf(bound.getGateSourceKeys());

        for (String sourceKey : bound.getGateSourceKeys()) {
            assertThat(defaults.gateAppliesTo(sourceKey))
                    .as("默认实例五键现网回归：%s 应过门", sourceKey)
                    .isTrue();
            assertThat(bound.gateAppliesTo(sourceKey))
                    .as("yaml 绑定实例五键现网回归：%s 应过门", sourceKey)
                    .isTrue();
        }
        assertThat(defaults.gateAppliesTo("rthk-local-news"))
                .as("rthk-local-news 已于 2026-10-29 停用，不得残留在任何一面；复启=本键与 Java 缺省镜像同步回填")
                .isFalse();
        assertThat(bound.gateAppliesTo("polyu-latest-news"))
                .as("列外源不经门（#277 红线）")
                .isFalse();
    }
}
