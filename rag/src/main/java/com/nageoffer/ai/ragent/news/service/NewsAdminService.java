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

package com.nageoffer.ai.ragent.news.service;

/**
 * 资讯管理面服务
 *
 * <p>admin UI 前的最小止血通道：人工抽检发现坏摘要/坏条目时单条下架。
 */
public interface NewsAdminService {

    /**
     * 单条下架（status=published → hidden）：列表/热点/主题计数与详情同步消失；
     * 幂等——已 hidden 的条目再次下架不报错
     *
     * @param id 条目 ID
     * @throws com.nageoffer.ai.ragent.framework.exception.ClientException 条目不存在
     */
    void hide(Long id);
}
