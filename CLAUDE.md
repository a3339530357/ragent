# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Ragent 是一个 Agentic RAG (Retrieval-Augmented Generation) 智能问答系统，基于 Spring Boot 3.5 + React 18 构建。覆盖多路检索、意图识别、查询重写、会话记忆、模型路由容错、MCP 工具集成、文档入库 ETL 管道等能力。

## Build & Run Commands

### Backend (Java 17, Maven multi-module)

```bash
# 完整构建（含 Spotless 代码格式化）
./mvnw clean package

# 跳过测试构建
./mvnw clean package -DskipTests

# 只编译不打包
./mvnw compile

# 运行单个测试类
./mvnw test -pl bootstrap -Dtest=SomeTestClass

# 运行单个测试方法
./mvnw test -pl bootstrap -Dtest=SomeTestClass#someMethod

# 代码格式化（Spotless 在 compile 阶段绑定 apply 目标，编译时自动执行）
./mvnw spotless:apply

# 启动主应用（bootstrap 模块）
./mvnw spring-boot:run -pl bootstrap

# 启动 MCP Server（端口 9099，独立进程）
./mvnw spring-boot:run -pl mcp-server
```

注意：父 `pom.xml` 已强制为所有模块引入 `spring-boot-starter`、`spring-boot-starter-test`、`lombok`，子模块无需重复声明。Surefire 通过 `-javaagent:${org.mockito:mockito-core:jar}` 启用 Mockito agent，新增依赖时不要破坏 `argLine` 链。

### Frontend (React + Vite + TypeScript)

```bash
cd frontend
npm install
npm run dev        # 开发服务器 localhost:5173，自动代理 /api -> localhost:9090
npm run build      # 生产构建（vite build）
npm run preview    # 预览构建产物
npm run lint       # ESLint --max-warnings 0（任何 warning 都视为失败）
npm run format     # Prettier 格式化
```

前端 `package.json` 当前未声明 `test` 脚本，前端测试规范见 `frontend/TESTING.md`。

### Infrastructure (Docker Compose)

`resources/docker/` 实际目录清单：

```bash
# 开发环境一体化栈（PostgreSQL + Redis + RocketMQ + RustFS + Milvus）
docker compose -f resources/docker/dev-all-in-one.compose.yaml up -d

# 仅 Milvus 独立栈（2.6.6）
docker compose -f resources/docker/milvus-stack-2.6.6.compose.yaml up -d

# 仅 RocketMQ 独立栈（标准镜像 / AMD 镜像两套）
docker compose -f resources/docker/rocketmq-stack-5.2.0.compose.yaml up -d
docker compose -f resources/docker/rocketmq-stack-amd-5.2.0.compose.yaml up -d

# 低配机器轻量栈（带内存限制，2.5.8 / 2.6.6 两版本）
docker compose -f resources/docker/lightweight/milvus-stack-2.5.8.compose.yaml up -d
docker compose -f resources/docker/lightweight/milvus-stack-2.6.6.compose.yaml up -d
```

数据库 schema 与初始数据：`resources/database/schema_pg.sql`、`init_data_pg.sql`；版本升级脚本形如 `upgrade_v1.0_to_v1.1.sql`、`upgrade_v1.1_to_v1.2.sql`，需按版本顺序执行；旧 backup 在 `resources/database/backups/`。**当前正式后端是 PostgreSQL（README 中提到的 MySQL 已弃用）**。

## Architecture

### Maven 模块结构

```
ragent (parent pom, groupId=com.nageoffer.ai, artifactId=ragent, packaging=pom)
├── framework      # 公共基础框架：异常体系、分布式ID、幂等、缓存、RocketMQ、链路追踪、SSE 封装
├── infra-ai       # AI 基础设施：多模型提供商 Chat/Embedding/Rerank 客户端 + 优先级路由 + 熔断
├── bootstrap      # 主业务应用（Spring Boot 启动入口 + 全部业务代码），端口 9090，context-path /api/ragent
└── mcp-server     # 独立 MCP 工具服务器（端口 9099，JSON-RPC over HTTP）
```

### 根包名

`com.nageoffer.ai.ragent`

### bootstrap 业务模块（`com.nageoffer.ai.ragent.*`）

