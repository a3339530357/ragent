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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型请求全量 dump 配置：开启后，Agent 引擎（AgentScope）与自研 OkHttp 客户端发出的每一次模型请求
 * 都会把完整请求头与请求体落盘，用于排查鉴权、参数拼装等问题。
 */
@Data
@ConfigurationProperties(prefix = "ai.request-dump")
public class ModelRequestDumpProperties {

    /**
     * 总开关；注意 dump 内容包含 Authorization 请求头（API Key 明文），仅限本机调试使用
     */
    private boolean enabled = false;

    /**
     * dump 落盘文件路径（相对后端进程工作目录）
     */
    private String file = "logs/model-request-dump.log";
}
