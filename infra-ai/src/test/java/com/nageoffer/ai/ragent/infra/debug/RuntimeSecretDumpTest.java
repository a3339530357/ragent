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

package com.nageoffer.ai.ragent.infra.debug;

import static org.junit.jupiter.api.Assertions.*;

import com.nageoffer.ai.ragent.framework.convention.ModelCallType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.Map;

class RuntimeSecretDumpTest {
    @TempDir Path dir;

    @Test
    void credentialsNeverReachRequestDump() throws Exception {
        new ModelRequestDumpWriter(dir.toString())
                .begin(
                        ModelCallType.INTENT_CLASSIFY,
                        "test",
                        "POST",
                        "http://localhost/model",
                        Map.of(
                                "Authorization",
                                "Bearer private-secret",
                                "X-API-Key",
                                "other-secret",
                                "Content-Type",
                                "application/json"),
                        "{}");
        try (var files = Files.walk(dir)) {
            String value =
                    Files.readString(files.filter(Files::isRegularFile).findFirst().orElseThrow());
            assertFalse(value.contains("private-secret"));
            assertFalse(value.contains("other-secret"));
            assertTrue(value.contains("[REDACTED]"));
            assertTrue(value.contains("application/json"));
        }
    }
}
