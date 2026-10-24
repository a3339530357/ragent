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

package com.nageoffer.ai.ragent.rag.config;

import com.nageoffer.ai.ragent.infra.debug.ModelRequestDumpInterceptor;
import com.nageoffer.ai.ragent.infra.debug.ModelRequestDumpProperties;
import com.nageoffer.ai.ragent.infra.debug.ModelRequestDumpWriter;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * HTTP 客户端配置类
 */
@Configuration
public class HttpClientConfig {

    private final ModelRequestDumpProperties requestDumpProperties;

    private final ModelRequestDumpWriter requestDumpWriter;

    public HttpClientConfig(ModelRequestDumpProperties requestDumpProperties,
                            ModelRequestDumpWriter requestDumpWriter) {
        this.requestDumpProperties = requestDumpProperties;
        this.requestDumpWriter = requestDumpWriter;
    }

    /**
     * 流式 HTTP 客户端（Primary）
     */
    @Bean
    @Primary
    public OkHttpClient streamingHttpClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(60))
                .readTimeout(Duration.ZERO)
                .callTimeout(Duration.ZERO)
                .retryOnConnectionFailure(true);
        if (requestDumpProperties.isEnabled()) {
            builder.addInterceptor(new ModelRequestDumpInterceptor(requestDumpWriter));
        }
        return builder.build();
    }

    /**
     * 同步 HTTP 客户端
     */
    @Bean
    public OkHttpClient syncHttpClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .writeTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(30))
                .callTimeout(Duration.ofSeconds(45))
                .retryOnConnectionFailure(true);
        if (requestDumpProperties.isEnabled()) {
            builder.addInterceptor(new ModelRequestDumpInterceptor(requestDumpWriter));
        }
        return builder.build();
    }
}
