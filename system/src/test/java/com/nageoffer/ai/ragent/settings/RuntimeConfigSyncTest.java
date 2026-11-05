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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.redisson.api.*;
import org.redisson.api.listener.MessageListener;
import org.redisson.client.codec.StringCodec;

import java.util.concurrent.TimeUnit;

class RuntimeConfigSyncTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void notificationAndPollingBothRefreshAndHeartbeatContainsNoConfig() throws Exception {
        RuntimeConfigService service = mock(RuntimeConfigService.class);
        RedissonClient redis = mock(RedissonClient.class);
        RTopic topic = mock(RTopic.class);
        RMapCache<String, String> status = mock(RMapCache.class);
        when(redis.getTopic(anyString())).thenReturn(topic);
        when(redis.getMapCache(anyString(), eq(StringCodec.INSTANCE)))
                .thenReturn((RMapCache) status);
        when(service.appliedVersion()).thenReturn(3L);
        when(service.targetVersion()).thenReturn(4L);
        var listener = org.mockito.ArgumentCaptor.forClass(MessageListener.class);
        var sync = new RuntimeConfigSync(service, redis, new ObjectMapper());
        sync.start();
        verify(topic).addListener(eq(Long.class), listener.capture());
        clearInvocations(service);
        listener.getValue().onMessage("topic", 4L);
        verify(service).refresh();
        clearInvocations(service);
        sync.tick();
        verify(service).refresh();
        var payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(status, atLeastOnce())
                .put(anyString(), payload.capture(), eq(30L), eq(TimeUnit.SECONDS));
        assertTrue(payload.getValue().contains("appliedVersion"));
        assertFalse(payload.getValue().contains("apiKey"));
        sync.announce(4L);
        verify(topic).publish(4L);
    }

    @Test
    void redisFailureDoesNotUndoCommittedPublication() {
        var service = mock(RuntimeConfigService.class);
        var redis = mock(RedissonClient.class);
        when(redis.getTopic(anyString())).thenThrow(new IllegalStateException("offline"));
        var sync = new RuntimeConfigSync(service, redis, new ObjectMapper());
        assertDoesNotThrow(() -> sync.announce(5));
        assertEquals(1, sync.instances().size());
    }
}
