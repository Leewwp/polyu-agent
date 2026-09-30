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

package com.nageoffer.ai.ragent.calendar.sync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.nageoffer.ai.ragent.calendar.KeyDateUid;
import com.nageoffer.ai.ragent.calendar.dao.entity.KeyDateDO;
import com.nageoffer.ai.ragent.calendar.dao.entity.KeyDateSourceDO;
import com.nageoffer.ai.ragent.calendar.dao.mapper.KeyDateMapper;
import com.nageoffer.ai.ragent.calendar.dao.mapper.KeyDateSourceMapper;
import com.nageoffer.ai.ragent.calendar.i18n.KeyDateTitles;
import com.nageoffer.ai.ragent.calendar.model.Disposition;
import com.nageoffer.ai.ragent.calendar.model.KeyDateCandidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 校历源级同步服务（合同§4/§5/§6 的落库实现；Store 语义的真库版）。
 *
 * <p><b>完整候选 → 单事务原子发布</b>（applyComplete）：upsert 全部事件
 * （新=insert rev1；withdrawn→published=恢复 rev+1；dates(precision,start,end)
 * 变化=改期 rev+1；展示字段随页面刷新但不触发 revision）+ <b>限定覆盖域撤回</b>
 * （门 5：仅本源、published、academic_year==本次覆盖学年、缺席于候选 →
 * withdrawn；旧学年/他源/校验域不进撤回集）+ 源行更新（last_success_at/
 * coverage/last_complete_snapshot——只在完整发布后刷新）。事务经
 * {@link TransactionTemplate}（生产由 Spring 事务管理器装配；门控 IT 注入
 * DataSourceTransactionManager 构造的模板，与生产同一事务路径——@Transactional
 * 注解在非 Spring 代理调用面不生效，编程式事务两面一致）。
 *
 * <p><b>退化 → 零事件写</b>（applyDegraded）：不更新事件行、不撤回、不覆盖
 * last_complete_snapshot、不刷新 last_success_at；只写源状态（streak/auto_state
 * 经状态机）与有界诊断。「零写」专指事件数据，不禁止诊断。
 *
 * <p><b>校验源 → 零写径</b>（recordDiscrepancies）：VERIFY 片段与库内权威
 * published 事件逐字段比对，不一致只写源诊断——校验源失败或冲突不得回滚权威
 * 源最后正确版本，也不能自动取得写权（K11）。
 *
 * <p>注：事件行更新用显式 {@link LambdaUpdateWrapper}（updateById 跳过 null
 * 字段——恢复时 withdrawn_at 清空须显式 set null，项目既有判例）。
 */
@Slf4j
@Service
public class KeyDateSyncService {

    /**
     * 变更摘要列宽（有界，超长截断）
     */
    static final int CHANGE_SUMMARY_LIMIT = 512;

    /**
     * 诊断列宽（有界）
     */
    static final int DIAG_LIMIT = 4000;

    private final KeyDateMapper keyDateMapper;
    private final KeyDateSourceMapper sourceMapper;
    private final TransactionTemplate txTemplate;
    private final Clock clock;

    @Autowired
    public KeyDateSyncService(KeyDateMapper keyDateMapper, KeyDateSourceMapper sourceMapper,
                              PlatformTransactionManager transactionManager) {
        this(keyDateMapper, sourceMapper, new TransactionTemplate(transactionManager), Clock.systemDefaultZone());
    }

    /**
     * 全参构造器（测试注入事务模板与可控时钟）
     */
    public KeyDateSyncService(KeyDateMapper keyDateMapper, KeyDateSourceMapper sourceMapper,
                              TransactionTemplate txTemplate, Clock clock) {
        this.keyDateMapper = keyDateMapper;
        this.sourceMapper = sourceMapper;
        this.txTemplate = txTemplate;
        this.clock = clock;
    }

    /**
     * 单轮发布结果（对齐 replay.py Store.changed）
     */
    public record CompleteResult(List<String> upserted, List<String> withdrawn,
                                 List<String> restored, int unchanged) {
    }

