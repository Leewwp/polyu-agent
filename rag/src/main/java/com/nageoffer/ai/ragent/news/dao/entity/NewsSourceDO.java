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
 * 资讯信源注册表实体（U12-A，doc 19 §3）
 *
 * <p>V1 只上校级账号；扩源=加行无代码改动（enabled 列即源级开关）。
 * consecutive_failures 沿用 K2c 滞回范式：阈值 3 自动置 enabled=false。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_news_source")
public class NewsSourceDO {

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
     * 源级开关（campus-reports 停更默认禁用；K2c 滞回 3 连败自动禁源也落此列）
     */
    private Boolean enabled;

    /**
     * 连续抓取失败计数
     */
    private Integer consecutiveFailures;

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
