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

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 主题提案治理服务实现（#202）
 *
 * <p>三轨语义见接口 javadoc。留痕双层：t_news_topic_governance_event
 * append-only 流水（本类落行）+ /admin/** ADMIN_AUDIT 自动审计拦截器双保险。
 *
 * <p>别名账同键覆盖：同一 alias_key 再次入账时按最新裁决 UPDATE（action/target/
 * source/reason 刷新）——人工改判（维护者 SQL 反转终态后重处置）后账面随之收敛。
 */
@Slf4j
@Service
public class NewsTopicGovernanceServiceImpl implements NewsTopicGovernanceService {

    /**
     * 处置轨别（请求 action 值，大写）
     */
    static final String TRACK_MERGE = "MERGE";
    static final String TRACK_PROMOTE = "PROMOTE";
    static final String TRACK_REJECT = "REJECT";

    /**
     * 结果轨别
     */
    static final String OUTCOME_APPLIED = "APPLIED";
    static final String OUTCOME_SKIPPED = "SKIPPED";

    /**
     * 建议轨别（列表面）
     */
    static final String SUGGEST_PROMOTE = "promote";
    static final String SUGGEST_REJECT = "reject";
    static final String SUGGEST_REVIEW = "review";

    /**
     * 稳定 slug 形态：小写字母/数字/连字符，不以连字符收尾（首尾字母数字），
     * 长度 ≤64（DDL 列宽）；prop- 前缀保留给提案哈希不得用作稳定 slug
     */
    static final Pattern STABLE_SLUG_PATTERN = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?");

    private static final String PROPOSAL_SLUG_PREFIX = "prop-";

    private final NewsTopicMapper topicMapper;
    private final NewsItemTopicMapper itemTopicMapper;
    private final NewsTopicAliasMapper aliasMapper;
    private final NewsTopicGovernanceEventMapper eventMapper;
    private final NewsTopicGovernanceProperties properties;
    private final Supplier<Date> nowSupplier;

    @Autowired
    public NewsTopicGovernanceServiceImpl(NewsTopicMapper topicMapper,
                                          NewsItemTopicMapper itemTopicMapper,
                                          NewsTopicAliasMapper aliasMapper,
                                          NewsTopicGovernanceEventMapper eventMapper,
                                          NewsTopicGovernanceProperties properties) {
        this(topicMapper, itemTopicMapper, aliasMapper, eventMapper, properties, Date::new);
    }

    /**
     * 全参构造器（测试注入时钟——留痕 event_time 与建议阈值口径的时间旁证）
     */
    NewsTopicGovernanceServiceImpl(NewsTopicMapper topicMapper,
                                   NewsItemTopicMapper itemTopicMapper,
                                   NewsTopicAliasMapper aliasMapper,
                                   NewsTopicGovernanceEventMapper eventMapper,
                                   NewsTopicGovernanceProperties properties,
                                   Supplier<Date> nowSupplier) {
        this.topicMapper = topicMapper;
        this.itemTopicMapper = itemTopicMapper;
        this.aliasMapper = aliasMapper;
        this.eventMapper = eventMapper;
        this.properties = properties;
        this.nowSupplier = nowSupplier;
    }

    // ==================== 列表面 ====================

    @Override
    public List<NewsTopicProposalVO> listPendingProposals() {
        List<NewsTopicDO> pending = topicMapper.selectList(Wrappers.lambdaQuery(NewsTopicDO.class)
                .eq(NewsTopicDO::getCurated, false)
                .eq(NewsTopicDO::getStatus, NewsTopicDO.STATUS_ACTIVE)
                .orderByAsc(NewsTopicDO::getId));
        if (pending.isEmpty()) {
            return List.of();
        }
        int threshold = effectivePromoteThreshold();
        List<NewsTopicProposalVO> result = new ArrayList<>(pending.size());
        for (NewsTopicDO proposal : pending) {
            long refs = countLinks(proposal.getId());
            Suggestion suggestion = suggestTrack(refs, threshold);
            result.add(NewsTopicProposalVO.builder()
                    .id(proposal.getId())
                    .slug(proposal.getSlug())
                    .nameZh(proposal.getNameZh())
                    .nameEn(proposal.getNameEn())
                    .topicGroup(proposal.getTopicGroup())
                    .itemRefs(refs)
                    .suggestedAction(suggestion.suggested())
                    .suggestedReason(suggestion.reason().apply(threshold))
                    .createTime(proposal.getCreateTime())
                    .build());
        }
        return result;
    }

