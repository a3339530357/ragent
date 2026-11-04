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

package com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor;

import cn.hutool.crypto.SecureUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunkKey;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 内容去重键独立于片段身份键，归因与融合沿用先身份、再内容的分组顺序。 */
final class RetrievedChunkContentKey {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private RetrievedChunkContentKey() {
    }

    static String of(RetrievedChunk chunk) {
        String text = chunk.getText() == null ? "" : chunk.getText();
        return SecureUtil.sha256(WHITESPACE.matcher(text.trim()).replaceAll(" ").trim());
    }

    /** 同一身份在不同通道的文本可能不同，以首次出现的文本为准，与身份去重一致。 */
    static Map<String, String> index(List<SearchChannelResult> results) {
        Map<String, String> keys = new LinkedHashMap<>();
        if (results != null) {
            for (SearchChannelResult result : results) {
                if (result == null || result.getChunks() == null) {
                    continue;
                }
                for (RetrievedChunk chunk : result.getChunks()) {
                    keys.computeIfAbsent(RetrievedChunkKey.of(chunk), ignored -> of(chunk));
                }
            }
        }
        return keys;
    }
}
