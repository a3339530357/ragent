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

import org.junit.jupiter.api.Test;

import java.util.Base64;

class RuntimeConfigCipherTest {
    @Test
    void authenticatedEncryptionRejectsWrongKeyAndTampering() {
        var cipher = new RuntimeConfigCipher(Base64.getEncoder().encodeToString(new byte[32]));
        String encrypted = cipher.encrypt("private-api-key");
        assertEquals("private-api-key", cipher.decrypt(encrypted));
        assertNotEquals(encrypted, cipher.encrypt("private-api-key"));
        byte[] other = new byte[32];
        other[0] = 1;
        assertThrows(
                IllegalStateException.class,
                () ->
                        new RuntimeConfigCipher(Base64.getEncoder().encodeToString(other))
                                .decrypt(encrypted));
        String[] parts = encrypted.split("\\.");
        byte[] bytes = Base64.getDecoder().decode(parts[1]);
        bytes[0] ^= 1;
        assertThrows(
                IllegalStateException.class,
                () -> cipher.decrypt(parts[0] + "." + Base64.getEncoder().encodeToString(bytes)));
    }
}
