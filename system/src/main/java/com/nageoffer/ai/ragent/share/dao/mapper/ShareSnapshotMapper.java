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

package com.nageoffer.ai.ragent.share.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.ragent.share.dao.entity.ShareSnapshotDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

/**
 * 统一分享快照 mapper（issue #124）。包内只放 mapper 接口：
 * @MapperScan 会把包内全部接口注册为 mapper bean，服务接口混扫会撞双 bean（#85 判例）
 */
public interface ShareSnapshotMapper extends BaseMapper<ShareSnapshotDO> {

    /**
     * 保留任务物理删除：到期行硬删（含 REVOKED 行）。不能用 BaseMapper.delete——
     * 实体带 @TableLogic 会降级成软删（deleted=1），与原保留任务两段裸 DELETE 的
     * 硬删语义不等价（行会永久残留）；手写 SQL 是仓内合法先例（AgentStateMapper）
     */
    @Delete("DELETE FROM t_share_snapshot WHERE expire_time IS NOT NULL AND expire_time < #{before}")
    int physicalDeleteExpired(@Param("before") Date before);
}
