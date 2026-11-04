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

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelStreamAssemblerTest {

    @Test
    void emptyNameInLaterFragmentsDoesNotOverwriteToolName() {
        List<String> lines = List.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"query_order\",\"arguments\":\"\"},\"index\":0}]}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"function\":{\"name\":\"\",\"arguments\":\"{\\\"orderNo\\\": \"},\"index\":0}]}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"function\":{\"name\":\"\",\"arguments\":\"\\\"88234\\\"}\"},\"index\":0}]}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                "data: [DONE]");

        JsonObject call = ModelStreamAssembler.assemble(lines).json()
                .getAsJsonArray("toolCalls").get(0).getAsJsonObject();

        assertEquals("call_1", call.get("id").getAsString());
        assertEquals("query_order", call.get("name").getAsString());
        assertEquals("88234", call.getAsJsonObject("arguments").get("orderNo").getAsString());
    }
}
