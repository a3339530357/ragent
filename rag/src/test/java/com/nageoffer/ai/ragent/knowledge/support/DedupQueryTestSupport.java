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

package com.nageoffer.ai.ragent.knowledge.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

public final class DedupQueryTestSupport {
    private DedupQueryTestSupport() {
    }

    public static void init(Class<?> entity) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
    }

    public static Object equalValue(AbstractWrapper<?, ?, ?> query, String column) {
        var matcher = Pattern.compile("\\b" + column + " = #\\{ew.paramNameValuePairs.([^}]+)}")
                .matcher(query.getSqlSegment());
        assertTrue(matcher.find(), "查询必须限制 " + column + "：" + query.getSqlSegment());
        return query.getParamNameValuePairs().get(matcher.group(1));
    }
}
