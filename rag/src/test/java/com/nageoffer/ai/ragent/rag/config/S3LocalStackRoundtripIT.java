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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * awssdk 升版本地栈往返冒烟（B7 后续，2026-11-12 升级窗段二）：put→get→delete 全链路打真实
 * MinIO（本地共享栈 polyu-minio，S3 API 9000，凭据=polyu-local-0b1 compose 的 rustfsadmin）。
 *
 * <p>命名 {@code *IT} 后缀=Surefire 默认不拾取（CI 纯单测口径不破），升版窗手动跑：
 * {@code ./mvnw -pl rag test -Dtest=S3LocalStackRoundtripIT}（本地栈需在跑）。
 * 栈不在位时 assumeTrue 跳过而非红（MilvusCollectionTests 同思路）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3LocalStackRoundtripIT {

    private static final String ENDPOINT = "http://127.0.0.1:9000";
    private static final String ACCESS_KEY = "rustfsadmin";
    private static final String SECRET_KEY = "rustfsadmin";

    @Test
    void putGetDeleteRoundtripAgainstLocalMinio() {
        S3Client client = S3Client.builder()
                .endpointOverride(URI.create(ENDPOINT))
                .region(Region.of("us-east-1"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .forcePathStyle(true)
                .build();
        String bucket = "ragent-sources";   // 本地栈既有桶（StorageInitializer 建）
        String key = "s3-sdk-upgrade-smoke/roundtrip-" + System.currentTimeMillis() + ".txt";
        String payload = "awssdk-roundtrip-" + System.currentTimeMillis();
        try {
            // 栈不在位→连接失败，assumeTrue 把「无栈」与「真故障」分开
            boolean reachable = true;
            try {
                client.headBucket(b -> b.bucket(bucket));
            } catch (Exception e) {
                reachable = false;
            }
            assumeTrue(reachable, "本地 polyu-minio 栈不在位或桶缺失，跳过（升版窗手动跑本 IT）");

            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromString(payload, StandardCharsets.UTF_8));
            String got = new String(client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
                    .asByteArray(), StandardCharsets.UTF_8);
            assertEquals(payload, got, "put→get 往返字节不一致");
        } finally {
            try {
                client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            } catch (Exception ignored) {
                // 清理失败不影响断言结果
            }
            client.close();
        }
    }
}
