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

package com.nageoffer.ai.ragent.site.service.impl;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.site.controller.vo.SiteFeedbackVO;
import com.nageoffer.ai.ragent.site.dao.entity.SiteFeedbackDO;
import com.nageoffer.ai.ragent.site.dao.mapper.SiteFeedbackMapper;
import com.nageoffer.ai.ragent.site.service.SiteFeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.Set;

/**
 * 站点反馈实现：IP 日限走 DB 计数（当日行数），日界固定 Asia/Hong_Kong
 * （与 AnonymousTrialGuard R4 判例同款，JVM 默认时区在 UTC 容器下不漂移）
 */
@Service
@RequiredArgsConstructor
public class SiteFeedbackServiceImpl implements SiteFeedbackService {

    /**
     * 同 IP 当日提交上限（D1）
     */
    private static final int DAILY_LIMIT_PER_IP = 5;
    private static final int CONTENT_MIN = 10;
    private static final int CONTENT_MAX = 2000;
    private static final int CONTACT_MAX = 100;
    private static final ZoneId HKT_ZONE = ZoneId.of("Asia/Hong_Kong");
    private static final Set<Integer> VALID_STATUS = Set.of(0, 1, 2);

    private final SiteFeedbackMapper siteFeedbackMapper;

    @Override
    public void submit(String content, String contact, String clientIp) {
        String trimmed = StrUtil.trim(content);
        Assert.notNull(trimmed, () -> new ClientException("反馈内容不能为空"));
        Assert.isTrue(trimmed.length() >= CONTENT_MIN && trimmed.length() <= CONTENT_MAX,
                () -> new ClientException("反馈内容须为 10–2000 字"));
        String trimmedContact = StrUtil.trimToNull(contact);
        Assert.isTrue(trimmedContact == null || trimmedContact.length() <= CONTACT_MAX,
                () -> new ClientException("联系方式不能超过 100 字"));
        String ip = StrUtil.blankToDefault(clientIp, "unknown");

        Date dayStart = Date.from(LocalDate.now(HKT_ZONE).atStartOfDay(HKT_ZONE).toInstant());
        Long todayCount = siteFeedbackMapper.selectCount(new LambdaQueryWrapper<SiteFeedbackDO>()
                .eq(SiteFeedbackDO::getClientIp, ip)
                .ge(SiteFeedbackDO::getCreateTime, dayStart));
        Assert.isTrue(todayCount == null || todayCount < DAILY_LIMIT_PER_IP,
                () -> new ClientException("今日提交已达上限，请明天再来"));

        siteFeedbackMapper.insert(SiteFeedbackDO.builder()
                .content(trimmed)
                .contact(trimmedContact)
                .clientIp(ip)
                .status(0)
                .build());
    }

    @Override
    public IPage<SiteFeedbackVO> pageQuery(int page, int size, Integer status) {
        Integer safeStatus = status == null ? null : (VALID_STATUS.contains(status) ? status : null);
        IPage<SiteFeedbackDO> result = siteFeedbackMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<SiteFeedbackDO>()
                        .eq(safeStatus != null, SiteFeedbackDO::getStatus, safeStatus)
                        .orderByDesc(SiteFeedbackDO::getId));
        return result.convert(this::toVo);
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        Assert.notNull(id, () -> new ClientException("反馈 ID 不能为空"));
        Assert.isTrue(status != null && VALID_STATUS.contains(status),
                () -> new ClientException("非法状态值"));
        SiteFeedbackDO existing = siteFeedbackMapper.selectById(id);
        Assert.notNull(existing, () -> new ClientException("反馈不存在"));
        SiteFeedbackDO update = new SiteFeedbackDO();
        update.setId(id);
        update.setStatus(status);
        siteFeedbackMapper.updateById(update);
    }

    @Override
    public void delete(Long id) {
        Assert.notNull(id, () -> new ClientException("反馈 ID 不能为空"));
        siteFeedbackMapper.deleteById(id);
    }

    private SiteFeedbackVO toVo(SiteFeedbackDO entity) {
        return SiteFeedbackVO.builder()
                .id(entity.getId())
                .content(entity.getContent())
                .contact(entity.getContact())
                .clientIpMasked(maskIp(entity.getClientIp()))
                .status(entity.getStatus())
                .createTime(entity.getCreateTime())
                .updateTime(entity.getUpdateTime())
                .build();
    }

    /**
     * IPv4 保前后段掩中段（1.2.*.4）；非点分形态保前 4 字符
     */
    private static String maskIp(String ip) {
        if (StrUtil.isBlank(ip)) {
            return "";
        }
        String[] parts = ip.split("\\.");
        if (parts.length == 4) {
            return parts[0] + "." + parts[1] + ".*.*";
        }
        return ip.length() <= 4 ? ip : ip.substring(0, 4) + "***";
    }
}
