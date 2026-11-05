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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** 整份配置加密，历史版本与草稿中也不落明文密钥。 */
@Component
public class RuntimeConfigCipher {
    private final byte[] key;

    public RuntimeConfigCipher(@Value("${ragent.runtime-config.master-key:}") String encoded) {
        if (encoded == null || encoded.isBlank()) {
            key = null;
            return;
        }
        try {
            key = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("运行时配置主密钥必须为 Base64", e);
        }
        if (key.length != 32) throw new IllegalStateException("运行时配置主密钥必须为 32 字节");
    }

    public boolean configured() {
        return key != null;
    }

    public String encrypt(String value) {
        requireKey();
        try {
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD("ragent-runtime-v1".getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(nonce)
                    + "."
                    + Base64.getEncoder()
                            .encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("配置加密失败", e);
        }
    }

    public String decrypt(String value) {
        requireKey();
        try {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
            cipher.updateAAD("ragent-runtime-v1".getBytes(StandardCharsets.UTF_8));
            return new String(
                    cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("配置解密失败，请核对主密钥", e);
        }
    }

    private void requireKey() {
        if (key == null)
            throw new IllegalStateException("未配置 RAGENT_RUNTIME_CONFIG_MASTER_KEY，配置写入及解密不可用");
    }
}
