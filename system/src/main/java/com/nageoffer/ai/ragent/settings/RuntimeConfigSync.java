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

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

/** 通知只携带版本号；轮询负责补偿漏通知，实例状态有有效期。 */
@Component
@RequiredArgsConstructor
@Slf4j
@MapperScan(basePackageClasses = RuntimeConfigMapper.class, annotationClass = Mapper.class)
public class RuntimeConfigSync {
    private static final String TOPIC = "ragent:runtime-config:published";
    private static final String INSTANCES = "ragent:runtime-config:instances";
    private final RuntimeConfigService service;
    private final RedissonClient redis;
    private final ObjectMapper json;
    private final String instanceId = UUID.randomUUID().toString();
    private volatile boolean ready;
    private Integer listenerId;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ready = true;
        try {
            listenerId =
                    redis.getTopic(TOPIC).addListener(Long.class, (channel, version) -> tick());
        } catch (Exception e) {
            log.warn("配置通知订阅失败，将使用版本轮询补偿");
        }
        tick();
    }

    public void announce(long version) {
        try {
            redis.getTopic(TOPIC).publish(version);
        } catch (Exception e) {
            log.warn("配置版本通知发送失败，将由各实例轮询同步 version={}", version);
        }
        heartbeat();
    }

    @Scheduled(fixedDelay = 5000)
    public void tick() {
        if (!ready) return;
        service.refresh();
        heartbeat();
    }

    private void heartbeat() {
        try {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("instanceId", instanceId);
            state.put("appliedVersion", service.appliedVersion());
            state.put("targetVersion", service.targetVersion());
            state.put("error", service.applyError());
            state.put("updatedAt", System.currentTimeMillis());
            redis.<String, String>getMapCache(INSTANCES, StringCodec.INSTANCE)
                    .put(instanceId, json.writeValueAsString(state), 30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("配置实例心跳暂时不可用");
        }
    }

    public List<Map<String, Object>> instances() {
        try {
            List<Map<String, Object>> result = new ArrayList<>();
            for (String value :
                    redis.<String, String>getMapCache(INSTANCES, StringCodec.INSTANCE)
                            .readAllValues()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> row = json.readValue(value, Map.class);
                result.add(row);
            }
            return result;
        } catch (Exception e) {
            Map<String, Object> local = new LinkedHashMap<>();
            local.put("instanceId", instanceId);
            local.put("appliedVersion", service.appliedVersion());
            local.put("targetVersion", service.targetVersion());
            local.put("error", "实例状态同步不可用，仅显示本机");
            return List.of(local);
        }
    }

    @PreDestroy
    public void stop() {
        ready = false;
        try {
            if (listenerId != null) redis.getTopic(TOPIC).removeListener(listenerId);
            redis.getMapCache(INSTANCES, StringCodec.INSTANCE).remove(instanceId);
        } catch (Exception ignored) {
        }
    }
}
