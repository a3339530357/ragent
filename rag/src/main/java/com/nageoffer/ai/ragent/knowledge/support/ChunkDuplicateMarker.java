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

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 统一标记重复片段，不过滤关系库或向量库的写入。 */
public final class ChunkDuplicateMarker {

    private ChunkDuplicateMarker() {
    }

    public static void mark(KnowledgeChunkMapper mapper, List<KnowledgeChunkDO> rows) {
        Map<String, List<KnowledgeChunkDO>> byKb = rows.stream()
                .collect(Collectors.groupingBy(KnowledgeChunkDO::getKbId));
        byKb.forEach((kbId, group) -> {
            List<String> hashes = group.stream().map(KnowledgeChunkDO::getContentHash)
                    .filter(Objects::nonNull).distinct().toList();
            List<String> ownIds = group.stream().map(KnowledgeChunkDO::getId)
                    .filter(Objects::nonNull).toList();
            Map<String, String> firstIds = new HashMap<>();
            // 分批查询避免大文档产生过长的 IN 参数；同一哈希只会落入一个查询批次
            for (int from = 0; from < hashes.size(); from += 500) {
                List<KnowledgeChunkDO> existing = mapper.selectList(Wrappers.lambdaQuery(KnowledgeChunkDO.class)
                        .select(KnowledgeChunkDO::getId, KnowledgeChunkDO::getContentHash)
                        .eq(KnowledgeChunkDO::getKbId, kbId)
                        .eq(KnowledgeChunkDO::getDeleted, 0)
                        .in(KnowledgeChunkDO::getContentHash, hashes.subList(from, Math.min(from + 500, hashes.size())))
                        .notIn(!ownIds.isEmpty(), KnowledgeChunkDO::getId, ownIds)
                        .orderByAsc(KnowledgeChunkDO::getCreateTime, KnowledgeChunkDO::getId));
                existing.forEach(row -> firstIds.putIfAbsent(row.getContentHash(), row.getId()));
            }
            for (KnowledgeChunkDO row : group) {
                row.setDuplicateOf(firstIds.get(row.getContentHash()));
                if (row.getContentHash() != null && row.getId() != null) {
                    firstIds.putIfAbsent(row.getContentHash(), row.getId());
                }
            }
        });
    }
}
