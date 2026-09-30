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
import java.util.Set;

/**
 * 资讯主题词表实体
 *
 * <p>三维分组（FACULTY 学院与部门 / RESEARCH 研究领域与话题 / STUDENT_AFFAIRS 学生事务）；
 * 种子词表 curated=true 进目录；LLM 新提案 curated=false 不进目录，人工抽检审后转正/合并/丢弃。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_news_topic")
public class NewsTopicDO {

    /**
     * 正常展示：curated=true 进目录；curated=false=AI 提案待审
     */
    public static final String STATUS_ACTIVE = "active";

    /**
     * 已并入近义 curated 主题（#202 merge 轨）：关联已迁移至目标，本行保留审计
     * （curated 保持 false），名称入别名账防再提
     */
    public static final String STATUS_MERGED = "merged";

    /**
     * 已弃（#202 reject 轨）：泛化无检索价值，残留关联已摘除并留痕
     * （curated 保持 false），名称入别名账防再提
     */
    public static final String STATUS_REJECTED = "rejected";

    /**
     * 正式三维分组之外的 AI 提案占位组（目录按 curated=true 过滤，本组永不展示）
     */
    public static final String GROUP_PROPOSED = "PROPOSED";

    /**
     * 正式分组（promote 转正时人工归入）
     */
    public static final Set<String> FORMAL_GROUPS = Set.of("FACULTY", "RESEARCH", "STUDENT_AFFAIRS");

    /**
     * 主键 ID，数据库自增（BIGSERIAL）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 主题稳定标识，与原型 TOPICS 注册表键一致（eng/bus/ai/admission 等）
     */
    private String slug;

    /**
     * 主题名（中文）
     */
    private String nameZh;

    /**
     * 主题名（英文）
     */
    private String nameEn;

    /**
     * 三维分组：FACULTY / RESEARCH / STUDENT_AFFAIRS
     */
    private String topicGroup;

    /**
     * 界定描述（中文）
     */
    private String descriptionZh;

    /**
     * 界定描述（英文）
     */
    private String descriptionEn;

    /**
     * TRUE=策展词表进目录；FALSE=AI 提案待审不进目录
     */
    private Boolean curated;

    /**
     * active=正常展示（curated=true 进目录；curated=false=AI 提案待审）/
     * merged=已并入近义 curated 主题（关联已迁移，行保留审计）/ rejected=已弃
     * （残留关联已摘除留痕）——一律软状态不硬删（#202）
     */
    private String status;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    /**
     * 更新时间（提案转正等治理动作的审计时刻；DB 端 DEFAULT now() 兜底）
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;
}
