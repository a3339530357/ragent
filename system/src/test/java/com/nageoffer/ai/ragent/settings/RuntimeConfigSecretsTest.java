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

package com.nageoffer.ai.ragent.settings;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.framework.exception.ClientException;

import org.junit.jupiter.api.Test;

class RuntimeConfigSecretsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void keepReplaceClearAreExplicitAndReadNeverReturnsSecret() throws Exception {
        ObjectNode old =
                (ObjectNode) json.readTree("{\"providers\":{\"a\":{\"apiKey\":\"old-secret\"}}}");
        ObjectNode masked = RuntimeConfigSecrets.mask(old);
        assertFalse(masked.toString().contains("old-secret"));
        assertEquals(old, RuntimeConfigSecrets.resolve(masked, old));
        ObjectNode action = (ObjectNode) masked.path("providers").path("a").path("apiKey");
        action.put("action", "replace");
        action.put("value", "new-secret");
        assertEquals(
                "new-secret",
                RuntimeConfigSecrets.resolve(masked, old)
                        .path("providers")
                        .path("a")
                        .path("apiKey")
                        .asText());
        action.put("action", "clear");
        assertEquals(
                "",
                RuntimeConfigSecrets.resolve(masked, old)
                        .path("providers")
                        .path("a")
                        .path("apiKey")
                        .asText());
        assertThrows(ClientException.class, () -> RuntimeConfigSecrets.resolve(old, old));
        assertEquals("old-secret", old.path("providers").path("a").path("apiKey").asText());
    }

    @Test
    void keepingAnUnconfiguredNullSecretDoesNotCreateTheLiteralNullKey() throws Exception {
        ObjectNode old = (ObjectNode) json.readTree("{\"apiKey\":null}");
        ObjectNode result = RuntimeConfigSecrets.resolve(RuntimeConfigSecrets.mask(old), old);
        assertEquals("", result.path("apiKey").asText());
        assertFalse(
                RuntimeConfigSecrets.mask(result).path("apiKey").path("configured").asBoolean());
    }
}
