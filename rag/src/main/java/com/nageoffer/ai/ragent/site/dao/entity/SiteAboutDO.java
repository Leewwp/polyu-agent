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

package com.nageoffer.ai.ragent.site.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
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
 * 关于页单行内容表（doc 25）：id 固定 1，service 层 upsert（无行则插），
 * 零种子依赖——关于文案由维护者后台粘贴
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_site_about")
public class SiteAboutDO {

    /**
     * 固定 1（单行表）
     */
    @TableId(type = IdType.INPUT)
    private Long id;

    /**
     * 关于页 markdown 内容
     */
    private String content;

    /**
     * 关于页英文 markdown（可空；空时前端英文档回落中文内容）。ALWAYS 同 qr 两列——
     * 后台清空英文内容传 null 必须能写回
     */
    @TableField(value = "content_en", updateStrategy = FieldStrategy.ALWAYS)
    private String contentEn;

    /**
     * 赞赏二维码 URL（可空；与 alt 同时为空时前端赞赏区整区不渲染）。
     * updateStrategy=ALWAYS：saveAbout 的「移除」路径传 null，若走默认 NOT_NULL
     * 策略 updateById 会跳过 null 字段——移除永远不生效（保存假成功）
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String qrImageUrl;

    /**
     * 第二张赞赏二维码 URL（可空）。ALWAYS 同上——null 要能写回
     */
    @TableField(value = "qr_image_url_alt", updateStrategy = FieldStrategy.ALWAYS)
    private String qrImageUrlAlt;

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
}
