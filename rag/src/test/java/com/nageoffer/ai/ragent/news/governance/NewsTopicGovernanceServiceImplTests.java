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

package com.nageoffer.ai.ragent.news.governance;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.news.controller.request.NewsTopicGovernanceApplyRequest;
import com.nageoffer.ai.ragent.news.controller.vo.NewsTopicGovernanceApplyResultVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsTopicGovernanceEventVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsTopicProposalVO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemTopicDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsTopicAliasDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsTopicDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsTopicGovernanceEventDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemTopicMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsTopicAliasMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsTopicGovernanceEventMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsTopicMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 三轨处置服务单测（#202）：mock Mapper 字段级断言——建议轨别三档、merge 关联
 * 迁移+别名入账、promote 阈值/slug/组校验、reject 摘除+别名、终态幂等与改判
 * 冲突拒绝、别名同键覆盖。真库行为（关联迁移 SQL/级联/计数连续性/再提拦截）
 * 归 {@code NewsTopicGovernancePgIt}。
 */
class NewsTopicGovernanceServiceImplTests {

    private NewsTopicMapper topicMapper;
    private NewsItemTopicMapper itemTopicMapper;
    private NewsTopicAliasMapper aliasMapper;
    private NewsTopicGovernanceEventMapper eventMapper;
    private NewsTopicGovernanceProperties properties;
    private NewsTopicGovernanceServiceImpl service;

    @BeforeEach
    void setUp() {
        // lambda wrapper 解析需要表元信息注册（沿 NewsEnrichServiceTests 先例）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, NewsTopicDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsItemTopicDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsTopicAliasDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsTopicGovernanceEventDO.class);
        topicMapper = mock(NewsTopicMapper.class);
        itemTopicMapper = mock(NewsItemTopicMapper.class);
        aliasMapper = mock(NewsTopicAliasMapper.class);
        eventMapper = mock(NewsTopicGovernanceEventMapper.class);
        properties = new NewsTopicGovernanceProperties();
        service = new NewsTopicGovernanceServiceImpl(topicMapper, itemTopicMapper,
                aliasMapper, eventMapper, properties);
    }

    private static NewsTopicDO proposal(long id, String slug, String nameZh, String nameEn) {
        return NewsTopicDO.builder().id(id).slug(slug).nameZh(nameZh).nameEn(nameEn)
                .topicGroup("PROPOSED").curated(false).status("active").build();
    }

    private static NewsTopicDO curated(long id, String slug) {
        return NewsTopicDO.builder().id(id).slug(slug).nameZh(slug).nameEn(slug)
                .topicGroup("STUDENT_AFFAIRS").curated(true).status("active").build();
    }

    private static NewsTopicGovernanceApplyRequest.NewsTopicDisposition disposition(
            long topicId, String action, Long mergeTarget, String promoteSlug, String promoteGroup, String reason) {
        NewsTopicGovernanceApplyRequest.NewsTopicDisposition d =
                new NewsTopicGovernanceApplyRequest.NewsTopicDisposition();
        d.setTopicId(topicId);
        d.setAction(action);
        d.setMergeTargetTopicId(mergeTarget);
        d.setPromoteSlug(promoteSlug);
        d.setPromoteGroup(promoteGroup);
        d.setReason(reason);
        return d;
    }

    // ==================== 列表面：建议轨别三档 ====================

    @Test
    void listPendingProposalsSuggestsTrackByRefs() {
        when(topicMapper.selectList(any())).thenReturn(List.of(
                proposal(21, "prop-aaa", "research", "research"),
                proposal(26, "prop-bbb", "alumni", "alumni"),
                proposal(27, "prop-ccc", "efficiency", "efficiency")));
        when(itemTopicMapper.selectCount(any())).thenReturn(19L, 2L, 0L);

        List<NewsTopicProposalVO> pending = service.listPendingProposals();

        assertEquals(3, pending.size());
        assertEquals(21L, pending.get(0).getId());
        assertEquals(19L, pending.get(0).getItemRefs());
        assertEquals("promote", pending.get(0).getSuggestedAction(), "19≥阈值 10 → 建议转正");
        assertTrue(pending.get(0).getSuggestedReason().contains("19"));
        assertEquals("review", pending.get(1).getSuggestedAction(), "2 未达阈值 → 人工近义判定/观察");
        assertTrue(pending.get(1).getSuggestedReason().contains("10"));
        assertEquals("reject", pending.get(2).getSuggestedAction(), "零引用 → 建议弃");
        assertTrue(pending.get(2).getSuggestedReason().contains("零引用"));
    }

