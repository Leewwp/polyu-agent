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

package com.nageoffer.ai.ragent.calendar.parse;

import com.nageoffer.ai.ragent.calendar.model.Disposition;
import com.nageoffer.ai.ragent.calendar.model.KeyDateCandidate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 整源门禁（转译 replay.py evaluate_source，合同§4 五门中的四道解析面门禁——
 * 门 5 撤回权限在同步服务的限定域撤回逻辑里实现）：
 * <ol>
 *   <li>结构与覆盖证据：页面学年证据存在；WRITE/VERIFY 日期落学年窗；星期列
 *       校验已在解析器内拦截（矛盾行落 UNKNOWN）；</li>
 *   <li>候选片段全量归类：未知候选必须为 0（HTTP 200/锚点/行数下限/未知率≤10%
 *       均不能替代）；</li>
 *   <li>解析出处闭合：合并由 {@link CandidateMerger} 白名单进行，歧义在这里拦截；</li>
 *   <li>时间与身份合法：exact-range 端点合法有序、WRITE 必有学年。</li>
 * </ol>
 * 任一门失败 → degraded=true：退化版本不更新事件行、不撤回、不覆盖
 * last_complete_snapshot、不刷新 last_success_at（合同§4 末段「零写」）。
 */
public final class SourceGate {

    private SourceGate() {
    }

    /**
     * 门禁结果：degraded + 逐条原因（有界诊断面）+ 合并后写事件（完整版本才有意义）
     */
    public record GateResult(boolean degraded, List<String> reasons, List<KeyDateCandidate> events,
                             List<KeyDateCandidate> candidates) {
    }

    /**
     * 对一页主表行执行解析 + 门禁（纯函数：rows/pageText → 结果，无时钟无 IO）
     */
    public static GateResult evaluate(CalendarPageParser parser, List<List<String>> rows, String pageText) {
        List<KeyDateCandidate> cands = parser.parse(rows, pageText);
        return evaluate(parser.sourceKey(), cands, pageText, rows);
    }

    /**
     * 对已解析候选执行门禁（K 反例直接注入候选/变异行时复用同一路径）
     */
    public static GateResult evaluate(String sourceKey, List<KeyDateCandidate> cands,
                                      String pageText, List<List<String>> rows) {
        List<String> reasons = new ArrayList<>();
        List<KeyDateCandidate> unknowns = cands.stream()
                .filter(c -> c.getDisposition() == Disposition.UNKNOWN).toList();
        if (!unknowns.isEmpty()) {
            reasons.add("未知候选 %d 个：%s".formatted(unknowns.size(),
                    unknowns.stream().map(KeyDateCandidate::getLocator).collect(Collectors.joining(", "))));
        }
        String ay = CalendarPageParser.pageAy(sourceKey, pageText,
                "cal-academic-calendar".equals(sourceKey) ? rows : null);
        if (ay == null) {
            reasons.add("页面覆盖学年证据缺失");
        } else {
            LocalDate[] window = CalendarDates.ayWindow(ay);
            for (KeyDateCandidate c : cands) {
                if ((c.getDisposition() == Disposition.WRITE || c.getDisposition() == Disposition.VERIFY)
                        && c.getDateStart() != null
                        && (c.getDateStart().isBefore(window[0]) || c.getDateStart().isAfter(window[1]))) {
                    reasons.add("%s 日期 %s 出学年窗 [%s,%s]".formatted(
                            c.getLocator(), c.getDateStart(), window[0], window[1]));
                }
                if (c.getDisposition() == Disposition.WRITE && c.getAy() == null) {
                    reasons.add("%s 无页面学年证据".formatted(c.getLocator()));
                }
            }
        }
        List<KeyDateCandidate> events = CandidateMerger.merge(
                cands.stream().filter(c -> c.getDisposition() == Disposition.WRITE).toList());
        for (KeyDateCandidate e : events) {
            if (e.isAmbiguous()) {
                reasons.add("身份歧义（同键多实例且无白名单合并）：%s".formatted(e.getLocator()));
            }
            if ("exact-range".equals(e.getPrecision()) && !(e.getDateStart() != null && e.getDateEnd() != null
                    && !e.getDateStart().isAfter(e.getDateEnd()))) {
                reasons.add("区间非法/不完整：%s".formatted(e.getLocator()));
            }
        }
        return new GateResult(!reasons.isEmpty(), reasons, events, cands);
    }

    /**
     * 跨源校验（转译 replay.py cross_verify）：VERIFY 片段 × 权威 WRITE 事件逐字段
     * 比对（precision/date_start/date_end/fuzzy_bucket）。权威键=（学年,学期,
     * 事件码,slot）四元组（与 replay.py 口径一致）。返回 discrepancy 行
     * [源, locator, 事件码, slot, 描述]——只告警，校验源不得取得写权（K11）
     */
    public static List<String[]> crossVerify(Map<String, GateResult> results) {
        Map<List<String>, KeyDateCandidate> authorities = new java.util.HashMap<>();
        for (GateResult r : results.values()) {
            for (KeyDateCandidate e : r.events()) {
                authorities.put(Arrays.asList(e.getAy(), e.getTerm(), e.getEventCode(), e.getSlot()), e);
            }
        }
        List<String[]> disc = new ArrayList<>();
        for (Map.Entry<String, GateResult> entry : results.entrySet()) {
            String key = entry.getKey();
            for (KeyDateCandidate c : entry.getValue().candidates()) {
                if (c.getDisposition() != Disposition.VERIFY) {
                    continue;
                }
                KeyDateCandidate a = authorities.get(Arrays.asList(c.getAy(), c.getTerm(), c.getEventCode(), c.getSlot()));
                if (a == null) {
                    disc.add(new String[]{key, c.getLocator(), c.getEventCode(), c.getSlot(), "无权威值可比"});
                    continue;
                }
                compare(disc, key, c, a, "precision", a.getPrecision(), c.getPrecision());
                compare(disc, key, c, a, "date_start",
                        a.getDateStart() == null ? null : a.getDateStart().toString(),
                        c.getDateStart() == null ? null : c.getDateStart().toString());
                compare(disc, key, c, a, "date_end",
                        a.getDateEnd() == null ? null : a.getDateEnd().toString(),
                        c.getDateEnd() == null ? null : c.getDateEnd().toString());
                compare(disc, key, c, a, "fuzzy_bucket", a.getFuzzyBucket(), c.getFuzzyBucket());
            }
        }
        return disc;
    }

    private static void compare(List<String[]> disc, String key, KeyDateCandidate c,
                                KeyDateCandidate a, String field, String authority, String local) {
        if (!java.util.Objects.equals(authority, local)) {
            disc.add(new String[]{key, c.getLocator(), c.getEventCode(), c.getSlot(),
                    "%s 权威='%s' 本源='%s'".formatted(field, authority, local)});
        }
    }
}
