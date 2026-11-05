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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.framework.exception.ClientException;

import java.util.ArrayList;
import java.util.List;

/** 密钥字段在线路上始终是动作对象；客户端不能把脱敏占位符误存为真实密钥。 */
public final class RuntimeConfigSecrets {
    private RuntimeConfigSecrets() {}

    public static ObjectNode mask(ObjectNode document) {
        ObjectNode copy = document.deepCopy();
        visit(copy, null, true);
        return copy;
    }

    public static ObjectNode resolve(ObjectNode input, ObjectNode previous) {
        ObjectNode copy = input.deepCopy();
        visit(copy, previous, false);
        return copy;
    }

    private static void visit(JsonNode node, JsonNode old, boolean mask) {
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++)
                visit(node.get(i), old != null ? old.path(i) : null, mask);
        } else if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode value = object.get(name);
                JsonNode previous = old == null ? null : old.get(name);
                if (name.equals("apiKey")) {
                    if (mask) {
                        ObjectNode action = object.objectNode();
                        action.put("action", "keep");
                        action.put("configured", value.isTextual() && !value.asText().isBlank());
                        object.set(name, action);
                    } else {
                        if (!value.isObject())
                            throw new ClientException("密钥必须指定 keep、replace 或 clear 动作");
                        switch (value.path("action").asText()) {
                            case "keep" ->
                                    object.put(
                                            name,
                                            previous == null || previous.isNull()
                                                    ? ""
                                                    : previous.asText());
                            case "clear" -> object.put(name, "");
                            case "replace" -> {
                                if (!value.path("value").isTextual()
                                        || value.path("value").asText().isBlank())
                                    throw new ClientException("替换密钥不能为空");
                                object.put(name, value.path("value").asText());
                            }
                            default -> throw new ClientException("未知密钥操作");
                        }
                    }
                } else visit(value, previous, mask);
            }
        }
    }
}
