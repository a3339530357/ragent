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

package com.nageoffer.ai.ragent.framework.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** 发布后只读的配置及按版本隔离的派生对象；在途任务持有快照，不修改旧版本。 */
public final class RuntimeConfigSnapshot {
    private final long version;
    private final ObjectNode document;
    private final Map<Class<?>, Object> sections;
    private final Map<String, Object> resources = new ConcurrentHashMap<>();

    public RuntimeConfigSnapshot(
            long version, ObjectNode document, Map<Class<?>, Object> sections) {
        this.version = version;
        this.document = document.deepCopy();
        this.sections = Map.copyOf(sections);
    }

    public RuntimeConfigSnapshot withVersion(long id) {
        RuntimeConfigSnapshot copy = new RuntimeConfigSnapshot(id, document, sections);
        copy.resources.putAll(resources);
        return copy;
    }

    public long version() {
        return version;
    }

    public ObjectNode document() {
        return document.deepCopy();
    }

    public <T> T section(Class<T> type) {
        return type.cast(sections.get(type));
    }

    @SuppressWarnings("unchecked")
    public <T> T resource(String key, Supplier<T> factory) {
        return (T) resources.computeIfAbsent(key, ignored -> factory.get());
    }
}
