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
import com.nageoffer.ai.ragent.news.dao.entity.NewsDailyDigestActivityDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsDailyDigestDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsDailyDigestItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsDailyDigestKeyDateDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsDailyDigestActivityMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsDailyDigestItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsDailyDigestKeyDateMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsDailyDigestMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 日报两表内存 fake（#212 测试，#240 增 digest_id IN 批查与刊头 LIMIT 形状，
 * #316 增关键日期栏目快照行，#330 增校园活动版面快照行）：真实模拟本服务面
 * 固定发出的形状——刊头 insert（回填 id）/delete(digest_date=)/selectCount/
 * selectOne/selectList（digest_date 倒序+LIMIT n=取最近 n 期）；快照 insert/
 * selectList（digest_id= 或 digest_id IN (…)，ORDER BY seq）；刊头删除
 * <b>模拟外键 ON DELETE CASCADE</b>带走快照行（条目+关键日期+活动，幂等
 * 重建不留残行）。真实 SQL 口径（唯一约束/级联）由 PG 环境保障，本 fake
 * 只驱动行为断言。
 */
final class FakeDailyDigestStore {

    private static final Pattern EQ = Pattern.compile("(\\w+)\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)\\}");
    /** digest_id IN (…) 批查形状（#240 目录 firstTitle/期级 RSS 的批量复检） */
    private static final Pattern DIGEST_ID_IN = Pattern.compile("digest_id\\s+IN\\s*\\(([^)]+)\\)");
    private static final Pattern LIMIT = Pattern.compile("LIMIT\\s+(\\d+)");

    private final List<NewsDailyDigestDO> headers = new ArrayList<>();
    private final List<NewsDailyDigestItemDO> items = new ArrayList<>();
    private final List<NewsDailyDigestKeyDateDO> keyDates = new ArrayList<>();
    private final List<NewsDailyDigestActivityDO> activities = new ArrayList<>();
    private final AtomicLong headerIdSeq = new AtomicLong();
    private final AtomicLong itemIdSeq = new AtomicLong();
    private final AtomicLong keyDateIdSeq = new AtomicLong();
    private final AtomicLong activityIdSeq = new AtomicLong();

    final NewsDailyDigestMapper digestMapper = mock(NewsDailyDigestMapper.class);
    final NewsDailyDigestItemMapper digestItemMapper = mock(NewsDailyDigestItemMapper.class);
    final NewsDailyDigestKeyDateMapper digestKeyDateMapper = mock(NewsDailyDigestKeyDateMapper.class);
    final NewsDailyDigestActivityMapper digestActivityMapper = mock(NewsDailyDigestActivityMapper.class);