```
RagentApplication.java            # Spring Boot 启动类
admin/                            # 管理后台仪表盘（Dashboard）
core/                             # 通用文档解析（Markdown/Tika）+ 分块策略
ingestion/                        # 数据摄入 ETL 管道
knowledge/                        # 知识库 / 文档 / 分块 CRUD + S3 文件存储
rag/                              # RAG 对话核心
└── aop/ config/ constant/ controller/ core/ dao/ dto/ enums/ mq/ service/ util/
user/                             # Sa-Token 用户认证
```

`rag/core/` 内的关键子包（实现复杂度集中在这里）：

```
rag/core/
├── guidance/        # 置信度不足时的澄清引导
├── intent/          # 意图识别（IntentTreeFactory + IntentTreeCacheManager + DefaultIntentClassifier）
├── mcp/             # MCP 客户端调用封装
├── memory/          # 会话记忆加载 / 摘要压缩
├── prompt/          # Prompt 场景路由（PromptScene: KB_ONLY / MCP_ONLY / MIXED）
├── retrieve/        # MultiChannelRetrievalEngine + SearchChannel + SearchResultPostProcessor
├── rewrite/         # MultiQuestionRewriteService + QueryTermMappingService
├── vector/          # VectorStoreService 接口 + Pg / Milvus 双实现
├── citation/        # CitationService 引用标注
├── configbootstrap/ # 据已入库文档启发式生成「候选」术语映射 + 意图节点（config-bootstrap.st）
└── model/           # InternalChatModelSelector 内部模型选择
```

此外 `rag/eval/`（与 `rag/core/` 同级）提供单题调试接口 `/rag/eval`（`EvalController` + `EvalProperties` + `EvalResponse`），用于对单条问题观测检索/意图/答案，便于 Prompt 与模型调优。

### infra-ai 模块（根包 `com.nageoffer.ai.ragent.infra.*`，注意是 `infra` 而非 `infra.ai`）

```
infra/
├── chat/        # ChatClient 接口 + AbstractOpenAIStyleChatClient + 各 Provider 实现
├── embedding/   # EmbeddingClient 接口 + 各 Provider 实现
├── rerank/      # RerankClient 接口 + 各 Provider 实现
├── config/      # 候选清单装配、ai.providers / ai.chat / ai.embedding / ai.rerank
├── enums/       # ModelProvider 枚举（OLLAMA / BAI_LIAN / SILICON_FLOW / MOYU / NEW_API / NOOP）
├── http/        # OpenAIStyleSseParser、OkHttp 配置
├── model/       # 健康存储、选择器、SSE 探测
├── token/       # token 估算
└── util/
```

### framework 模块（`com.nageoffer.ai.ragent.framework.*`）

12 个横切关注点子包：

```
framework/
├── cache/         # 缓存抽象
├── config/        # 通用 Spring 配置
├── context/       # UserContext（基于 TTL，跨线程池透传）
├── convention/    # Result<T> + Results 工具 + 通用枚举
├── database/      # MyBatis-Plus 配置、CustomIdentifierGenerator、自动填充
├── distributedid/ # Snowflake：Lua 脚本原子分配 workerId/datacenterId（最多 1024 节点）
├── errorcode/     # IBaseErrorCode + 业务专用错误码
├── exception/     # AbstractException → ClientException(A) / ServiceException(B) / RemoteException(C)
├── idempotent/    # @IdempotentSubmit（Redisson + SpEL）+ @IdempotentConsume（Redis SET NX 状态机）
├── mq/            # MessageQueueProducer → RocketMQProducerAdapter + DelegatingTransactionListener
├── trace/         # @RagTraceRoot / @RagTraceNode + RagTraceAspect + Deque 栈
└── web/           # GlobalExceptionHandler、SseEmitterSender（AtomicBoolean CAS 防重复关闭）
```

### mcp-server 模块（`com.nageoffer.ai.ragent.mcp.*`）

独立 Spring Boot 进程，端口 9099，JSON-RPC 2.0 协议：

```
mcp/
├── MCPServerApplication.java
├── core/        # 业务核心
├── endpoint/    # HTTP 端点
├── executor/    # MCPToolExecutor 实现（每加一个工具就新增一个实现类）
└── protocol/    # JsonRpcRequest / JsonRpcResponse / JsonRpcError
```

### 配置与基础设施

