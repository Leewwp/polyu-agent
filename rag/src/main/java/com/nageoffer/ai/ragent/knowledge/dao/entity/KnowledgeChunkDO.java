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

package com.nageoffer.ai.ragent.knowledge.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Date;

/**
 * RAG 知识库文档分块表实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_knowledge_chunk")
public class KnowledgeChunkDO {

    /**
     * ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 知识库ID
     */
    private String kbId;

    /**
     * 文档ID
     */
    private String docId;

    /**
     * 分块序号（从0开始）
     */
    private Integer chunkIndex;

    /**
     * 分块正文内容
     */
    private String content;

    /**
     * 内容哈希（用于幂等/去重）
     */
    private String contentHash;

    /**
     * 字符数（可用于统计/调参）
     */
    private Integer charCount;

    /**
     * Token数（可选）
     */
    private Integer tokenCount;

    /**
     * 向量文本：章节路径 + 正文
     * <p>
     * 落库不是为了展示——它是重建向量的唯一正确来源：结构信息（章节路径、表格的 {@code 列名: 值}
     * 渲染、图片去 URL 后的描述）只存在于这一列，用 content 重算会静默丢掉它们
     */
    private String embeddingText;

    /**
     * 是否启用 0：禁用 1：启用
     */
    private Integer enabled;

    /**
     * 创建人
     */
    private String createdBy;

    /**
     * 修改人
     */
    private String updatedBy;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    /**
     * 是否删除 0：正常 1：删除
     */
    @TableLogic
    private Integer deleted;

    /**
     * 发布时间（#411 日期元数据）：页面发布/更新日期（#275 口径），文档级事实文档内同值传播；
     * NULL=日期未知（不虚构不推算）
     */
    private LocalDate sourceDate;

    /**
     * 活动截止日期（#411 日期元数据）：条目级截止事实，仅含显式截止标记的块落值、文档内不传播；
     * NULL=无截止语义或日期未知
     */
    private LocalDate activityEndDate;
}
