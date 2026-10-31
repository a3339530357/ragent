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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.framework.convention.ModelCallType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 模型请求 dump 写入器：每次调用单独写一个文件，按调用类型分子目录，文件名用请求时间。
 * 请求发出前先落一版（response 为 pending），拿到返回后由 {@link ModelRequestDump} 补上 response 整体重写。
 * 内容整体是缩进好的 JSON（请求/响应体能解析成 JSON 时原样嵌入，否则按字符串保存），VS Code 打开即可折叠阅读。
 * 写文件失败一律静默吞掉——dump 是旁路调试能力，绝不允许影响模型调用主链路。
 */
public class ModelRequestDumpWriter {

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    private static final DateTimeFormatter FILE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss-SSS");

    /**
     * 同一毫秒内同类型并发请求时文件名追加序号，序号上限只是防御死循环
     */
    private static final int MAX_NAME_ATTEMPTS = 100;

    private static final Gson PRETTY_GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private final Path baseDir;

    public ModelRequestDumpWriter(String dir) {
        this.baseDir = Paths.get(dir);
    }

    /**
     * 请求发出前调用：落盘请求部分，返回用于补写响应的句柄；任何失败都返回空操作句柄，不抛异常
     *
     * @param callType 业务调用类型，决定子目录；null 落到 untagged
     * @param source   请求来源标识（agentscope / agentscope-stream / okhttp）
     * @param method   HTTP 方法
     * @param url      完整请求地址
     * @param headers  请求头（已展开为单值键值对，同名头逗号合并）
     * @param body     请求体原文；null 视为无请求体
     */
    public ModelRequestDump begin(ModelCallType callType, String source, String method, String url,
                                  Map<String, String> headers, String body) {
        try {
            ModelCallType type = callType == null ? ModelCallType.UNTAGGED : callType;
            LocalDateTime now = LocalDateTime.now();

            JsonObject record = new JsonObject();
            record.addProperty("time", now.format(ISO_FORMATTER));
            record.addProperty("callType", type.dir());
            record.addProperty("callTypeDesc", type.description());
            record.addProperty("source", source);
            record.addProperty("method", method);
            record.addProperty("url", url);
            JsonObject headerJson = new JsonObject();
            if (headers != null) {
                headers.forEach(headerJson::addProperty);
            }
            record.add("headers", headerJson);
            record.add("body", parseJsonOrString(body));
            JsonObject pending = new JsonObject();
            pending.addProperty("state", "pending");
            record.add("response", pending);

            Path dir = baseDir.resolve(type.dir());
            Files.createDirectories(dir);
            Path file = createNewFile(dir, now.format(FILE_FORMATTER), toPrettyJson(record));
            return file == null ? ModelRequestDump.NOOP : new ModelRequestDump(this, file, record);
        } catch (Exception ignored) {
            // 旁路 dump 失败不打断请求
            return ModelRequestDump.NOOP;
        }
    }

    /**
     * 补写响应后整体覆盖原文件
     */
    void rewrite(Path file, JsonObject record) {
        try {
            Files.writeString(file, toPrettyJson(record), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (Exception ignored) {
            // 旁路 dump 失败不打断请求
        }
    }

    /**
     * 能解析成 JSON 的原样嵌入（保留结构、缩进展示），否则按字符串保存；空串与 null 记为 null
     */
    static JsonElement parseJsonOrString(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return JsonParser.parseString(text);
        } catch (Exception e) {
            return PRETTY_GSON.toJsonTree(text);
        }
    }

    private static String toPrettyJson(JsonObject record) {
        return PRETTY_GSON.toJson(record) + "\n";
    }

    private static Path createNewFile(Path dir, String baseName, String content) throws IOException {
        for (int seq = 1; seq <= MAX_NAME_ATTEMPTS; seq++) {
            Path file = dir.resolve(seq == 1 ? baseName + ".json" : baseName + "-" + seq + ".json");
            try {
                Files.writeString(file, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return file;
            } catch (FileAlreadyExistsException ignored) {
                // 同名文件已存在，换下一个序号
            }
        }
        return null;
    }
}