    @Override
    public List<NewsTopicGovernanceEventVO> listGovernanceEvents(int limit) {
        int bounded = Math.max(1, Math.min(limit, 200));
        List<NewsTopicGovernanceEventDO> events = eventMapper.selectList(
                Wrappers.lambdaQuery(NewsTopicGovernanceEventDO.class)
                        .orderByDesc(NewsTopicGovernanceEventDO::getId)
                        .last("LIMIT " + bounded));
        if (events.isEmpty()) {
            return List.of();
        }
        Set<Long> topicIds = new LinkedHashSet<>();
        for (NewsTopicGovernanceEventDO event : events) {
            topicIds.add(event.getTopicId());
            if (event.getTargetTopicId() != null) {
                topicIds.add(event.getTargetTopicId());
            }
        }
        Map<Long, String> slugById = topicMapper.selectBatchIds(topicIds).stream()
                .collect(Collectors.toMap(NewsTopicDO::getId,
                        topic -> topic.getSlug() != null ? topic.getSlug() : String.valueOf(topic.getId()),
                        (a, b) -> a));
        Function<Long, String> slugOf = id -> slugById.getOrDefault(id, "deleted#" + id);
        return events.stream()
                .map(event -> NewsTopicGovernanceEventVO.builder()
                        .id(event.getId())
                        .topicId(event.getTopicId())
                        .topicSlug(slugOf.apply(event.getTopicId()))
                        .action(event.getAction())
                        .targetTopicId(event.getTargetTopicId())
                        .targetTopicSlug(event.getTargetTopicId() == null ? null : slugOf.apply(event.getTargetTopicId()))
                        .detail(event.getDetail())
                        .operator(event.getOperator())
                        .eventTime(event.getEventTime())
                        .build())
                .toList();
    }

    // ==================== 批量应用 ====================

