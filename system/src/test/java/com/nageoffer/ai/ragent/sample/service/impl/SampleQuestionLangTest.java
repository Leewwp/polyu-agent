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

package com.nageoffer.ai.ragent.sample.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.sample.controller.request.SampleQuestionCreateRequest;
import com.nageoffer.ai.ragent.sample.controller.request.SampleQuestionUpdateRequest;
import com.nageoffer.ai.ragent.sample.controller.vo.SampleQuestionVO;
import com.nageoffer.ai.ragent.sample.dao.entity.SampleQuestionDO;
import com.nageoffer.ai.ragent.sample.dao.mapper.SampleQuestionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T20 lang 三分支：限定抽样 / 无命中回落全量 / create-update 透传
 */
class SampleQuestionLangTest {

    private final SampleQuestionMapper mapper = mock(SampleQuestionMapper.class);
    private final BizChangeLogContext logContext = mock(BizChangeLogContext.class);
    private final SampleQuestionServiceImpl service = new SampleQuestionServiceImpl(mapper, logContext);

    private static SampleQuestionDO row(String id, String question, String lang) {
        return SampleQuestionDO.builder().id(id).question(question).lang(lang).build();
    }

    @Test
    void randomLimitsToRequestedLanguage() {
        when(mapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(row("e1", "What are the Library opening hours?", "en")));

        List<SampleQuestionVO> result = service.listRandomQuestions(4, "en");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getLang()).isEqualTo("en");
        // 限定命中就不再回落全量：只查一次
        verify(mapper, times(1)).selectList(any(Wrapper.class));
    }

    /**
     * 该语言无行（EN 未配置）回落全量：中文兜底，避免待机页空 chips
     */
    @Test
    void randomFallsBackToAllWhenLanguageHasNoRows() {
        when(mapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of())                        // 第一次：lang=en 限定，0 行
                .thenReturn(List.of(row("z1", "图书馆的开放时间？", "zh"))); // 第二次：全量

        List<SampleQuestionVO> result = service.listRandomQuestions(4, "en");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getQuestion()).contains("图书馆");
        verify(mapper, times(2)).selectList(any(Wrapper.class));
    }

    @Test
    void randomDefaultsToZhForUnknownLang() {
        // 未知语言（fr）归一 zh：经 create 路径断言（listRandom 的 wrapper 断言需 MP 元数据，不便直查）
        SampleQuestionCreateRequest request = new SampleQuestionCreateRequest();
        request.setQuestion("下学期的课程注册和 Add/Drop 是什么时候？");
        request.setLang("fr");

        service.create(request);

        ArgumentCaptor<SampleQuestionDO> captor = ArgumentCaptor.forClass(SampleQuestionDO.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getLang()).isEqualTo("zh");
    }

    @Test
    void createNormalizesAndPersistsLang() {
        SampleQuestionCreateRequest request = new SampleQuestionCreateRequest();
        request.setQuestion("How can I book a Group Room in the Library?");
        request.setLang("EN");

        service.create(request);

        ArgumentCaptor<SampleQuestionDO> captor = ArgumentCaptor.forClass(SampleQuestionDO.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getLang()).isEqualTo("en");
    }

    @Test
    void updatePersistsLangChange() {
        SampleQuestionDO existing = row("q1", "旧问题", "zh");
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(mapper.selectById(anyString())).thenReturn(existing);

        SampleQuestionUpdateRequest request = new SampleQuestionUpdateRequest();
        request.setLang("en");
        service.update("q1", request);

        ArgumentCaptor<SampleQuestionDO> captor = ArgumentCaptor.forClass(SampleQuestionDO.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getLang()).isEqualTo("en");
    }
}
