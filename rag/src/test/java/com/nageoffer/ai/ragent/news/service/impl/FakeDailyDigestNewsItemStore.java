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
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 日报面 t_news_item 只读 fake（#212 测试）：只覆盖日报两服务固定发出的
 * 查询形状——生成侧候选查询（status= + 发布门 OR 组 + publish_time 非空
 * /ge/lt + ORDER BY publish_time DESC,id DESC + LIMIT）与读取侧下架复检
 * （id IN 批查）。条件求值按解析出的 WHERE 语义逐行过滤；真实 SQL 口径由
 * NewsPipelinePgIt 族在本地 PG 证明。
 */
final class FakeDailyDigestNewsItemStore {

    private static final Pattern EQ = Pattern.compile("(\\w+)\\s*=\s*#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)\\}");
    private static final Pattern CMP = Pattern.compile("(\\w+)\\s*(<=|>=|<|>)\\s*#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)\\}");
    private static final Pattern GATE_OR = Pattern.compile(
            "eligible_time IS NULL OR eligible_time <= #\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)\\}");
    private static final Pattern IN_LIST = Pattern.compile("id\\s+IN\\s*\\(([^)]+)\\)");
    private static final Pattern LIMIT = Pattern.compile("LIMIT\\s+(\\d+)");

    private final List<NewsItemDO> rows = new ArrayList<>();

    final NewsItemMapper mapper = mock(NewsItemMapper.class);

    FakeDailyDigestNewsItemStore() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, NewsItemDO.class);
        when(mapper.selectList(any(Wrapper.class))).thenAnswer(invocation ->
                select(invocation.getArgument(0, Wrapper.class)).stream()
                        .map(row -> copy((NewsItemDO) row)).toList());
    }

    /** 持久行视图（克隆防调用方污染） */
    List<NewsItemDO> rows() {
        return rows.stream().map(FakeDailyDigestNewsItemStore::copy).toList();
    }

    void seed(NewsItemDO row) {
        rows.add(copy(row));
    }

    /** 模拟保留清理：按 id 删除行（快照独立性验收的驱动动作） */
    void purgeById(Long id) {
        rows.removeIf(row -> id.equals(row.getId()));
    }

    /** 模拟人工下架：status→hidden */
    void hideById(Long id) {
        rows.stream().filter(row -> id.equals(row.getId())).forEach(row -> row.setStatus("hidden"));
    }

    /** 模拟迟到富化完成：status→published+eligible_time 就绪 */
    void publishWithEligibleTime(Long id, Date eligibleTime) {
        rows.stream().filter(row -> id.equals(row.getId())).forEach(row -> {
            row.setStatus("published");
            row.setEligibleTime(eligibleTime);
        });
    }

    private List<NewsItemDO> select(Wrapper<NewsItemDO> wrapper) {
        String sql = wrapper.getSqlSegment();
        Map<String, Object> params = params(wrapper);
        // WHERE 语义解析（本服务面固定形状）
        String statusEq = eq(sql, params, "status");
        Date gateFloorParsed = null;
        Matcher gate = GATE_OR.matcher(sql);
        if (gate.find()) {
            Object value = params.get(gate.group(1));
            gateFloorParsed = value instanceof Date date ? date : null;
        }
        final Date gateFloor = gateFloorParsed;
        boolean publishNotNull = sql.contains("publish_time IS NOT NULL");
        Date geFloor = cmpDate(sql, params, "publish_time", ">=");
        Date ltCeiling = cmpDate(sql, params, "publish_time", "<");
        Set<Long> idIn = inIds(sql, params);
        List<NewsItemDO> matched = rows.stream()
                .filter(row -> statusEq == null || statusEq.equals(row.getStatus()))
                .filter(row -> gateFloor == null || row.getEligibleTime() == null
                        || !row.getEligibleTime().after(gateFloor))
                .filter(row -> !publishNotNull || row.getPublishTime() != null)
                .filter(row -> geFloor == null || row.getPublishTime() != null && !row.getPublishTime().before(geFloor))
                .filter(row -> ltCeiling == null || row.getPublishTime() != null && row.getPublishTime().before(ltCeiling))
                .filter(row -> idIn == null || idIn.contains(row.getId()))
                .collect(Collectors.toList());
        if (sql.contains("publish_time DESC")) {
            matched.sort(Comparator.comparing(NewsItemDO::getPublishTime,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(NewsItemDO::getId, Comparator.reverseOrder()));
        }
        Matcher limit = LIMIT.matcher(sql);
        if (limit.find()) {
            int cap = Integer.parseInt(limit.group(1));
            return matched.size() <= cap ? matched : matched.subList(0, cap);
        }
        return matched;
    }

    private static String eq(String sql, Map<String, Object> params, String column) {
        Matcher matcher = EQ.matcher(sql);
        while (matcher.find()) {
            if (column.equals(matcher.group(1))) {
                Object value = params.get(matcher.group(2));
                return value == null ? null : String.valueOf(value);
            }
        }
        return null;
    }

    private static Date cmpDate(String sql, Map<String, Object> params, String column, String op) {
        Matcher matcher = CMP.matcher(sql);
        while (matcher.find()) {
            if (column.equals(matcher.group(1)) && op.equals(matcher.group(2))) {
                Object value = params.get(matcher.group(3));
                if (value instanceof Date date) {
                    return date;
                }
            }
        }
        return null;
    }

    private static Set<Long> inIds(String sql, Map<String, Object> params) {
        Matcher matcher = IN_LIST.matcher(sql);
        if (!matcher.find()) {
            return null;
        }
        return Pattern.compile("#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)\\}")
                .matcher(matcher.group(1))
                .results()
                .map(result -> ((Number) params.get(result.group(1))).longValue())
                .collect(Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> params(Wrapper<?> wrapper) {
        return ((AbstractWrapper<?, String, ?>) wrapper).getParamNameValuePairs();
    }

    private static NewsItemDO copy(NewsItemDO row) {
        return NewsItemDO.builder()
                .id(row.getId()).sourceId(row.getSourceId()).url(row.getUrl())
                .urlHash(row.getUrlHash()).titleZh(row.getTitleZh()).titleEn(row.getTitleEn())
                .summaryZh(row.getSummaryZh()).summaryEn(row.getSummaryEn())
                .category(row.getCategory()).langRaw(row.getLangRaw())
                .publishTime(row.getPublishTime()).activityEndTime(row.getActivityEndTime())
                .publishTimePrecision(row.getPublishTimePrecision())
                .fetchTime(row.getFetchTime())
                .status(row.getStatus()).heat(row.getHeat())
                .eligibleTime(row.getEligibleTime()).summarySource(row.getSummarySource())
                .promptVersion(row.getPromptVersion()).contentHash(row.getContentHash())
                .createTime(row.getCreateTime())
                .build();
    }
}
