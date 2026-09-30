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

package com.nageoffer.ai.ragent.news.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 资讯信源注册表实体
 *
 * <p>V1 只上校级账号；扩源=加行无代码改动（enabled 列即源级开关）。
 * consecutive_failures 沿用失败滞回范式：阈值 3 自动置 enabled=false。
 * #186 源治理：停用原因三分（disabled_reason：manual 人工停用 / auto 自动隔离 /
 * policy 策略禁止——只探活 auto）+ 探活记账（probe_*）+ 最近六类结果（last_outcome）
 * + 停止/复归审计（isolated_time/recovered_time，事件流水另见
 * t_news_source_health_event）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_news_source")
public class NewsSourceDO {

    /**
     * 停用原因（disabled_reason 列）：人工停用（seed 明示停更/维护者 SQL 置停，
     * 不探活不自动解禁）
     */
    public static final String DISABLED_REASON_MANUAL = "manual";

    /**
     * 停用原因（disabled_reason 列）：自动隔离（连续 3 次失败滞回——唯一探活对象，
     * 日级两次有效完整成功自动复归）
     */
    public static final String DISABLED_REASON_AUTO = "auto";

    /**
     * 停用原因（disabled_reason 列）：策略禁止（robots Disallow/出站守卫拒绝——
     * 不因可达自动解禁，复归=人工）
     */
    public static final String DISABLED_REASON_POLICY = "policy";

    /**
     * 主键 ID，数据库自增（BIGSERIAL）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 信源稳定标识：news-sitemap / media-releases / youtube-main 等
     */
    private String sourceKey;

    /**
     * 平台：official / youtube / prn / events 等；非 official 卡片带平台徽章
     */
    private String platform;

    /**
     * 信源展示名（中文）
     */
    private String displayName;

    /**
     * 信源展示名（英文）
     */
    private String displayNameEn;

    /**
     * 信源主页 URL
     */
    private String homeUrl;

    /**
     * 抓取入口；events 型含 date=YYYY/MM 占位，由抓取器按当前月+下月替换
     */
    private String fetchEndpoint;

    /**
     * 抓取策略：SITEMAP / HTML_LIST / RSS / JSON_API 四型
     */
    private String fetchStrategy;

    /**
     * 是否官网（polyu.edu.hk）来源
     */
    private Boolean official;

    /**
     * 源级开关（campus-reports 停更默认禁用；失败滞回 3 连败自动禁源也落此列）
     */
    private Boolean enabled;

    /**
     * 连续抓取失败计数
     */
    private Integer consecutiveFailures;

    /**
     * 停用原因（#186 三分）：manual=人工停用 / auto=自动隔离（唯一探活对象） /
     * policy=策略禁止（robots/出站守卫）；NULL=启用中或未判定。既有禁用行由
     * upgrades 迁移判据归类（consecutive_failures≥3 → auto，其余保守 → manual）
     */
    private String disabledReason;

    /**
     * 最近一次停用（自动隔离/策略禁止转停）时刻——admin 停止记录可查
     */
    private Date isolatedTime;

    /**
     * 最近一次探活自动复归时刻——admin 复归记录可查
     */
    private Date recoveredTime;

    /**
     * 连续有效完整成功次数（探活记账；任一失败清零，复归阈值 2）
     */
    private Integer probeSuccesses;

    /**
     * 最近一次探活时刻（HKT 日级节拍：每源每日至多探一次；defer 不推进）
     */
    private Date probeTime;

    /**
     * 最近一轮六类结果代码（{@link com.nageoffer.ai.ragent.news.fetch.NewsFetchOutcome}）
     */
    private String lastOutcome;

    /**
     * 最近一轮结果落账时刻
     */
    private Date lastOutcomeTime;

    /**
     * 独立来源组（#187 事件投票去重键）：同机构多 feed/聚合口归同组只计一票
     * （官网各栏目+官方 YouTube=polyu-official；PRN 双语 wire=prn-wire；
     * GNews 检索面=gnews）；NULL=按 sourceKey 自成一组（AI 扩源默认独立）。
     * 事件参与者证据行入组时对本列做快照，映射变更不回溯历史证据。
     */
    private String independenceGroup;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    /**
     * 更新时间（无 MyBatis-Plus 自动填充标记：DB 端 DEFAULT now() 兜底，滞回计数走显式更新）
     */
    private Date updateTime;
}
