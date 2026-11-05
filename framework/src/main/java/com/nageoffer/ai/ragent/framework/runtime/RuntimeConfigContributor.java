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

import com.fasterxml.jackson.databind.JsonNode;

/** 各模块提供配置类型和校验，不让配置存储模块反向依赖业务模块。 */
public interface RuntimeConfigContributor {
    String key();

    /** 配置数据的声明类型，导出时以此限定字段，不使用 Spring 代理的运行时类型。 */
    Class<?> type();

    Object defaults();

    default void validate(RuntimeConfigSnapshot candidate, JsonNode baseline) {}

    /** 只构造本地对象，不调用外部模型。 */
    default void prepare(RuntimeConfigSnapshot candidate) {}
}
