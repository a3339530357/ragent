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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 把 OpenAI 兼容协议的流式数据块拼回完整结果，只给 dump 看，不参与业务解析：
 * 正文（content）、思考（reasoning_content / reasoning）、工具调用（tool_calls 按 index 拼接参数）、
 * 结束原因与用量分开放，方便直接读出模型到底说了什么。
 */
final class ModelStreamAssembler {

    private static final String DATA_PREFIX = "data:";

    private static final String DONE_MARKER = "[DONE]";

    private ModelStreamAssembler() {
    }

    record Result(JsonObject json, boolean done) {
    }

    static Result assemble(List<String> lines) {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Map<Integer, JsonObject> toolCalls = new TreeMap<>();
        String finishReason = null;
        JsonElement usage = null;
        JsonElement error = null;
        boolean done = false;

        for (String rawLine : lines) {
            String data = toData(rawLine);
            if (data == null) {
                continue;
            }
            if (DONE_MARKER.equals(data)) {
                done = true;
                continue;
            }
            JsonObject chunk;
            try {
                chunk = JsonParser.parseString(data).getAsJsonObject();
            } catch (Exception e) {
                continue;
            }
            if (chunk.has("error") && !chunk.get("error").isJsonNull()) {
                error = chunk.get("error");
            }
            if (chunk.has("usage") && !chunk.get("usage").isJsonNull()) {
                usage = chunk.get("usage");
            }
            JsonArray choices = chunk.has("choices") && chunk.get("choices").isJsonArray()
                    ? chunk.getAsJsonArray("choices") : null;
            if (choices == null || choices.isEmpty() || !choices.get(0).isJsonObject()) {
                continue;
            }
            JsonObject choice = choices.get(0).getAsJsonObject();
            String reason = stringOf(choice, "finish_reason");
            if (reason != null) {
                finishReason = reason;
            }
            JsonObject delta = choice.has("delta") && choice.get("delta").isJsonObject()
                    ? choice.getAsJsonObject("delta") : null;
            if (delta == null) {
                continue;
            }
            appendIfPresent(content, delta, "content");
            appendIfPresent(reasoning, delta, "reasoning_content");
            appendIfPresent(reasoning, delta, "reasoning");
            if (delta.has("tool_calls") && delta.get("tool_calls").isJsonArray()) {
                mergeToolCalls(toolCalls, delta.getAsJsonArray("tool_calls"));
            }
        }

        JsonObject json = new JsonObject();
        json.addProperty("finishReason", finishReason);
        json.addProperty("reasoning", reasoning.isEmpty() ? null : reasoning.toString());
        json.addProperty("content", content.isEmpty() ? null : content.toString());
        if (!toolCalls.isEmpty()) {
            JsonArray calls = new JsonArray();
            toolCalls.values().forEach(call -> {
                // 参数是拼起来的 JSON 字符串，能解析就展开显示
                JsonElement args = ModelRequestDumpWriter.parseJsonOrString(stringOf(call, "arguments"));
                call.add("arguments", args);
                calls.add(call);
            });
            json.add("toolCalls", calls);
        }
        json.add("usage", usage);
        if (error != null) {
            json.add("error", error);
        }
        // 有结束原因也算正常结束：部分网关不发 [DONE]，或调用方读到结束原因后就关流
        return new Result(json, done || finishReason != null);
    }

    /**
     * 统一成数据块正文：SSE 的 data: 行去前缀，event:/id:/注释行跳过；本身就是数据块（无前缀）的原样返回
     */
    private static String toData(String rawLine) {
        if (rawLine == null) {
            return null;
        }
        String line = rawLine.strip();
        if (line.isEmpty() || line.startsWith(":") || line.startsWith("event:") || line.startsWith("id:")
                || line.startsWith("retry:")) {
            return null;
        }
        if (line.startsWith(DATA_PREFIX)) {
            line = line.substring(DATA_PREFIX.length()).strip();
        }
        return line.isEmpty() ? null : line;
    }

    private static void mergeToolCalls(Map<Integer, JsonObject> toolCalls, JsonArray fragments) {
        for (JsonElement element : fragments) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject fragment = element.getAsJsonObject();
            int index = fragment.has("index") ? fragment.get("index").getAsInt() : toolCalls.size();
            JsonObject call = toolCalls.computeIfAbsent(index, i -> {
                JsonObject created = new JsonObject();
                created.addProperty("id", (String) null);
                created.addProperty("name", (String) null);
                created.addProperty("arguments", "");
                return created;
            });
            String id = stringOf(fragment, "id");
            if (id != null) {
                call.addProperty("id", id);
            }
            JsonObject function = fragment.has("function") && fragment.get("function").isJsonObject()
                    ? fragment.getAsJsonObject("function") : null;
            if (function != null) {
                String name = stringOf(function, "name");
                if (name != null) {
                    call.addProperty("name", name);
                }
                String args = stringOf(function, "arguments");
                if (args != null) {
                    call.addProperty("arguments", stringOf(call, "arguments") + args);
                }
            }
        }
    }

    private static void appendIfPresent(StringBuilder target, JsonObject delta, String field) {
        String value = stringOf(delta, field);
        if (value != null) {
            target.append(value);
        }
    }

    private static String stringOf(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? null : value.getAsString();
    }
}
