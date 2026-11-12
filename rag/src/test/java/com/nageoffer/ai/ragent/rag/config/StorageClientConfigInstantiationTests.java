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

package com.nageoffer.ai.ragent.rag.config;

import com.nageoffer.ai.ragent.rag.core.storage.S3ObjectStorageClient;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 生产 profile S3Client/S3Presigner 实例化覆盖（B7 后续，2026-11-12 升级窗段二）：
 * #438 事故形态=运行期混版本在真实构建/调用时才炸（NoSuchMethod 等），CI 纯单测口径对
 * 「SDK 升版后客户端能否实例化」零覆盖——本测试以生产同形属性走 {@link StorageClientConfig}
 * 两个工厂方法真实构建（无网络调用，build 即触发类装载与 SPI 装配），升版破坏在 CI 即红。
 */
class StorageClientConfigInstantiationTests {

    private RagStorageProperties prodShapedProperties() {
        RagStorageProperties properties = new RagStorageProperties();
        // 与 application-prod.yaml 的 rag.storage.s3 同形（值=本地栈占位，不触网）
        RagStorageProperties.S3 s3 = properties.getS3();
        s3.setEndpoint("http://127.0.0.1:9000");
        s3.setAccessKey("polyu-minio");
        s3.setSecretKey("smoke-minio-pass");
        s3.setRegion("us-east-1");
        s3.setPathStyle(true);
        return properties;
    }

    @Test
    void s3ClientInstantiatesWithProdShapedProperties() {
        StorageClientConfig config = new StorageClientConfig();
        try (S3Client client = config.s3Client(prodShapedProperties())) {
            assertNotNull(client);
            assertEquals("s3", client.serviceName());
        }
    }

    @Test
    void s3PresignerInstantiatesWithProdShapedProperties() {
        StorageClientConfig config = new StorageClientConfig();
        try (S3Presigner presigner = config.s3Presigner(prodShapedProperties())) {
            assertNotNull(presigner);
        }
    }

    @Test
    void objectStorageClientWiresInstantiatedClients() {
        // 装配层一并覆盖：ObjectStorageClient 委托面持有两客户端实例（升版后接口漂移在此暴露）
        RagStorageProperties properties = new RagStorageProperties();
        properties.setKbBucket("ragent-sources");
        properties.setAssetBucket("ragent-assets");
        StorageClientConfig config = new StorageClientConfig();
        try (S3Client s3 = config.s3Client(prodShapedProperties());
             S3Presigner presigner = config.s3Presigner(prodShapedProperties())) {
            S3ObjectStorageClient client = new S3ObjectStorageClient(s3, presigner, properties);
            assertNotNull(client);
        }
    }
}