    FakeDailyDigestStore() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, NewsDailyDigestDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsDailyDigestItemDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsDailyDigestKeyDateDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsDailyDigestActivityDO.class);

        when(digestMapper.insert(any(NewsDailyDigestDO.class))).thenAnswer(invocation -> {
            NewsDailyDigestDO row = invocation.getArgument(0, NewsDailyDigestDO.class);
            row.setId(headerIdSeq.incrementAndGet());
            headers.add(row);
            return 1;
        });
        when(digestMapper.delete(any(Wrapper.class))).thenAnswer(invocation ->
                deleteHeaders(invocation.getArgument(0, Wrapper.class)));
        when(digestMapper.selectCount(any(Wrapper.class))).thenAnswer(invocation ->
                (long) matchHeaders(invocation.getArgument(0, Wrapper.class)).size());
        when(digestMapper.selectOne(any(Wrapper.class))).thenAnswer(invocation -> {
            List<NewsDailyDigestDO> matched = matchHeaders(invocation.getArgument(0, Wrapper.class));
            return matched.isEmpty() ? null : matched.get(0);
        });
        when(digestMapper.selectList(any(Wrapper.class))).thenAnswer(invocation ->
                new ArrayList<>(matchHeaders(invocation.getArgument(0, Wrapper.class))));

        when(digestItemMapper.insert(any(NewsDailyDigestItemDO.class))).thenAnswer(invocation -> {
            NewsDailyDigestItemDO row = invocation.getArgument(0, NewsDailyDigestItemDO.class);
            row.setId(itemIdSeq.incrementAndGet());
            items.add(row);
            return 1;
        });
        when(digestItemMapper.selectList(any(Wrapper.class))).thenAnswer(invocation ->
                matchItems(invocation.getArgument(0, Wrapper.class)));

        when(digestKeyDateMapper.insert(any(NewsDailyDigestKeyDateDO.class))).thenAnswer(invocation -> {
            NewsDailyDigestKeyDateDO row = invocation.getArgument(0, NewsDailyDigestKeyDateDO.class);
            row.setId(keyDateIdSeq.incrementAndGet());
            keyDates.add(row);
            return 1;
        });
        when(digestKeyDateMapper.selectList(any(Wrapper.class))).thenAnswer(invocation ->
                matchKeyDates(invocation.getArgument(0, Wrapper.class)));

        when(digestActivityMapper.insert(any(NewsDailyDigestActivityDO.class))).thenAnswer(invocation -> {
            NewsDailyDigestActivityDO row = invocation.getArgument(0, NewsDailyDigestActivityDO.class);
            row.setId(activityIdSeq.incrementAndGet());
            activities.add(row);
            return 1;
        });
        when(digestActivityMapper.selectList(any(Wrapper.class))).thenAnswer(invocation ->
                matchActivities(invocation.getArgument(0, Wrapper.class)));
    }

    /** 刊头持久视图（克隆防调用方污染） */
    List<NewsDailyDigestDO> headers() {
        return headers.stream().map(FakeDailyDigestStore::copyHeader).toList();
    }

    /** 快照持久视图（克隆） */
    List<NewsDailyDigestItemDO> items() {
        return items.stream().map(FakeDailyDigestStore::copyItem).toList();
    }

    /** 关键日期栏目快照持久视图（克隆，#316） */
    List<NewsDailyDigestKeyDateDO> keyDates() {
        return keyDates.stream().map(FakeDailyDigestStore::copyKeyDate).toList();
    }

    /** 校园活动版面快照持久视图（克隆，#330） */
    List<NewsDailyDigestActivityDO> activities() {
        return activities.stream().map(FakeDailyDigestStore::copyActivity).toList();
    }

    private int deleteHeaders(Wrapper<NewsDailyDigestDO> wrapper) {
        List<NewsDailyDigestDO> doomed = matchHeaders(wrapper);
        // 模拟 ON DELETE CASCADE：刊头删除带走其全部快照行（条目+关键日期+活动）
        items.removeIf(item -> doomed.stream().anyMatch(h -> h.getId().equals(item.getDigestId())));
        keyDates.removeIf(row -> doomed.stream().anyMatch(h -> h.getId().equals(row.getDigestId())));
        activities.removeIf(row -> doomed.stream().anyMatch(h -> h.getId().equals(row.getDigestId())));
        headers.removeAll(doomed);
        return doomed.size();
    }

    private List<NewsDailyDigestDO> matchHeaders(Wrapper<NewsDailyDigestDO> wrapper) {
        Map<String, Object> params = params(wrapper);
        String sql = wrapper.getSqlSegment();
        Long digestId = eqLong(sql, params, "id");
        Object digestDate = eqValue(sql, params, "digest_date");
        List<NewsDailyDigestDO> matched = headers.stream()
                .filter(h -> (digestId == null || digestId.equals(h.getId()))
                        && (digestDate == null || digestDate.equals(h.getDigestDate())))
                .sorted(Comparator.comparing(NewsDailyDigestDO::getDigestDate).reversed())
                .toList();
        // LIMIT n（目录/期级 feed 的固定 LIMIT 形状；已按 digest_date 倒序=取最近 n 期）
        Matcher limit = LIMIT.matcher(sql);
        if (limit.find()) {
            int cap = Integer.parseInt(limit.group(1));
            return matched.size() <= cap ? matched : matched.subList(0, cap);
        }
        return matched;
    }

    private List<NewsDailyDigestItemDO> matchItems(Wrapper<NewsDailyDigestItemDO> wrapper) {
        Map<String, Object> params = params(wrapper);
        String sql = wrapper.getSqlSegment();
        Long digestId = eqLong(sql, params, "digest_id");
        Set<Long> digestIdIn = inDigestIds(sql, params);
        boolean seqAsc = sql != null && sql.contains("seq ASC");
        return items.stream()
                .filter(item -> digestId == null || digestId.equals(item.getDigestId()))
                .filter(item -> digestIdIn == null || digestIdIn.contains(item.getDigestId()))
                .sorted(seqAsc ? Comparator.comparing(NewsDailyDigestItemDO::getSeq)
                        : Comparator.comparing(NewsDailyDigestItemDO::getId))
                .map(FakeDailyDigestStore::copyItem)
                .toList();
    }

    /** 关键日期栏目快照批查（digest_id= 固定形状，seq 升序——#316 读取面直映） */
    private List<NewsDailyDigestKeyDateDO> matchKeyDates(Wrapper<NewsDailyDigestKeyDateDO> wrapper) {
        Map<String, Object> params = params(wrapper);
        String sql = wrapper.getSqlSegment();
        Long digestId = eqLong(sql, params, "digest_id");
        return keyDates.stream()
                .filter(row -> digestId == null || digestId.equals(row.getDigestId()))
                .sorted(Comparator.comparing(NewsDailyDigestKeyDateDO::getSeq))
                .map(FakeDailyDigestStore::copyKeyDate)
                .toList();
    }

    /** 校园活动版面快照批查（digest_id= 固定形状，seq 升序——#330 读取面直映） */
    private List<NewsDailyDigestActivityDO> matchActivities(Wrapper<NewsDailyDigestActivityDO> wrapper) {
        Map<String, Object> params = params(wrapper);
        String sql = wrapper.getSqlSegment();
        Long digestId = eqLong(sql, params, "digest_id");
        return activities.stream()
                .filter(row -> digestId == null || digestId.equals(row.getDigestId()))
                .sorted(Comparator.comparing(NewsDailyDigestActivityDO::getSeq))
                .map(FakeDailyDigestStore::copyActivity)
                .toList();
    }

    private static Long eqLong(String sql, Map<String, Object> params, String column) {
        Object value = eqValue(sql, params, column);
        return value == null ? null : ((Number) value).longValue();
    }

    /** digest_id IN (#{…}, #{…}) 参数集解析（无该形状时 null=不过滤） */
    private static Set<Long> inDigestIds(String sql, Map<String, Object> params) {
        if (sql == null) {
            return null;
        }
        Matcher matcher = DIGEST_ID_IN.matcher(sql);
        if (!matcher.find()) {
            return null;
        }
        return Pattern.compile("#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)\\}")
                .matcher(matcher.group(1))
                .results()
                .map(result -> ((Number) params.get(result.group(1))).longValue())
                .collect(Collectors.toSet());
    }

    private static Object eqValue(String sql, Map<String, Object> params, String column) {
        if (sql == null) {
            return null;
        }
        Matcher matcher = EQ.matcher(sql);
        while (matcher.find()) {
            if (column.equals(matcher.group(1))) {
                return params.get(matcher.group(2));
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> params(Wrapper<?> wrapper) {
        return ((AbstractWrapper<?, String, ?>) wrapper).getParamNameValuePairs();
    }

    private static NewsDailyDigestDO copyHeader(NewsDailyDigestDO row) {
        return NewsDailyDigestDO.builder()
                .id(row.getId()).digestDate(row.getDigestDate())
                .windowStart(row.getWindowStart()).windowEnd(row.getWindowEnd())
                .introZh(row.getIntroZh()).introEn(row.getIntroEn())
                .introSource(row.getIntroSource()).itemCount(row.getItemCount())
                .status(row.getStatus()).buildTime(row.getBuildTime())
                .createTime(row.getCreateTime()).updateTime(row.getUpdateTime())
                .build();
    }

    private static NewsDailyDigestItemDO copyItem(NewsDailyDigestItemDO row) {
        return NewsDailyDigestItemDO.builder()
                .id(row.getId()).digestId(row.getDigestId()).itemId(row.getItemId())
                .seq(row.getSeq()).url(row.getUrl()).urlHash(row.getUrlHash())
                .titleZh(row.getTitleZh()).titleEn(row.getTitleEn())
                .summaryZh(row.getSummaryZh()).summaryEn(row.getSummaryEn())
                .category(row.getCategory()).topicSlugs(row.getTopicSlugs())
                .sourceId(row.getSourceId()).sourceKey(row.getSourceKey())
                .sourcePlatform(row.getSourcePlatform()).sourceOfficial(row.getSourceOfficial())
                .sourceDisplayName(row.getSourceDisplayName())
                .sourceDisplayNameEn(row.getSourceDisplayNameEn())
                .publishTime(row.getPublishTime())
                .build();
    }

    private static NewsDailyDigestKeyDateDO copyKeyDate(NewsDailyDigestKeyDateDO row) {
        return NewsDailyDigestKeyDateDO.builder()
                .id(row.getId()).digestId(row.getDigestId()).keyDateId(row.getKeyDateId())
                .seq(row.getSeq()).uid(row.getUid())
                .titleZh(row.getTitleZh()).titleEn(row.getTitleEn())
                .audienceText(row.getAudienceText()).precision(row.getPrecision())
                .dateStart(row.getDateStart()).dateEnd(row.getDateEnd())
                .fuzzyHint(row.getFuzzyHint())
                .ongoing(row.getOngoing()).daysUntil(row.getDaysUntil())
                .build();
    }

    private static NewsDailyDigestActivityDO copyActivity(NewsDailyDigestActivityDO row) {
        return NewsDailyDigestActivityDO.builder()
                .id(row.getId()).digestId(row.getDigestId()).itemId(row.getItemId())
                .seq(row.getSeq())
                .titleZh(row.getTitleZh()).titleEn(row.getTitleEn())
                .url(row.getUrl())
                .dateStart(row.getDateStart()).dateEnd(row.getDateEnd())
                .ongoing(row.getOngoing())
                .build();
    }
}
