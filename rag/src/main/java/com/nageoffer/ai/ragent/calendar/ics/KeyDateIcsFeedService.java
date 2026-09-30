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

package com.nageoffer.ai.ragent.calendar.ics;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.nageoffer.ai.ragent.calendar.dao.entity.KeyDateDO;
import com.nageoffer.ai.ragent.calendar.dao.mapper.KeyDateMapper;
import com.nageoffer.ai.ragent.calendar.ics.IcsCalendarWriter.EventSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 校历 .ics 订阅导出服务（#194；只读消费 t_key_date，唯一写径=ics_export_state/
 * ics_last_dates 两列——票面明示例外，其余零写、零 LLM）。
 *
 * <p><b>资格规则（票面四条之 3/4）</b>：
 * <ul>
 *   <li><b>活跃导出</b>：status ∈ {published, archived}（与 #193 展示可见集同口径；
 *       archived=正常过期展示态≠官方取消，原样继续导出且<b>不发 CANCELLED</b>）
 *       且 precision ∈ {exact-day, exact-range} 且 date_start 非空。fuzzy/onwards
 *       只入展示不入提醒——到不了导出面。</li>
 *   <li><b>精确变模糊/撤回 → 取消</b>：曾导出（ics_export_state=exported）后失去
 *       资格（status=withdrawn 或 precision 降为 fuzzy/onwards 或日期缺失）→ 发
 *       同 UID 的 STATUS:CANCELLED 组件（SEQUENCE=revision——#192 合同撤回/日期
 *       变更均 revision+1，保证大于客户端持有的最后活跃 SEQUENCE），不留旧精确
 *       提醒；写回 ics_export_state=cancelled（ics_last_dates 快照保留作审计）。
 *       恢复（withdrawn→published 或模糊回精确）沿用同 UID、revision 再+1 → 重新
 *       活跃导出，SEQUENCE 严格大于取消组件——客户端去取消态恢复事件。</li>
 *   <li><b>取消留存 ≥ 撤回日起 90 天</b>：已发取消（state=cancelled）的组件按锚点
 *       保留；锚点=withdrawn_at（撤回合同锚），精确变模糊无撤回时间列——回退
 *       last_seen_at（≥ 其失去资格时刻，留存只会更长，≥90 天口径保守成立）。
 *       留存窗外不再发（客户端此时早已过刷新窗；合同下限即 90）。</li>
 *   <li><b>从未导出（state=NULL）且无资格</b>（含从未导出的 withdrawn/fuzzy）：
 *       不发组件不写库——无订阅端持有即无需取消。</li>
 * </ul>
 *
 * <p><b>幂等/并发</b>：同库两次导出（数据未变）产出字节一致（UID 升序稳定序+
 * DTSTAMP 取行 last_seen_at）且零写回（state/快照未变不 update）；并发导出双写
 * 同值幂等收敛，无锁需求。
 */
@Slf4j
@Service
public class KeyDateIcsFeedService {

    /**
     * 日历显示名（订阅端日历标题）
     */
    static final String CALENDAR_NAME = "PolyU 关键日期 Key Dates";

    private static final ZoneId FEED_ZONE = ZoneId.of("Asia/Hong_Kong");

    private static final String STATUS_PUBLISHED = "published";
    private static final String STATUS_ARCHIVED = "archived";
    private static final String PRECISION_EXACT_DAY = "exact-day";
    private static final String PRECISION_EXACT_RANGE = "exact-range";

    private static final String EXPORT_STATE_EXPORTED = "exported";
    private static final String EXPORT_STATE_CANCELLED = "cancelled";

    private final KeyDateMapper keyDateMapper;
    private final IcsExportProperties properties;
    private final Clock clock;

    @Autowired
    public KeyDateIcsFeedService(KeyDateMapper keyDateMapper, IcsExportProperties properties) {
        this(keyDateMapper, properties, Clock.system(FEED_ZONE));
    }

