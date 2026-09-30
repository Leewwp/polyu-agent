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

package com.nageoffer.ai.ragent.news.heat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.news.dao.entity.NewsEventDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsEventItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsEventMigrationDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsEventSourceVoteDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsEventItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsEventMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsEventMigrationMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsEventSourceVoteMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 事件四表内存 fake（#187 测试）：以 Mockito mock 承载、状态真实模拟
 * MyBatis-Plus 行为——insert 回填 id 存克隆；selectList/selectCount 按解析出的
 * WHERE 求值；update 按 SET 应用克隆；delete/deleteById 求值删除。只覆盖
 * {@link NewsEventService} 固定发出的形状（lambda eq 查询 + lambdaUpdate set +
 * 全表 select + deleteById/delete(eq)）；真实 SQL 口径由 NewsEventIdentityPgIt
 * 在本地 PG 证明。断言直接读 {@link #events()}/{@link #items()}/{@link #votes()}/
 * {@link #migrations()} 的 DO 字段。
 */
public final class FakeNewsEventStore {

    private static final Pattern CONDITION = Pattern.compile(
            "(\\w+)\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)\\}");
    private static final Pattern SET_PAIR = Pattern.compile(
            "(\\w+)=#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)\\}");

    private final List<NewsEventDO> events = new ArrayList<>();
    private final List<NewsEventItemDO> items = new ArrayList<>();
    private final List<NewsEventSourceVoteDO> votes = new ArrayList<>();
    private final List<NewsEventMigrationDO> migrations = new ArrayList<>();
    private final AtomicLong idSeq = new AtomicLong();

    public final NewsEventMapper eventMapper = mock(NewsEventMapper.class);
    public final NewsEventItemMapper eventItemMapper = mock(NewsEventItemMapper.class);
    public final NewsEventSourceVoteMapper voteMapper = mock(NewsEventSourceVoteMapper.class);
    public final NewsEventMigrationMapper migrationMapper = mock(NewsEventMigrationMapper.class);

    public FakeNewsEventStore() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, NewsEventDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsEventItemDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsEventSourceVoteDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsEventMigrationDO.class);

        when(eventMapper.insert(any(NewsEventDO.class))).thenAnswer(invocation -> {
            NewsEventDO row = invocation.getArgument(0, NewsEventDO.class);
            row.setId(idSeq.incrementAndGet());
            events.add(cloneEvent(row));
            return 1;
        });
        when(eventMapper.selectList(any(Wrapper.class))).thenAnswer(invocation ->
                selectEvents(invocation.getArgument(0, Wrapper.class)));
        when(eventMapper.selectCount(any(Wrapper.class))).thenAnswer(invocation ->
                (long) selectEvents(invocation.getArgument(0, Wrapper.class)).size());
        when(eventMapper.update(any(), any(Wrapper.class))).thenAnswer(invocation ->
                updateEvents(invocation.getArgument(1, Wrapper.class)));

        when(eventItemMapper.insert(any(NewsEventItemDO.class))).thenAnswer(invocation -> {
            NewsEventItemDO row = invocation.getArgument(0, NewsEventItemDO.class);
            row.setId(idSeq.incrementAndGet());
            items.add(cloneItem(row));
            return 1;
        });
        when(eventItemMapper.selectList(any(Wrapper.class))).thenAnswer(invocation ->
                selectItems(invocation.getArgument(0, Wrapper.class)));
        when(eventItemMapper.update(any(), any(Wrapper.class))).thenAnswer(invocation ->
                updateItems(invocation.getArgument(1, Wrapper.class)));
        when(eventItemMapper.deleteById(any(java.io.Serializable.class))).thenAnswer(invocation -> {
            long id = ((Number) invocation.getArgument(0, java.io.Serializable.class)).longValue();
            return items.removeIf(row -> row.getId() == id) ? 1 : 0;
        });

        when(voteMapper.insert(any(NewsEventSourceVoteDO.class))).thenAnswer(invocation -> {
            NewsEventSourceVoteDO row = invocation.getArgument(0, NewsEventSourceVoteDO.class);
            row.setId(idSeq.incrementAndGet());
            votes.add(cloneVote(row));
            return 1;
        });
        when(voteMapper.delete(any(Wrapper.class))).thenAnswer(invocation -> {
            Long eventId = eqValue(invocation.getArgument(0, Wrapper.class), "event_id");
            return votes.removeIf(row -> row.getEventId().equals(eventId)) ? 1 : 0;
        });

        when(migrationMapper.insert(any(NewsEventMigrationDO.class))).thenAnswer(invocation -> {
            NewsEventMigrationDO row = invocation.getArgument(0, NewsEventMigrationDO.class);
            row.setId(idSeq.incrementAndGet());
            migrations.add(cloneMigration(row));
            return 1;
        });
    }

    public List<NewsEventDO> events() {
        return events.stream().map(FakeNewsEventStore::cloneEvent).toList();
    }

    public List<NewsEventItemDO> items() {
        return items.stream().map(FakeNewsEventStore::cloneItem).toList();
    }

    public List<NewsEventSourceVoteDO> votes() {
        return votes.stream().map(FakeNewsEventStore::cloneVote).toList();
    }

    public List<NewsEventMigrationDO> migrations() {
        return migrations.stream().map(FakeNewsEventStore::cloneMigration).toList();
    }

    // ================== WHERE/SET 求值（NewsEventService 固定形状：lambda eq） ==================

    private static Long eqValue(Wrapper<?> wrapper, String column) {
        Map<String, Object> params = ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
        Matcher condition = CONDITION.matcher(wrapper.getSqlSegment() == null ? "" : wrapper.getSqlSegment());
        while (condition.find()) {
            if (column.equals(condition.group(1))) {
                Object value = params.get(condition.group(2));
                return value == null ? null : ((Number) value).longValue();
            }
        }
        return null;
    }

    private static String eqString(Wrapper<?> wrapper, String column) {
        Map<String, Object> params = ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
        Matcher condition = CONDITION.matcher(wrapper.getSqlSegment() == null ? "" : wrapper.getSqlSegment());
        while (condition.find()) {
            if (column.equals(condition.group(1))) {
                Object value = params.get(condition.group(2));
                return value == null ? null : String.valueOf(value);
            }
        }
        return null;
    }

    private List<NewsEventDO> selectEvents(Wrapper<NewsEventDO> wrapper) {
        String status = eqString(wrapper, "status");
        return events.stream()
                .filter(row -> status == null || status.equals(row.getStatus()))
                .map(FakeNewsEventStore::cloneEvent)
                .toList();
    }

    private int updateEvents(Wrapper<NewsEventDO> wrapper) {
        Long id = eqValue(wrapper, "id");
        Map<String, Object> params = ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
        Map<String, Object> setters = new java.util.LinkedHashMap<>();
        Matcher setPair = SET_PAIR.matcher(wrapper instanceof com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<?> luw
                && luw.getSqlSet() != null ? luw.getSqlSet() : "");
        while (setPair.find()) {
            setters.put(setPair.group(1), params.get(setPair.group(2)));
        }
        int changed = 0;
        for (NewsEventDO row : events) {
            if (id != null && !row.getId().equals(id)) {
                continue;
            }
            setters.forEach((column, value) -> {
                switch (column) {
                    case "status" -> row.setStatus((String) value);
                    case "heat" -> row.setHeat(((Number) value).intValue());
                    case "first_report_time" -> row.setFirstReportTime((Date) value);
                    case "last_activity_time" -> row.setLastActivityTime((Date) value);
                    default -> throw new IllegalArgumentException("event fake 未覆盖 SET 列：" + column);
                }
            });
            changed++;
        }
        return changed;
    }

    private List<NewsEventItemDO> selectItems(Wrapper<NewsEventItemDO> wrapper) {
        Long eventId = eqValue(wrapper, "event_id");
        return items.stream()
                .filter(row -> eventId == null || eventId.equals(row.getEventId()))
                .map(FakeNewsEventStore::cloneItem)
                .toList();
    }

    private int updateItems(Wrapper<NewsEventItemDO> wrapper) {
        Long id = eqValue(wrapper, "id");
        Map<String, Object> params = ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
        Long target = null;
        Matcher setPair = SET_PAIR.matcher(wrapper instanceof com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<?> luw
                && luw.getSqlSet() != null ? luw.getSqlSet() : "");
        while (setPair.find()) {
            if ("event_id".equals(setPair.group(1))) {
                target = ((Number) params.get(setPair.group(2))).longValue();
            }
        }
        int changed = 0;
        for (NewsEventItemDO row : items) {
            if (id != null && row.getId().equals(id)) {
                row.setEventId(target);
                changed++;
            }
        }
        return changed;
    }

    private static NewsEventDO cloneEvent(NewsEventDO row) {
        return NewsEventDO.builder().id(row.getId()).status(row.getStatus()).heat(row.getHeat())
                .firstReportTime(row.getFirstReportTime()).lastActivityTime(row.getLastActivityTime())
                .createTime(row.getCreateTime()).updateTime(row.getUpdateTime()).build();
    }

    private static NewsEventItemDO cloneItem(NewsEventItemDO row) {
        return NewsEventItemDO.builder().id(row.getId()).eventId(row.getEventId()).itemId(row.getItemId())
                .sourceId(row.getSourceId()).independenceGroup(row.getIndependenceGroup())
                .publishTime(row.getPublishTime()).joinedTime(row.getJoinedTime()).build();
    }

    private static NewsEventSourceVoteDO cloneVote(NewsEventSourceVoteDO row) {
        return NewsEventSourceVoteDO.builder().id(row.getId()).eventId(row.getEventId())
                .independenceGroup(row.getIndependenceGroup()).voteCount(row.getVoteCount())
                .firstVoteTime(row.getFirstVoteTime()).lastVoteTime(row.getLastVoteTime())
                .updateTime(row.getUpdateTime()).build();
    }

    private static NewsEventMigrationDO cloneMigration(NewsEventMigrationDO row) {
        return NewsEventMigrationDO.builder().id(row.getId()).oldEventId(row.getOldEventId())
                .newEventId(row.getNewEventId()).kind(row.getKind()).itemId(row.getItemId())
                .reason(row.getReason()).createTime(row.getCreateTime()).build();
    }
}
