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

package com.nageoffer.ai.ragent.rag.security;

import okhttp3.Dns;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * 连接级出站地址复校（#101 M4 残余面，DNS 重绑定彻底解法）
 *
 * <p>URL 级（{@link IngestionUrlGuard#validateOutboundTarget}）与重定向级
 * （{@link RedirectGuard} 逐跳）校验的都是主机名，校验与建连的解析是两次独立
 * 查询——攻击者控制源域名 DNS 时可校验时解析公网、TTL 过期后建连解析内网
 * （TOCTOU）。本 SPI 挂在 OkHttp 建连路径上：连接实际使用的解析结果逐个过
 * {@link IngestionUrlGuard#checkResolvedAddresses}，命中内网/元数据地址即抛错
 * 整单失败——解析与校验落在同一次查询上，窗口消失。
 *
 * <p>挂载面=文档抓取（HttpClientHelper）+ 资讯抓取（NewsHttpFetchClient，
 * fetchOnce 与 robots.txt 拉取共用同一 client 派生副本）；syncHttpClient 共享
 * bean 不动（MinerU/LightRAG/WebSearchChannel 是部署方配置的可信内部端点）。
 */
public final class GuardedDns implements Dns {

    private final IngestionUrlGuard guard;
    private final Dns delegate;

    public GuardedDns(IngestionUrlGuard guard) {
        this(guard, Dns.SYSTEM);
    }

    /** 测试注入形态：delegate 可替换为 stub 解析 */
    GuardedDns(IngestionUrlGuard guard, Dns delegate) {
        this.guard = guard;
        this.delegate = delegate;
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        List<InetAddress> addresses = delegate.lookup(hostname);
        // 不吞原始 UnknownHostException：解析失败照原语义上抛
        guard.checkResolvedAddresses(hostname, addresses);
        return addresses;
    }
}