    /**
     * 完整候选版本原子发布（writer 源）。coverageAy=本页覆盖学年（撤回域限定；
     * 事件自身 academic_year 可早/晚于覆盖学年——如 class-tt-release 目标学年）。
     *
     * <p>防线 C（#195 审核修正，纵深兜底）：本次完整候选为空且库内该源覆盖学年
     * 存在 published 行 → 拒绝发布并按退化处理（原因入 last_diag 可查），不执行
     * 撤回——防线 A/B（解析 fail-closed/门禁 writer 空集下限）失效时的保底：
     * 空 events 的 seen 表会把该覆盖学年全部 published 行撤空且无告警。
     */
    public CompleteResult applyComplete(KeyDateSourceDO source, String coverageAy,
                                        List<KeyDateCandidate> events) {
        if (events.isEmpty()) {
            Long published = keyDateMapper.selectCount(new LambdaQueryWrapper<KeyDateDO>()
                    .eq(KeyDateDO::getSourceKey, source.getSourceKey())
                    .eq(KeyDateDO::getAcademicYear, coverageAy)
                    .eq(KeyDateDO::getStatus, "published"));
            if (published != null && published > 0) {
                log.warn("[calendar] 源 {} 完整候选为空但库内覆盖学年 {} 有 {} 条 published——按退化处理，不发布不撤回（防线 C 兜底）",
                        source.getSourceKey(), coverageAy, published);
                applyDegraded(source, List.of("空完整候选拒绝发布（防线 C 兜底）：库内覆盖学年 " + coverageAy
                        + " 仍有 " + published + " 条 published 事件，撤回被拦截"));
                return new CompleteResult(List.of(), List.of(), List.of(), 0);
            }
        }
        return txTemplate.execute(tx -> {
            LocalDateTime now = LocalDateTime.now(clock);
            List<String> upserted = new ArrayList<>();
            List<String> restored = new ArrayList<>();
            List<String> withdrawn = new ArrayList<>();
            int unchanged = 0;

            Map<String, KeyDateDO> existing = keyDateMapper.selectList(new LambdaQueryWrapper<KeyDateDO>()
                            .eq(KeyDateDO::getSourceKey, source.getSourceKey()))
                    .stream().collect(Collectors.toMap(KeyDateDO::getUid, r -> r, (a, b) -> a));

            for (KeyDateCandidate e : events) {
                String uid = KeyDateUid.uidOf(e.getAy(), e.getTerm(), e.getEventCode(), e.getAudienceCode(), e.getSlot());
                KeyDateDO rec = existing.get(uid);
                if (rec == null) {
                    keyDateMapper.insert(toDo(uid, e, source, now));
                    upserted.add(uid);
                    continue;
                }
                List<String> changes = new ArrayList<>();
                boolean restoredNow = false;
                if ("withdrawn".equals(rec.getStatus())) {
                    rec.setStatus("published");
                    rec.setRevision(rec.getRevision() + 1);
                    rec.setWithdrawnAt(null);
                    changes.add("status: withdrawn→published");
                    restored.add(uid);
                    restoredNow = true;
                }
                boolean datesChanged = !Objects.equals(triple(rec), tripleOf(e));
                if (datesChanged) {
                    changes.add("dates: " + triple(rec) + "→" + tripleOf(e));
                    rec.setPrecision(e.getPrecision());
                    rec.setDateStart(e.getDateStart());
                    rec.setDateEnd(e.getDateEnd());
                    rec.setFuzzyHint(e.getFuzzyBucket());
                    rec.setRevision(rec.getRevision() + 1);
                }
                // 展示字段随页面刷新（不触发 revision——身份与日期之外的证据面）
                rec.setRawText(e.getRawText());
                rec.setProvenance(String.join(",", e.getProvenance()));
                rec.setTitleEn(KeyDateTitles.titleEn(e));
                rec.setTitleZh(KeyDateTitles.titleZh(e));
                rec.setAudienceText(e.getAudienceText());
                rec.setLastSeenAt(now);
                if (!changes.isEmpty()) {
                    rec.setChangeSummary(bound(String.join("; ", changes), CHANGE_SUMMARY_LIMIT));
                    upserted.add(uid);
                } else {
                    unchanged++;
                }
                updateEventRow(rec, restoredNow);
            }

            // 门 5 限定覆盖域撤回：本源 published 且身份学年==覆盖学年 且缺席于本次候选
            List<String> seen = events.stream()
                    .map(e -> KeyDateUid.uidOf(e.getAy(), e.getTerm(), e.getEventCode(), e.getAudienceCode(), e.getSlot()))
                    .toList();
            for (KeyDateDO rec : keyDateMapper.selectList(new LambdaQueryWrapper<KeyDateDO>()
                    .eq(KeyDateDO::getSourceKey, source.getSourceKey())
                    .eq(KeyDateDO::getAcademicYear, coverageAy)
                    .eq(KeyDateDO::getStatus, "published"))) {
                if (!seen.contains(rec.getUid())) {
                    rec.setStatus("withdrawn");
                    rec.setWithdrawnAt(now);
                    rec.setRevision(rec.getRevision() + 1);
                    rec.setChangeSummary(bound("withdrawn: 完整候选缺席（覆盖域 " + coverageAy + "）", CHANGE_SUMMARY_LIMIT));
                    updateEventRow(rec, false);
                    withdrawn.add(rec.getUid());
                }
            }

            // 源行：last_success_at / coverage / 最后完整快照（退化轮不触碰本段）；
            // 状态机记完整轮（清退化计数）
            KeyDateSourceStateMachine machine = machineOf(source);
            machine.onParse(false);
            persistMachine(source, machine);
            source.setCoverageAcademicYear(coverageAy);
            source.setLastSuccessAt(now);
            source.setLastCompleteSnapshot(snapshotOf(events));
            source.setLastDiag(bound("完整发布：" + events.size() + " 事件（upsert " + upserted.size()
                    + " / 撤回 " + withdrawn.size() + " / 恢复 " + restored.size() + " / 不变 " + unchanged + "）", DIAG_LIMIT));
            saveSourceState(source, now);
            log.info("[calendar] 源 {} 完整发布：{} 事件（upsert {} 撤回 {} 恢复 {} 不变 {}）",
                    source.getSourceKey(), events.size(), upserted.size(), withdrawn.size(), restored.size(), unchanged);
            return new CompleteResult(upserted, withdrawn, restored, unchanged);
        });
    }

