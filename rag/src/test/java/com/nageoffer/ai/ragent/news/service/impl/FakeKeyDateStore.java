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

package com.nageoffer.ai.ragent.news.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.calendar.dao.entity.KeyDateDO;
import com.nageoffer.ai.ragent.calendar.dao.mapper.KeyDateMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * t_key_date 内存 fake（#316 日报校历关键日期栏目测试）：selectList 返回
 * 全部种子行（不解析 wrapper——装配服务对 status 有 Java 侧防御性双保险
 * 过滤，KeyDateQueryServiceImpl.board 同判例；真实 SQL 过滤由 PG 环境保障），
 * 日期落窗/排序/ongoing/days_until 行为断言全部走服务层内存逻辑。
 */
final class FakeKeyDateStore {

    private final List<KeyDateDO> rows = new ArrayList<>();

    final KeyDateMapper mapper = mock(KeyDateMapper.class);

    FakeKeyDateStore() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KeyDateDO.class);
        when(mapper.selectList(any(Wrapper.class))).thenAnswer(invocation -> new ArrayList<>(rows));
    }

    void seed(KeyDateDO row) {
        rows.add(row);
    }
}
