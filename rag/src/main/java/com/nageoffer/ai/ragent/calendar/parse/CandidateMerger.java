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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 候选后处理：白名单合并（转译 replay.py post_merge，合同§3/§4 门 3）。
 *
 * <p>仅三类合并规则（预定义起止配对，其余合并=身份歧义）：
 * <ul>
 *   <li>pair：exam-period / revision-days 的 commences/ends 两半行合一区间事件；</li>
 *   <li>cross-month-overall：congregation 跨月两段合并为总体区间；</li>
 * </ul>
 * 同身份键多实例且无规则覆盖 → 标记 ambiguous（整源退化）。合并保留全部出处
 * 映射（provenance 排序列表）与拼接 raw_text——一对多/多对一出处的证据闭合。
 */
public final class CandidateMerger {

    private CandidateMerger() {
    }

    /**
     * WRITE 候选 → 合并后事件列表（VERIFY/KNOWN_SKIP/UNKNOWN 不进事件集）
     */
    public static List<KeyDateCandidate> merge(List<KeyDateCandidate> cands) {
        Map<List<String>, KeyDateCandidate> events = new LinkedHashMap<>();
        Map<List<String>, List<KeyDateCandidate>> pairs = new LinkedHashMap<>();
        List<KeyDateCandidate> congregationParts = null;
        for (KeyDateCandidate c : cands) {
            if (c.getDisposition() != Disposition.WRITE) {
                continue;
            }
            List<String> key = Arrays.asList(c.getAy(), c.getTerm(), c.getEventCode(), c.getAudienceCode(), c.getSlot());
            boolean pair = "exam-period".equals(c.getEventCode()) || "revision-days".equals(c.getEventCode());
            boolean crossMonth = "congregation".equals(c.getEventCode());
            if (pair) {
                pairs.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
            } else if (crossMonth) {
                if (congregationParts == null) {
                    congregationParts = new ArrayList<>();
                }
                congregationParts.add(c);
            } else if (events.containsKey(key)) {
                KeyDateCandidate first = events.get(key);
                first.setAmbiguous(true);
                first.getProvenance().addAll(c.getProvenance());
            } else {
                events.put(key, c);
            }
        }
        List<KeyDateCandidate> out = new ArrayList<>(events.values());
        for (Map.Entry<List<String>, List<KeyDateCandidate>> e : pairs.entrySet()) {
            List<KeyDateCandidate> parts = e.getValue();
            KeyDateCandidate start = null;
            KeyDateCandidate end = null;
            for (KeyDateCandidate p : parts) {
                if ("start".equals(p.getHalf())) {
                    start = start == null ? p : start;
                } else if ("end".equals(p.getHalf())) {
                    end = end == null ? p : end;
                }
            }
            boolean uniqueStart = parts.stream().filter(p -> "start".equals(p.getHalf())).count() == 1;
            boolean uniqueEnd = parts.stream().filter(p -> "end".equals(p.getHalf())).count() == 1;
            if (!uniqueStart || !uniqueEnd || start == null || end == null
                    || start.getHalfDate() == null || end.getHalfDate() == null) {
                KeyDateCandidate broken = new KeyDateCandidate(parts.get(0).getSourceKey(),
                        String.join(",", parts.stream().map(KeyDateCandidate::getLocator).toList()),
                        String.join(" + ", parts.stream().map(KeyDateCandidate::getRawText).toList()),
                        Disposition.WRITE, null);
                broken.setAy(e.getKey().get(0));
                broken.setTerm(e.getKey().get(1));
                broken.setEventCode(e.getKey().get(2));
                broken.setSlot(e.getKey().get(4));
                broken.setProvenance(new ArrayList<>(parts.stream().map(KeyDateCandidate::getLocator).toList()));
                broken.setAmbiguous(true);
                out.add(broken);
                continue;
            }
            KeyDateCandidate merged = start.copy();
            merged.setPrecision("exact-range");
            merged.setDateStart(start.getHalfDate());
            merged.setDateEnd(end.getHalfDate());
            merged.setProvenance(parts.stream().map(KeyDateCandidate::getLocator).sorted()
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
            merged.setRawText(parts.stream()
                    .sorted(Comparator.comparing(KeyDateCandidate::getLocator))
                    .map(KeyDateCandidate::getRawText)
                    .reduce((a, b) -> a + " + " + b).orElse(""));
            out.add(merged);
        }
        if (congregationParts != null) {
            List<KeyDateCandidate> parts = congregationParts.stream()
                    .sorted(Comparator.comparing(KeyDateCandidate::getDateStart,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            KeyDateCandidate merged = parts.get(0).copy();
            merged.setPrecision("exact-range");
            merged.setDateStart(parts.get(0).getDateStart());
            merged.setDateEnd(parts.get(parts.size() - 1).getDateEnd());
            merged.setProvenance(parts.stream().map(KeyDateCandidate::getLocator).sorted()
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
            merged.setRawText(parts.stream().map(KeyDateCandidate::getRawText)
                    .reduce((a, b) -> a + " + " + b).orElse(""));
            out.add(merged);
        }
        return out;
    }
}
