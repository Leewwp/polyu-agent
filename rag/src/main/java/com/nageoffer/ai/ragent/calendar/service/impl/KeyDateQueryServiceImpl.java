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

package com.nageoffer.ai.ragent.calendar.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nageoffer.ai.ragent.calendar.KeyDateSemantics;
import com.nageoffer.ai.ragent.calendar.controller.vo.KeyDateAuditVO;
import com.nageoffer.ai.ragent.calendar.controller.vo.KeyDateBoardVO;
import com.nageoffer.ai.ragent.calendar.controller.vo.KeyDateSourceVO;
import com.nageoffer.ai.ragent.calendar.controller.vo.KeyDateVO;
import com.nageoffer.ai.ragent.calendar.dao.entity.KeyDateDO;
import com.nageoffer.ai.ragent.calendar.dao.entity.KeyDateSourceDO;
import com.nageoffer.ai.ragent.calendar.dao.mapper.KeyDateMapper;
import com.nageoffer.ai.ragent.calendar.dao.mapper.KeyDateSourceMapper;
import com.nageoffer.ai.ragent.calendar.service.KeyDateDisplayProperties;
import com.nageoffer.ai.ragent.calendar.service.KeyDateQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 校历查询服务实现（#193；只读消费，零事件写、零 LLM）。
 *
 * <p><b>可见性判据（单一入口）</b>：status ∈ {published, archived} 进入展示面
 * ——published 按日期分入 current/recent/archived 段；DB archived（摄取面
 * 生命周期归档，跨学年旧行）一律进展示 archived 段不冒充最新；withdrawn
 * （限定覆盖域撤回）保留 UID 与历史但不可见。后续日期 MCP（#182）复用本实现，
 * 不得另写判据。
 *
 * <p><b>分类锚点</b>：Asia/Hong_Kong 当日（与摄取调度 zone 同口径）；「过期
 * N 天归档」每次请求重算，不写回库（铁律：只读消费 t_key_date）。归档边界
 * 含端：结束日恰为 N 天前仍属 recentPast，严格早于该日才进 archived。
 *
 * <p><b>倒计时门</b>：daysUntil 仅 exact-day/exact-range 计算（合同§4）；
 * onwards（开放起点）/fuzzy（模糊窗）不伪造精确截止语义，恒 null。
 *
 * <p><b>三态口径</b>：源状态 normal/degraded/isolated（+manual_disabled）
 * 由 auto_state×degraded_streak×enabled 派生；退化/隔离源的 last_success_at
 * 停在上次完整发布（摄取面合同§6 不刷新），本层原样透出——页面据
 * anySourceAbnormal 按「最后完整版本（截至 lastFullSyncAt）」呈现。
 */
@Service
public class KeyDateQueryServiceImpl implements KeyDateQueryService {

    /**
     * 分类锚点时区（合同即 HKT 口径；KeyDateSyncJob @Scheduled 同判例）
     */
    static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Hong_Kong");

    private static final String STATUS_PUBLISHED = "published";
    private static final String STATUS_ARCHIVED = "archived";
    private static final String STATUS_WITHDRAWN = "withdrawn";

    private static final String PHASE_TODAY = "today";
    private static final String PHASE_ONGOING = "ongoing";
    private static final String PHASE_UPCOMING = "upcoming";
    private static final String PHASE_RECENT = "recent";
    private static final String PHASE_ARCHIVED = "archived";
    private static final String PHASE_UNDATED = "undated";

    private final KeyDateMapper keyDateMapper;
    private final KeyDateSourceMapper sourceMapper;
    private final KeyDateDisplayProperties properties;
    private final Clock clock;

    @Autowired
    public KeyDateQueryServiceImpl(KeyDateMapper keyDateMapper, KeyDateSourceMapper sourceMapper,
                                   KeyDateDisplayProperties properties) {
        this(keyDateMapper, sourceMapper, properties, Clock.system(DISPLAY_ZONE));
    }