    /**
     * 全批原子：任一指令非法（缺参/阈值未达/slug 冲突/改判冲突/条件更新未生效）抛
     * ClientException 整批回滚（已处置行不残留半程态）；已处目标终态的行 SKIPPED
     * 不报错（幂等重跑）；三轨条件更新均校验影响行数，0 行=状态漂移拒绝防假留痕（#206）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public NewsTopicGovernanceApplyResultVO applyBatch(
            List<NewsTopicGovernanceApplyRequest.NewsTopicDisposition> dispositions, String operator) {
        if (dispositions == null || dispositions.isEmpty()) {
            throw new ClientException("处置指令为空");
        }
        List<NewsTopicGovernanceApplyResultVO.DispositionOutcome> outcomes = new ArrayList<>(dispositions.size());
        int applied = 0;
        int skipped = 0;
        for (NewsTopicGovernanceApplyRequest.NewsTopicDisposition disposition : dispositions) {
            NewsTopicGovernanceApplyResultVO.DispositionOutcome outcome = applyOne(disposition, operator);
            outcomes.add(outcome);
            if (OUTCOME_APPLIED.equals(outcome.getOutcome())) {
                applied++;
            } else {
                skipped++;
            }
        }
        log.info("[news][topic-governance] 批量处置完成：applied={}，skipped={}，operator={}", applied, skipped, operator);
        return NewsTopicGovernanceApplyResultVO.builder()
                .appliedCount(applied)
                .skippedCount(skipped)
                .results(outcomes)
                .build();
    }

    private NewsTopicGovernanceApplyResultVO.DispositionOutcome applyOne(
            NewsTopicGovernanceApplyRequest.NewsTopicDisposition disposition, String operator) {
        if (disposition == null || disposition.getTopicId() == null) {
            throw new ClientException("处置指令缺 topicId");
        }
        String action = disposition.getAction() == null ? "" : disposition.getAction().strip().toUpperCase(Locale.ROOT);
        NewsTopicDO proposal = topicMapper.selectById(disposition.getTopicId());
        if (proposal == null) {
            throw new ClientException("提案行不存在：" + disposition.getTopicId());
        }
        return switch (action) {
            case TRACK_MERGE -> merge(proposal, disposition, operator);
            case TRACK_PROMOTE -> promote(proposal, disposition, operator);
            case TRACK_REJECT -> reject(proposal, disposition, operator);
            default -> throw new ClientException("未知处置轨别：" + disposition.getAction()
                    + "（合法值 MERGE / PROMOTE / REJECT）");
        };
    }

    // ==================== 三轨实现 ====================

    /**
     * merge 并入：关联迁移（先去重同挂两主题的源侧行防复合主键冲突，再整批改写
     * topic_id——行不删，item 与目标主题的关联覆盖不丢）→ 提案行 status='merged'
     * （curated 保持 false）→ 名称入别名账 → 留痕
     */
    private NewsTopicGovernanceApplyResultVO.DispositionOutcome merge(
            NewsTopicDO proposal, NewsTopicGovernanceApplyRequest.NewsTopicDisposition disposition, String operator) {
        if (disposition.getMergeTargetTopicId() == null) {
            throw new ClientException("MERGE 轨缺 mergeTargetTopicId");
        }
        if (disposition.getMergeTargetTopicId().equals(proposal.getId())) {
            throw new ClientException("merge 目标不得为提案自身");
        }
        // 幂等先行：已 merged 的重跑比对既往并入目标（留痕流水回查）——同目标跳过、
        // 异目标=改判冲突；不先做目标行校验（重跑不依赖目标行仍在词表态）
        String skipDetail = skipIfTerminal(proposal, TRACK_MERGE, disposition.getMergeTargetTopicId());
        if (skipDetail != null) {
            return outcome(proposal.getId(), TRACK_MERGE, OUTCOME_SKIPPED, skipDetail);
        }
        NewsTopicDO target = requireCuratedTarget(disposition.getMergeTargetTopicId());
        long linksBefore = countLinks(proposal.getId());
        int deduped = itemTopicMapper.deleteLinksAlsoInTarget(proposal.getId(), target.getId());
        int migrated = itemTopicMapper.migrateLinksToTarget(proposal.getId(), target.getId());
        requireSingleRowUpdate(topicMapper.update(null, Wrappers.lambdaUpdate(NewsTopicDO.class)
                        .eq(NewsTopicDO::getId, proposal.getId())
                        .eq(NewsTopicDO::getStatus, NewsTopicDO.STATUS_ACTIVE)
                        .set(NewsTopicDO::getStatus, NewsTopicDO.STATUS_MERGED)),
                TRACK_MERGE, proposal);
        bookAliases(proposal, NewsTopicAliasDO.ACTION_MERGED, target.getId(), disposition.getReason(), operator);
        String detail = truncate("merged into " + target.getSlug() + "(id=" + target.getId() + "): linksBefore="
                + linksBefore + ", migrated=" + migrated + ", deduped=" + deduped
                + "; aliases=" + aliasKeys(proposal) + "; reason=" + safe(disposition.getReason()));
        recordEvent(proposal.getId(), NewsTopicGovernanceEventDO.ACTION_MERGED, target.getId(), detail, operator);
        return outcome(proposal.getId(), TRACK_MERGE, OUTCOME_APPLIED,
                "已并入 " + target.getSlug() + "（迁移 " + migrated + " 关联，去重 " + deduped + "）");
    }

