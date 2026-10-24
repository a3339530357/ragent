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

package com.nageoffer.ai.ragent.infra.config;

import com.nageoffer.ai.ragent.infra.debug.ModelRequestDumpProperties;
import com.nageoffer.ai.ragent.infra.debug.ModelRequestDumpWriter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模型请求 dump 装配。
 * 写入器无条件注册（构造只建目录、不产生 IO），是否真正落盘由
 * {@link ModelRequestDumpProperties#isEnabled()} 在各挂载点判断——
 * 这样关闭开关时消费方注入不受影响。
 */
@Configuration
@EnableConfigurationProperties(ModelRequestDumpProperties.class)
public class ModelRequestDumpConfiguration {

    @Bean
    public ModelRequestDumpWriter modelRequestDumpWriter(ModelRequestDumpProperties properties) {
        return new ModelRequestDumpWriter(properties.getFile());
    }
}