    /**
     * 退化轮：零事件写（不碰事件行/快照/last_success_at）；只走状态机与诊断
     */
    public void applyDegraded(KeyDateSourceDO source, List<String> reasons) {
        txTemplate.executeWithoutResult(tx -> {
            LocalDateTime now = LocalDateTime.now(clock);
            KeyDateSourceStateMachine machine = machineOf(source);
            machine.onParse(true);
            persistMachine(source, machine);
            source.setLastDiag(bound("退化（零事件写，保留最后完整版本）：" + String.join("; ", reasons), DIAG_LIMIT));
            saveSourceState(source, now);
            log.warn("[calendar] 源 {} 退化：{}（事件库不变，不撤回，不刷新 last_success_at）",
                    source.getSourceKey(), reasons);
        });
    }

    /**
     * 隔离期只读探测：不发布候选（两次完整后恢复 active，下一轮正常发布——
     * 合同§6「探测期间不发布候选」）；只更新状态机与诊断
     */
    public void recordProbe(KeyDateSourceDO source, boolean degraded) {
        txTemplate.executeWithoutResult(tx -> {
            LocalDateTime now = LocalDateTime.now(clock);
            KeyDateSourceStateMachine machine = machineOf(source);
            machine.onParse(degraded);
            persistMachine(source, machine);
            source.setLastDiag(bound("隔离期只读探测（不发布候选）：" + machine.getMode(), DIAG_LIMIT));
            saveSourceState(source, now);
        });
    }

