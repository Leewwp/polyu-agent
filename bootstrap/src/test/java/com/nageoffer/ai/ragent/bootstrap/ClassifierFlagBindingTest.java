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

import com.nageoffer.ai.ragent.rag.config.RAGClassifierProperties;
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
 * 回答前分类器 flag 绑定绊网（#430）：flag 走 Spring/env 现役机制
 * （@ConfigurationProperties + yaml env 占位）。此处钉两头：
 * yaml 键形带 env 占位且默认关 + 平铺路径经真实绑定器真的绑进属性类
 * （对齐 NewsGateWordlistBindingTest 绊网范式）
 */
class ClassifierFlagBindingTest {

    private static Properties loadApplicationYaml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        Properties properties = yaml.getObject();
        assertThat(properties).isNotNull();
        return properties;
    }

    /**
     * 键形绊网：rag.classifier.enabled 必须存在且默认关（env 占位展开后为 false），
     * 防止「键缺失只剩 Java 缺省」或「默认值漂移」两类静默漂移
     */
    @Test
    void 分类器开关键必须默认关() {
        Properties properties = loadApplicationYaml();
        assertThat(properties.getProperty("rag.classifier.enabled"))
                .as("rag.classifier.enabled 须在 application.yaml 带占位（生产 env 翻转走该路径）")
                .isEqualTo("${RAG_CLASSIFIER_ENABLED:false}");
        assertThat(properties.getProperty("rag.classifier.llm-votes")).isEqualTo("2");
        assertThat(properties.getProperty("rag.classifier.llm-timeout-ms")).isEqualTo("3000");
    }

    /**
     * 活路径探针：经真实绑定器把 true 从 rag.classifier.enabled 送进属性类——
     * 证明该路径就是 env/配置的生效覆盖路径
     */
    @Test
    void 分类器开关键路径必须真实绑进属性类() {
        Map<String, Object> probe = new HashMap<>();
        probe.put("rag.classifier.enabled", "true");
        probe.put("rag.classifier.llm-votes", "1");
        PropertySource<Map<String, Object>> source = new MapPropertySource("classifier-probe", probe);
        Iterable<ConfigurationPropertySource> configSources = ConfigurationPropertySources.from(source);

        RAGClassifierProperties bound = new Binder(configSources)
                .bind("rag.classifier", Bindable.of(RAGClassifierProperties.class)).get();

        assertThat(bound.getEnabled()).isTrue();
        assertThat(bound.getLlmVotes()).isEqualTo(1);
    }

    /**
     * 端到端：真实 application.yaml 整档过绑定器。env 占位按「未设 env」场景解析
     * （取 `:-` 后默认值），等价于生产容器未配 RAG_CLASSIFIER_ENABLED 时的 Spring 解析结果
     */
    @Test
    void 真实yaml整档绑定后属性类可达() {
        Properties properties = loadApplicationYaml();
        Map<String, Object> flat = new HashMap<>();
        properties.forEach((key, value) -> flat.put(String.valueOf(key), resolvePlaceholder(value)));

        RAGClassifierProperties bound = new Binder(
                ConfigurationPropertySources.from(new MapPropertySource("application-yaml", flat)))
                .bind("rag.classifier", Bindable.of(RAGClassifierProperties.class)).get();

        assertThat(bound.getEnabled())
                .as("未设 env 时占位默认 false 生效：flag 默认关")
                .isFalse();
        assertThat(bound.getLlmVotes()).isEqualTo(2);
        assertThat(bound.getLlmTimeoutMs()).isEqualTo(3000L);
    }

    private static Object resolvePlaceholder(Object value) {
        if (value instanceof String text && text.startsWith("${") && text.endsWith("}")) {
            String body = text.substring(2, text.length() - 1);
            int separator = body.indexOf(':');
            return separator >= 0 ? body.substring(separator + 1) : "";
        }
        return value;
    }
}