- 后端端口 **9090**，context-path **`/api/ragent`**
- 前端端口 **5173**（Vite），`/api` 反向代理到 9090
- MCP Server 端口 **9099**
- 主配置 `bootstrap/src/main/resources/application.yaml`
- `rag.vector.type` 切换向量后端（`pg` 默认 / `milvus`）
- `ai.chat.candidates` 定义带 `priority` / `supports-thinking` 的多模型候选清单
- `ai.chat.default-model` 是常规问答首选，`ai.chat.deep-thinking-model` 是深度思考开关使用的模型
- `ai.selection.failure-threshold` / `open-duration-ms` 调熔断阈值与冷却窗口
- `ai.providers.{ollama|newapi|siliconflow|moyu}` 是各供应商 base url + apiKey + endpoints
- `rag.rate-limit.global` 开关全局并发限流（默认 max-concurrent=1，演示场景）
- `rag.memory.history-keep-turns` / `summary-start-turns` / `summary-enabled` 控制会话记忆窗口与摘要触发
- `rag.search.channels.{vector-global|intent-directed}` 调每个通道的置信度阈值与 top-k 倍率
- `rag.semaphore.document-upload` 限制文档上传并发
- `rag.pdf.ocr-strategy`（`NO_OCR` / `AUTO` / `OCR_ONLY`，默认 `AUTO`）+ `rag.pdf.ocr.languages`（默认 `chi_sim+eng`）控制扫描件 PDF 的 OCR
- `rag.knowledge.schedule.*` 定时增量刷新参数（DB 乐观锁 + 锁续期）
- `rag.mcp.servers` 注册 MCP Server 列表（默认指向 `http://localhost:9099`）
- `rag.trace.enabled` 关闭可在性能测试时绕过 AOP
- `HttpClientConfig`（`rag/config/`）有两个 OkHttpClient：`streamingHttpClient`(@Primary，read/call 超时=0，SSE 用) 与 `syncHttpClient`(read 30s / call 45s)。**重型同步 LLM 调用（如 config-bootstrap 的大 prompt）会撞 30s readTimeout**，需单独长超时客户端而非全局调大（避免影响 rerank/embedding）

> ⚠️ **PDF OCR 依赖宿主机安装 Tesseract**：`ocr-strategy=AUTO`/`OCR_ONLY` 遇图片型扫描件会调 Tesseract，未装时抛 `couldn't find an OCR parser to handle image/ocr-png` 并整篇入库失败。需 `apt install tesseract-ocr tesseract-ocr-chi-sim tesseract-ocr-eng`，装后**须重启后端**（Tika 会缓存探测结果）。

环境变量：`BAILIAN_API_KEY`（百炼）、`SILICONFLOW_API_KEY`（SiliconFlow）、`NEWAPI_API_KEY`（NewAPI）。MoYu 默认硬编码 key（仅演示）。

## Core Implementation Details

### RAG 对话管线（`bootstrap/rag/`）

入口：`RAGChatServiceImpl.streamChat()`，完整流程：

1. **记忆加载** — `ConversationMemoryService` 并行加载历史消息+摘要；摘要由 LLM 按 `conversation-summary.st` 压缩，超过 `rag.memory.summary-start-turns` 触发
2. **查询改写** — `MultiQuestionRewriteService`：先用 `QueryTermMappingService` 做术语归一化（DB 映射表，长词优先匹配，`QueryTermMappingCacheManager` 负责 Redis 缓存），再 LLM 按 `user-question-rewrite.st` 改写+拆分（带最近 2 轮历史做指代消解），失败兜底规则拆分
3. **意图识别** — `DefaultIntentClassifier`：`IntentTreeFactory` + `IntentTreeCacheManager` 从 DB/Redis 加载意图树（`IntentKind.KB` / `SYSTEM` / `MCP` 三种类型），LLM 低温度按 `intent-classifier.st` 分类返回 `[{id, score, reason}]`；`IntentResolver` 并行对多子问题识别，保底+竞争策略控总意图数
4. **多通道检索** — `MultiChannelRetrievalEngine`：`IntentDirectedSearchChannel`（定向，高优先级）+ `VectorGlobalSearchChannel`（全局兜底）；`AbstractParallelRetriever<T>` 模板方法封装通道内并行
5. **后处理链** — 责任链模式：`DeduplicationPostProcessor`（按通道优先级去重）→ `RerankPostProcessor`（语义重排）；实现 `SearchResultPostProcessor` 接口自动加入链
6. **Prompt 构建** — `RAGPromptService` 按 `PromptScene`（KB_ONLY / MCP_ONLY / MIXED）选模板；单意图时可走节点级 Prompt
7. **流式生成** — SSE 推送，`StreamCancellationHandles` + Redis Pub/Sub 实现跨实例取消

