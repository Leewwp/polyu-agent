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

import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识库作答形态开关（#574）：本仓 application.yaml 必须显式写出 {@code rag.search.knowledge-answer-form=synthesized}。
 * 上游窗 idx 27「以开关收编」落在这一键上——键丢了或值被动过，主 Agent 拿到的就不再是成品答案。
 * 钉两头：yaml 的值经真实绑定器进属性类仍是合成；取 evidence 的覆盖路径确实绑得进去。
 */
class KnowledgeAnswerFormBindingTest {

    private static final String KEY = "rag.search.knowledge-answer-form";

    private static Map<String, Object> applicationYaml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        Properties properties = yaml.getObject();
        assertThat(properties).isNotNull();
        Map<String, Object> flat = new HashMap<>();
        properties.forEach((key, value) -> flat.put(String.valueOf(key), value));
        return flat;
    }

    @Test
    void 本仓yaml显式写出作答形态且为合成() {
        Map<String, Object> flat = applicationYaml();

        assertThat(flat)
                .as("开关必须显式写在 yaml 里（票 #574 第 2 条硬要求），缺了就只剩 Java 缺省可依")
                .containsEntry(KEY, "synthesized");

        SearchChannelProperties bound = bind(flat);
        assertThat(bound.getKnowledgeAnswerForm()).isEqualTo(SearchChannelProperties.KnowledgeAnswerForm.SYNTHESIZED);
        assertThat(bound.isEvidenceAnswer()).isFalse();
    }

    @Test
    void 同一属性类经覆盖可切到资料原文() {
        Map<String, Object> overridden = new HashMap<>(applicationYaml());
        overridden.put(KEY, "evidence");

        assertThat(bind(overridden).isEvidenceAnswer()).isTrue();
    }

    private static SearchChannelProperties bind(Map<String, Object> flat) {
        return new Binder(ConfigurationPropertySources.from(new MapPropertySource("application-yaml", flat)))
                .bind("rag.search", Bindable.of(SearchChannelProperties.class)).get();
    }
}