    /**
     * 校验片段 × 库内权威比对（writer/verifier 源的 VERIFY 片段同路径；K11）。
     * 权威键=（学年,学期,事件码,slot）四元组的 published 事件；不一致写源诊断
     * ——零事件写径，权威值不受影响
     */
    public List<String[]> recordDiscrepancies(KeyDateSourceDO source, List<KeyDateCandidate> candidates) {
        List<String[]> disc = new ArrayList<>();
        txTemplate.executeWithoutResult(tx -> {
            for (KeyDateCandidate c : candidates) {
                if (c.getDisposition() != Disposition.VERIFY) {
                    continue;
                }
                List<KeyDateDO> authority = keyDateMapper.selectList(new LambdaQueryWrapper<KeyDateDO>()
                        .eq(KeyDateDO::getAcademicYear, c.getAy())
                        .eq(KeyDateDO::getTerm, c.getTerm())
                        .eq(KeyDateDO::getEventCode, c.getEventCode())
                        .eq(KeyDateDO::getSemanticSlot, c.getSlot() == null ? "" : c.getSlot())
                        .eq(KeyDateDO::getStatus, "published"));
                if (authority.isEmpty()) {
                    disc.add(new String[]{source.getSourceKey(), c.getLocator(), c.getEventCode(), c.getSlot(), "无权威值可比"});
                    continue;
                }
                KeyDateDO a = authority.get(0);
                diff(disc, source.getSourceKey(), c, "precision", a.getPrecision(), c.getPrecision());
                diff(disc, source.getSourceKey(), c, "date_start",
                        a.getDateStart() == null ? null : a.getDateStart().toString(),
                        c.getDateStart() == null ? null : c.getDateStart().toString());
                diff(disc, source.getSourceKey(), c, "date_end",
                        a.getDateEnd() == null ? null : a.getDateEnd().toString(),
                        c.getDateEnd() == null ? null : c.getDateEnd().toString());
                diff(disc, source.getSourceKey(), c, "fuzzy_hint", a.getFuzzyHint(), c.getFuzzyBucket());
            }
            if (!disc.isEmpty()) {
                source.setLastDiag(bound("跨源校验 discrepancy " + disc.size() + " 条（仅告警，校验源零写径）："
                        + disc.stream().map(d -> String.join("|", d)).collect(Collectors.joining("; ")), DIAG_LIMIT));
                saveSourceState(source, LocalDateTime.now(clock));
                log.warn("[calendar] 源 {} 校验 discrepancy {} 条（权威值保持，不自动接管写权）",
                        source.getSourceKey(), disc.size());
            }
        });
        return disc;
    }

    private void diff(List<String[]> disc, String sourceKey, KeyDateCandidate c,
                      String field, String authority, String local) {
        if (!Objects.equals(authority, local)) {
            disc.add(new String[]{sourceKey, c.getLocator(), c.getEventCode(), c.getSlot(),
                    "%s 权威='%s' 本源='%s'".formatted(field, authority, local)});
        }
    }

    /**
     * 显式全字段更新（updateById 跳过 null——恢复时 withdrawn_at 须显式清空）
     */
    private void updateEventRow(KeyDateDO rec, boolean clearWithdrawnAt) {
        LambdaUpdateWrapper<KeyDateDO> update = new LambdaUpdateWrapper<KeyDateDO>()
                .eq(KeyDateDO::getId, rec.getId())
                .set(KeyDateDO::getStatus, rec.getStatus())
                .set(KeyDateDO::getRevision, rec.getRevision())
                .set(KeyDateDO::getChangeSummary, rec.getChangeSummary())
                .set(KeyDateDO::getPrecision, rec.getPrecision())
                .set(KeyDateDO::getDateStart, rec.getDateStart())
                .set(KeyDateDO::getDateEnd, rec.getDateEnd())
                .set(KeyDateDO::getFuzzyHint, rec.getFuzzyHint())
                .set(KeyDateDO::getRawText, rec.getRawText())
                .set(KeyDateDO::getProvenance, rec.getProvenance())
                .set(KeyDateDO::getTitleEn, rec.getTitleEn())
                .set(KeyDateDO::getTitleZh, rec.getTitleZh())
                .set(KeyDateDO::getAudienceText, rec.getAudienceText())
                .set(KeyDateDO::getLastSeenAt, rec.getLastSeenAt())
                .set(KeyDateDO::getWithdrawnAt, rec.getWithdrawnAt());
        keyDateMapper.update(null, update);
    }

    private KeyDateDO toDo(String uid, KeyDateCandidate e, KeyDateSourceDO source, LocalDateTime now) {
        return KeyDateDO.builder()
                .uid(uid)
                .academicYear(e.getAy())
                .term(e.getTerm())
                .eventCode(e.getEventCode())
                .audienceCode(e.getAudienceCode())
                .semanticSlot(e.getSlot())
                .sourceKey(source.getSourceKey())
                .sourceUrl(source.getSourceUrl())
                .titleEn(KeyDateTitles.titleEn(e))
                .titleZh(KeyDateTitles.titleZh(e))
                .audienceText(e.getAudienceText())
                .rawText(e.getRawText())
                .provenance(String.join(",", e.getProvenance()))
                .precision(e.getPrecision())
                .dateStart(e.getDateStart())
                .dateEnd(e.getDateEnd())
                .fuzzyHint(e.getFuzzyBucket())
                .status("published")
                .revision(1)
                .changeSummary(null)
                .firstSeenAt(now)
                .lastSeenAt(now)
                .build();
    }