`ChatRateLimit` + `ChatQueueLimiter` 在入口层做并发限流：请求入 Redis ZSET 排队，Lua 脚本（`bootstrap/src/main/resources/lua/queue_claim_atomic.lua`）原子出队，Pub/Sub 广播唤醒其他实例，队列状态 SSE 推送给前端。

### Prompt 模板（`bootstrap/src/main/resources/prompt/`）

16 个 `.st`（Spring AI String Template）模板，定位在管线中各阶段（另有 `buckup/` 存放历史版本，非运行时使用）：

| 模板 | 用途 |
|------|------|
| `user-question-rewrite.st` | 多问题改写+拆分（带历史） |
| `intent-classifier.st` | LLM 意图分类 |
| `conversation-summary.st` | 历史轮次压缩成摘要 |
| `conversation-title.st` | 自动生成会话标题 |
| `guidance-prompt.st` | 置信度不足时澄清引导 |
| `guidance-ambiguity-check.st` | 候选意图分数歧义判定 |
| `mcp-parameter-extract.st` | MCP 工具调用参数提取（system 段） |
| `mcp-parameter-extract-user.st` | MCP 工具调用参数提取（user 段） |
| `context-format.st` | 检索片段拼装成 Prompt 上下文 |
| `answer-chat-kb.st` | 仅 KB 检索结果生成回答 |
| `answer-chat-mcp.st` | 仅 MCP 工具结果生成回答 |
| `answer-chat-mcp-kb-mixed.st` | KB + MCP 混合场景 |
| `answer-chat-system.st` | SYSTEM 类意图（FAQ / 系统指令） |
| `pdf-format-guard.st` | PDF 解析后的格式守卫 |
| `opening-reply.st` | 正式检索/回答前的低承诺开场承接句 |
| `config-bootstrap.st` | 据已入库分块生成「候选」术语映射与意图节点 |

### 模型路由（`infra-ai/`）

三层：Service 接口 → `RoutingXxxService`（@Primary） → 具体 `ChatClient` / `EmbeddingClient` / `RerankClient`

- **优先级调度**（`ModelSelector`）：过滤不可用 → priority 升序 → 首选模型置顶 → 熔断器过滤
- **三态熔断器**（`ModelHealthStore`）：CLOSED→OPEN→HALF_OPEN，连续失败 `ai.selection.failure-threshold` 次熔断 `open-duration-ms` 毫秒，半开仅放行 1 个探测请求，`ConcurrentHashMap.compute()` 保证状态转换原子
- **流式首包探测**：`ProbeStreamBridge` + `ProbeBufferingCallback`（装饰器模式缓冲事件，commit 后回放）；首包失败透明切换下一候选，用户端不会看到半截脏数据
- **提供商**（`ModelProvider` 枚举）：
  - `OLLAMA` — 本地（Chat + Embedding）
  - `BAI_LIAN` — 阿里云百炼（Chat + Rerank）
  - `SILICON_FLOW` — SiliconFlow / 智谱（Chat + Embedding）
  - `MOYU` — OpenAI 兼容代理（Chat）
  - `NEW_API` — OpenAI 兼容代理（Chat）
  - `NOOP` — 占位/测试兜底（Rerank `priority=100` 默认兜底位）
- **SSE 解析**：`OpenAIStyleSseParser` 被 `AbstractOpenAIStyleChatClient` 的子类共用（BaiLian、SiliconFlow、MoYu、NewApi）

### 知识库 & ETL（`knowledge/` + `ingestion/`）

三层数据模型：`KnowledgeBase` 1:N `KnowledgeDocument` 1:N `KnowledgeChunk`

