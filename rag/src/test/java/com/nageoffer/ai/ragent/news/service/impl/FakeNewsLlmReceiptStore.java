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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.news.dao.entity.NewsLlmReceiptDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsLlmReceiptMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回执账本内存 fake（#184 修正点6：测试按字段名断言且反映持久值）
 *
 * <p>以 Mockito mock 承载、状态真实模拟 MyBatis-Plus 行为：insert 回填 id 并存入
 * <b>克隆</b>（调用方对象与持久层隔离，同真实库）；selectOne/selectList 每次返回
 * 克隆（同真实库逐查询独立对象）；update(wrapper) 按解析出的 SET 列应用克隆；
 * selectObjs 按匹配行计算 SUM(cost_estimate)。断言直接读 {@link #rows()} 的
 * DO 字段（getAttempts/getRetries/getCostEstimate/...），杜绝整 map containsValue
 * 的列混淆（id 冒充计数）。
 *
 * <p>列解析基于 wrapper 的 sqlSegment/getSqlSet 文本（列名 = #{ew.paramNameValuePairs.MPGENVALn}），
 * 仅覆盖 {@link NewsLlmBudgetService} 固定发出的三种形状（lambda eq 查询 /
 * lambdaUpdate set / QueryWrapper SUM 聚合）。
 */
final class FakeNewsLlmReceiptStore {

    private static final Pattern CONDITION = Pattern.compile("(\\w+)\\s*(=|<>)\\s*#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)\\}");

    private static final Map<String, BiConsumer<NewsLlmReceiptDO, Object>> COLUMN_SETTERS = Map.ofEntries(
            Map.entry("request_fingerprint", (row, v) -> row.setRequestFingerprint((String) v)),
            Map.entry("stat_date", (row, v) -> row.setStatDate((LocalDate) v)),
            Map.entry("stat_month", (row, v) -> row.setStatMonth((String) v)),
            Map.entry("model_id", (row, v) -> row.setModelId((String) v)),
            Map.entry("served_model_id", (row, v) -> row.setServedModelId((String) v)),
            Map.entry("attempts", (row, v) -> row.setAttempts(((Number) v).intValue())),
            Map.entry("retries", (row, v) -> row.setRetries(((Number) v).intValue())),
            Map.entry("cost_estimate", (row, v) -> row.setCostEstimate((BigDecimal) v)),
            Map.entry("response_text", (row, v) -> row.setResponseText((String) v)),
            Map.entry("status", (row, v) -> row.setStatus((String) v)),
            Map.entry("error_brief", (row, v) -> row.setErrorBrief((String) v)));

    // synchronizedList：fake 自防并发（服务侧准入锁只覆盖记账临界区，鉴权外的
    // insert/select 路径在并发测试下仍可能交错触达本表）
    private final List<NewsLlmReceiptDO> rows = java.util.Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong idSeq = new AtomicLong();

    final NewsLlmReceiptMapper mapper = mock(NewsLlmReceiptMapper.class);

    FakeNewsLlmReceiptStore() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, NewsLlmReceiptDO.class);
        when(mapper.insert(any(NewsLlmReceiptDO.class))).thenAnswer(invocation -> {
            NewsLlmReceiptDO row = invocation.getArgument(0, NewsLlmReceiptDO.class);
            row.setId(idSeq.incrementAndGet());
            rows.add(copy(row));
            return 1;
        });
        when(mapper.selectOne(any(Wrapper.class))).thenAnswer(invocation -> {
            List<NewsLlmReceiptDO> matched = match(parseConditions(invocation.getArgument(0, Wrapper.class)));
            return matched.isEmpty() ? null : copy(matched.get(0));
        });
        when(mapper.selectList(any(Wrapper.class))).thenAnswer(invocation -> {
            List<NewsLlmReceiptDO> matched = new ArrayList<>(match(parseConditions(invocation.getArgument(0, Wrapper.class))));
            matched.sort(java.util.Comparator.comparing(NewsLlmReceiptDO::getId));
            return matched.stream().map(FakeNewsLlmReceiptStore::copy).toList();
        });
        when(mapper.selectObjs(any(Wrapper.class))).thenAnswer(invocation -> {
            Wrapper<NewsLlmReceiptDO> wrapper = invocation.getArgument(0, Wrapper.class);
            if (!(wrapper instanceof QueryWrapper<NewsLlmReceiptDO> query)
                    || query.getSqlSelect() == null || !query.getSqlSelect().contains("SUM(cost_estimate)")) {
                throw new UnsupportedOperationException("fake 只支持 SUM(cost_estimate) 聚合形状，实际=" + wrapper);
            }
            BigDecimal sum = match(parseConditions(wrapper)).stream()
                    .map(row -> row.getCostEstimate() == null ? BigDecimal.ZERO : row.getCostEstimate())
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(6, java.math.RoundingMode.HALF_UP);
            return List.of(sum);
        });
        when(mapper.update(any(), any(Wrapper.class))).thenAnswer(invocation -> {
            Wrapper<NewsLlmReceiptDO> wrapper = invocation.getArgument(1, Wrapper.class);
            if (!(wrapper instanceof LambdaUpdateWrapper<NewsLlmReceiptDO> update)) {
                throw new UnsupportedOperationException("fake 只支持 lambdaUpdate 形状，实际=" + wrapper);
            }
            Map<String, Object> params = update.getParamNameValuePairs();
            Map<String, Object> sets = new LinkedHashMap<>();
            Matcher setMatcher = CONDITION.matcher(String.valueOf(update.getSqlSet()));
            while (setMatcher.find()) {
                sets.put(setMatcher.group(1), params.get(setMatcher.group(3)));
            }
            Conditions conditions = parseConditions(wrapper);
            Object expectedId = conditions.eq().get("id");
            NewsLlmReceiptDO found = null;
            synchronized (rows) {
                for (NewsLlmReceiptDO row : rows) {
                    if (Objects.equals(row.getId(), ((Number) expectedId).longValue())) {
                        found = row;
                        break;
                    }
                }
            }
            if (found == null) {
                return 0;
            }
            final NewsLlmReceiptDO target = found;
            sets.forEach((column, value) -> {
                BiConsumer<NewsLlmReceiptDO, Object> setter = COLUMN_SETTERS.get(column);
                if (setter == null) {
                    throw new UnsupportedOperationException("fake 未覆盖列：" + column);
                }
                setter.accept(target, value);
            });
            return 1;
        });
    }

    /**
     * 持久层真值（字段级断言入口；行对象即库内状态）
     */
    List<NewsLlmReceiptDO> rows() {
        return rows;
    }

    /**
     * 预置历史行（构造既有账本状态；自动补 id）
     */
    NewsLlmReceiptDO seed(NewsLlmReceiptDO row) {
        row.setId(idSeq.incrementAndGet());
        rows.add(row);
        return row;
    }

    private List<NewsLlmReceiptDO> match(Conditions conditions) {
        List<NewsLlmReceiptDO> snapshot;
        synchronized (rows) {
            snapshot = new ArrayList<>(rows);
        }
        return snapshot.stream()
                .filter(row -> conditions.eq().entrySet().stream().allMatch(condition -> matches(row, condition)))
                .filter(row -> conditions.ne().entrySet().stream().noneMatch(condition -> matches(row, condition)))
                .toList();
    }

    private static boolean matches(NewsLlmReceiptDO row, Map.Entry<String, Object> condition) {
        Object expected = condition.getValue();
        return switch (condition.getKey()) {
            case "id" -> Objects.equals(row.getId(), ((Number) expected).longValue());
            case "request_fingerprint" -> Objects.equals(row.getRequestFingerprint(), expected);
            case "stat_date" -> Objects.equals(row.getStatDate(), expected);
            case "stat_month" -> Objects.equals(row.getStatMonth(), expected);
            case "status" -> Objects.equals(row.getStatus(), expected);
            default -> throw new UnsupportedOperationException("fake 未覆盖查询列：" + condition.getKey());
        };
    }

    private record Conditions(Map<String, Object> eq, Map<String, Object> ne) {
    }

    /**
     * 解析 WHERE 段条件（本服务只发 eq/ne 两种：等值查找与聚合排除指纹）
     */
    private static Conditions parseConditions(Wrapper<NewsLlmReceiptDO> wrapper) {
        if (!(wrapper instanceof com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> abstractWrapper)) {
            throw new UnsupportedOperationException("fake 只支持 AbstractWrapper 形状，实际=" + wrapper);
        }
        Map<String, Object> params = abstractWrapper.getParamNameValuePairs();
        Map<String, Object> eq = new LinkedHashMap<>();
        Map<String, Object> ne = new LinkedHashMap<>();
        String segment = wrapper.getSqlSegment() == null ? "" : wrapper.getSqlSegment();
        Matcher matcher = CONDITION.matcher(segment);
        while (matcher.find()) {
            ("=".equals(matcher.group(2)) ? eq : ne).put(matcher.group(1), params.get(matcher.group(3)));
        }
        return new Conditions(eq, ne);
    }

    private static NewsLlmReceiptDO copy(NewsLlmReceiptDO source) {
        return NewsLlmReceiptDO.builder()
                .id(source.getId())
                .requestFingerprint(source.getRequestFingerprint())
                .statDate(source.getStatDate())
                .statMonth(source.getStatMonth())
                .modelId(source.getModelId())
                .servedModelId(source.getServedModelId())
                .attempts(source.getAttempts())
                .retries(source.getRetries())
                .costEstimate(source.getCostEstimate())
                .responseText(source.getResponseText())
                .status(source.getStatus())
                .errorBrief(source.getErrorBrief())
                .createTime(source.getCreateTime())
                .updateTime(source.getUpdateTime())
                .build();
    }
}
