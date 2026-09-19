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

package com.nageoffer.ai.ragent.rag.core.storage;

import com.nageoffer.ai.ragent.rag.config.RagStorageProperties;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * 资产桶公开 URL 组合语义（O3/L6）：public-url=含桶名的完整前缀，桶名不再拼接；
 * 留空回退 endpoint+asset-bucket（与收敛前本地形态逐字相同——回归锚点）
 */
class S3PublicUrlCompositionTests {

    private S3ObjectStorageClient client(RagStorageProperties properties) {
        return new S3ObjectStorageClient(mock(S3Client.class), mock(S3Presigner.class), properties);
    }

    private RagStorageProperties props(String publicUrl, String endpoint) {
        RagStorageProperties properties = new RagStorageProperties();
        properties.setAssetBucket("ragent-assets");
        properties.getS3().setEndpoint(endpoint);
        if (publicUrl != null) {
            properties.getS3().setPublicUrl(publicUrl);
        }
        return properties;
    }

    @Test
    void explicitPrefixIsUsedAsIsWithoutAppendingBucket() {
        S3ObjectStorageClient client = client(props("https://polyuguide.com/minio/ragent-assets",
                "http://polyu-minio:9000"));

        assertEquals("https://polyuguide.com/minio/ragent-assets/img/abc.png",
                client.buildPublicUrl("ragent-assets", "img/abc.png"));
    }

    @Test
    void trailingSlashOfConfiguredPrefixIsStripped() {
        S3ObjectStorageClient client = client(props("https://polyuguide.com/minio/ragent-assets/",
                "http://polyu-minio:9000"));

        assertEquals("https://polyuguide.com/minio/ragent-assets/x.png",
                client.buildPublicUrl("ragent-assets", "x.png"));
    }

    @Test
    void blankFallsBackToEndpointPlusBucketIdenticalToLegacyForm() {
        // 回退形态与收敛前 base+bucket+key 逐字相同：本地直连 MinIO 的存量用法零变化
        S3ObjectStorageClient client = client(props(null, "http://localhost:9000"));

        assertEquals("http://localhost:9000/ragent-assets/x.png",
                client.buildPublicUrl("ragent-assets", "x.png"));
    }
}
