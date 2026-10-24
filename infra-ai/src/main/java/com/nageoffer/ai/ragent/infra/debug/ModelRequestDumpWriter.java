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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 模型请求 dump 写入器：把完整请求头与请求体追加落盘。
 * 写文件失败一律静默吞掉——dump 是旁路调试能力，绝不允许影响模型调用主链路。
 */
public class ModelRequestDumpWriter {

    private static final DateTimeFormatter TS_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final Path file;

    private final Object lock = new Object();

    public ModelRequestDumpWriter(String filePath) {
        this.file = Paths.get(filePath);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
        } catch (IOException ignored) {
            // 目录创建失败时首次写文件还会再失败，dump 静默降级
        }
    }

    /**
     * 追加一条请求记录
     *
     * @param source  请求来源标识（agentscope / okhttp）
     * @param method  HTTP 方法
     * @param url     完整请求地址
     * @param headers 请求头（已展开为单值键值对，同名头逗号合并）
     * @param body    请求体原文；null 视为无请求体
     */
    public void dump(String source, String method, String url, Map<String, String> headers, String body) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("======== ").append(LocalDateTime.now().format(TS_FORMATTER))
                .append("  [").append(source).append("] ")
                .append(method).append(' ').append(url).append('\n');
        if (headers != null) {
            headers.forEach((name, value) -> sb.append("H ").append(name).append(": ").append(value).append('\n'));
        }
        sb.append("--- body ---\n");
        if (body != null && !body.isEmpty()) {
            sb.append(body);
            if (!body.endsWith("\n")) {
                sb.append('\n');
            }
        } else {
            sb.append("(empty)\n");
        }
        sb.append('\n');
        try {
            synchronized (lock) {
                Files.writeString(file, sb, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException ignored) {
            // 旁路 dump 失败不打断请求
        }
    }
}
