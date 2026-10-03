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
import com.nageoffer.ai.ragent.news.dao.entity.NewsDailyDigestDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsDailyDigestItemDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsDailyDigestItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsDailyDigestMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 日报两表内存 fake（#212 测试）：真实模拟本服务面固定发出的形状——
 * 刊头 insert（回填 id）/delete(digest_date=)/selectCount/selectOne/selectList；
 * 快照 insert/selectList(digest_id=, ORDER BY seq)；刊头删除<b>模拟外键
 * ON DELETE CASCADE</b>带走快照行（幂等重建不留残行）。
 * 真实 SQL 口径（唯一约束/级联）由 PG 环境保障，本 fake 只驱动行为断言。
 */
final class FakeDailyDigestStore {

    private static final Pattern EQ = Pattern.compile("(\\w+)\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)\\}");

    private final List<NewsDailyDigestDO> headers = new ArrayList<>();
    private final List<NewsDailyDigestItemDO> items = new ArrayList<>();
    private final AtomicLong headerIdSeq = new AtomicLong();
    private final AtomicLong itemIdSeq = new AtomicLong();

    final NewsDailyDigestMapper digestMapper = mock(NewsDailyDigestMapper.class);
    final NewsDailyDigestItemMapper digestItemMapper = mock(NewsDailyDigestItemMapper.class);

    FakeDailyDigestStore() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, NewsDailyDigestDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsDailyDigestItemDO.class);

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
    }

    /** 刊头持久视图（克隆防调用方污染） */
    List<NewsDailyDigestDO> headers() {
        return headers.stream().map(FakeDailyDigestStore::copyHeader).toList();
    }

    /** 快照持久视图（克隆） */
    List<NewsDailyDigestItemDO> items() {
        return items.stream().map(FakeDailyDigestStore::copyItem).toList();
    }

    private int deleteHeaders(Wrapper<NewsDailyDigestDO> wrapper) {
        List<NewsDailyDigestDO> doomed = matchHeaders(wrapper);
        // 模拟 ON DELETE CASCADE：刊头删除带走其全部快照行
        items.removeIf(item -> doomed.stream().anyMatch(h -> h.getId().equals(item.getDigestId())));
        headers.removeAll(doomed);
        return doomed.size();
    }

    private List<NewsDailyDigestDO> matchHeaders(Wrapper<NewsDailyDigestDO> wrapper) {
        Map<String, Object> params = params(wrapper);
        String sql = wrapper.getSqlSegment();
        Long digestId = eqLong(sql, params, "id");
        Object digestDate = eqValue(sql, params, "digest_date");
        return headers.stream()
                .filter(h -> (digestId == null || digestId.equals(h.getId()))
                        && (digestDate == null || digestDate.equals(h.getDigestDate())))
                .sorted(Comparator.comparing(NewsDailyDigestDO::getDigestDate).reversed())
                .toList();
    }

    private List<NewsDailyDigestItemDO> matchItems(Wrapper<NewsDailyDigestItemDO> wrapper) {
        Map<String, Object> params = params(wrapper);
        String sql = wrapper.getSqlSegment();
        Long digestId = eqLong(sql, params, "digest_id");
        boolean seqAsc = sql != null && sql.contains("seq ASC");
        return items.stream()
                .filter(item -> digestId == null || digestId.equals(item.getDigestId()))
                .sorted(seqAsc ? Comparator.comparing(NewsDailyDigestItemDO::getSeq)
                        : Comparator.comparing(NewsDailyDigestItemDO::getId))
                .map(FakeDailyDigestStore::copyItem)
                .toList();
    }

    private static Long eqLong(String sql, Map<String, Object> params, String column) {
        Object value = eqValue(sql, params, column);
        return value == null ? null : ((Number) value).longValue();
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
}
