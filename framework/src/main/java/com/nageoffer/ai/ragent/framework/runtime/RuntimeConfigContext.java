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

import com.alibaba.ttl.TransmittableThreadLocal;

import java.util.concurrent.atomic.AtomicReference;

/** 线程池使用 TTL 传递；响应式入口同时将快照写入 Reactor Context。 */
public final class RuntimeConfigContext {
    public static final String KEY = "ragent.runtime.config";
    private static final AtomicReference<RuntimeConfigSnapshot> ACTIVE = new AtomicReference<>();
    private static final TransmittableThreadLocal<RuntimeConfigSnapshot> LOCAL =
            new TransmittableThreadLocal<>() {
                @Override
                protected RuntimeConfigSnapshot childValue(RuntimeConfigSnapshot parentValue) {
                    // 新建工作线程不能永久继承创建时的请求；只允许任务包装器临时传递。
                    return null;
                }
            };

    private RuntimeConfigContext() {}

    public static RuntimeConfigSnapshot current() {
        RuntimeConfigSnapshot local = LOCAL.get();
        return local == null ? ACTIVE.get() : local;
    }

    public static RuntimeConfigSnapshot active() {
        return ACTIVE.get();
    }

    public static RuntimeConfigSnapshot local() {
        return LOCAL.get();
    }

    public static void activate(RuntimeConfigSnapshot snapshot) {
        ACTIVE.set(snapshot);
    }

    public static <T> T section(Class<T> type) {
        RuntimeConfigSnapshot snapshot = current();
        return snapshot == null ? null : snapshot.section(type);
    }

    public static void setLocal(RuntimeConfigSnapshot snapshot) {
        if (snapshot == null) LOCAL.remove();
        else LOCAL.set(snapshot);
    }

    public static Scope use(RuntimeConfigSnapshot snapshot) {
        RuntimeConfigSnapshot previous = LOCAL.get();
        setLocal(snapshot);
        return () -> setLocal(previous);
    }

    public static Runnable wrap(Runnable task) {
        RuntimeConfigSnapshot snapshot = current();
        return () -> {
            try (Scope ignored = use(snapshot)) {
                task.run();
            }
        };
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
