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

package com.nageoffer.ai.ragent.audit.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L44（#95）审计快照读时脱敏单测：USER 掩码/非 USER 透传/diff 末段识别/脏形状不阻断。
 */
class AuditSnapshotMaskerTest {

    private final AuditSnapshotMasker masker = new AuditSnapshotMasker(new ObjectMapper());

    @Test
    void userSnapshotMasksUsernameEmailAndPassword() {
        String snapshot = "{\"id\":\"1\",\"username\":\"weipei08@outlook.com\",\"email\":\"alice@example.com\","
                + "\"password\":\"$2a$10$hashhashhashhashhash\",\"role\":\"admin\"}";
        String masked = masker.maskSnapshot("USER", snapshot);
        assertThat(masked).contains("\"we****om\"");
        assertThat(masked).contains("\"al****om\"");
        assertThat(masked).contains("\"password\":\"******\"");
        assertThat(masked).contains("\"role\":\"admin\"");
        assertThat(masked).doesNotContain("weipei08").doesNotContain("outlook").doesNotContain("hashhash");
    }

    @Test
    void nonUserBizTypePassesThroughUntouched() {
        String snapshot = "{\"docName\":\"library.pdf\",\"username\":\"weipei08@outlook.com\"}";
        assertThat(masker.maskSnapshot("KNOWLEDGE_DOCUMENT", snapshot)).isEqualTo(snapshot);
        assertThat(masker.maskDiff("KNOWLEDGE_DOCUMENT", "[{\"field\":\"/username\",\"before\":\"a@b.com\",\"after\":null}]"))
                .isEqualTo("[{\"field\":\"/username\",\"before\":\"a@b.com\",\"after\":null}]");
    }

    @Test
    void blankOrMalformedSnapshotPassesThrough() {
        assertThat(masker.maskSnapshot("USER", null)).isNull();
        assertThat(masker.maskSnapshot("USER", "")).isEmpty();
        assertThat(masker.maskSnapshot("USER", "not-json")).isEqualTo("not-json");
        assertThat(masker.maskDiff("USER", null)).isNull();
        assertThat(masker.maskDiff("USER", "oops")).isEqualTo("oops");
    }

    @Test
    void userDiffMasksByLeafSegmentOnlyForPiiFields() {
        String diff = "[{\"field\":\"/username\",\"before\":\"old@mail.com\",\"after\":\"new@mail.com\"},"
                + "{\"field\":\"/role\",\"before\":\"user\",\"after\":\"admin\"}]";
        String masked = masker.maskDiff("USER", diff);
        assertThat(masked).contains("\"before\":\"ol****om\"");
        assertThat(masked).contains("\"after\":\"ne****om\"");
        assertThat(masked).contains("\"field\":\"/role\",\"before\":\"user\",\"after\":\"admin\"");
    }

    @Test
    void shortPiiValuesMaskConservatively() {
        String snapshot = "{\"username\":\"ab\",\"email\":\"abc@x\"}";
        String masked = masker.maskSnapshot("USER", snapshot);
        assertThat(masked).contains("\"username\":\"**\"");
        assertThat(masked).contains("\"email\":\"a***\"");
    }

    @Test
    void nonObjectSnapshotShapePassesThrough() {
        assertThat(masker.maskSnapshot("USER", "[\"weipei08@outlook.com\"]"))
                .isEqualTo("[\"weipei08@outlook.com\"]");
    }
}
