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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 检索配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.search")
public class SearchChannelProperties {

    /**
     * 默认返回的 TopK
     */
    private int defaultTopK = 10;

    /**
     * 检索通道配置
     */
    private Channels channels = new Channels();

    /**
     * 全局检索过滤配置
     */
    private Filters filters = new Filters();

    /**
     * 多通道融合配置
     */
    private Fusion fusion = new Fusion();

    /**
     * Rerank 后处理配置
     */
    private Rerank rerank = new Rerank();

    /**
     * Lost in the Middle 位置重排配置
     */
    private LostInTheMiddle lostInTheMiddle = new LostInTheMiddle();

    @Data
    public static class Channels {

        /**
         * 向量全局检索配置
         */
        private VectorGlobal vectorGlobal = new VectorGlobal();

        /**
         * 意图定向检索配置
         */
        private IntentDirected intentDirected = new IntentDirected();

        /**
         * 关键词/BM25 检索配置
         */
        private KeywordEs keywordEs = new KeywordEs();
    }

    @Data
    public static class VectorGlobal {

        /**
         * 是否启用
         */
        private boolean enabled = true;

        /**
         * 意图置信度阈值
         * 当意图识别的最高分数低于此阈值时，启用全局检索
         */
        private double confidenceThreshold = 0.6;

        /**
         * 单意图补充检索阈值
         * 当仅识别出一个意图且分数低于此阈值时，启用全局检索作为安全网
         */
        private double singleIntentSupplementThreshold = 0.8;

        /**
         * TopK 倍数
         * 全局检索时召回更多候选，后续通过 Rerank 筛选
         */
        private int topKMultiplier = 3;

        /**
         * 全局检索最低相似度分数
         * 低于该分数的 Chunk 会被丢弃，避免无关问题硬召回文档
         */
        private double minScore = 0.55;
    }

    @Data
    public static class IntentDirected {

        /**
         * 是否启用
         */
        private boolean enabled = true;

        /**
         * 最低意图分数
         * 低于此分数的意图节点会被过滤
         */
        private double minIntentScore = 0.4;

        /**
         * TopK 倍数
         */
        private int topKMultiplier = 2;
    }

    @Data
    public static class KeywordEs {

        /**
         * 是否启用
         */
        private boolean enabled = true;

        /**
         * TopK 倍数。关键词召回会先多取候选，再交给 Fusion/Rerank。
         */
        private int topKMultiplier = 3;

        /**
         * 关键词检索最低分数。
         */
        private double minScore = 0.05;

        /**
         * PostgreSQL 全文检索配置，默认 simple，适合代码、错误码、接口名等精确词。
         */
        private String textSearchConfig = "simple";

        /**
         * 从 query 中抽取的精确匹配词最大数量。
         */
        private int maxQueryTerms = 8;

        /**
         * 整句短语命中的加分。
         */
        private double exactPhraseBoost = 0.35;

        /**
         * 文档标题/名称命中的加分。
         */
        private double titleBoost = 0.25;

        /**
         * 单个 query term 命中的加分。
         */
        private double termBoost = 0.08;
    }

    @Data
    public static class Filters {

        /**
         * 是否启用全局过滤上下文。
         */
        private boolean enabled = true;

        /**
         * 仅召回启用状态的知识库文档和 Chunk。
         */
        private boolean enabledOnly = true;

        /**
         * 仅召回入库成功的文档。
         */
        private boolean successStatusOnly = true;

        /**
         * 是否按创建人隔离知识库/文档。默认关闭，避免破坏现有无权限模型部署。
         */
        private boolean userOwnedOnly = false;

        /**
         * 管理员是否绕过 userOwnedOnly。
         */
        private boolean adminBypassUserOwned = true;

        /**
         * 固定允许检索的知识库 ID。为空表示不限制。
         */
        private List<String> allowedKbIds = List.of();

        /**
         * 固定允许检索的文档 ID。为空表示不限制。
         */
        private List<String> allowedDocIds = List.of();

        /**
         * 只召回最近 N 天更新的文档；<=0 表示不限制。
         */
        private int recentDays = 0;
    }

    @Data
    public static class Fusion {

        /**
         * 是否启用 RRF 融合。
         */
        private boolean enabled = true;

        /**
         * Reciprocal Rank Fusion 参数 k，值越大越平滑。
         */
        private int rrfK = 60;

        /**
         * Rerank 前最多保留多少融合候选；<=0 表示不截断。
         */
        private int maxCandidatesBeforeRerank = 50;

        /**
         * 通道权重，key 支持 intent-directed / keyword-es / vector-global / hybrid。
         */
        private Map<String, Double> channelWeights = new HashMap<>();
    }

    @Data
    public static class Rerank {

        /**
         * Rerank 后最低相关性分数
         * 低于该分数的 Chunk 会被丢弃；<= 0 表示不启用过滤
         */
        private double minScore = 0.2;
    }

    @Data
    public static class LostInTheMiddle {

        /**
         * 是否启用 Lost in the Middle 位置重排。
         * <p>
         * LLM 对长上下文「中间段」的注意力较弱（Liu et al. 2023），将最相关的片段
         * 交替排在头/尾、最不相关的片段放中间，可在零模型成本下缓解该问题。
         */
        private boolean enabled = true;

        /**
         * 触发重排的最小 Chunk 数量。
         * <p>
         * 候选片段过少时不存在「中间被忽略」的问题，低于该阈值直接保留原相关性顺序。
         */
        private int minChunks = 4;
    }
}
