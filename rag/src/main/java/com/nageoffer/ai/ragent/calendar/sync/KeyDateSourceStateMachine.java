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

/**
 * 源隔离状态机（转译 replay.py SourceState，合同§6）：
 * <ul>
 *   <li>active 连续 3 轮退化 → auto_isolated（自动隔离）；</li>
 *   <li>auto_isolated 保留日级只读探测（探测轮不发布候选——由调度层拦截）；
 *       连续 2 轮完整有效候选 → 恢复 active（恢复后的下一轮才发布）；</li>
 *   <li>manual_disabled（人工停用/策略禁止）任何输入都不变——不自动复活，
 *       与自动隔离严格区分。仅内存态：人工停用的落库表示是
 *       t_key_date_source.enabled='0'（auto_state 的 CHECK 约束只允许
 *       active/auto_isolated，本枚举值禁落库——同步服务 persist 前过滤）；</li>
 *   <li>哈希长期不变是年度表正常情况——本状态机无「内容不变判死」路径。源失败
 *       不撤回事件（退化轮零写由同步服务保证，与状态机正交）。</li>
 * </ul>
 * 纯逻辑无持久化：调用方（同步服务）每轮 onParse 后把 mode/streaks 落
 * t_key_date_source。
 */
public final class KeyDateSourceStateMachine {

    /**
     * 隔离阈值：连续退化轮数
     */
    public static final int DEGRADED_STREAK_LIMIT = 3;

    /**
     * 复归阈值：连续完整探测轮数
     */
    public static final int PROBE_OK_STREAK_LIMIT = 2;

    public enum Mode {
        ACTIVE, AUTO_ISOLATED, MANUAL_DISABLED
    }

    private Mode mode = Mode.ACTIVE;
    private int degradedStreak;
    private int probeOkStreak;

    public KeyDateSourceStateMachine() {
    }

    public KeyDateSourceStateMachine(Mode mode, int degradedStreak, int probeOkStreak) {
        this.mode = mode;
        this.degradedStreak = degradedStreak;
        this.probeOkStreak = probeOkStreak;
    }

    /**
     * 一轮解析后的状态转移（转译 replay.py on_parse；返回转移后 mode）
     */
    public Mode onParse(boolean degraded) {
        if (mode == Mode.MANUAL_DISABLED) {
            return mode;
        }
        if (degraded) {
            degradedStreak++;
            probeOkStreak = 0;
            if (mode == Mode.ACTIVE && degradedStreak >= DEGRADED_STREAK_LIMIT) {
                mode = Mode.AUTO_ISOLATED;
            }
        } else {
            degradedStreak = 0;
            if (mode == Mode.AUTO_ISOLATED) {
                probeOkStreak++;
                if (probeOkStreak >= PROBE_OK_STREAK_LIMIT) {
                    mode = Mode.ACTIVE;
                }
            } else {
                mode = Mode.ACTIVE;
            }
        }
        return mode;
    }

    public Mode getMode() {
        return mode;
    }

    public int getDegradedStreak() {
        return degradedStreak;
    }

    public int getProbeOkStreak() {
        return probeOkStreak;
    }
}