    /**
     * promote 转正：终态防线先行（merged/rejected=改判冲突拒绝——防人工恢复关联使
     * refs 过线的形态下条件更新 0 行生效仍记事件的假留痕）→ 幂等先行（已转正同
     * slug SKIPPED，先于阈值——转正后引用被保留期清理回落不阻断重放）→ 引用阈值
     * 机器校验（refs ≥ promote-threshold）→ 稳定 slug 校验（形态+全库唯一+不得
     * prop- 前缀）→ curated=true + 正式组 + slug 替换（关联按 topic_id 引用不伤）
     * + 可选名称/描述策展覆盖 → 留痕（旧→新 slug）
     */
    private NewsTopicGovernanceApplyResultVO.DispositionOutcome promote(
            NewsTopicDO proposal, NewsTopicGovernanceApplyRequest.NewsTopicDisposition disposition, String operator) {
        String slug = disposition.getPromoteSlug();
        if (slug == null || slug.isBlank()) {
            throw new ClientException("PROMOTE 轨缺 promoteSlug");
        }
        slug = slug.strip().toLowerCase(Locale.ROOT);
        if (slug.startsWith(PROPOSAL_SLUG_PREFIX)) {
            throw new ClientException("稳定 slug 不得使用保留前缀 prop-（提案哈希形态）");
        }
        if (!STABLE_SLUG_PATTERN.matcher(slug).matches()) {
            throw new ClientException("稳定 slug 形态非法（小写字母/数字/连字符，不以连字符收尾）：" + slug);
        }
        String group = disposition.getPromoteGroup();
        if (group == null || !NewsTopicDO.FORMAL_GROUPS.contains(group.strip().toUpperCase(Locale.ROOT))) {
            throw new ClientException("PROMOTE 轨 promoteGroup 须为 FACULTY / RESEARCH / STUDENT_AFFAIRS");
        }
        group = group.strip().toUpperCase(Locale.ROOT);
        // 终态防线（对齐 merge/reject 轨，#206）：merged/rejected 软状态一律改判冲突
        // 拒绝，不得落入阈值/更新路径（refs 过线+条件更新 eq(status,'active') 0 行生效
        // 仍记事件返 APPLIED——假留痕）
        String terminalSkip = skipIfTerminal(proposal, TRACK_PROMOTE, null);
        if (terminalSkip != null) {
            return outcome(proposal.getId(), TRACK_PROMOTE, OUTCOME_SKIPPED, terminalSkip);
        }
        // 幂等先行（先于 slug 全库比对与阈值校验，#206）：已转正（curated=true 且
        // active——skipIfTerminal 已保证）同 slug → SKIPPED；slug 不一致=改判冲突拒绝
        if (Boolean.TRUE.equals(proposal.getCurated())) {
            if (slug.equals(proposal.getSlug())) {
                return outcome(proposal.getId(), TRACK_PROMOTE, OUTCOME_SKIPPED, "已转正（slug=" + slug + "），幂等跳过");
            }
            throw new ClientException("提案已转正（slug=" + proposal.getSlug() + "），slug 变更属改判，归维护者 SQL");
        }
        Long conflicts = topicMapper.selectCount(Wrappers.lambdaQuery(NewsTopicDO.class)
                .eq(NewsTopicDO::getSlug, slug)
                .ne(NewsTopicDO::getId, proposal.getId()));
        if (conflicts != null && conflicts > 0) {
            throw new ClientException("稳定 slug 与现有主题冲突：" + slug);
        }
        long refs = countLinks(proposal.getId());
        int threshold = effectivePromoteThreshold();
        if (refs < threshold) {
            throw new ClientException("引用数 " + refs + " 未达转正阈值 " + threshold + "（规则合同：无近义目标 AND item_refs≥阈值）");
        }
        String oldSlug = proposal.getSlug();
        // 可选策展覆盖四列：先空安全归一（条件 set 的实参是急切求值，须先判空）
        String nameZh = stripOrNull(disposition.getPromoteNameZh());
        String nameEn = stripOrNull(disposition.getPromoteNameEn());
        String descriptionZh = stripOrNull(disposition.getPromoteDescriptionZh());
        String descriptionEn = stripOrNull(disposition.getPromoteDescriptionEn());
        requireSingleRowUpdate(topicMapper.update(null, Wrappers.lambdaUpdate(NewsTopicDO.class)
                        .eq(NewsTopicDO::getId, proposal.getId())
                        .eq(NewsTopicDO::getStatus, NewsTopicDO.STATUS_ACTIVE)
                        .set(NewsTopicDO::getSlug, slug)
                        .set(NewsTopicDO::getTopicGroup, group)
                        .set(NewsTopicDO::getCurated, true)
                        .set(nameZh != null, NewsTopicDO::getNameZh, nameZh)
                        .set(nameEn != null, NewsTopicDO::getNameEn, nameEn)
                        .set(descriptionZh != null, NewsTopicDO::getDescriptionZh, descriptionZh)
                        .set(descriptionEn != null, NewsTopicDO::getDescriptionEn, descriptionEn)),
                TRACK_PROMOTE, proposal);
        String detail = truncate("promoted: refs=" + refs + " >= threshold " + threshold
                + "; slug " + oldSlug + " -> " + slug + "; group " + proposal.getTopicGroup() + " -> " + group
                + "; reason=" + safe(disposition.getReason()));
        recordEvent(proposal.getId(), NewsTopicGovernanceEventDO.ACTION_PROMOTED, null, detail, operator);
        return outcome(proposal.getId(), TRACK_PROMOTE, OUTCOME_APPLIED,
                "已转正（slug " + oldSlug + " → " + slug + "，组 " + group + "，引用 " + refs + "）");
    }