- **双处理模式**：`ProcessMode.CHUNK`（直接分块入库）与 `ProcessMode.PIPELINE`（走 ETL 管道）
- **事务消息驱动**：RocketMQ 半消息保证"文档状态变更 + 任务投递"原子性，`KnowledgeDocumentChunkTransactionChecker` 回查 DB
- **原子持久化**：`persistChunksAndVectorsAtomically()` 在编程式事务中统一完成 DB 写入 + 向量库写入，任一失败整体回滚
- **ETL 引擎**（`IngestionEngine`）：6 种节点（Fetcher / Parser / Chunker / Enhancer / Enricher / Indexer），`nextNodeId` 链表拓扑 + 环检测，Spring IoC 自动扫描注册
- **条件执行**（`ConditionEvaluator`）：支持 SpEL 表达式 + JSON DSL（`all` / `any` / `not` + `eq` / `regex` / `contains` 等操作符）
- **Fetcher 策略族**：`S3Fetcher` / `HttpUrlFetcher` / `LocalFileFetcher`（按 `SourceType` 分派；其他 Feishu 等扩展见 `ingestion/strategy/fetcher/`）
- **`skipIndexerWrite` 双模式**：pipeline 独立运行时直接写向量库；被 knowledge 调用时跳过写入，由调用方在事务中统一持久化
- **增量刷新**：定时扫描 + DB 乐观锁 + 锁续期；变更检测走 ETag → Last-Modified → SHA-256 三级降级

### 向量存储（`rag/core/vector/`）

两种后端由 `rag.vector.type` 切换：

- `pg` — `PgVectorStoreService` + `PgVectorStoreAdmin`（基于 PostgreSQL + pgvector 扩展，**默认**）
- `milvus` — `MilvusVectorStoreService` + `MilvusVectorStoreAdmin`

统一接口：`VectorStoreService`（检索）、`VectorStoreAdmin`（Collection 创建/删除/索引管理）。`VectorSpaceId` + `VectorSpaceSpec` 封装集合命名与维度/度量类型。默认集合 `rag_default_store`，维度 `1024`，度量 `COSINE`（见 `rag.default.*`）。注意 `schema_pg.sql` 里静态表 `t_knowledge_vector` 的 `vector(1536)` 是模板/历史遗留，**实际向量集合由 `PgVectorStoreAdmin` 按 KB embedding 维度动态建表**（bge-m3 = 1024），排查向量问题时勿被 1536 误导。

### 基础框架（`framework/`）

- **三级异常体系**：`ClientException`(A) / `ServiceException`(B) / `RemoteException`(C) 继承 `AbstractException`，`GlobalExceptionHandler` 统一拦截转 `Result<T>`；`errorcode/` 内含 KB 模块等专用错误码
- **分布式 ID**：Redis Lua 脚本原子分配 workerId/datacenterId（最多 1024 节点轮转），注入 Hutool Snowflake 单例，MyBatis-Plus 用 `CustomIdentifierGenerator` 自动填充
- **幂等双方案**：`@IdempotentSubmit`（Redisson 分布式锁 + SpEL 动态 Key） + `@IdempotentConsume`（Redis SET NX 两阶段状态机）
- **链路追踪**：`@RagTraceRoot` / `@RagTraceNode` 注解声明式埋点，`RagTraceAspect` AOP 拦截，TransmittableThreadLocal 跨线程池传播，Deque 栈管理嵌套深度
- **用户上下文**：`UserContext` 基于 TTL，异步线程池安全透传
- **MQ 封装**：`MessageQueueProducer` → `RocketMQProducerAdapter`，`DelegatingTransactionListener` 全局单例按 topic 路由回查逻辑，`MessageWrapper<T>` 统一消息信封（keys + body + uuid + timestamp）
- **SSE**：`SseEmitterSender` 用 AtomicBoolean CAS 保证连接只关闭一次

### 线程池分层（`rag/config/ThreadPoolExecutorConfig`）

10 个按工作负载特征独立配置的线程池，均用 `TtlExecutors.getTtlExecutor(...)` 包装以透传用户上下文与 Trace。`SynchronousQueue` 配 `CallerRunsPolicy` 用于纯并行短任务，`LinkedBlockingQueue(200)` 配 `AbortPolicy` 用于流式长任务以避免拖垮调用方：

