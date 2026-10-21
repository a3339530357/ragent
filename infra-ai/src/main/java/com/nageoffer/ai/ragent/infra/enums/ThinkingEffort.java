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

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 思考强度档位：关 / 低 / 中 / 高
 * <p>
 * 取值即 OpenAI 标准 reasoning_effort 的线上值；只认开关的方言把 LOW~HIGH 一律当「开」
 */
@Getter
@RequiredArgsConstructor
public enum ThinkingEffort {

    OFF("none"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    /**
     * reasoning_effort 字段的线上取值
     */
    private final String wireValue;
}