    /**
     * reject 弃：提案行 status='rejected'（curated 保持 false）→ 残留关联摘除
     * （票面明文授权的治理摘除，计数留痕）→ 名称入别名账 → 留痕
     */
    private NewsTopicGovernanceApplyResultVO.DispositionOutcome reject(
            NewsTopicDO proposal, NewsTopicGovernanceApplyRequest.NewsTopicDisposition disposition, String operator) {
        String skipDetail = skipIfTerminal(proposal, TRACK_REJECT, null);
        if (skipDetail != null) {
            return outcome(proposal.getId(), TRACK_REJECT, OUTCOME_SKIPPED, skipDetail);
        }
        long detached = itemTopicMapper.delete(Wrappers.lambdaQuery(NewsItemTopicDO.class)
                .eq(NewsItemTopicDO::getTopicId, proposal.getId()));
        requireSingleRowUpdate(topicMapper.update(null, Wrappers.lambdaUpdate(NewsTopicDO.class)
                        .eq(NewsTopicDO::getId, proposal.getId())
                        .eq(NewsTopicDO::getStatus, NewsTopicDO.STATUS_ACTIVE)
                        .set(NewsTopicDO::getStatus, NewsTopicDO.STATUS_REJECTED)),
                TRACK_REJECT, proposal);
        bookAliases(proposal, NewsTopicAliasDO.ACTION_REJECTED, null, disposition.getReason(), operator);
        String detail = truncate("rejected: detachedLinks=" + detached
                + "; aliases=" + aliasKeys(proposal) + "; reason=" + safe(disposition.getReason()));
        recordEvent(proposal.getId(), NewsTopicGovernanceEventDO.ACTION_REJECTED, null, detail, operator);
        return outcome(proposal.getId(), TRACK_REJECT, OUTCOME_APPLIED, "已弃（摘除 " + detached + " 关联）");
    }

    // ==================== 内部工具 ====================