    /**
     * 全参构造器（测试注入可控时钟——留存窗口断言不受真实日期影响）
     */
    public KeyDateIcsFeedService(KeyDateMapper keyDateMapper, IcsExportProperties properties, Clock clock) {
        this.keyDateMapper = keyDateMapper;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 单次导出结果（计数供日志/冒烟证据；feed=完整 .ics 文本）
     *
     * @param liveCount    活跃组件数（published/archived × exact）
     * @param cancelCount  CANCELLED 组件数（新发取消+留存期内）
     * @param stateWrites  ics_export_state 变更写回次数（exported↔cancelled）
     * @param snapshotWrites ics_last_dates 快照写回次数（含随 state 同写的行）
     */
    public record FeedResult(String feed, int liveCount, int cancelCount,
                             int stateWrites, int snapshotWrites) {
    }

    /**
     * 生成订阅 feed 并执行唯一写径回写（ics_export_state/ics_last_dates）
     */
    public FeedResult exportFeed() {
        List<KeyDateDO> rows = new ArrayList<>(keyDateMapper.selectList(null));
        rows.sort(Comparator.comparing(KeyDateDO::getUid)); // 稳定序：同数据两次导出字节一致
        LocalDate today = LocalDate.now(clock);
        int retentionDays = properties.effectiveRetentionDays();

        List<EventSpec> specs = new ArrayList<>(rows.size());
        int live = 0;
        int cancelled = 0;
        int stateWrites = 0;
        int snapshotWrites = 0;

        for (KeyDateDO row : rows) {
            if (isExportEligible(row)) {
                specs.add(liveSpec(row));
                live++;
                String snapshot = snapshotOf(row);
                boolean stateStale = !EXPORT_STATE_EXPORTED.equals(row.getIcsExportState());
                boolean snapshotStale = !snapshot.equals(row.getIcsLastDates());
                if (stateStale || snapshotStale) {
                    // 唯一写径：仅 ics_export_state + ics_last_dates 两列（铁律）
                    keyDateMapper.update(null, new LambdaUpdateWrapper<KeyDateDO>()
                            .eq(KeyDateDO::getId, row.getId())
                            .set(KeyDateDO::getIcsExportState, EXPORT_STATE_EXPORTED)
                            .set(KeyDateDO::getIcsLastDates, snapshot));
                    if (stateStale) {
                        stateWrites++;
                    }
                    snapshotWrites++;
                }
            } else if (EXPORT_STATE_EXPORTED.equals(row.getIcsExportState())) {
                // 新取消：取消尚未发出——无论留存窗恒发（迟发好过不发），转 cancelled
                specs.add(cancelSpec(row));
                cancelled++;
                keyDateMapper.update(null, new LambdaUpdateWrapper<KeyDateDO>()
                        .eq(KeyDateDO::getId, row.getId())
                        .set(KeyDateDO::getIcsExportState, EXPORT_STATE_CANCELLED));
                // ics_last_dates 不动——保留最后导出快照（审计+取消组件日期源）
                stateWrites++;
            } else if (EXPORT_STATE_CANCELLED.equals(row.getIcsExportState())
                    && withinRetention(row, today, retentionDays)) {
                // 已发取消的留存期回放（≥撤回日起 90 天合同面）
                specs.add(cancelSpec(row));
                cancelled++;
            }
        }

        String feed = IcsCalendarWriter.write(specs, CALENDAR_NAME, properties.getAlarmLeadDays());
        log.info("[calendar] .ics 导出：活跃 {} / 取消 {}（写回 state {} 快照 {}），feed {} 字节",
                live, cancelled, stateWrites, snapshotWrites, feed.length());
        return new FeedResult(feed, live, cancelled, stateWrites, snapshotWrites);
    }

    /**
     * 活跃导出资格（票面：fuzzy/onwards 只入展示；withdrawn 不可见；archived
     * 继续导出不发 CANCELLED）
     */
    private static boolean isExportEligible(KeyDateDO row) {
        boolean visible = STATUS_PUBLISHED.equals(row.getStatus()) || STATUS_ARCHIVED.equals(row.getStatus());
        boolean exact = PRECISION_EXACT_DAY.equals(row.getPrecision())
                || PRECISION_EXACT_RANGE.equals(row.getPrecision());
        return visible && exact && row.getDateStart() != null;
    }

    /**
     * 留存窗：锚点=withdrawn_at（撤回合同锚）?? last_seen_at（精确变模糊回退锚，
     * ≥ 失去资格时刻——留存只会更长）；窗口含端（恰第 90 天仍发）
     */
    private static boolean withinRetention(KeyDateDO row, LocalDate today, int retentionDays) {
        LocalDateTime anchor = row.getWithdrawnAt() != null ? row.getWithdrawnAt() : row.getLastSeenAt();
        if (anchor == null) {
            return true; // 无锚可依：保守继续发（≥90 天方向）
        }
        return !anchor.toLocalDate().isBefore(today.minusDays(retentionDays));
    }

    /**
     * 活跃组件规格（VALARM 仅 deadline 白名单事件码——票面反例：fee-notification
     * 同 fee 类别但不挂）
     */
    private EventSpec liveSpec(KeyDateDO row) {
        String title = row.getTitleZh() != null ? row.getTitleZh() : row.getTitleEn();
        boolean alarm = properties.getDeadlineEventCodes().contains(row.getEventCode());
        return new EventSpec(row.getUid(), title, descriptionOf(row, false), row.getSourceUrl(),
                row.getDateStart(), row.getDateEnd(), row.getRevision(), false, alarm,
                row.getLastSeenAt());
    }

    /**
     * 取消组件规格（同 UID、SEQUENCE=revision（撤回/降精度已 +1）、不挂 VALARM）
     */
    private EventSpec cancelSpec(KeyDateDO row) {
        LocalDate[] dates = lastExportedDates(row);
        String title = (row.getTitleZh() != null ? row.getTitleZh() : row.getTitleEn()) + "（已取消）";
        return new EventSpec(row.getUid(), title, descriptionOf(row, true), row.getSourceUrl(),
                dates[0], dates[1], row.getRevision(), true, false, row.getLastSeenAt());
    }

    /**
     * 取消组件日期=订阅端最后见到的日期（ics_last_dates 快照优先——精确变模糊后
     * 行内日期已失真/为空；回退行内日期；再回退 last_seen_at 当日——CANCELLED 组件
     * RFC 必备 DTSTART，UID 才是取消的身份锚）
     */
    private LocalDate[] lastExportedDates(KeyDateDO row) {
        String snapshot = row.getIcsLastDates();
        if (snapshot != null && !snapshot.isBlank()) {
            String[] parts = snapshot.split("\\|", -1);
            if (parts.length == 3 && !parts[1].isBlank()) {
                try {
                    return new LocalDate[]{LocalDate.parse(parts[1]),
                            parts[2].isBlank() ? null : LocalDate.parse(parts[2])};
                } catch (RuntimeException ignored) {
                    // 快照损坏走行内回退
                }
            }
        }
        if (row.getDateStart() != null) {
            return new LocalDate[]{row.getDateStart(), row.getDateEnd()};
        }
        LocalDateTime anchor = row.getWithdrawnAt() != null ? row.getWithdrawnAt() : row.getLastSeenAt();
        LocalDate fallback = anchor != null ? anchor.toLocalDate() : LocalDate.now(clock);
        return new LocalDate[]{fallback, null};
    }

    /**
     * 描述（未转义原文，转义在 writer）：英文标题+受众限制原文（DDL：不得省略）
     * +取消标记
     */
    private static String descriptionOf(KeyDateDO row, boolean cancelled) {
        StringBuilder sb = new StringBuilder(96);
        if (row.getTitleEn() != null) {
            sb.append(row.getTitleEn());
        }
        if (row.getAudienceText() != null && !row.getAudienceText().isBlank()) {
            sb.append("\n适用: ").append(row.getAudienceText());
        }
        if (row.getSourceUrl() != null && !row.getSourceUrl().isBlank()) {
            sb.append("\n来源: ").append(row.getSourceUrl());
        }
        if (cancelled) {
            sb.append("\n官方已撤回或不再具有精确日期，此提醒已取消。");
        }
        return sb.toString();
    }

    /**
     * 快照格式 {@code precision|start|end}（end 空=exact-day）——取消组件日期源
     */
    static String snapshotOf(KeyDateDO row) {
        return row.getPrecision() + "|" + row.getDateStart() + "|"
                + (row.getDateEnd() != null ? row.getDateEnd() : "");
    }
}