| Bean | 队列 / 拒绝策略 | 用途 |
|------|------|------|
| `chatEntryExecutor` | LBQ(200) / Abort | 对话入口调度 |
| `modelStreamExecutor` | LBQ(200) / Abort | 流式 Chat 异步执行 |
| `intentClassifyThreadPoolExecutor` | SyncQ / CallerRuns | 意图识别并行 |
| `ragRetrievalThreadPoolExecutor` | SyncQ / CallerRuns | 检索通道间并行 |
| `ragInnerRetrievalThreadPoolExecutor` | LBQ(100) / CallerRuns | 通道内多 Collection 并行 |
| `ragContextThreadPoolExecutor` | SyncQ / CallerRuns | 多子问题上下文构建 |
| `mcpBatchThreadPoolExecutor` | SyncQ / CallerRuns | MCP 工具批量并行调用 |
| `memoryLoadThreadPoolExecutor` | LBQ(200) / CallerRuns | 会话记忆并行加载 |
| `memorySummaryThreadPoolExecutor` | LBQ(200) / CallerRuns | 记忆摘要压缩（核心线程数 = 1） |
| `knowledgeChunkExecutor` | LBQ(200) / Abort | 文档分块异步处理 |

### 前端结构（`frontend/`）

React 18 + TypeScript + Vite + TailwindCSS + shadcn/ui (Radix) + Zustand + react-hook-form + react-markdown + recharts + react-virtuoso。

```
frontend/src/
├── pages/       # ChatPage（问答）、LoginPage、admin/*（仪表板/KB/意图树/入库/Trace/设置）
├── components/  # chat/、ui/（shadcn）、layout/、session/
├── services/    # axios API 调用层，统一前缀 /api/ragent
├── stores/      # Zustand stores（auth、chat、theme）
├── hooks/       # useAuth、useChat、useStreamResponse 等
└── router.tsx   # 路由配置
```

路径别名 `@` → `src/`。Sa-Token 鉴权 header 名 `Authorization`（见 `application.yaml` 的 `sa-token.token-name`）。

## Extension Points

新增扩展实现 Spring Bean 即可自动生效：

- **检索通道**：实现 `SearchChannel` 接口
- **后处理器**：实现 `SearchResultPostProcessor` 接口（按 `getOrder()` 串联）
- **MCP 工具**：bootstrap 侧实现 `MCPToolExecutor`（被 `DefaultMCPToolRegistry` 发现）；mcp-server 侧在 `mcp/executor/` 新增 `MCPToolExecutor` 实现
- **入库节点**：实现 `IngestionNode` 接口，配置 `nextNodeId` 加入 Pipeline
- **模型提供商**：在 `infra-ai/chat` / `embedding` / `rerank` 下实现对应 Client 接口，新增 `ModelProvider` 枚举，在 `application.yaml` 的候选列表注册

## Code Conventions

- 所有 Java 源文件必须包含 Apache 2.0 License 头（Spotless 在 `compile` 阶段自动 apply，模板在 `resources/format/copyright.txt`）
- Lombok：`@Data` / `@Builder` / `@RequiredArgsConstructor`，配置见 `lombok.config`（已设 `config.stopBubbling=true`、`equalsAndHashCode.callSuper=skip`、`copyableAnnotations += @Qualifier`）
- MyBatis-Plus ORM，自动填充 `createTime` / `updateTime`
- 分布式 ID 用雪花算法（`CustomIdentifierGenerator`），不要在业务代码里自己生成主键
- 统一返回结构 `Result<T>`，通过 `Results` 工具类构建
- 异常继承 `AbstractException`，按业务域划入 `ClientException` / `ServiceException` / `RemoteException`
- MCP 协议约定：mcp-server 端使用 JSON-RPC 2.0（`JsonRpcRequest` / `JsonRpcResponse` / `JsonRpcError`）
- Bash / SQL / Lua 脚本与 Prompt 模板放在 `bootstrap/src/main/resources/`（`lua/`、`prompt/`），不要放业务包内

## Reference Docs（仓库内）

- `docs/quick-start.md` — 启动流程与最小配置
- `docs/multi-channel-retrieval.md` — 多路检索引擎架构与扩展指南
- `docs/refactoring-summary.md` — 关键重构里程碑回顾
- `docs/examples/pdf-ingestion-example.md` — PDF 入库 Pipeline 示例
- `frontend/TESTING.md` — 前端测试约定
- `README.md` — 项目背景与架构图（注：内含的 MySQL 描述已被 PostgreSQL 取代，以 `application.yaml` 与 `schema_pg.sql` 为准）
