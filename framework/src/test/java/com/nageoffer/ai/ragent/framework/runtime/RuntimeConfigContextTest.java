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

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.ttl.threadpool.TtlExecutors;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.*;

import java.util.Map;
import java.util.concurrent.*;

class RuntimeConfigContextTest {
    record Settings(String model) {}

    @AfterEach
    void clear() {
        RuntimeConfigContext.activate(null);
        RuntimeConfigContext.setLocal(null);
    }

    private RuntimeConfigSnapshot version(long id) {
        return new RuntimeConfigSnapshot(
                id,
                new ObjectMapper().createObjectNode(),
                Map.of(Settings.class, new Settings("m" + id)));
    }

    @Test
    void asyncTasksKeepCapturedVersionAndThreadsAreCleaned() throws Exception {
        var one = version(1);
        var two = version(2);
        RuntimeConfigContext.activate(one);
        ExecutorService raw = Executors.newSingleThreadExecutor();
        ExecutorService ttl = TtlExecutors.getTtlExecutorService(raw);
        CountDownLatch ready = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            Future<Long> task;
            try (var ignored = RuntimeConfigContext.use(one)) {
                task =
                        ttl.submit(
                                () -> {
                                    ready.countDown();
                                    release.await();
                                    return RuntimeConfigContext.current().version();
                                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            RuntimeConfigContext.activate(two);
            release.countDown();
            assertEquals(1, task.get(5, TimeUnit.SECONDS));
            assertNull(raw.submit(RuntimeConfigContext::local).get());
            assertEquals(2, ttl.submit(() -> RuntimeConfigContext.current().version()).get());
        } finally {
            release.countDown();
            raw.shutdownNow();
        }
    }

    @Test
    void explicitlyWrappedDelayedWorkDoesNotReadNewConfiguration() {
        RuntimeConfigContext.activate(version(1));
        long[] observed = {0};
        Runnable task =
                RuntimeConfigContext.wrap(
                        () -> observed[0] = RuntimeConfigContext.current().version());
        RuntimeConfigContext.activate(version(2));
        task.run();
        assertEquals(1, observed[0]);
        assertNull(RuntimeConfigContext.local());
    }
}