    /**
     * 终态幂等/改判冲突判定：返回 null=仍 active 可处置；
     * 返回文案=SKIPPED（与请求同轨同参的终态——merge 轨须与既往并入目标一致，
     * 从留痕流水回查最近一次 merged 事件的目标）；改判冲突（异轨/异目标终态）
     * 抛 ClientException（软状态不自动反转，改判归维护者 SQL）
     */
    private String skipIfTerminal(NewsTopicDO proposal, String requestedTrack, Long requestedTargetId) {
        String status = proposal.getStatus();
        if (NewsTopicDO.STATUS_ACTIVE.equals(status)) {
            return null;
        }
        if (NewsTopicDO.STATUS_MERGED.equals(status)) {
            if (TRACK_MERGE.equals(requestedTrack)) {
                Long previousTarget = latestMergedTarget(proposal.getId());
                if (previousTarget != null && previousTarget.equals(requestedTargetId)) {
                    return "已并入目标 id=" + previousTarget + "（软状态保留），幂等跳过";
                }
                throw new ClientException("提案已并入目标 id=" + previousTarget
                        + "，与本请求目标 id=" + requestedTargetId + " 不一致（改判归维护者 SQL）：" + proposal.getSlug());
            }
            throw new ClientException("提案已 merged（软状态不自动反转；改判归维护者 SQL）：" + proposal.getSlug());
        }
        if (NewsTopicDO.STATUS_REJECTED.equals(status)) {
            if (TRACK_REJECT.equals(requestedTrack)) {
                return "已 rejected（软状态保留），幂等跳过";
            }
            throw new ClientException("提案已 rejected（软状态不自动反转；改判归维护者 SQL）：" + proposal.getSlug());
        }
        throw new ClientException("提案状态异常：" + proposal.getSlug() + " status=" + status);
    }

    /**
     * 既往并入目标回查：该提案最近一次 merged 留痕事件的目标（幂等重跑比对用）
     */
    private Long latestMergedTarget(Long topicId) {
        NewsTopicGovernanceEventDO latest = eventMapper.selectOne(
                Wrappers.lambdaQuery(NewsTopicGovernanceEventDO.class)
                        .eq(NewsTopicGovernanceEventDO::getTopicId, topicId)
                        .eq(NewsTopicGovernanceEventDO::getAction, NewsTopicGovernanceEventDO.ACTION_MERGED)
                        .orderByDesc(NewsTopicGovernanceEventDO::getId)
                        .last("LIMIT 1"));
        return latest == null ? null : latest.getTargetTopicId();
    }

    /**
     * 条件状态更新须恰好生效一行（#206）：0 行=提案状态在本批读取之后已漂移（并发
     * 处置或人工改态）——拒绝并随事务整批回滚，杜绝「0 行生效仍记事件返 APPLIED」
     * 的假留痕
     */
    private static void requireSingleRowUpdate(int updatedRows, String track, NewsTopicDO proposal) {
        if (updatedRows != 1) {
            throw new ClientException("提案状态已非 active（并发处置或人工改态），" + track
                    + " 拒绝以防假留痕：" + proposal.getSlug());
        }
    }

    private NewsTopicDO requireCuratedTarget(Long targetId) {
        NewsTopicDO target = topicMapper.selectById(targetId);
        if (target == null) {
            throw new ClientException("merge 目标主题不存在：" + targetId);
        }
        if (!Boolean.TRUE.equals(target.getCurated()) || !NewsTopicDO.STATUS_ACTIVE.equals(target.getStatus())) {
            throw new ClientException("merge 目标须为 curated=true 且 active 的主题：" + target.getSlug());
        }
        return target;
    }

    /**
     * 别名入账：提案 name_zh/name_en 规范化去重后逐键 upsert——
     * 同键（含既往 rejected/merged 遗留）按最新裁决覆盖更新
     */
    private void bookAliases(NewsTopicDO proposal, String action, Long targetTopicId, String reason, String operator) {
        for (String key : aliasKeySet(proposal)) {
            NewsTopicAliasDO existing = aliasMapper.selectByAliasKey(key);
            if (existing == null) {
                aliasMapper.insert(NewsTopicAliasDO.builder()
                        .aliasKey(key)
                        .aliasDisplay(aliasDisplayOf(proposal, key))
                        .action(action)
                        .sourceTopicId(proposal.getId())
                        .targetTopicId(targetTopicId)
                        .operator(operator)
                        .reason(safe(reason))
                        .build());
            } else {
                aliasMapper.update(null, Wrappers.lambdaUpdate(NewsTopicAliasDO.class)
                        .eq(NewsTopicAliasDO::getId, existing.getId())
                        .set(NewsTopicAliasDO::getAliasDisplay, aliasDisplayOf(proposal, key))
                        .set(NewsTopicAliasDO::getAction, action)
                        .set(NewsTopicAliasDO::getSourceTopicId, proposal.getId())
                        .set(NewsTopicAliasDO::getTargetTopicId, targetTopicId)
                        .set(NewsTopicAliasDO::getOperator, operator)
                        .set(NewsTopicAliasDO::getReason, safe(reason)));
            }
        }
    }