    /**
     * 全参构造器（测试注入可控时钟——分类边界断言不受真实日期影响）
     */
    public KeyDateQueryServiceImpl(KeyDateMapper keyDateMapper, KeyDateSourceMapper sourceMapper,
                                   KeyDateDisplayProperties properties, Clock clock) {
        this.keyDateMapper = keyDateMapper;
        this.sourceMapper = sourceMapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public KeyDateBoardVO board() {
        LocalDate today = LocalDate.now(clock);
        List<KeyDateSourceDO> sources = sourceMapper.selectList(null);

        List<KeyDateSourceVO> sourceVOs = sources.stream()
                .map(this::toSourceVO)
                .toList();
        boolean anyAbnormal = sourceVOs.stream()
                .anyMatch(s -> "degraded".equals(s.getState()) || "isolated".equals(s.getState()));
        String coverage = sources.stream()
                .filter(s -> "writer".equals(s.getRole()) && s.getCoverageAcademicYear() != null)
                .map(KeyDateSourceDO::getCoverageAcademicYear)
                .max(Comparator.naturalOrder())
                .orElse(null);
        LocalDateTime lastFullSync = sources.stream()
                .map(KeyDateSourceDO::getLastSuccessAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        List<KeyDateVO> current = new ArrayList<>();
        List<KeyDateVO> recent = new ArrayList<>();
        List<KeyDateVO> archived = new ArrayList<>();
        List<KeyDateVO> undated = new ArrayList<>();
        long archivedTotal = 0;
        LocalDate archiveBoundary = today.minusDays(properties.getArchiveAfterDays());

        for (KeyDateDO row : keyDateMapper.selectList(new LambdaQueryWrapper<KeyDateDO>()
                .in(KeyDateDO::getStatus, STATUS_PUBLISHED, STATUS_ARCHIVED))) {
            // 防御性兜底（与 wrapper 同判据双保险）：withdrawn=限定覆盖域撤回，
            // 保留 UID 与历史但不可见——mock 单测直测服务层时 wrapper 过滤不可达
            if (STATUS_WITHDRAWN.equals(row.getStatus())) {
                continue;
            }
            // DB 生命周期归档（跨学年旧行）不参与日期分桶：一律展示 archived 段
            if (STATUS_ARCHIVED.equals(row.getStatus())) {
                archivedTotal++;
                if (archived.size() < properties.getArchivedCap()) {
                    archived.add(toVO(row, PHASE_ARCHIVED, today));
                }
                continue;
            }
            LocalDate start = row.getDateStart();
            if (start == null) {
                // fuzzy 行（date_* 为 NULL，check 约束兜底）+ 防御性兜底（非 fuzzy 缺日期）
                undated.add(toVO(row, PHASE_UNDATED, today));
                continue;
            }
            LocalDate end = KeyDateSemantics.effectiveEnd(row);
            if (start.isEqual(today)) {
                current.add(toVO(row, PHASE_TODAY, today));
            } else if (KeyDateSemantics.isOngoing(row, today)) {
                // exact-range 进行中（含今日为结束日的区间）
                current.add(toVO(row, PHASE_ONGOING, today));
            } else if (start.isAfter(today)) {
                current.add(toVO(row, PHASE_UPCOMING, today));
            } else if (!end.isBefore(archiveBoundary)) {
                // 已结束但过期 ≤N 天（含端：恰 N 天前仍 recent）
                recent.add(toVO(row, PHASE_RECENT, today));
            } else {
                archivedTotal++;
                if (archived.size() < properties.getArchivedCap()) {
                    archived.add(toVO(row, PHASE_ARCHIVED, today));
                }
            }
        }

        // 临近度排序：current 段按起始日升序（进行中已开行者自然在前、越近越前）；
        // recent/archived 按有效结束日（date_end ?? date_start——exact-day 无结束列）
        // 倒序（最新过期在前）；uid 兜底稳定序
        Comparator<KeyDateVO> byStartAsc = Comparator
                .comparing(KeyDateVO::getDateStart, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(KeyDateVO::getUid);
        Comparator<KeyDateVO> byEffectiveEndDesc = Comparator
                .comparing(KeyDateQueryServiceImpl::effectiveEnd, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(KeyDateVO::getUid)
                .reversed();
        current.sort(byStartAsc);
        recent.sort(byEffectiveEndDesc);
        archived.sort(byEffectiveEndDesc);
        undated.sort(Comparator.comparing(KeyDateVO::getAcademicYear, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(KeyDateVO::getTerm, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(KeyDateVO::getUid));

        return KeyDateBoardVO.builder()
                .coverageAcademicYear(coverage)
                .lastFullSyncAt(lastFullSync)
                .today(today)
                .anySourceAbnormal(anyAbnormal)
                .sources(sourceVOs)
                .currentAndUpcoming(current)
                .recentPast(recent)
                .archived(archived)
                .archivedTotal(archivedTotal)
                .undated(undated)
                .build();
    }

    @Override
    public List<KeyDateAuditVO> auditSources() {
        List<KeyDateDO> events = keyDateMapper.selectList(null);
        return sourceMapper.selectList(null).stream()
                .map(source -> {
                    String key = source.getSourceKey();
                    return KeyDateAuditVO.builder()
                            .sourceKey(key)
                            .sourceUrl(source.getSourceUrl())
                            .role(source.getRole())
                            .enabled("1".equals(source.getEnabled()))
                            .autoState(source.getAutoState())
                            .degradedStreak(source.getDegradedStreak())
                            .probeOkStreak(source.getProbeOkStreak())
                            .coverageAcademicYear(source.getCoverageAcademicYear())
                            .lastSuccessAt(source.getLastSuccessAt())
                            .lastDiag(source.getLastDiag())
                            .updatedAt(source.getUpdatedAt())
                            .publishedCount(events.stream().filter(e -> key.equals(e.getSourceKey())
                                    && STATUS_PUBLISHED.equals(e.getStatus())).count())
                            .withdrawnCount(events.stream().filter(e -> key.equals(e.getSourceKey())
                                    && STATUS_WITHDRAWN.equals(e.getStatus())).count())
                            .archivedCount(events.stream().filter(e -> key.equals(e.getSourceKey())
                                    && STATUS_ARCHIVED.equals(e.getStatus())).count())
                            .build();
                })
                .sorted(Comparator.comparing(KeyDateAuditVO::getSourceKey))
                .toList();
    }

    private KeyDateSourceVO toSourceVO(KeyDateSourceDO source) {
        String state;
        if (!"1".equals(source.getEnabled())) {
            state = "manual_disabled";
        } else if ("auto_isolated".equals(source.getAutoState())) {
            state = "isolated";
        } else if (source.getDegradedStreak() != null && source.getDegradedStreak() > 0) {
            state = "degraded";
        } else {
            state = "normal";
        }
        return KeyDateSourceVO.builder()
                .sourceKey(source.getSourceKey())
                .role(source.getRole())
                .enabled("1".equals(source.getEnabled()))
                .state(state)
                .coverageAcademicYear(source.getCoverageAcademicYear())
                .lastSuccessAt(source.getLastSuccessAt())
                .degradedStreak(source.getDegradedStreak())
                .build();
    }

    /**
     * 有效结束日（date_end ?? date_start）的 VO 排序投影——口径单一源
     * {@link KeyDateSemantics#effectiveEnd}（工具类面向 DO，recent/archived
     * 倒序排面对 VO 编程）
     */
    private static LocalDate effectiveEnd(KeyDateVO vo) {
        return vo.getDateEnd() != null ? vo.getDateEnd() : vo.getDateStart();
    }

    private KeyDateVO toVO(KeyDateDO row, String phase, LocalDate today) {
        // 倒计时门（负=已过，供 recent 段「已过 N 天」文案）——KeyDateSemantics 单一源
        Integer daysUntil = KeyDateSemantics.daysUntil(row, today);
        return KeyDateVO.builder()
                .uid(row.getUid())
                .academicYear(row.getAcademicYear())
                .term(row.getTerm())
                .titleEn(row.getTitleEn())
                .titleZh(row.getTitleZh())
                .precision(row.getPrecision())
                .dateStart(row.getDateStart())
                .dateEnd(row.getDateEnd())
                .fuzzyHint(row.getFuzzyHint())
                .audienceText(row.getAudienceText())
                .sourceUrl(row.getSourceUrl())
                .phase(phase)
                .daysUntil(daysUntil)
                .build();
    }
}
