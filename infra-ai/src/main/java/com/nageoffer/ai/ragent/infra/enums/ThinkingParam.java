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

package com.nageoffer.ai.ragent.infra.enums;

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;

import java.util.Map;

/**
 * 思考开关的请求参数方言
 * <p>
 * 各家「关思考 / 调强度」的字段互不兼容：发错字段轻则被静默忽略（模型照样思考、白等一段），
 * 重则被判为未知参数直接 400，故由 {@code ai.providers.<name>.thinking-param} 按供应商显式声明
 */
public enum ThinkingParam {

    /**
     * OpenAI 标准 reasoning_effort：none 关闭，low / medium / high 分档
     * <p>
     * new-api 这类聚合网关只认这一种写法，会把它换算成上游各家的思考开关与预算；
     * 经网关时 thinking 对象、enable_thinking 都会在协议转换中被丢掉
     */
    REASONING_EFFORT,

    /**
     * DashScope 系 enable_thinking 布尔：只有开关，没有强度
     */
    ENABLE_THINKING,

    /**
     * DeepSeek / 智谱官方 thinking 对象 {"type": "enabled|disabled"}：只有开关，没有强度
     */
    THINKING_OBJECT;

    /**
     * 解析供应商的思考方言：显式声明优先，未声明时按专用客户端的既有方言兜底，其余不发任何思考字段
     */
    public static ThinkingParam resolve(String providerId, AIModelProperties.ProviderConfig provider) {
        if (provider != null && provider.getThinkingParam() != null) {
            return provider.getThinkingParam();
        }
        if (ModelProvider.DEEP_SEEK.matches(providerId)) {
            return THINKING_OBJECT;
        }
        if (ModelProvider.BAI_LIAN.matches(providerId) || ModelProvider.SILICON_FLOW.matches(providerId)) {
            return ENABLE_THINKING;
        }
        return null;
    }

    /**
     * 生成表达该档位的请求体字段
     *
     * @param effort 思考档位；null 表示「开，但不指定强度」，reasoning_effort 方言此时不发字段、交给模型默认
     */
    public Map<String, Object> bodyParams(ThinkingEffort effort) {
        boolean enabled = effort != ThinkingEffort.OFF;
        return switch (this) {
            case REASONING_EFFORT -> effort == null ? Map.of() : Map.of("reasoning_effort", effort.getWireValue());
            case ENABLE_THINKING -> Map.of("enable_thinking", enabled);
            case THINKING_OBJECT -> Map.of("thinking", Map.of("type", enabled ? "enabled" : "disabled"));
        };
    }
}
