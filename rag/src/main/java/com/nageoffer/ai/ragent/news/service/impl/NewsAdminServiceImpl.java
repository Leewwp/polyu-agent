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

package com.nageoffer.ai.ragent.news.service.impl;

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.service.NewsAdminService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 资讯管理面服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsAdminServiceImpl implements NewsAdminService {

    private final NewsItemMapper itemMapper;

    @Override
    public void hide(Long id) {
        int rows = itemMapper.update(null, Wrappers.lambdaUpdate(NewsItemDO.class)
                .eq(NewsItemDO::getId, id)
                .eq(NewsItemDO::getStatus, "published")
                .set(NewsItemDO::getStatus, "hidden"));
        if (rows == 0) {
            // 幂等语义：仅 published → hidden 会产生变更；hidden 重复下架与不存在同样 0 行，
            // 先查一次区分两种语义给出准确反馈
            Long exists = itemMapper.selectCount(Wrappers.lambdaQuery(NewsItemDO.class)
                    .eq(NewsItemDO::getId, id));
            if (exists != null && exists > 0) {
                log.info("[news] 条目 {} 已是 hidden，重复下架忽略", id);
                return;
            }
            throw new ClientException("条目不存在");
        }
        log.info("[news] 条目 {} 已下架（published → hidden）", id);
    }
}