    /**
     * 提案双语名称的规范化键集合（LinkedHashSet 去重保序——英文新词 name_zh 与
     * name_en 同串时只入一行）
     */
    static Set<String> aliasKeySet(NewsTopicDO proposal) {
        Set<String> keys = new LinkedHashSet<>();
        for (String name : List.of(nullSafe(proposal.getNameZh()), nullSafe(proposal.getNameEn()))) {
            String key = NewsTopicAliasDO.normalizeKey(name);
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    /** 入账键集合的可读形态（留痕 detail 用） */
    private static List<String> aliasKeys(NewsTopicDO proposal) {
        return List.copyOf(aliasKeySet(proposal));
    }

    /** 键对应的原始展示形态（优先完整原名） */
    private static String aliasDisplayOf(NewsTopicDO proposal, String key) {
        if (NewsTopicAliasDO.normalizeKey(proposal.getNameZh()).equals(key)) {
            return proposal.getNameZh();
        }
        if (NewsTopicAliasDO.normalizeKey(proposal.getNameEn()) != null
                && NewsTopicAliasDO.normalizeKey(proposal.getNameEn()).equals(key)) {
            return proposal.getNameEn();
        }
        return key;
    }

    private void recordEvent(Long topicId, String action, Long targetTopicId, String detail, String operator) {
        eventMapper.insert(NewsTopicGovernanceEventDO.builder()
                .topicId(topicId)
                .action(action)
                .targetTopicId(targetTopicId)
                .detail(detail)
                .operator(operator)
                .eventTime(nowSupplier.get())
                .build());
    }

    private long countLinks(Long topicId) {
        Long count = itemTopicMapper.selectCount(Wrappers.lambdaQuery(NewsItemTopicDO.class)
                .eq(NewsItemTopicDO::getTopicId, topicId));
        return count == null ? 0L : count;
    }

    private int effectivePromoteThreshold() {
        return properties.getPromoteThreshold() > 0 ? properties.getPromoteThreshold() : 10;
    }

    /**
     * 规则建议轨别：promote（refs ≥ 阈值）/ reject（零引用）/ review
     * （有引用未达阈值——人工近义判定或 pending 观察）。机器侧只看引用数，
     * 近义判定与 PolyU 检索价值归人工
     */
    static Suggestion suggestTrack(long refs, int threshold) {
        if (refs >= threshold) {
            return new Suggestion(SUGGEST_PROMOTE,
                    t -> "引用数 " + refs + " ≥ 转正阈值 " + t + "——无近义 curated 目标时建议转正");
        }
        if (refs == 0) {
            return new Suggestion(SUGGEST_REJECT,
                    t -> "零引用——泛化无检索价值嫌疑，建议弃（近义目标明确可人工改 MERGE）");
        }
        return new Suggestion(SUGGEST_REVIEW,
                t -> "引用数 " + refs + " 未达转正阈值 " + t + "——人工近义判定（MERGE）或 pending 观察");
    }

    /**
     * 建议轨别+理由模板（理由携带阈值口径供人工复核）
     */
    record Suggestion(String suggested, Function<Integer, String> reason) {
    }

    private static NewsTopicGovernanceApplyResultVO.DispositionOutcome outcome(
            Long topicId, String action, String outcome, String detail) {
        return NewsTopicGovernanceApplyResultVO.DispositionOutcome.builder()
                .topicId(topicId)
                .action(action)
                .outcome(outcome)
                .detail(detail)
                .build();
    }

    private static String truncate(String text) {
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    /** 可选覆盖列的空安全归一：null/空白返回 null（条件 set 跳过该列，保留提案现值） */
    private static String stripOrNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