    private static String triple(KeyDateDO rec) {
        return "[" + rec.getPrecision() + "," + rec.getDateStart() + "," + rec.getDateEnd() + "]";
    }

    private static String tripleOf(KeyDateCandidate e) {
        return "[" + e.getPrecision() + "," + e.getDateStart() + "," + e.getDateEnd() + "]";
    }

    /**
     * 最后完整候选快照（轻量排序串：uid|precision|start|end）——退化不覆盖，
     * 保留用于比对与恢复判定
     */
    static String snapshotOf(List<KeyDateCandidate> events) {
        return events.stream()
                .sorted(Comparator.comparing(e -> KeyDateUid.uidOf(e.getAy(), e.getTerm(),
                        e.getEventCode(), e.getAudienceCode(), e.getSlot())))
                .map(e -> KeyDateUid.uidOf(e.getAy(), e.getTerm(), e.getEventCode(), e.getAudienceCode(), e.getSlot())
                        + "|" + e.getPrecision() + "|" + e.getDateStart() + "|" + e.getDateEnd())
                .collect(Collectors.joining(";"));
    }

    private static KeyDateSourceStateMachine machineOf(KeyDateSourceDO source) {
        // manual_disabled 仅内存态：库面 CHECK（ck_key_date_source_auto）只允许
        // active/auto_isolated——人工停用的落库表示是 enabled='0'，不走本列
        KeyDateSourceStateMachine.Mode mode = switch (source.getAutoState() == null ? "active" : source.getAutoState()) {
            case "auto_isolated" -> KeyDateSourceStateMachine.Mode.AUTO_ISOLATED;
            case "manual_disabled" -> KeyDateSourceStateMachine.Mode.MANUAL_DISABLED;
            default -> KeyDateSourceStateMachine.Mode.ACTIVE;
        };
        return new KeyDateSourceStateMachine(mode,
                source.getDegradedStreak() == null ? 0 : source.getDegradedStreak(),
                source.getProbeOkStreak() == null ? 0 : source.getProbeOkStreak());
    }

    /**
     * 状态机落源行（包私有=护栏测试直测，snapshotOf 同先例）：MANUAL_DISABLED
     * 仅内存态禁落库（#195 审核修正 P3）——auto_state 的 CHECK 约束只允许
     * active/auto_isolated，人工停用落库面=enabled='0'；遇 MANUAL_DISABLED 时
     * 保持 auto_state 原值不动（不写 manual_disabled 违约值）
     */
    static void persistMachine(KeyDateSourceDO source, KeyDateSourceStateMachine machine) {
        if (machine.getMode() == KeyDateSourceStateMachine.Mode.MANUAL_DISABLED) {
            source.setDegradedStreak(machine.getDegradedStreak());
            source.setProbeOkStreak(machine.getProbeOkStreak());
            return;
        }
        source.setAutoState(switch (machine.getMode()) {
            case AUTO_ISOLATED -> "auto_isolated";
            case ACTIVE -> "active";
            case MANUAL_DISABLED -> "active"; // 不可达（上方已过滤）——编译期穷尽性要求
        });
        source.setDegradedStreak(machine.getDegradedStreak());
        source.setProbeOkStreak(machine.getProbeOkStreak());
    }

    private void saveSourceState(KeyDateSourceDO source, LocalDateTime now) {
        source.setUpdatedAt(now);
        KeyDateSourceDO existing = sourceMapper.selectOne(new LambdaQueryWrapper<KeyDateSourceDO>()
                .eq(KeyDateSourceDO::getSourceKey, source.getSourceKey()));
        if (existing == null) {
            sourceMapper.insert(source);
        } else {
            source.setId(existing.getId());
            sourceMapper.updateById(source);
        }
    }

    static String bound(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
