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

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 日报「八校/高教」独立栏目快照实体（t_news_daily_digest_higher_ed，#412
 * ——父票 #406 B 线：八校联校课程与交换 / JUPAS 与研究生政策 / UGC 高教
 * 政策三类横向信息）。
 *
 * <p><b>快照独立性红线</b>（沿 {@link NewsDailyDigestItemDO}/
 * {@link NewsDailyDigestActivityDO}）：item_id/source_* 只作溯源，<b>不建
 * 外键</b>——t_news_item 行被 90 天保留清理删除不连带；标题/摘要/URL/来源
 * 等展示字段全部冗余快照，读取面零 LLM、仅按 item_id 回查 status 做主动
 * 下架复检。(digest_id, item_id) 唯一=同刊内一条目一行。
 *
 * <p>选材口径见建表迁移注释：候选与主资讯流同窗同判据（窗口内统一公开资格
 * 条目），按 source_key ∈ rag.news.digest-higher-ed-source-keys（#410 注册
 * 的 B 线官方源）分流进本栏目——<b>不落 t_news_daily_digest_item</b>（理大
 * 主流程不含 B 线条目）；栏内序=publish_time DESC, id DESC（与主流程同一
 * 确定性排序）。窗口零条目=零快照行（读取面整段隐藏）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_news_daily_digest_higher_ed")
public class NewsDailyDigestHigherEdDO {

    /**
     * 主键 ID，数据库自增（BIGSERIAL）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属刊头（t_news_daily_digest.id，ON DELETE CASCADE 随刊头重建带走）
     */
    private Long digestId;

    /**
     * 溯源 t_news_item.id（无外键：保留清理删除不连带；读取期仅当源行
     * 仍存在且 status 不为 published 时过滤失格——正常清理后的缺失行保留展示）
     */
    private Long itemId;

    /**
     * 栏内序（1 起，确定性：publish_time DESC、id 兜底——与主流程条目同一排序口径）
     */
    private Integer seq;

    /**
     * 原文 URL 快照（JUPAS/UGC 官方原文永久外链）
     */
    private String url;

    /**
     * sha256(url) 快照（url_hash）
     */
    private String urlHash;

    private String titleZh;

    private String titleEn;

    private String summaryZh;

    private String summaryEn;

    /**
     * 固定 8 类+other 快照
     */
    private String category;

    /**
     * B 线源 source_key 快照（生成分流判据留证：命中
     * rag.news.digest-higher-ed-source-keys）
     */
    private String sourceKey;

    /**
     * 信源平台快照（B 线官方源=media）
     */
    private String sourcePlatform;

    /**
     * 信源是否官网快照（B 线官方源=FALSE——非 polyu.edu.hk 官网语义）
     */
    private Boolean sourceOfficial;

    /**
     * 信源展示名（中文）快照（来源标识，页面徽章直映）
     */
    private String sourceDisplayName;

    /**
     * 信源展示名（英文）快照
     */
    private String sourceDisplayNameEn;

    /**
     * 原文发布时间快照（窗口归属判定的锚，与主流程同窗）
     */
    private Date publishTime;

    /**
     * 发布时间精度冗余（#275）：date=归期代表值（只显日期）/ datetime=真实瞬时
     * / unknown=旧快照
     */
    private String publishTimePrecision;
}
