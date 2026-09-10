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
 * 资讯主题词表实体（U12-A，doc 19 §3 第二轮评审加入）
 *
 * <p>三维分组（FACULTY 学院与部门 / RESEARCH 研究领域与话题 / STUDENT_AFFAIRS 学生事务）；
 * 种子词表 curated=true 进目录；LLM 新提案 curated=false 不进目录，晨报抽样审后转正/合并/丢弃。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_news_topic")
public class NewsTopicDO {

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
     * active=正常展示
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