    // ==================== merge 轨 ====================

    @Test
    void mergeMigratesLinksAndBooksBothNameAliases() {
        NewsTopicDO culture = proposal(28, "prop-culture", "文化", "Culture");
        NewsTopicDO campus = curated(15, "campus");
        when(topicMapper.selectById(28L)).thenReturn(culture);
        when(topicMapper.selectById(15L)).thenReturn(campus);
        when(itemTopicMapper.selectCount(any())).thenReturn(3L);
        when(itemTopicMapper.deleteLinksAlsoInTarget(28L, 15L)).thenReturn(1);
        when(itemTopicMapper.migrateLinksToTarget(28L, 15L)).thenReturn(2);
        when(topicMapper.update(any(), any())).thenReturn(1); // 条件更新生效一行（#206 行数校验）
        when(aliasMapper.selectByAliasKey(any())).thenReturn(null);

        NewsTopicGovernanceApplyResultVO result = service.applyBatch(
                List.of(disposition(28, "MERGE", 15L, null, null, "文化活动≈校园生活")), "it-admin");

        assertEquals(1, result.getAppliedCount());
        assertEquals(0, result.getSkippedCount());
        assertEquals("APPLIED", result.getResults().get(0).getOutcome());
        // 关联迁移顺序：先去重同挂两主题的源侧行，再整批改写 topic_id
        verify(itemTopicMapper).deleteLinksAlsoInTarget(28L, 15L);
        verify(itemTopicMapper).migrateLinksToTarget(28L, 15L);
        // 提案行软状态：status='merged'（curated 保持 false——set 列表不含 curated）
        verify(topicMapper).update(any(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class));
        // 双语名称各入一行别名：culture + 文化（规范化键）
        verify(aliasMapper, times(2)).insert(any(NewsTopicAliasDO.class));
        var aliasCaptor = org.mockito.ArgumentCaptor.forClass(NewsTopicAliasDO.class);
        verify(aliasMapper, times(2)).insert(aliasCaptor.capture());
        List<String> keys = aliasCaptor.getAllValues().stream().map(NewsTopicAliasDO::getAliasKey).toList();
        assertTrue(keys.contains("culture") && keys.contains("文化"), "双语名规范化入账，实际=" + keys);
        assertEquals("merged", aliasCaptor.getAllValues().get(0).getAction());
        assertEquals(15L, aliasCaptor.getAllValues().get(0).getTargetTopicId());
        assertEquals("it-admin", aliasCaptor.getAllValues().get(0).getOperator());
        // 留痕：merged 事件含迁移/去重计数
        var eventCaptor = org.mockito.ArgumentCaptor.forClass(NewsTopicGovernanceEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        NewsTopicGovernanceEventDO event = eventCaptor.getValue();
        assertEquals(NewsTopicGovernanceEventDO.ACTION_MERGED, event.getAction());
        assertEquals(28L, event.getTopicId());
        assertEquals(15L, event.getTargetTopicId());
        assertTrue(event.getDetail().contains("migrated=2"), event.getDetail());
        assertTrue(event.getDetail().contains("deduped=1"), event.getDetail());
        assertNotNull(event.getEventTime());
    }

    @Test
    void mergeRejectsSelfTargetAndNonCuratedTarget() {
        when(topicMapper.selectById(28L)).thenReturn(proposal(28, "prop-culture", "文化", "Culture"));
        assertThrows(ClientException.class,
                () -> service.applyBatch(List.of(disposition(28, "MERGE", 28L, null, null, "x")), "op"),
                "merge 目标不得为提案自身");
        NewsTopicDO otherProposal = proposal(99, "prop-xyz", "其他", null);
        when(topicMapper.selectById(99L)).thenReturn(otherProposal);
        assertThrows(ClientException.class,
                () -> service.applyBatch(List.of(disposition(28, "MERGE", 99L, null, null, "x")), "op"),
                "merge 目标须 curated=true");
        NewsTopicDO missing = curated(15, "campus");
        when(topicMapper.selectById(15L)).thenReturn(missing);
        assertThrows(ClientException.class,
                () -> service.applyBatch(List.of(disposition(28, "MERGE", null, null, null, "x")), "op"),
                "MERGE 缺 mergeTargetTopicId");
    }

    // ==================== promote 轨 ====================

    @Test
    void promoteEnforcesRefsThresholdAndAppliesStableSlug() {
        NewsTopicDO research = proposal(21, "prop-research", "research", "research");
        when(topicMapper.selectById(21L)).thenReturn(research);
        when(topicMapper.selectCount(any())).thenReturn(0L); // slug 无冲突
        when(itemTopicMapper.selectCount(any())).thenReturn(19L); // refs=19
        when(topicMapper.update(any(), any())).thenReturn(1); // 条件更新生效一行（#206 行数校验）

        NewsTopicGovernanceApplyResultVO result = service.applyBatch(List.of(disposition(
                21, "PROMOTE", null, "research", "RESEARCH", "RESEARCH 组无通用科研位；唯一过阈值行")), "it-admin");

        assertEquals(1, result.getAppliedCount());
        var captor = org.mockito.ArgumentCaptor
                .forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(topicMapper).update(any(), captor.capture());
        java.util.Map<String, Object> params = captor.getValue().getParamNameValuePairs();
        assertTrue(params.containsValue("research"), "稳定 slug 替换 prop- 哈希，实际=" + params);
        assertTrue(params.containsValue("RESEARCH"), "归入正式组");
        assertTrue(params.containsValue(Boolean.TRUE), "curated=true");
        var eventCaptor = org.mockito.ArgumentCaptor.forClass(NewsTopicGovernanceEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(NewsTopicGovernanceEventDO.ACTION_PROMOTED, eventCaptor.getValue().getAction());
        assertTrue(eventCaptor.getValue().getDetail().contains("prop-research -> research"),
                eventCaptor.getValue().getDetail());
        assertTrue(eventCaptor.getValue().getDetail().contains("threshold 10"), eventCaptor.getValue().getDetail());
    }

    @Test
    void promoteRejectsBelowThresholdBadSlugAndConflicts() {
        when(topicMapper.selectById(21L)).thenReturn(proposal(21, "prop-research", "research", "research"));
        when(itemTopicMapper.selectCount(any())).thenReturn(9L); // refs=9 < 10
        assertThrows(ClientException.class,
                () -> service.applyBatch(List.of(disposition(21, "PROMOTE", null, "research", "RESEARCH", "x")), "op"),
                "9<10 未达转正阈值");

        when(itemTopicMapper.selectCount(any())).thenReturn(19L);
        assertThrows(ClientException.class,
                () -> service.applyBatch(List.of(disposition(21, "PROMOTE", null, "prop-abc123", "RESEARCH", "x")), "op"),
                "prop- 保留前缀不得用作稳定 slug");
        assertThrows(ClientException.class,
                () -> service.applyBatch(List.of(disposition(21, "PROMOTE", null, "Bad_Slug", "RESEARCH", "x")), "op"),
                "slug 形态非法");
        assertThrows(ClientException.class,
                () -> service.applyBatch(List.of(disposition(21, "PROMOTE", null, "research", "PROPOSED", "x")), "op"),
                "组须为正式三维组");
        when(topicMapper.selectCount(any())).thenReturn(1L); // slug 冲突
        assertThrows(ClientException.class,
                () -> service.applyBatch(List.of(disposition(21, "PROMOTE", null, "campus", "RESEARCH", "x")), "op"),
                "slug 与现有主题冲突");
        verify(eventMapper, never()).insert(any(NewsTopicGovernanceEventDO.class));
    }

    // ==================== reject 轨 ====================

    @Test
    void rejectDetachesLinksAndBooksAliasesWithoutTarget() {
        NewsTopicDO polyu = proposal(34, "prop-polyu", "polyu", "polyu");
        when(topicMapper.selectById(34L)).thenReturn(polyu);
        when(itemTopicMapper.delete(any())).thenReturn(1);
        when(topicMapper.update(any(), any())).thenReturn(1); // 条件更新生效一行（#206 行数校验）
        when(aliasMapper.selectByAliasKey(any())).thenReturn(null);

        NewsTopicGovernanceApplyResultVO result = service.applyBatch(
                List.of(disposition(34, "REJECT", null, null, null, "全站皆 PolyU 零区分度")), "it-admin");

        assertEquals(1, result.getAppliedCount());
        verify(itemTopicMapper).delete(any()); // 残留关联摘除（票面授权+留痕）
        var aliasCaptor = org.mockito.ArgumentCaptor.forClass(NewsTopicAliasDO.class);
        verify(aliasMapper).insert(aliasCaptor.capture());
        assertEquals("rejected", aliasCaptor.getValue().getAction());
        assertEquals(null, aliasCaptor.getValue().getTargetTopicId(), "rejected 别名无目标");
        assertEquals("polyu", aliasCaptor.getValue().getAliasKey());
        var eventCaptor = org.mockito.ArgumentCaptor.forClass(NewsTopicGovernanceEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(NewsTopicGovernanceEventDO.ACTION_REJECTED, eventCaptor.getValue().getAction());
        assertTrue(eventCaptor.getValue().getDetail().contains("detachedLinks=1"), eventCaptor.getValue().getDetail());
    }

    // ==================== 幂等与改判冲突 ====================

    @Test
    void batchRerunSkipsTerminalStatesAndConflictingTrackThrows() {
        NewsTopicDO mergedAlready = proposal(28, "prop-culture", "文化", "Culture");
        mergedAlready.setStatus("merged");
        when(topicMapper.selectById(28L)).thenReturn(mergedAlready);
        // 既往并入目标=15（留痕流水回查）：与重跑请求一致 → 幂等跳过
        when(eventMapper.selectOne(any())).thenReturn(NewsTopicGovernanceEventDO.builder()
                .topicId(28L).action(NewsTopicGovernanceEventDO.ACTION_MERGED).targetTopicId(15L).build());

        NewsTopicGovernanceApplyResultVO rerun = service.applyBatch(
                List.of(disposition(28, "MERGE", 15L, null, null, "重跑")), "it-admin");
        assertEquals(0, rerun.getAppliedCount(), "同轨同目标重跑幂等跳过");
        assertEquals(1, rerun.getSkippedCount());
        assertEquals("SKIPPED", rerun.getResults().get(0).getOutcome());

        assertThrows(ClientException.class,
                () -> service.applyBatch(List.of(disposition(28, "REJECT", null, null, null, "改判")), "op"),
                "已 merged 再 reject=改判冲突，软状态不自动反转");
        assertThrows(ClientException.class,
                () -> service.applyBatch(List.of(disposition(28, "MERGE", 16L, null, null, "换目标")), "op"),
                "已并入 15 再并入 16=改判冲突（既往目标回查比对）");

        NewsTopicDO rejectedAlready = proposal(34, "prop-polyu", "polyu", "polyu");
        rejectedAlready.setStatus("rejected");
        when(topicMapper.selectById(34L)).thenReturn(rejectedAlready);
        NewsTopicGovernanceApplyResultVO rejectRerun = service.applyBatch(
                List.of(disposition(34, "REJECT", null, null, null, "重跑")), "op");
        assertEquals(1, rejectRerun.getSkippedCount(), "同轨 rejected 重跑幂等跳过");

        assertThrows(ClientException.class, () -> service.applyBatch(List.of(), "op"), "空指令拒绝");
        assertThrows(ClientException.class,
                () -> service.applyBatch(List.of(disposition(34, "DROP", null, null, null, "x")), "op"),
                "未知轨别拒绝");
    }

    @Test
    void aliasBookingOverwritesSameKeyOnRejudgement() {
        NewsTopicDO polyu = proposal(34, "prop-polyu", "polyu", "polyu");
        when(topicMapper.selectById(34L)).thenReturn(polyu);
        when(itemTopicMapper.delete(any())).thenReturn(0);
        when(topicMapper.update(any(), any())).thenReturn(1); // 条件更新生效一行（#206 行数校验）
        NewsTopicAliasDO existing = NewsTopicAliasDO.builder().id(7L).aliasKey("polyu")
                .action("rejected").sourceTopicId(34L).build();
        when(aliasMapper.selectByAliasKey(any())).thenReturn(existing);

        service.applyBatch(List.of(disposition(34, "REJECT", null, null, null, "再次弃，覆盖入账")), "it-admin");

        verify(aliasMapper, never()).insert(any(NewsTopicAliasDO.class));
        var captor = org.mockito.ArgumentCaptor
                .forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(aliasMapper).update(any(), captor.capture());
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("再次弃，覆盖入账"),
                "同键按最新裁决覆盖更新（action/target/reason 刷新），实际="
                        + captor.getValue().getParamNameValuePairs());
    }

    // ==================== 归一化键合同 ====================

    @Test
    void aliasKeyNormalizationMatchesEnrichConsumptionSide() {
        NewsTopicDO proposal = proposal(1, "prop-x", " Culture  Life ", null);
        assertEquals(java.util.Set.of("culturelife"), NewsTopicGovernanceServiceImpl.aliasKeySet(proposal),
                "小写+去全部空白（与 NewsEnrichService 消费口径一致）");
        assertEquals("culture", NewsTopicAliasDO.normalizeKey(" Culture "));
        assertEquals(null, NewsTopicAliasDO.normalizeKey("  "));
        assertEquals(null, NewsTopicAliasDO.normalizeKey(null));
    }

    // ==================== 收口票 #206：promote 防线与留痕面 ====================

    @Test
    void promoteOnTerminalRowIsConflictAndNeverRecordsEvent() {
        NewsTopicDO mergedAlready = proposal(28, "prop-culture", "文化", "Culture");
        mergedAlready.setStatus("merged");
        when(topicMapper.selectById(28L)).thenReturn(mergedAlready);
        when(itemTopicMapper.selectCount(any())).thenReturn(19L); // 人工恢复关联使 refs 过线

        assertThrows(ClientException.class, () -> service.applyBatch(List.of(disposition(
                28, "PROMOTE", null, "culture", "FACULTY", "x")), "op"),
                "已 merged 再 promote=改判冲突拒绝（不得假留痕）");
        verify(topicMapper, never()).update(any(), any());
        verify(eventMapper, never()).insert(any(NewsTopicGovernanceEventDO.class));
    }

    @Test
    void promoteRerunSkipsBeforeThresholdWhenRefsFallen() {
        NewsTopicDO promotedAlready = proposal(21, "research", "research", "research");
        promotedAlready.setCurated(true);
        when(topicMapper.selectById(21L)).thenReturn(promotedAlready);
        when(itemTopicMapper.selectCount(any())).thenReturn(0L); // 转正后引用被保留期清理摘空

        NewsTopicGovernanceApplyResultVO rerun = service.applyBatch(List.of(disposition(
                21, "PROMOTE", null, "research", "RESEARCH", "重跑")), "op");

        assertEquals(0, rerun.getAppliedCount(), "幂等先于阈值：引用回落不阻断同 slug 重放");
        assertEquals(1, rerun.getSkippedCount());
        assertEquals("SKIPPED", rerun.getResults().get(0).getOutcome());
    }

    @Test
    void conditionalUpdateAffectingZeroRowsRefusesInsteadOfFalseAudit() {
        when(topicMapper.selectById(34L)).thenReturn(proposal(34, "prop-polyu", "polyu", "polyu"));
        when(itemTopicMapper.delete(any())).thenReturn(1);
        when(aliasMapper.selectByAliasKey(any())).thenReturn(null);
        when(topicMapper.update(any(), any())).thenReturn(0); // 读后状态漂移

        assertThrows(ClientException.class, () -> service.applyBatch(List.of(disposition(
                34, "REJECT", null, null, null, "x")), "op"),
                "0 行生效=状态漂移，拒绝防假留痕");
        verify(eventMapper, never()).insert(any(NewsTopicGovernanceEventDO.class));
    }

    @Test
    void listGovernanceEventsBackfillsSlugAndClampsLimit() {
        when(eventMapper.selectList(any())).thenReturn(List.of(
                NewsTopicGovernanceEventDO.builder().id(9L).topicId(1L)
                        .action(NewsTopicGovernanceEventDO.ACTION_MERGED).targetTopicId(2L)
                        .detail("d1").operator("op").build(),
                NewsTopicGovernanceEventDO.builder().id(8L).topicId(99L)
                        .action(NewsTopicGovernanceEventDO.ACTION_REJECTED)
                        .detail("d2").operator("op").build()));
        when(topicMapper.selectBatchIds(any())).thenReturn(List.of(
                curated(1, "ai"),
                NewsTopicDO.builder().id(2L).nameZh("无slug").build()));

        List<NewsTopicGovernanceEventVO> events = service.listGovernanceEvents(500);
        service.listGovernanceEvents(0);

        assertEquals(2, events.size());
        assertEquals("ai", events.get(0).getTopicSlug());
        assertEquals("2", events.get(0).getTargetTopicSlug(), "目标行 slug 缺失回退 id 字串");
        assertEquals("deleted#99", events.get(1).getTopicSlug(), "主题行已删回退 deleted#id");
        assertEquals(null, events.get(1).getTargetTopicSlug());
        var captor = org.mockito.ArgumentCaptor
                .forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(eventMapper, times(2)).selectList(captor.capture());
        assertTrue(captor.getAllValues().get(0).getSqlSegment().contains("LIMIT 200"),
                "limit=500 限幅至 200，实际=" + captor.getAllValues().get(0).getSqlSegment());
        assertTrue(captor.getAllValues().get(1).getSqlSegment().contains("LIMIT 1"),
                "limit=0 下限托底至 1，实际=" + captor.getAllValues().get(1).getSqlSegment());
    }
}
