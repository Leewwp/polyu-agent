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
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 资讯条目内存 fake（#185 回放/资源测试）：状态真实模拟 MyBatis-Plus 行为——
 * insert 回填 id 存克隆；selectList/update 按解析出的 WHERE/SET 语义求值；
 * selectMaps 实现「日准入计数聚合」（fetch_time≥日切 AND status&lt;&gt;archived
 * GROUP BY source_id）。断言直接读 {@link #rows()} 的 DO 字段（禁整 map containsValue）。
 *
 * <p>列解析基于 wrapper 的 sqlSegment/getSqlSet 文本（fake 只覆盖
 * {@link NewsFetchService}/{@link NewsEnrichService} 固定发出的形状：
 * lambda eq/isNull/ge/lt/in 查询 + lambdaUpdate set + QueryWrapper 聚合；
 * 真实 SQL 口径由 NewsPipelinePgIt 在本地 PG 证明）。
 */
final class FakeNewsItemStore {

    private static final Pattern CONDITION = Pattern.compile(
            "(\\w+)\\s*(=|<>|<|>=)\\s*#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)\\}");
    private static final Pattern IN_LIST = Pattern.compile(
            "(\\w+)\\s+IN\\s*\\(([^)]+)\\)");
    private static final Pattern IS_NULL = Pattern.compile("(\\w+)\\s+IS\\s+NULL");
    private static final Pattern SET_PAIR = Pattern.compile(
            "(\\w+)=#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)\\}");
    private static final Pattern LIMIT = Pattern.compile("LIMIT\\s+(\\d+)");

    private final List<NewsItemDO> rows = new ArrayList<>();
    private final AtomicLong idSeq = new AtomicLong();

    final NewsItemMapper mapper = mock(NewsItemMapper.class);

    FakeNewsItemStore() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, NewsItemDO.class);
        when(mapper.insert(any(NewsItemDO.class))).thenAnswer(invocation -> {
            NewsItemDO row = invocation.getArgument(0, NewsItemDO.class);
            row.setId(idSeq.incrementAndGet());
            rows.add(copy(row));
            return 1;
        });
        when(mapper.selectList(any(Wrapper.class))).thenAnswer(invocation ->
                select(invocation.getArgument(0, Wrapper.class)).stream()
                        .map(row -> copy((NewsItemDO) row)).toList());
        when(mapper.selectMaps(any(Wrapper.class))).thenAnswer(invocation ->
                admissionCountRows(invocation.getArgument(0, Wrapper.class)));
        when(mapper.update(any(), any(Wrapper.class))).thenAnswer(invocation ->
                applyUpdate(invocation.getArgument(1, Wrapper.class)));
    }

    /**
     * 持久行视图（克隆防调用方污染）
     */
    List<NewsItemDO> rows() {
        return rows.stream().map(FakeNewsItemStore::copy).toList();
    }

    // ================== WHERE 解析（一次解析逐行求值——资源测试行数 × IN 参数量级防平方扫描） ==================

    /**
     * 等值/不等/比较条件（column op paramValue）
     */
    private record Cmp(String column, String op, Object value) {
    }

    /**
     * 预解析 WHERE：eq/ne/lt/ge 条件 + IN 集合（值集合一次展开）+ IS NULL 列
     */
    private record ParsedWhere(List<Cmp> cmps, Map<String, java.util.Set<Object>> inSets, List<String> nullColumns) {

        static final ParsedWhere EMPTY = new ParsedWhere(List.of(), Map.of(), List.of());
    }

    private ParsedWhere parseWhere(String sql, Map<String, Object> params) {
        List<Cmp> cmps = new ArrayList<>();
        Matcher condition = CONDITION.matcher(sql);
        while (condition.find()) {
            cmps.add(new Cmp(condition.group(1), condition.group(2), params.get(condition.group(3))));
        }
        Map<String, java.util.Set<Object>> inSets = new LinkedHashMap<>();
        Matcher inList = IN_LIST.matcher(sql);
        while (inList.find()) {
            java.util.Set<Object> values = new java.util.HashSet<>();
            for (String token : inList.group(2).split(",")) {
                String key = token.trim().replace("#{ew.paramNameValuePairs.", "").replace("}", "");
                values.add(params.get(key));
            }
            inSets.put(inList.group(1), values);
        }
        List<String> nullColumns = new ArrayList<>();
        Matcher isNull = IS_NULL.matcher(sql);
        while (isNull.find()) {
            nullColumns.add(isNull.group(1));
        }
        return new ParsedWhere(cmps, inSets, nullColumns);
    }

    private boolean matches(NewsItemDO row, ParsedWhere where) {
        for (Cmp cmp : where.cmps()) {
            if (!compare(rowValue(row, cmp.column()), cmp.op(), cmp.value())) {
                return false;
            }
        }
        for (Map.Entry<String, java.util.Set<Object>> entry : where.inSets().entrySet()) {
            if (!entry.getValue().contains(rowValue(row, entry.getKey()))) {
                return false;
            }
        }
        for (String column : where.nullColumns()) {
            if (rowValue(row, column) != null) {
                return false;
            }
        }
        return true;
    }

    // ================== SELECT：WHERE 求值 + ORDER BY id + LIMIT ==================

    private List<NewsItemDO> select(Wrapper<NewsItemDO> wrapper) {
        String sql = wrapper.getSqlSegment();
        ParsedWhere where = parseWhere(sql,
                ((AbstractWrapper<NewsItemDO, ?, ?>) wrapper).getParamNameValuePairs());
        List<NewsItemDO> matched = new ArrayList<>();
        for (NewsItemDO row : rows) {
            if (matches(row, where)) {
                matched.add(row);
            }
        }
        matched.sort(java.util.Comparator.comparing(NewsItemDO::getId));
        Matcher limit = LIMIT.matcher(sql);
        if (limit.find()) {
            int n = Integer.parseInt(limit.group(1));
            matched = matched.size() > n ? new ArrayList<>(matched.subList(0, n)) : matched;
        }
        return matched;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean compare(Object left, String operator, Object right) {
        if (left == null || right == null) {
            return false;
        }
        int cmp;
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            cmp = Long.compare(leftNumber.longValue(), rightNumber.longValue());
        } else if (left instanceof Date leftDate && right instanceof Date rightDate) {
            cmp = leftDate.compareTo(rightDate);
        } else {
            cmp = ((Comparable) left).compareTo(right);
        }
        return switch (operator) {
            case "=" -> cmp == 0;
            case "<>" -> cmp != 0;
            case "<" -> cmp < 0;
            case ">=" -> cmp >= 0;
            default -> false;
        };
    }

    // ================== SELECT MAPS：日准入计数聚合（GROUP BY source_id 形状） ==================

    /**
     * 形状=select(source_id, COUNT(*) AS cnt).ge(fetch_time, 日切)
     * .ne(status,'archived').groupBy(source_id)——每源一行 Map
     * （source_id/cnt 键），与 NewsFetchService#countAdmittedTodayBySource 消费面一致
     */
    private List<Map<String, Object>> admissionCountRows(Wrapper<NewsItemDO> wrapper) {
        String sql = wrapper.getSqlSegment();
        if (!sql.toUpperCase().contains("GROUP BY")) {
            return List.of();
        }
        Map<String, Object> params = ((AbstractWrapper<NewsItemDO, ?, ?>) wrapper).getParamNameValuePairs();
        Date dayStart = null;
        String excludedStatus = null;
        Matcher condition = CONDITION.matcher(sql);
        while (condition.find()) {
            if ("fetch_time".equals(condition.group(1)) && ">=".equals(condition.group(2))) {
                dayStart = (Date) params.get(condition.group(3));
            }
            if ("status".equals(condition.group(1)) && "<>".equals(condition.group(2))) {
                excludedStatus = String.valueOf(params.get(condition.group(3)));
            }
        }
        Map<Long, Long> counts = new LinkedHashMap<>();
        for (NewsItemDO row : rows) {
            if (dayStart != null && (row.getFetchTime() == null || row.getFetchTime().before(dayStart))) {
                continue;
            }
            if (excludedStatus != null && excludedStatus.equals(row.getStatus())) {
                continue;
            }
            if (row.getSourceId() != null) {
                counts.merge(row.getSourceId(), 1L, Long::sum);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        counts.forEach((sourceId, cnt) -> {
            Map<String, Object> row = new HashMap<>();
            row.put("source_id", sourceId);
            row.put("cnt", cnt);
            result.add(row);
        });
        return result;
    }

    // ================== UPDATE：WHERE 求值 + SET 应用 ==================

    private int applyUpdate(Wrapper<NewsItemDO> wrapper) {
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<NewsItemDO> update =
                (com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<NewsItemDO>) wrapper;
        String sqlSet = update.getSqlSet();
        Map<String, Object> params = update.getParamNameValuePairs();
        Map<String, Object> setters = new LinkedHashMap<>();
        Matcher setPair = SET_PAIR.matcher(sqlSet == null ? "" : sqlSet);
        while (setPair.find()) {
            setters.put(setPair.group(1), params.get(setPair.group(2)));
        }
        ParsedWhere where = parseWhere(update.getSqlSegment(), update.getParamNameValuePairs());
        int changed = 0;
        for (NewsItemDO row : rows) {
            if (matches(row, where)) {
                setters.forEach((column, value) -> applyColumn(row, column, value));
                changed++;
            }
        }
        return changed;
    }

    private static void applyColumn(NewsItemDO row, String column, Object value) {
        switch (column) {
            case "status" -> row.setStatus((String) value);
            case "title_zh" -> row.setTitleZh((String) value);
            case "title_en" -> row.setTitleEn((String) value);
            case "summary_zh" -> row.setSummaryZh((String) value);
            case "summary_en" -> row.setSummaryEn((String) value);
            case "category" -> row.setCategory((String) value);
            case "eligible_time" -> row.setEligibleTime((Date) value);
            case "summary_source" -> row.setSummarySource((String) value);
            case "prompt_version" -> row.setPromptVersion((String) value);
            case "content_hash" -> row.setContentHash((String) value);
            case "heat" -> row.setHeat(((Number) value).intValue());
            default -> throw new IllegalArgumentException("fake 未覆盖 SET 列：" + column);
        }
    }

    private static Object rowValue(NewsItemDO row, String column) {
        return switch (column) {
            case "id" -> row.getId();
            case "source_id" -> row.getSourceId();
            case "url_hash" -> row.getUrlHash();
            case "status" -> row.getStatus();
            case "summary_en" -> row.getSummaryEn();
            case "summary_source" -> row.getSummarySource();
            case "content_hash" -> row.getContentHash();
            case "fetch_time" -> row.getFetchTime();
            case "publish_time" -> row.getPublishTime();
            default -> throw new IllegalArgumentException("fake 未覆盖 WHERE 列：" + column);
        };
    }

    private static NewsItemDO copy(NewsItemDO row) {
        return NewsItemDO.builder()
                .id(row.getId()).sourceId(row.getSourceId()).url(row.getUrl()).urlHash(row.getUrlHash())
                .titleZh(row.getTitleZh()).titleEn(row.getTitleEn())
                .summaryZh(row.getSummaryZh()).summaryEn(row.getSummaryEn())
                .category(row.getCategory()).langRaw(row.getLangRaw())
                .publishTime(row.getPublishTime()).activityEndTime(row.getActivityEndTime())
                .publishTimePrecision(row.getPublishTimePrecision())
                .fetchTime(row.getFetchTime())
                .status(row.getStatus()).heat(row.getHeat())
                .eligibleTime(row.getEligibleTime()).summarySource(row.getSummarySource())
                .promptVersion(row.getPromptVersion()).contentHash(row.getContentHash())
                .createTime(row.getCreateTime()).build();
    }
}
