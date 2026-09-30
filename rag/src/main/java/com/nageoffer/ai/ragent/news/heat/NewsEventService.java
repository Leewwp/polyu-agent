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

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.news.dao.entity.NewsEventDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsEventItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsEventMigrationDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsEventSourceVoteDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsEventItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsEventMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsEventMigrationMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsEventSourceVoteMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * 事件最小模型服务（#187：持久身份+参与者证据+独立来源映射+24h 半衰热度——
 * 明确不做综述/事件页/向量/评分）
 *
 * <p><b>重归组编排</b>（{@link #regroupEvents()}，抓取轮末尾触发，节奏 3 段/日）：
 * <ol>
 * <li><b>终态摘除</b>：参与者证据行中已不在当前发布窗（status≠published 或出窗）
 * 的条目摘除（detach 迁移）——下架/过期处理事件证据；公开投影由查询面按条目
 * status 过滤天然摘除，两面一致。</li>
 * <li><b>簇认领（keeper）</b>：每个存活事件被「含其最早成员的簇」认领（确定原组
 * 判据=事件内最早 publish_time 的成员所在簇，平手取成员 id 小者）。</li>
 * <li><b>簇→目标事件</b>：认领本簇的事件 0 个→新建事件；1 个→沿用（同事件 ID
 * 稳定）；≥2 个→<b>合并</b>：存续=最早 first_report_time（平手取小 id），其余转
 * superseded 并落 merge 迁移（旧→存续）——不要求多个旧 ID 合并后全部不变。</li>
 * <li><b>成员归属</b>：簇成员的目标=本簇目标事件。原属其它存活事件的成员落
 * split（目标为本轮新建事件）或 regroup（目标为既有存活事件）迁移；原属被合并
 * 事件（本轮已 superseded）的成员随事件合并改挂存续，不另记条目级迁移
 * （事件级 merge 行已覆盖）。</li>
 * <li><b>投票与热度</b>：受影响事件按成员全量重建独立来源投票账
 * * （(event_id, independence_group) 一行一票，同机构多 feed/聚合口不重复
 * 加票）；热度基数=48h 证据窗内（事件首报起算）的独立组数+Σ组内最大源权重，
 * ×24h 半衰（锚=first_report_time）写事件行与成员条目 heat。</li>
 * </ol>
 *
 * <p>窗口=4 天（NewsHeatService.HEAT_WINDOW_DAYS）：窗口外事件不再重算（成员
 * 摘除后自然空置，行保留审计不删）。单轮失败不阻断抓取（调用侧 runSafely）。
 */
@Slf4j
@Service
public class NewsEventService {

    private final NewsItemMapper itemMapper;
    private final NewsEventMapper eventMapper;
    private final NewsEventItemMapper eventItemMapper;
    private final NewsEventSourceVoteMapper voteMapper;
    private final NewsEventMigrationMapper migrationMapper;
    private final NewsStoryAssembler assembler;
    private final NewsStoryClusterer clusterer;
    private final NewsHeatService heatService;
    private final NewsHeatProperties properties;
    private final Supplier<Date> nowSupplier;

    @Autowired
    public NewsEventService(NewsItemMapper itemMapper,
                            NewsEventMapper eventMapper,
                            NewsEventItemMapper eventItemMapper,
                            NewsEventSourceVoteMapper voteMapper,
                            NewsEventMigrationMapper migrationMapper,
                            NewsStoryAssembler assembler,
                            NewsStoryClusterer clusterer,
                            NewsHeatService heatService,
                            NewsHeatProperties properties) {
        this(itemMapper, eventMapper, eventItemMapper, voteMapper, migrationMapper,
                assembler, clusterer, heatService, properties, Date::new);
    }

    /**
     * 全参构造器（测试注入时钟，FakeWindowCounter 时间旅行先例同源；跨包资源回放测试消费故 public）
     */
    public NewsEventService(NewsItemMapper itemMapper,
                     NewsEventMapper eventMapper,
                     NewsEventItemMapper eventItemMapper,
                     NewsEventSourceVoteMapper voteMapper,
                     NewsEventMigrationMapper migrationMapper,
                     NewsStoryAssembler assembler,
                     NewsStoryClusterer clusterer,
                     NewsHeatService heatService,
                     NewsHeatProperties properties,
                     Supplier<Date> nowSupplier) {
        this.itemMapper = itemMapper;
        this.eventMapper = eventMapper;
        this.eventItemMapper = eventItemMapper;
        this.voteMapper = voteMapper;
        this.migrationMapper = migrationMapper;
        this.assembler = assembler;
        this.clusterer = clusterer;
        this.heatService = heatService;
        this.properties = properties;
        this.nowSupplier = nowSupplier;
    }

    /**
     * 一轮重归组结果（日志与验收口径计数）
     *
     * @param activeEvents 本轮收尾仍存活的事件数
     * @param created      新建事件数
     * @param merges       事件级合并次数（被并入的旧事件数）
     * @param splits       分裂迁出条目数（落新事件）
     * @param regroups     存活事件间改组条目数
     * @param detached     终态摘除参与者证据条目数
     * @param heatWrites   条目热度写入数
     */
    public record RegroupResult(int activeEvents, int created, int merges, int splits,
                                int regroups, int detached, int heatWrites) {
    }

    /**
     * 事件重归组一轮（编排详见类 javadoc）；返回计数供日志/验收。
     */
    public RegroupResult regroupEvents() {
        Date now = nowSupplier.get();
        Date windowFloor = new Date(now.getTime() - NewsHeatService.HEAT_WINDOW_DAYS * 24L * 3600L * 1000L);
        NewsStoryAssembler.NewsStoryWindow window = assembler.loadPublished(windowFloor);
        List<NewsStoryCluster> clusters = clusterer.cluster(window.items(), properties.isStoryMergeEnabled());

        Map<Long, NewsSourceDO> sourcesById = window.sourcesById();

        // 现有参与者证据 + 存活事件索引
        Map<Long, NewsEventItemDO> participationByItem = new LinkedHashMap<>();
        for (NewsEventItemDO row : eventItemMapper.selectList(Wrappers.lambdaQuery(NewsEventItemDO.class))) {
            participationByItem.put(row.getItemId(), row);
        }
        Map<Long, NewsEventDO> activeEvents = new HashMap<>();
        for (NewsEventDO event : eventMapper.selectList(Wrappers.lambdaQuery(NewsEventDO.class)
                .eq(NewsEventDO::getStatus, NewsEventDO.STATUS_ACTIVE))) {
            activeEvents.put(event.getId(), event);
        }

        // 1) 终态摘除：证据行中已不在当前发布窗的条目（下架/过期/出窗）
        Set<Long> liveItemIds = new HashSet<>();
        Map<Long, Integer> clusterIndexByItem = new HashMap<>();
        for (int i = 0; i < clusters.size(); i++) {
            for (NewsStoryItem member : clusters.get(i).members()) {
                liveItemIds.add(member.id());
                clusterIndexByItem.put(member.id(), i);
            }
        }
        int detached = 0;
        for (NewsEventItemDO row : List.copyOf(participationByItem.values())) {
            if (!liveItemIds.contains(row.getItemId())) {
                eventItemMapper.deleteById(row.getId());
                participationByItem.remove(row.getItemId());
                migrationMapper.insert(NewsEventMigrationDO.builder()
                        .oldEventId(row.getEventId())
                        .kind(NewsEventMigrationDO.KIND_DETACH)
                        .itemId(row.getItemId())
                        .reason("条目转终态/出热度窗，摘除参与者证据并撤票")
                        .build());
                detached++;
            }
        }

        // 2) 簇认领：存活事件 → 含其最早成员（publish_time，平手取成员 id 小者）的簇
        Map<Long, List<NewsEventItemDO>> membersByEvent = new HashMap<>();
        for (NewsEventItemDO row : participationByItem.values()) {
            if (activeEvents.containsKey(row.getEventId())) {
                membersByEvent.computeIfAbsent(row.getEventId(), k -> new ArrayList<>()).add(row);
            }
        }
        Map<Integer, Set<Long>> stakedEventsByCluster = new HashMap<>();
        for (Map.Entry<Long, List<NewsEventItemDO>> entry : membersByEvent.entrySet()) {
            NewsEventItemDO earliest = entry.getValue().stream()
                    .min(Comparator
                            .comparing(NewsEventItemDO::getPublishTime,
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(NewsEventItemDO::getItemId))
                    .orElse(null);
            Integer clusterIndex = earliest == null ? null : clusterIndexByItem.get(earliest.getItemId());
            if (clusterIndex != null) {
                stakedEventsByCluster.computeIfAbsent(clusterIndex, k -> new HashSet<>()).add(entry.getKey());
            }
        }

        // 3) 簇 → 目标事件（新建/沿用/合并）+ 4) 成员归属
        int created = 0;
        int merges = 0;
        int splits = 0;
        int regroups = 0;
        Set<Long> newEventIds = new HashSet<>();
        Set<Long> supersededThisRound = new HashSet<>();
        Map<Long, Long> targetByItem = new HashMap<>();
        for (int i = 0; i < clusters.size(); i++) {
            NewsStoryCluster cluster = clusters.get(i);
            Set<Long> staked = stakedEventsByCluster.getOrDefault(i, Set.of());
            Long target;
            if (staked.isEmpty()) {
                NewsEventDO createdEvent = createEvent(cluster);
                target = createdEvent.getId();
                created++;
                newEventIds.add(target);
                activeEvents.put(target, createdEvent);
            } else if (staked.size() == 1) {
                target = staked.iterator().next();
            } else {
                Long survivor = staked.stream().min(Comparator
                                .comparing((Long id) -> firstReportOf(activeEvents.get(id), membersByEvent),
                                        Comparator.nullsLast(Comparator.naturalOrder()))
                                .thenComparing(Long::longValue))
                        .orElseThrow();
                for (Long other : staked) {
                    if (other.equals(survivor)) {
                        continue;
                    }
                    eventMapper.update(null, Wrappers.lambdaUpdate(NewsEventDO.class)
                            .eq(NewsEventDO::getId, other)
                            .set(NewsEventDO::getStatus, NewsEventDO.STATUS_SUPERSEDED));
                    activeEvents.remove(other);
                    supersededThisRound.add(other);
                    migrationMapper.insert(NewsEventMigrationDO.builder()
                            .oldEventId(other)
                            .newEventId(survivor)
                            .kind(NewsEventMigrationDO.KIND_MERGE)
                            .reason("重归组合并：簇内多事件，存续=最早首报（平手取小 id）")
                            .build());
                    merges++;
                }
                target = survivor;
            }
            for (NewsStoryItem member : cluster.members()) {
                NewsEventItemDO existing = participationByItem.get(member.id());
                if (existing == null) {
                    insertParticipation(target, member, sourcesById.get(member.sourceId()));
                    participationByItem.put(member.id(), participationRow(target, member, sourcesById.get(member.sourceId())));
                } else if (!existing.getEventId().equals(target)) {
                    Long oldEventId = existing.getEventId();
                    eventItemMapper.update(null, Wrappers.lambdaUpdate(NewsEventItemDO.class)
                            .eq(NewsEventItemDO::getId, existing.getId())
                            .set(NewsEventItemDO::getEventId, target));
                    existing.setEventId(target);
                    if (!supersededThisRound.contains(oldEventId)) {
                        // 旧事件仍存活：条目离开原事件——目标是本轮新建事件=分裂迁出，
                        // 目标是既有存活事件=改组（旧事件被合并的成员随事件改挂，不另记）
                        boolean split = newEventIds.contains(target);
                        migrationMapper.insert(NewsEventMigrationDO.builder()
                                .oldEventId(oldEventId)
                                .newEventId(target)
                                .kind(split ? NewsEventMigrationDO.KIND_SPLIT : NewsEventMigrationDO.KIND_REGROUP)
                                .itemId(member.id())
                                .reason(split ? "分裂：原 ID 留给含最早成员的确定原组，迁出条目落新事件"
                                        : "重归组：条目在存活事件间改组")
                                .build());
                        if (split) {
                            splits++;
                        } else {
                            regroups++;
                        }
                    }
                }
                targetByItem.put(member.id(), target);
            }
        }
        // 5) 投票与热度重建：所有存活事件每轮全量重建（确定性优先——vote 账重建、
        // 热度按持久基线跳写）；被合并事件清票撤热度（成员已改挂存续方）
        Set<Long> touchedEvents = new HashSet<>(activeEvents.keySet());
        touchedEvents.addAll(supersededThisRound);
        Map<Long, List<NewsEventItemDO>> finalMembers = new HashMap<>();
        for (NewsEventItemDO row : participationByItem.values()) {
            finalMembers.computeIfAbsent(row.getEventId(), k -> new ArrayList<>()).add(row);
        }

        int heatWrites = 0;
        for (Long eventId : touchedEvents) {
            boolean active = activeEvents.containsKey(eventId);
            List<NewsEventItemDO> members = finalMembers.getOrDefault(eventId, List.of());
            rebuildVotes(eventId, members);
            if (active) {
                heatWrites += recomputeEventHeat(eventId, members, clusters, sourcesById, now);
            }
        }
        long activeCount = eventMapper.selectCount(Wrappers.lambdaQuery(NewsEventDO.class)
                .eq(NewsEventDO::getStatus, NewsEventDO.STATUS_ACTIVE));
        log.info("[news] 事件重归组完成：窗口 {} 条目 / {} 簇，新建 {} 事件，合并 {} 次，"
                        + "分裂迁出 {} 条，改组 {} 条，摘除 {} 条；存活事件 {}，热度写入 {} 条",
                window.items().size(), clusters.size(), created, merges, splits, regroups,
                detached, activeCount, heatWrites);
        return new RegroupResult((int) activeCount, created, merges, splits, regroups, detached, heatWrites);
    }

    /**
     * 事件首报（优先事件行持久值；新事件/空值时按成员最早 publish_time 现推）
     */
    private Date firstReportOf(NewsEventDO event, Map<Long, List<NewsEventItemDO>> membersByEvent) {
        if (event != null && event.getFirstReportTime() != null) {
            return event.getFirstReportTime();
        }
        return membersByEvent.getOrDefault(event == null ? null : event.getId(), List.of()).stream()
                .map(NewsEventItemDO::getPublishTime)
                .filter(java.util.Objects::nonNull)
                .min(Date::compareTo)
                .orElse(null);
    }

    /**
     * 新建事件（status=active；时间字段按簇成员首末发布时刻落定）——返回行含回填 id
     */
    private NewsEventDO createEvent(NewsStoryCluster cluster) {
        NewsEventDO event = NewsEventDO.builder()
                .status(NewsEventDO.STATUS_ACTIVE)
                .heat(0)
                .firstReportTime(cluster.earliestPublish())
                .lastActivityTime(cluster.latestPublish())
                .build();
        eventMapper.insert(event);
        return event;
    }

    private NewsEventItemDO participationRow(Long eventId, NewsStoryItem member, NewsSourceDO source) {
        return NewsEventItemDO.builder()
                .eventId(eventId)
                .itemId(member.id())
                .sourceId(member.sourceId())
                .independenceGroup(NewsHeatService.independenceKey(source))
                .publishTime(member.publishTime())
                // joined_time NOT NULL：显式落值（框架 MetaObjectHandler 只填 createTime/updateTime）
                .joinedTime(nowSupplier.get())
                .build();
    }

    private void insertParticipation(Long eventId, NewsStoryItem member, NewsSourceDO source) {
        eventItemMapper.insert(participationRow(eventId, member, source));
    }

    /**
     * 投票账全量重建：按独立组聚合（一行一票；vote_count=组内条目数不放大票权）
     */
    private void rebuildVotes(Long eventId, List<NewsEventItemDO> members) {
        voteMapper.delete(Wrappers.lambdaQuery(NewsEventSourceVoteDO.class)
                .eq(NewsEventSourceVoteDO::getEventId, eventId));
        Map<String, List<NewsEventItemDO>> byGroup = new TreeMap<>();
        for (NewsEventItemDO member : members) {
            byGroup.computeIfAbsent(member.getIndependenceGroup(), k -> new ArrayList<>()).add(member);
        }
        for (Map.Entry<String, List<NewsEventItemDO>> entry : byGroup.entrySet()) {
            Date first = entry.getValue().stream().map(NewsEventItemDO::getPublishTime)
                    .filter(java.util.Objects::nonNull).min(Date::compareTo).orElse(null);
            Date last = entry.getValue().stream().map(NewsEventItemDO::getPublishTime)
                    .filter(java.util.Objects::nonNull).max(Date::compareTo).orElse(null);
            voteMapper.insert(NewsEventSourceVoteDO.builder()
                    .eventId(eventId)
                    .independenceGroup(entry.getKey())
                    .voteCount(entry.getValue().size())
                    .firstVoteTime(first)
                    .lastVoteTime(last)
                    .build());
        }
    }

    /**
     * 事件热度重算并写事件行+成员条目：基数=48h 证据窗内独立组数+Σ组内最大源权重；
     * 半衰锚=事件首报（成员最早 publish_time）；成员条目与事件行同值。
     * 空成员事件（成员全部摘除/迁出）热度归零、时间字段保留
     */
    private int recomputeEventHeat(Long eventId, List<NewsEventItemDO> members,
                                   List<NewsStoryCluster> clusters, Map<Long, NewsSourceDO> sourcesById, Date now) {
        if (members.isEmpty()) {
            eventMapper.update(null, Wrappers.lambdaUpdate(NewsEventDO.class)
                    .eq(NewsEventDO::getId, eventId)
                    .set(NewsEventDO::getHeat, 0));
            return 0;
        }
        Date firstReport = members.stream().map(NewsEventItemDO::getPublishTime)
                .filter(java.util.Objects::nonNull).min(Date::compareTo).orElse(null);
        Date lastActivity = members.stream().map(NewsEventItemDO::getPublishTime)
                .filter(java.util.Objects::nonNull).max(Date::compareTo).orElse(null);
        // 48h 证据窗内的投票组（组证据时刻=组内最早 publish_time）
        int eligibleGroups = 0;
        int weightSum = 0;
        Map<String, List<NewsEventItemDO>> byGroup = new HashMap<>();
        for (NewsEventItemDO member : members) {
            byGroup.computeIfAbsent(member.getIndependenceGroup(), k -> new ArrayList<>()).add(member);
        }
        for (Map.Entry<String, List<NewsEventItemDO>> entry : byGroup.entrySet()) {
            Date groupFirst = entry.getValue().stream().map(NewsEventItemDO::getPublishTime)
                    .filter(java.util.Objects::nonNull).min(Date::compareTo).orElse(null);
            boolean inWindow = firstReport != null && groupFirst != null
                    && groupFirst.getTime() <= firstReport.getTime() + NewsHeatService.EVIDENCE_WINDOW_HOURS * 3600L * 1000L;
            int weight = 0;
            for (NewsEventItemDO member : entry.getValue()) {
                NewsSourceDO source = sourcesById.get(member.getSourceId());
                weight = Math.max(weight, heatService.sourceWeight(source));
            }
            if (inWindow) {
                eligibleGroups++;
                weightSum += weight;
            }
        }
        int heat = NewsHeatService.decayedHeat(eligibleGroups + weightSum, firstReport, now);
        eventMapper.update(null, Wrappers.lambdaUpdate(NewsEventDO.class)
                .eq(NewsEventDO::getId, eventId)
                .set(NewsEventDO::getHeat, heat)
                .set(NewsEventDO::getFirstReportTime, firstReport)
                .set(NewsEventDO::getLastActivityTime, lastActivity));
        // 成员条目热度（同簇同值；窗口条目上持久化的当前值作跳写基线）
        Map<Long, Integer> storedHeatByItem = new HashMap<>();
        for (NewsStoryItem item : clusters.stream().flatMap(c -> c.members().stream()).toList()) {
            storedHeatByItem.put(item.id(), item.heat());
        }
        int writes = 0;
        for (NewsEventItemDO member : members) {
            Integer stored = storedHeatByItem.get(member.getItemId());
            if (stored != null && stored == heat) {
                continue;
            }
            itemMapper.update(null, Wrappers.lambdaUpdate(NewsItemDO.class)
                    .eq(NewsItemDO::getId, member.getItemId())
                    .set(NewsItemDO::getHeat, heat));
            writes++;
        }
        return writes;
    }
}
