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

package com.nageoffer.ai.ragent.user.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

public interface UserMapper extends BaseMapper<UserDO> {

    /**
     * 按用户名或邮箱查活跃用户（登录双键；@TableLogic 对自定义 SQL 不生效，条件显式写 deleted = 0）
     */
    @Select("SELECT * FROM t_user WHERE deleted = 0 AND (username = #{key} OR email = #{key}) LIMIT 1")
    UserDO selectActiveByUsernameOrEmail(@Param("key") String key);

    /**
     * 按用户名或邮箱查软删冷静期用户（恢复入口专用；只认 delete_time 非空的注销行，不含历史管理删号）
     */
    @Select("SELECT * FROM t_user WHERE deleted = 1 AND delete_time IS NOT NULL "
            + "AND (username = #{key} OR email = #{key}) LIMIT 1")
    UserDO selectSoftDeletedByUsernameOrEmail(@Param("key") String key);

    /**
     * 注销软删：置 deleted=1 并落冷静期起点（delete_time）。@TableLogic 拦截常规 update，
     * 逻辑删字段必须走显式 SQL
     */
    @Update("UPDATE t_user SET deleted = 1, delete_time = #{deleteTime}, update_time = #{deleteTime} "
            + "WHERE id = #{id} AND deleted = 0")
    int softDeleteById(@Param("id") String id, @Param("deleteTime") Date deleteTime);

    /**
     * 撤销注销：恢复为活跃用户并清空冷静期标记
     */
    @Update("UPDATE t_user SET deleted = 0, delete_time = NULL, update_time = #{restoreTime} "
            + "WHERE id = #{id} AND deleted = 1")
    int restoreById(@Param("id") String id, @Param("restoreTime") Date restoreTime);
}
